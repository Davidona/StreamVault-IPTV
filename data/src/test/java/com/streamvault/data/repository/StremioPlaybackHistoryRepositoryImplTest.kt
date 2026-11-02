package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.StremioPlaybackHistoryDao
import com.streamvault.data.local.entity.StremioPlaybackHistoryEntity
import com.streamvault.domain.model.stremio.StremioPlaybackHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StremioPlaybackHistoryRepositoryImplTest {
    @Test
    fun `save and read retain exact identity and progress without stream urls`() = runTest {
        val dao = FakeStremioPlaybackHistoryDao()
        val repository = StremioPlaybackHistoryRepositoryImpl(dao)
        val history = StremioPlaybackHistory(
            contentKey = "stremio:global:series-episode:tt12345:1:2",
            mediaType = "series",
            exactVideoId = "tt12345:1:2",
            parentSeriesId = "tt12345",
            title = "Episode 2",
            posterUrl = "https://images.example.test/poster.jpg",
            resumePositionMs = 45_000L,
            totalDurationMs = 900_000L,
            catalogOriginKey = "a".repeat(64),
            streamOriginKeyHint = "b".repeat(64)
        )

        assertThat(repository.save(history).isSuccess).isTrue()
        assertThat(repository.get(history.contentKey)).isEqualTo(history)
        assertThat(dao.row?.exactVideoId).isEqualTo("tt12345:1:2")
        assertThat(dao.row?.resumePositionMs).isEqualTo(45_000L)
    }
    @Test fun dismissalPreservesProgressAndHistoryAndPlaybackCanRestoreIt() = runTest {
        val repo = StremioPlaybackHistoryRepositoryImpl(FakeStremioPlaybackHistoryDao())
        val record = StremioPlaybackHistory("key","movie","tt1",title="Movie",resumePositionMs=5000,
            totalDurationMs=10000,catalogOriginKey="a".repeat(64))
        repo.save(record); repo.dismiss(record.contentKey)
        val dismissed = repo.get(record.contentKey)!!
        assertThat(dismissed.resumePositionMs).isEqualTo(5000L)
        assertThat(dismissed.dismissedAtMs).isGreaterThan(0L)
        repo.save(record.copy(resumePositionMs=6000))
        assertThat(repo.get(record.contentKey)?.dismissedAtMs).isEqualTo(0L)
    }
    @Test fun lifecycleWriteAtCompletionDoesNotMakeCompletedVideoUnwatched() = runTest {
        val repo = StremioPlaybackHistoryRepositoryImpl(FakeStremioPlaybackHistoryDao())
        val record = StremioPlaybackHistory("key","movie","tt1",title="Movie",resumePositionMs=10000,
            totalDurationMs=10000,catalogOriginKey="a".repeat(64))
        repo.save(record)
        assertThat(repo.get("key")?.watched).isTrue()
        repo.save(record.copy(resumePositionMs=0,watched=false))
        assertThat(repo.get("key")?.watched).isFalse()
    }
}

private class FakeStremioPlaybackHistoryDao : StremioPlaybackHistoryDao {
    private val rows = MutableStateFlow<List<StremioPlaybackHistoryEntity>>(emptyList())
    val row: StremioPlaybackHistoryEntity? get() = rows.value.firstOrNull()

    override fun observeContinueWatching(limit: Int): Flow<List<StremioPlaybackHistoryEntity>> =
        rows.asStateFlow()

    override fun observeHistory(): Flow<List<StremioPlaybackHistoryEntity>> = rows.asStateFlow()
    override suspend fun dismiss(key: String, at: Long) {
        rows.value = rows.value.map { if (it.contentKey == key) it.copy(dismissedAtMs = at) else it }
    }

    override suspend fun get(contentKey: String): StremioPlaybackHistoryEntity? =
        rows.value.firstOrNull { it.contentKey == contentKey }

    override suspend fun getAll(): List<StremioPlaybackHistoryEntity> =
        rows.value.sortedWith(compareByDescending<StremioPlaybackHistoryEntity> { it.lastWatchedAt }.thenBy { it.contentKey })

    override suspend fun upsert(entity: StremioPlaybackHistoryEntity) {
        rows.value = rows.value.filterNot { it.contentKey == entity.contentKey } + entity
    }

    override suspend fun upsertAll(entities: List<StremioPlaybackHistoryEntity>) { entities.forEach { upsert(it) } }

    override suspend fun delete(contentKey: String) {
        rows.value = rows.value.filterNot { it.contentKey == contentKey }
    }
}
