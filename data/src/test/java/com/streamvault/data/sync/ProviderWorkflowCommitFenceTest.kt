package com.streamvault.data.sync

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.RoomDatabaseTransactionRunner
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.ProviderWorkflowPhase
import com.streamvault.data.local.entity.ProviderWorkflowReason
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProviderWorkflowCommitFenceTest {
    private lateinit var database: StreamVaultDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            StreamVaultDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `superseded generation cannot apply staged catalog rows`() = runTest {
        insertProvider()
        database.channelDao().insertAll(listOf(channel(name = "Committed", streamUrl = "old")))
        val workflowDao = database.providerWorkflowDao()
        val ticket = workflowDao.request(
            PROVIDER_ID,
            ProviderWorkflowPhase.PRIMARY_CATALOG,
            ProviderWorkflowReason.PERIODIC,
            now = 100L
        )
        val staleLease = checkNotNull(
            workflowDao.claim(ticket, "old-owner", 101L, 1_000L, staleHeartbeatBefore = 0L)
        )
        workflowDao.request(
            PROVIDER_ID,
            ProviderWorkflowPhase.PREPARE,
            ProviderWorkflowReason.CONFIG_CHANGE,
            now = 200L,
            supersede = true,
            force = true
        )

        val error = runCatching {
            withContext(ProviderWorkflowExecutionContext(staleLease)) {
                store().replaceLiveCatalog(
                    providerId = PROVIDER_ID,
                    categories = null,
                    channels = listOf(channel(name = "Stale replacement", streamUrl = "new"))
                )
            }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(ProviderWorkflowSupersededException::class.java)
        val retained = database.channelDao().getByProviderSync(PROVIDER_ID)
        assertThat(retained).hasSize(1)
        assertThat(retained.single().name).isEqualTo("Committed")
        assertThat(retained.single().streamUrl).isEqualTo("old")
    }

    @Test
    fun `superseded cleanup cannot erase a newer staged snapshot`() = runTest {
        insertProvider()
        val dao = database.providerWorkflowDao()
        val ticket = dao.request(PROVIDER_ID, ProviderWorkflowPhase.PRIMARY_CATALOG, ProviderWorkflowReason.PERIODIC, now = 100L)
        val oldLease = checkNotNull(dao.claim(ticket, "old-owner", 101L, 1_000L, staleHeartbeatBefore = 0L))
        dao.request(PROVIDER_ID, ProviderWorkflowPhase.PREPARE, ProviderWorkflowReason.CONFIG_CHANGE,
            now = 200L, supersede = true, force = true)
        store().stageChannelBatch(PROVIDER_ID, 222L, listOf(channel("New snapshot", "fresh")))
        val failure = runCatching {
            withContext(ProviderWorkflowExecutionContext(oldLease)) { store().clearProviderStaging(PROVIDER_ID) }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ProviderWorkflowSupersededException::class.java)
        assertThat(database.catalogSyncDao().countChannelStages(PROVIDER_ID, 222L)).isEqualTo(1)
    }

    @Test
    fun `current generation applies catalog inside fenced transaction`() = runTest {
        insertProvider()
        val workflowDao = database.providerWorkflowDao()
        val ticket = workflowDao.request(
            PROVIDER_ID,
            ProviderWorkflowPhase.PRIMARY_CATALOG,
            ProviderWorkflowReason.MANUAL,
            now = 100L
        )
        val lease = checkNotNull(
            workflowDao.claim(ticket, "manual-owner", 101L, 1_000L, staleHeartbeatBefore = 0L)
        )

        withContext(ProviderWorkflowExecutionContext(lease)) {
            store().replaceLiveCatalog(
                providerId = PROVIDER_ID,
                categories = null,
                channels = listOf(channel(name = "Fresh", streamUrl = "fresh"))
            )
        }

        assertThat(database.channelDao().getByProviderSync(PROVIDER_ID).single().name)
            .isEqualTo("Fresh")
    }

    @Test
    fun `failed candidate promotion rolls back replacement and retains committed catalog`() = runTest {
        insertProvider()
        database.channelDao().insertAll(listOf(channel(name = "Committed", streamUrl = "old")))
        val workflowDao = database.providerWorkflowDao()
        val ticket = workflowDao.request(
            PROVIDER_ID,
            ProviderWorkflowPhase.PREPARE,
            ProviderWorkflowReason.CONFIG_CHANGE,
            now = 100L,
            supersede = true,
            priority = 100,
            force = true
        )
        val lease = checkNotNull(
            workflowDao.claim(ticket, "candidate-owner", 101L, 1_000L, staleHeartbeatBefore = 0L)
        )

        val error = runCatching {
            withContext(ProviderWorkflowExecutionContext(lease)) {
                store().replaceLiveCatalog(
                    providerId = PROVIDER_ID,
                    categories = null,
                    channels = listOf(channel(name = "Candidate", streamUrl = "new")),
                    afterCatalogApply = { throw CancellationException("candidate cancelled") }
                )
            }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        val retained = database.channelDao().getByProviderSync(PROVIDER_ID).single()
        assertThat(retained.name).isEqualTo("Committed")
        assertThat(retained.streamUrl).isEqualTo("old")
    }

    @Test
    fun `cancellation before catalog commit retains committed catalog and clears staging`() = runTest {
        insertProvider()
        database.channelDao().insertAll(listOf(channel(name = "Committed", streamUrl = "old")))

        val error = runCatching {
            store(transactionRunner = transactionRunner(failBeforeCommit = true)).replaceLiveCatalog(
                providerId = PROVIDER_ID,
                categories = null,
                channels = listOf(channel(name = "Candidate", streamUrl = "new"))
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        val retained = database.channelDao().getByProviderSync(PROVIDER_ID).single()
        assertThat(retained.name).isEqualTo("Committed")
        assertThat(database.catalogSyncDao().countChannelStages(PROVIDER_ID, Long.MAX_VALUE)).isEqualTo(0)
    }

    @Test
    fun `cancellation after catalog transaction commits leaves the committed catalog durable`() = runTest {
        insertProvider()
        database.channelDao().insertAll(listOf(channel(name = "Committed", streamUrl = "old")))

        val error = runCatching {
            store(transactionRunner = transactionRunner(failAfterCommit = true)).replaceLiveCatalog(
                providerId = PROVIDER_ID,
                categories = null,
                channels = listOf(channel(name = "Candidate", streamUrl = "new"))
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
        val retained = database.channelDao().getByProviderSync(PROVIDER_ID).single()
        assertThat(retained.name).isEqualTo("Candidate")
        assertThat(retained.streamUrl).isEqualTo("new")
    }

    private fun store(
        transactionRunner: DatabaseTransactionRunner = RoomDatabaseTransactionRunner(database)
    ) = SyncCatalogStore(
        channelDao = database.channelDao(),
        movieDao = database.movieDao(),
        seriesDao = database.seriesDao(),
        categoryDao = database.categoryDao(),
        catalogSyncDao = database.catalogSyncDao(),
        tmdbIdentityDao = database.tmdbIdentityDao(),
        transactionRunner = transactionRunner,
        workflowCommitFence = ProviderWorkflowCommitFence(database.providerWorkflowDao())
    )

    private fun transactionRunner(
        failBeforeCommit: Boolean = false,
        failAfterCommit: Boolean = false
    ): DatabaseTransactionRunner {
        val delegate = RoomDatabaseTransactionRunner(database)
        var calls = 0
        return object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                calls += 1
                if (failBeforeCommit && calls == 2) {
                    throw CancellationException("cancelled before catalog commit")
                }
                val result = delegate.inTransaction(block)
                if (failAfterCommit && calls == 2) {
                    throw CancellationException("cancelled after catalog commit")
                }
                return result
            }
        }
    }

    private suspend fun insertProvider() {
        database.providerDao().insert(
            ProviderEntity(
                id = PROVIDER_ID,
                name = "Provider",
                type = ProviderType.M3U,
                serverUrl = "https://example.com"
            )
        )
    }

    private fun channel(
        name: String,
        streamUrl: String,
        id: Long = 0L
    ) = ChannelEntity(
        id = id,
        streamId = 10L,
        name = name,
        streamUrl = streamUrl,
        providerId = PROVIDER_ID
    )

    private companion object {
        const val PROVIDER_ID = 1L
    }
}
