package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.entity.PlaybackHistoryEntity
import com.streamvault.data.local.entity.PlaybackHistoryLiteEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.mapper.toEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.PlaybackWatchedStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.check

class PlaybackHistoryRepositoryImplTest {
    @Test fun `Trakt import respects current incognito preference without waiting for state collection`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(true))
        val identity = com.streamvault.domain.trakt.TraktMediaIdentity.MovieId(603)
        assertThat(repository().importTraktHistory(movieHistory(), identity,
            com.streamvault.domain.trakt.TraktHistoryMatch(identity, 2000, 1)).getOrNull()).isFalse()
        verify(historyDao, never()).insertOrUpdate(org.mockito.kotlin.any())
    }
    @Test fun `Trakt import is idempotent and preserves remote time count and completion`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val original = movieHistory().copy(lastWatchedAt = 1000, watchCount = 4)
        var stored = original.toEntity()
        whenever(historyDao.get(10, ContentType.MOVIE.name, 5)).thenAnswer { stored }
        org.mockito.kotlin.doAnswer {
            stored = it.getArgument(0)
            Unit
        }.whenever(historyDao).insertOrUpdate(org.mockito.kotlin.any())
        val repository = repository()
        val identity = com.streamvault.domain.trakt.TraktMediaIdentity.MovieId(603)
        val match = com.streamvault.domain.trakt.TraktHistoryMatch(identity, 2000, 2)
        assertThat(repository.importTraktHistory(original, identity, match).getOrNull()).isTrue()
        assertThat(repository.importTraktHistory(original, identity, match).getOrNull()).isFalse()
        assertThat(stored.lastWatchedAt).isEqualTo(2000)
        assertThat(stored.watchCount).isEqualTo(4)
        assertThat(stored.resumePositionMs).isEqualTo(original.totalDurationMs)
        assertThat(stored.watchedStatus).isEqualTo(PlaybackWatchedStatus.COMPLETED_AUTO.name)
        verify(historyDao, times(1)).insertOrUpdate(org.mockito.kotlin.any())
        verify(movieDao, times(1)).syncWatchProgressFromHistory(10, 5)
    }

    @Test fun `Trakt import rechecks persisted newer progress before writing`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val base = movieHistory().copy(lastWatchedAt = 1000)
        val newer = base.copy(lastWatchedAt = 3000)
        whenever(historyDao.get(10, ContentType.MOVIE.name, 5)).thenReturn(newer.toEntity())
        val identity = com.streamvault.domain.trakt.TraktMediaIdentity.MovieId(603)
        assertThat(repository().importTraktHistory(base, identity,
            com.streamvault.domain.trakt.TraktHistoryMatch(identity, 2000, 1)).getOrNull()).isFalse()
        verify(historyDao, never()).insertOrUpdate(org.mockito.kotlin.any())
    }

    private val historyDao: PlaybackHistoryDao = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val movieDao: MovieDao = mock()
    private val episodeDao: EpisodeDao = mock()
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private fun repository(
        transactionRunner: DatabaseTransactionRunner = this.transactionRunner
    ) = PlaybackHistoryRepositoryImpl(
        dao = historyDao,
        preferencesRepository = preferencesRepository,
        movieDao = movieDao,
        episodeDao = episodeDao,
        transactionRunner = transactionRunner
    )

    private fun movieHistory(
        resumePositionMs: Long = 1_000L,
        totalDurationMs: Long = 10_000L
    ) = PlaybackHistory(
        contentId = 10L,
        contentType = ContentType.MOVIE,
        providerId = 5L,
        title = "Movie",
        streamUrl = "https://provider.example.com/movie",
        resumePositionMs = resumePositionMs,
        totalDurationMs = totalDurationMs,
        watchedStatus = PlaybackWatchedStatus.IN_PROGRESS
    )

    @Test
    fun `clearAllHistory resets denormalized movie and episode progress directly`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val repository = repository()

        val result = repository.clearAllHistory()

        assertThat(result.isSuccess).isTrue()
        verify(historyDao).deleteAll()
        verify(movieDao).resetAllWatchProgress()
        verify(episodeDao).resetAllWatchProgress()
    }

    @Test
    fun `updateResumePosition buffers playback history until an explicit flush`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val repository = repository()

        val result = repository.updateResumePosition(movieHistory())

        assertThat(result.isSuccess).isTrue()
        verify(historyDao, never()).insertOrUpdate(org.mockito.kotlin.any())
        verify(movieDao, never()).syncWatchProgressFromHistory(10L, 5L)

        val flushResult = repository.flushPendingProgress()

        assertThat(flushResult.isSuccess).isTrue()
        verify(historyDao, times(1)).insertOrUpdate(org.mockito.kotlin.any())
        verify(movieDao).syncWatchProgressFromHistory(10L, 5L)
    }

    @Test
    fun `successive resume updates coalesce to the newest position before flush`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val repository = repository()

        repository.updateResumePosition(movieHistory(resumePositionMs = 2_000L))
        repository.updateResumePosition(movieHistory(resumePositionMs = 8_000L))

        verify(historyDao, never()).insertOrUpdate(org.mockito.kotlin.any())
        repository.flushPendingProgress()

        val historyCaptor = argumentCaptor<PlaybackHistoryEntity>()
        verify(historyDao, times(1)).insertOrUpdate(historyCaptor.capture())
        assertThat(historyCaptor.firstValue.resumePositionMs).isEqualTo(8_000L)
        verify(movieDao, times(1)).syncWatchProgressFromHistory(10L, 5L)
    }

    @Test
    fun `failed flush keeps the coalesced position available for retry`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val failingRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                throw IllegalStateException("database unavailable")
            }
        }
        val repository = repository(transactionRunner = failingRunner)

        repository.updateResumePosition(movieHistory(resumePositionMs = 6_000L))
        val result = repository.flushPendingProgress()

        assertThat(result.isError).isTrue()
        assertThat(repository.getPlaybackHistory(10L, ContentType.MOVIE, 5L)?.resumePositionMs)
            .isEqualTo(6_000L)
        verify(historyDao, never()).insertOrUpdate(org.mockito.kotlin.any())
    }

    @Test
    fun `two hours of five second progress uses one durable write per thirty second window`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val repository = repository()
        val progressIntervalMs = 5_000L
        val flushIntervalMs = 30_000L
        val playbackDurationMs = 2 * 60 * 60 * 1_000L

        for (positionMs in progressIntervalMs..playbackDurationMs step progressIntervalMs) {
            repository.updateResumePosition(movieHistory(resumePositionMs = positionMs))
            if (positionMs % flushIntervalMs == 0L) {
                repository.flushPendingProgress()
            }
        }

        val expectedDurableWrites = (playbackDurationMs / flushIntervalMs).toInt()
        verify(historyDao, times(expectedDurableWrites)).insertOrUpdate(org.mockito.kotlin.any())
        verify(movieDao, times(expectedDurableWrites)).syncWatchProgressFromHistory(10L, 5L)
    }

    @Test
    fun `repeating a flush without new progress does not duplicate the durable write`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val repository = repository()

        repository.updateResumePosition(movieHistory(resumePositionMs = 4_000L))
        repository.flushPendingProgress()
        repository.flushPendingProgress()

        verify(historyDao, times(1)).insertOrUpdate(org.mockito.kotlin.any())
        verify(movieDao, times(1)).syncWatchProgressFromHistory(10L, 5L)
    }

    @Test
    fun `getPlaybackHistory prefers buffered resume update before flush`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        whenever(historyDao.get(10L, ContentType.MOVIE.name, 5L)).thenReturn(
            PlaybackHistoryEntity(
                contentId = 10L,
                contentType = ContentType.MOVIE,
                providerId = 5L,
                title = "Movie",
                resumePositionMs = 1_500L,
                totalDurationMs = 10_000L,
                lastWatchedAt = 111L
            )
        )
        val repository = repository()

        repository.updateResumePosition(movieHistory(resumePositionMs = 4_000L))
        val result = repository.getPlaybackHistory(10L, ContentType.MOVIE, 5L)

        assertThat(result?.resumePositionMs).isEqualTo(4_000L)
        assertThat(result?.lastWatchedAt).isGreaterThan(111L)
    }

    @Test
    fun `getRecentlyWatched overlays buffered resume updates before flush`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        whenever(historyDao.getRecentlyWatched(5)).thenReturn(
            flowOf(
                listOf(
                    PlaybackHistoryLiteEntity(
                        contentId = 10L,
                        contentType = ContentType.MOVIE,
                        providerId = 5L,
                        title = "Movie",
                        resumePositionMs = 1_500L,
                        totalDurationMs = 10_000L,
                        lastWatchedAt = 111L
                    )
                )
            )
        )
        val repository = repository()

        repository.updateResumePosition(movieHistory(resumePositionMs = 4_000L))
        val result = repository.getRecentlyWatched(limit = 5).first()

        assertThat(result).hasSize(1)
        assertThat(result.first().resumePositionMs).isEqualTo(4_000L)
        assertThat(result.first().lastWatchedAt).isGreaterThan(111L)
    }

    @Test
    fun `recordPlayback persists final playback state after buffered resume update`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val repository = repository()

        repository.updateResumePosition(movieHistory(resumePositionMs = 2_000L))
        val result = repository.recordPlayback(movieHistory(resumePositionMs = 3_000L))

        assertThat(result.isSuccess).isTrue()
        val historyCaptor = argumentCaptor<PlaybackHistoryEntity>()
        verify(historyDao, atLeastOnce()).insertOrUpdate(historyCaptor.capture())
        assertThat(historyCaptor.lastValue.resumePositionMs).isEqualTo(3_000L)
        assertThat(historyCaptor.lastValue.watchCount).isEqualTo(2)
        verify(movieDao, atLeastOnce()).syncWatchProgressFromHistory(10L, 5L)
    }

    @Test
    fun `markAsWatched syncs denormalized movie progress`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val repository = repository()

        val result = repository.markAsWatched(movieHistory(resumePositionMs = 0L, totalDurationMs = 15_000L))

        assertThat(result.isSuccess).isTrue()
        verify(historyDao).insertOrUpdate(check {
            assertThat(it.resumePositionMs).isEqualTo(15_000L)
            assertThat(it.totalDurationMs).isEqualTo(15_000L)
            assertThat(it.watchedStatus).isEqualTo(PlaybackWatchedStatus.COMPLETED_MANUAL.name)
        })
        verify(movieDao).syncWatchProgressFromHistory(10L, 5L)
    }

    @Test
    fun `flushPendingProgress persists buffered episode progress and syncs denormalized state`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val repository = repository()
        val history = PlaybackHistory(
            contentId = 11L,
            contentType = ContentType.SERIES_EPISODE,
            providerId = 6L,
            title = "Episode",
            streamUrl = "https://provider.example.com/episode",
            resumePositionMs = 7_500L,
            totalDurationMs = 12_000L,
            watchedStatus = PlaybackWatchedStatus.IN_PROGRESS
        )

        repository.updateResumePosition(history)
        val result = repository.flushPendingProgress()

        assertThat(result.isSuccess).isTrue()
        verify(historyDao, org.mockito.kotlin.atLeastOnce()).insertOrUpdate(check {
            assertThat(it.contentId).isEqualTo(11L)
            assertThat(it.contentType).isEqualTo(ContentType.SERIES_EPISODE)
            assertThat(it.providerId).isEqualTo(6L)
            assertThat(it.resumePositionMs).isEqualTo(7_500L)
        })
        verify(episodeDao, org.mockito.kotlin.atLeastOnce()).syncWatchProgressFromHistory(11L, 6L)
    }

    @Test
    fun `removeFromHistory deletes record and syncs denormalized movie progress`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val repository = repository()

        val result = repository.removeFromHistory(10L, ContentType.MOVIE, 5L)

        assertThat(result.isSuccess).isTrue()
        verify(historyDao).delete(10L, ContentType.MOVIE.name, 5L)
        verify(movieDao).syncWatchProgressFromHistory(10L, 5L)
    }

    @Test
    fun `clearHistoryForProvider resyncs denormalized movie and episode progress`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val repository = repository()

        val result = repository.clearHistoryForProvider(5L)

        assertThat(result.isSuccess).isTrue()
        verify(historyDao).deleteByProvider(5L)
        verify(movieDao).syncWatchProgressFromHistoryByProvider(5L)
        verify(episodeDao).syncWatchProgressFromHistoryByProvider(5L)
    }

    @Test
    fun `getPlaybackHistory falls back to shared tmdb movie history across providers`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        whenever(historyDao.get(10L, ContentType.MOVIE.name, 5L)).thenReturn(null)
        whenever(historyDao.getLatestMovieHistoryBySharedTmdb(10L, 5L)).thenReturn(
            PlaybackHistoryEntity(
                contentId = 42L,
                contentType = ContentType.MOVIE,
                providerId = 9L,
                title = "Movie",
                resumePositionMs = 4_000L,
                totalDurationMs = 10_000L,
                lastWatchedAt = 123L
            )
        )

        val result = repository().getPlaybackHistory(10L, ContentType.MOVIE, 5L)

        assertThat(result).isNotNull()
        assertThat(result?.providerId).isEqualTo(9L)
        assertThat(result?.resumePositionMs).isEqualTo(4_000L)
    }

    @Test
    fun `getRecentlyWatchedByProviders uses provider set query`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        whenever(historyDao.getRecentlyWatchedByProviders(setOf(5L, 6L), 8)).thenReturn(
            flowOf(
                listOf(
                    PlaybackHistoryLiteEntity(
                        contentId = 10L,
                        contentType = ContentType.MOVIE,
                        providerId = 5L,
                        title = "Movie",
                        resumePositionMs = 4_000L,
                        totalDurationMs = 10_000L,
                        lastWatchedAt = 123L
                    )
                )
            )
        )

        val result = repository().getRecentlyWatchedByProviders(setOf(5L, 6L), 8)

        assertThat(result.first()).hasSize(1)
        assertThat(result.first().first().providerId).isEqualTo(5L)
        verify(historyDao).getRecentlyWatchedByProviders(eq(setOf(5L, 6L)), eq(8))
    }
}
