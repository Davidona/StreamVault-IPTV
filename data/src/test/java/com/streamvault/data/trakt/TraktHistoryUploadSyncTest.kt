package com.streamvault.data.trakt

import com.streamvault.data.local.dao.*
import com.streamvault.data.mapper.toEntity
import com.streamvault.domain.model.*
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.trakt.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class TraktHistoryUploadSyncTest {
    private val account = mock<TraktAccount>()
    private val state = MutableStateFlow(TraktAccountState(configured = true, connected = true, username = "viewer"))
    private val source = mock<TraktHistorySource>()
    private val movies = mock<MovieDao>()
    private val series = mock<SeriesDao>()
    private val episodes = mock<EpisodeDao>()
    private val history = mock<PlaybackHistoryRepository>()
    private val emptyPage = TraktHistoryResult.Success(TraktHistoryPage(emptyList(), 1, false))

    init {
        whenever(account.state).thenReturn(state)
        runBlocking { whenever(source.history(1)).thenReturn(emptyPage) }
        whenever(history.getRecentlyWatched(5001)).thenReturn(flowOf(emptyList()))
        runBlocking { whenever(history.isIncognitoModeEnabled()).thenReturn(false) }
    }

    private fun service() = TraktHistorySyncService(account, source, movies, series, episodes, history)
    private fun completedMovie(contentId: Long, tmdbId: Long, watchedAt: Long, providerId: Long = 5) =
        PlaybackHistory(contentId = contentId, contentType = ContentType.MOVIE, providerId = providerId,
            title = "Movie $contentId", streamUrl = "https://provider.test/$contentId", lastWatchedAt = watchedAt,
            watchedStatus = PlaybackWatchedStatus.COMPLETED_AUTO)

    @Test fun uploads_only_exact_completed_local_movies_with_captured_owner() = runBlocking {
        val watchedAt = 1_700_000_000_123L
        val row = completedMovie(contentId = 77, tmdbId = 603, watchedAt = watchedAt)
        whenever(history.getRecentlyWatched(5001)).thenReturn(flowOf(listOf(row)))
        whenever(movies.getByIdsSync(listOf(77))).thenReturn(listOf(
            Movie(id = 77, name = "Movie 77", providerId = 5, tmdbId = 603).toEntity()))
        whenever(source.upload(eq("viewer"), any())).thenReturn(TraktHistoryUploadResult.Success(1))

        val result = service().sync()

        assertEquals(TraktHistorySyncResult.Success(0, 0, 0, 0, uploaded = 1, moreUploads = false), result)
        val events = argumentCaptor<List<TraktHistoryUpload>>()
        verify(source).upload(eq("viewer"), events.capture())
        assertEquals(listOf(TraktHistoryUpload(TraktMediaIdentity.MovieId(603), watchedAt)), events.firstValue)
    }

    @Test fun remote_history_in_the_same_minute_suppresses_duplicate_upload() = runBlocking {
        val watchedAt = 1_700_000_000_123L
        val minute = watchedAt / 60_000 * 60_000
        whenever(source.history(1)).thenReturn(TraktHistoryResult.Success(TraktHistoryPage(
            listOf(TraktHistoryEntry(1, TraktMediaIdentity.MovieId(603), minute)), 1, false)))
        whenever(movies.getByTmdbIdsSync(listOf(603))).thenReturn(emptyList())
        whenever(history.getRecentlyWatched(5001)).thenReturn(flowOf(listOf(completedMovie(77, 603, watchedAt))))
        whenever(movies.getByIdsSync(listOf(77))).thenReturn(listOf(
            Movie(id = 77, name = "Movie 77", providerId = 5, tmdbId = 603).toEntity()))

        val result = service().sync()

        assertTrue(result is TraktHistorySyncResult.Success)
        verify(source, never()).upload(any(), any())
        Unit
    }

    @Test fun upload_batch_is_capped_at_one_hundred_and_reports_remaining_work() = runBlocking {
        val rows = (1L..101L).map { id -> completedMovie(id, 1_000 + id, 1_700_000_000_000L + id) }
        whenever(history.getRecentlyWatched(5001)).thenReturn(flowOf(rows))
        whenever(movies.getByIdsSync((1L..101L).toList())).thenReturn(rows.map { row ->
            Movie(id = row.contentId, name = row.title, providerId = row.providerId,
                tmdbId = 1_000 + row.contentId).toEntity()
        })
        whenever(source.upload(eq("viewer"), any())).thenReturn(TraktHistoryUploadResult.Success(100))

        val result = service().sync()

        assertEquals(TraktHistorySyncResult.Success(0, 0, 0, 0, uploaded = 100, moreUploads = true), result)
        val events = argumentCaptor<List<TraktHistoryUpload>>()
        verify(source).upload(eq("viewer"), events.capture())
        assertEquals(100, events.firstValue.size)
        assertEquals(TraktMediaIdentity.MovieId(1001), events.firstValue.first().media)
    }

    @Test fun incognito_stops_before_remote_history_or_catalog_access() = runBlocking {
        whenever(history.isIncognitoModeEnabled()).thenReturn(true)

        assertEquals(TraktHistorySyncResult.Failure(TraktSyncFailure.INCOGNITO), service().sync())
        verifyNoInteractions(source, movies, series, episodes)
    }

    @Test fun incognito_enabled_while_resolving_candidates_blocks_outbound_write() = runBlocking {
        var incognitoEnabled = false
        whenever(history.isIncognitoModeEnabled()).thenAnswer { incognitoEnabled }
        val row = completedMovie(77, 603, 1_700_000_000_123L)
        whenever(history.getRecentlyWatched(5001)).thenAnswer {
            incognitoEnabled = true
            flowOf(listOf(row))
        }
        whenever(movies.getByIdsSync(listOf(77))).thenReturn(listOf(
            Movie(id = 77, name = "Movie 77", providerId = 5, tmdbId = 603).toEntity()))

        assertEquals(TraktHistorySyncResult.Failure(TraktSyncFailure.INCOGNITO), service().sync())
        verify(source, never()).upload(any(), any())
        Unit
    }

    @Test fun account_switch_during_local_resolution_aborts_before_upload() = runBlocking {
        val row = completedMovie(77, 603, 1_700_000_000_123L)
        whenever(history.getRecentlyWatched(5001)).thenReturn(flowOf(listOf(row)))
        whenever(movies.getByIdsSync(listOf(77))).thenAnswer {
            state.value = state.value.copy(username = "another-viewer")
            listOf(Movie(id = 77, name = "Movie 77", providerId = 5, tmdbId = 603).toEntity())
        }

        assertEquals(TraktHistorySyncResult.Failure(TraktSyncFailure.ACCOUNT_CHANGED), service().sync())
        verify(source, never()).upload(any(), any())
        Unit
    }

    @Test fun uploads_episode_only_when_catalog_series_and_episode_provider_match() = runBlocking {
        val watchedAt = 1_700_000_000_123L
        val row = PlaybackHistory(contentId = 10, contentType = ContentType.SERIES_EPISODE, providerId = 5,
            title = "Show - Episode", streamUrl = "https://provider.test/episode", lastWatchedAt = watchedAt,
            watchedStatus = PlaybackWatchedStatus.COMPLETED_MANUAL, seriesId = 42, seasonNumber = 2, episodeNumber = 3)
        whenever(history.getRecentlyWatched(5001)).thenReturn(flowOf(listOf(row)))
        whenever(series.getByIdsSync(listOf(42))).thenReturn(listOf(
            Series(id = 42, name = "Show", providerId = 5, tmdbId = 1396).toEntity()))
        whenever(episodes.getEntitiesBySeriesSync(42)).thenReturn(listOf(
            Episode(id = 10, title = "Episode", seasonNumber = 2, episodeNumber = 3,
                seriesId = 42, providerId = 5).toEntity(),
            Episode(id = 11, title = "Wrong provider", seasonNumber = 2, episodeNumber = 3,
                seriesId = 42, providerId = 8).toEntity()))
        whenever(source.upload(eq("viewer"), any())).thenReturn(TraktHistoryUploadResult.Success(1))

        val result = service().sync()

        assertEquals(TraktHistorySyncResult.Success(0, 0, 0, 0, uploaded = 1, moreUploads = false), result)
        val events = argumentCaptor<List<TraktHistoryUpload>>()
        verify(source).upload(eq("viewer"), events.capture())
        assertEquals(listOf(TraktHistoryUpload(TraktMediaIdentity.EpisodeId(1396, 2, 3), watchedAt)), events.firstValue)
    }
}
