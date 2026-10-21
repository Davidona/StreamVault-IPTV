package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Test

class SyncStatusPublicationCoordinatorTest {
    @Test
    fun `concurrent guide and catalog metadata preserve both counts`() = runTest {
        val repository = FakeSyncMetadataRepository(SyncMetadata(providerId = 7L))
        val slowRepository = object : SyncMetadataRepository by repository {
            override suspend fun getMetadata(providerId: Long): SyncMetadata? {
                val value = repository.getMetadata(providerId)
                kotlinx.coroutines.delay(1)
                return value
            }
        }
        val mutex = kotlinx.coroutines.sync.Mutex()
        val transactions = object : com.streamvault.data.local.DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                mutex.lock()
                return try { block() } finally { mutex.unlock() }
            }
        }
        val coordinator = SyncStatusPublicationCoordinator(slowRepository, SyncProgressBus(), transactionRunner = transactions)
        kotlinx.coroutines.coroutineScope {
            launch {
                coordinator.updateSummaryMetadata(7L, ContentType.MOVIE, 500, "RUNNING", 100, VodSyncMode.PAGED)
            }
            launch {
                transactions.inTransaction {
                    val metadata = requireNotNull(slowRepository.getMetadata(7L))
                    slowRepository.updateMetadata(metadata.copy(epgCount = 200, lastEpgSuccess = 100))
                }
            }
        }
        val metadata = requireNotNull(repository.getMetadata(7L))
        assertThat(metadata.movieCount).isEqualTo(500)
        assertThat(metadata.epgCount).isEqualTo(200)
        assertThat(metadata.lastEpgSuccess).isEqualTo(100)
    }

    @Test
    fun `partial movie publication preserves the known catalog count and marks it stale`() = runTest {
        val repository = FakeSyncMetadataRepository(
            SyncMetadata(
                providerId = 7L,
                movieCount = 10,
                lastMovieSuccess = 90L
            )
        )
        val coordinator = SyncStatusPublicationCoordinator(repository, SyncProgressBus())

        coordinator.updateSummaryMetadata(
            providerId = 7L,
            contentType = ContentType.MOVIE,
            indexedRows = 3,
            finalState = "PARTIAL",
            now = 100L,
            movieSyncMode = VodSyncMode.PAGED
        )

        val metadata = requireNotNull(repository.getMetadata(7L))
        assertThat(metadata.movieCount).isEqualTo(10)
        assertThat(metadata.lastMovieSuccess).isEqualTo(90L)
        assertThat(metadata.lastMoviePartial).isEqualTo(100L)
        assertThat(metadata.movieCatalogStale).isTrue()
        assertThat(metadata.movieSyncMode).isEqualTo(VodSyncMode.PAGED)
    }

    @Test
    fun `successful series publication replaces the indexed count and records success`() = runTest {
        val repository = FakeSyncMetadataRepository(SyncMetadata(providerId = 8L, seriesCount = 2))
        val coordinator = SyncStatusPublicationCoordinator(repository, SyncProgressBus())

        coordinator.updateSummaryMetadata(
            providerId = 8L,
            contentType = ContentType.SERIES,
            indexedRows = 12,
            finalState = "SUCCESS",
            now = 200L,
            movieSyncMode = VodSyncMode.UNKNOWN
        )

        val metadata = requireNotNull(repository.getMetadata(8L))
        assertThat(metadata.seriesCount).isEqualTo(12)
        assertThat(metadata.lastSeriesSync).isEqualTo(200L)
        assertThat(metadata.lastSeriesSuccess).isEqualTo(200L)
    }

    @Test
    fun `movie index rebuild attempt preserves the existing count while marking stale`() = runTest {
        val repository = FakeSyncMetadataRepository(SyncMetadata(providerId = 10L, movieCount = 15))
        val coordinator = SyncStatusPublicationCoordinator(repository, SyncProgressBus())

        coordinator.markMovieIndexRebuildAttempt(providerId = 10L, now = 300L)

        val metadata = requireNotNull(repository.getMetadata(10L))
        assertThat(metadata.movieCount).isEqualTo(15)
        assertThat(metadata.lastMovieAttempt).isEqualTo(300L)
        assertThat(metadata.movieCatalogStale).isTrue()
        assertThat(metadata.movieSyncMode).isEqualTo(VodSyncMode.UNKNOWN)
    }

    @Test
    fun `stale state session cannot publish after a replacement`() {
        val coordinator = SyncStatusPublicationCoordinator(
            FakeSyncMetadataRepository(),
            SyncProgressBus()
        )
        val first = coordinator.beginStateSession(9L)
        coordinator.publish(9L, SyncState.Syncing("first"))
        val replacement = coordinator.beginStateSession(9L)
        coordinator.publish(9L, SyncState.Syncing("replacement"))

        coordinator.finishStateSession(first)
        assertThat(coordinator.currentSyncState(9L)).isEqualTo(SyncState.Syncing("replacement"))

        coordinator.finishStateSession(replacement)
    }

    @Test
    fun `finishing stale progress session does not finish replacement state session`() {
        val coordinator = SyncStatusPublicationCoordinator(
            FakeSyncMetadataRepository(),
            SyncProgressBus()
        )
        val first = coordinator.beginProgressSession(9L)
        coordinator.publish(9L, SyncState.Syncing("first"))
        val replacement = coordinator.beginProgressSession(9L)
        coordinator.publish(9L, SyncState.Syncing("replacement"))

        coordinator.finishProgressSession(first)
        coordinator.publish(9L, SyncState.Success())

        assertThat(coordinator.currentSyncState(9L)).isInstanceOf(SyncState.Success::class.java)
        coordinator.finishProgressSession(replacement)
    }

    private class FakeSyncMetadataRepository(initial: SyncMetadata? = null) : SyncMetadataRepository {
        private val values = mutableMapOf<Long, SyncMetadata>()

        init {
            initial?.let { values[it.providerId] = it }
        }

        override fun observeMetadata(providerId: Long): Flow<SyncMetadata?> = flowOf(values[providerId])

        override suspend fun getMetadata(providerId: Long): SyncMetadata? = values[providerId]

        override suspend fun updateMetadata(metadata: SyncMetadata) {
            values[metadata.providerId] = metadata
        }

        override suspend fun clearMetadata(providerId: Long) {
            values.remove(providerId)
        }
    }
}
