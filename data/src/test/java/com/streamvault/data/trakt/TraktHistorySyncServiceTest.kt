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

class TraktHistorySyncServiceTest {
    private val account = mock<TraktAccount>()
    private val state = MutableStateFlow(TraktAccountState(configured = true, connected = true, username = "viewer"))
    private val source = mock<TraktHistorySource>()
    private val movies = mock<MovieDao>()
    private val series = mock<SeriesDao>()
    private val episodes = mock<EpisodeDao>()
    private val history = mock<PlaybackHistoryRepository>()
    private val identity = TraktMediaIdentity.MovieId(603)
    init {
        whenever(account.state).thenReturn(state)
        whenever(history.getRecentlyWatched(5001)).thenReturn(flowOf(emptyList()))
        runBlocking { whenever(history.isIncognitoModeEnabled()).thenReturn(false) }
    }
    private fun service() = TraktHistorySyncService(account, source, movies, series, episodes, history)
    private fun page(number: Int = 1, more: Boolean = false) = TraktHistoryResult.Success(
        TraktHistoryPage(listOf(TraktHistoryEntry(1, identity, 2000)), number, more))

    @Test fun `all pages stage before writes and duplicate history IDs are not extra plays`() = runBlocking {
        whenever(source.history(1)).thenReturn(page(1, true))
        whenever(source.history(2)).thenReturn(page(2))
        whenever(movies.getByTmdbIdsSync(listOf(603))).thenReturn(listOf(Movie(id = 10, providerId = 5, name = "Movie", tmdbId = 603).toEntity().copy(watchCount = 4)))
        whenever(history.importTraktHistory(any(), eq(identity), any())).thenReturn(Result.success(true))
        assertEquals(TraktHistorySyncResult.Success(1, 0, 0, 0), service().sync())
        val order = inOrder(source, history)
        order.verify(source).history(1)
        order.verify(source).history(2)
        order.verify(history).importTraktHistory(check { assertEquals(4, it.watchCount) }, eq(identity), check { assertEquals(1, it.playCount) })
        Unit
    }

    @Test fun `failed later page applies nothing`() = runBlocking {
        whenever(source.history(1)).thenReturn(page(1, true))
        whenever(source.history(2)).thenReturn(TraktHistoryResult.Failure(TraktMessage.NETWORK_ERROR))
        assertEquals(TraktHistorySyncResult.Failure(TraktSyncFailure.READ_FAILED), service().sync())
        verify(history).isIncognitoModeEnabled()
        verify(history, never()).importTraktHistory(any(), any(), any())
        verify(source, never()).upload(any(), any())
        verifyNoInteractions(movies, series, episodes)
    }

    @Test fun `page limit never applies a partial snapshot`() = runBlocking {
        whenever(source.history(any())).thenAnswer {
            page(it.getArgument(0), true)
        }
        assertEquals(TraktHistorySyncResult.Failure(TraktSyncFailure.READ_LIMIT), service().sync())
        verify(source, times(50)).history(any())
        verify(history).isIncognitoModeEnabled()
        verify(history, never()).importTraktHistory(any(), any(), any())
        verify(source, never()).upload(any(), any())
        verifyNoInteractions(movies, series, episodes)
    }

    @Test fun `account switch after reading aborts before catalog or history access`() = runBlocking {
        whenever(source.history(1)).thenAnswer {
            state.value = state.value.copy(username = "another-viewer")
            page()
        }
        assertEquals(TraktHistorySyncResult.Failure(TraktSyncFailure.ACCOUNT_CHANGED), service().sync())
        verify(history).isIncognitoModeEnabled()
        verify(history, never()).importTraktHistory(any(), any(), any())
        verifyNoInteractions(movies, series, episodes)
    }

    @Test fun `episodes match cached show IDs and exact ownership without provider network calls`() = runBlocking {
        val episodeIdentity = TraktMediaIdentity.EpisodeId(1396, 2, 3)
        whenever(source.history(1)).thenReturn(TraktHistoryResult.Success(
            TraktHistoryPage(listOf(TraktHistoryEntry(1, episodeIdentity, 2000)), 1, false)))
        whenever(series.getByTmdbIdsSync(listOf(1396))).thenReturn(listOf(Series(id = 42, name = "Show", tmdbId = 1396, providerId = 5).toEntity()))
        whenever(episodes.getEntitiesBySeriesSync(42)).thenReturn(listOf(
            Episode(id = 10, title = "Matched", seasonNumber = 2, episodeNumber = 3, seriesId = 42, providerId = 5).toEntity(),
            Episode(id = 11, title = "Wrong provider", seasonNumber = 2, episodeNumber = 3, seriesId = 42, providerId = 6).toEntity()))
        whenever(history.importTraktHistory(any(), eq(episodeIdentity), any())).thenReturn(Result.success(true))
        assertEquals(TraktHistorySyncResult.Success(1, 0, 0, 0), service().sync())
        verify(history).importTraktHistory(check { assertEquals(10L, it.contentId) }, eq(episodeIdentity), any())
        Unit
    }
}
