package com.streamvault.data.trakt

import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.domain.trakt.TraktAccountState
import com.streamvault.domain.trakt.TraktMessage
import com.streamvault.domain.trakt.TraktWatchlistIdentity
import com.streamvault.domain.trakt.TraktWatchlistPage
import com.streamvault.domain.trakt.TraktWatchlistPageResult
import com.streamvault.domain.trakt.TraktWatchlistRefreshResult
import com.streamvault.domain.trakt.TraktWatchlistSource
import com.streamvault.domain.trakt.TraktWatchlistType
import com.streamvault.domain.trakt.TraktWatchlistWriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TraktWatchlistServiceTest {
    private class Account : TraktAccount {
        val mutableState = MutableStateFlow(TraktAccountState(configured = true, connected = true, username = "viewer"))
        override val state = mutableState
        override suspend fun load() = Unit
        override suspend fun beginSignIn() = error("Not used")
        override suspend fun completeSignIn(callback: String) = error("Not used")
        override suspend fun cancelSignIn() = Unit
        override suspend fun disconnect() { mutableState.value = mutableState.value.copy(connected = false, username = null) }
        override suspend fun refreshProfile() = Unit
    }

    private class Source : TraktWatchlistSource {
        val pages = mutableMapOf<Pair<TraktWatchlistType, Int>, TraktWatchlistPageResult>()
        val reads = mutableListOf<Pair<TraktWatchlistType, Int>>()
        val writes = mutableListOf<Pair<TraktWatchlistIdentity, Boolean>>()
        var writeResult: TraktWatchlistWriteResult = TraktWatchlistWriteResult.Success
        var onRead: (() -> Unit)? = null

        override suspend fun watchlist(type: TraktWatchlistType, page: Int): TraktWatchlistPageResult {
            reads += type to page
            onRead?.invoke()
            return pages[type to page] ?: TraktWatchlistPageResult.Success(TraktWatchlistPage(emptyList(), page, false))
        }

        override suspend fun setWatchlisted(identity: TraktWatchlistIdentity, watchlisted: Boolean): TraktWatchlistWriteResult {
            writes += identity to watchlisted
            return writeResult
        }
    }

    private fun service(account: Account, source: Source, incognito: Boolean = false): TraktWatchlistService {
        val history: PlaybackHistoryRepository = mock()
        runBlocking { whenever(history.isIncognitoModeEnabled()).thenReturn(incognito) }
        return TraktWatchlistService(account, source, history)
    }

    @Test fun `refresh stages movie and show pages and cached refresh avoids repeated reads`() = runBlocking {
        val account = Account()
        val source = Source()
        val movie = TraktWatchlistIdentity(TraktWatchlistType.MOVIE, 603)
        val show = TraktWatchlistIdentity(TraktWatchlistType.SHOW, 1396)
        source.pages[TraktWatchlistType.MOVIE to 1] = TraktWatchlistPageResult.Success(
            TraktWatchlistPage(listOf(movie), page = 1, hasMore = true))
        source.pages[TraktWatchlistType.MOVIE to 2] = TraktWatchlistPageResult.Success(
            TraktWatchlistPage(emptyList(), page = 2, hasMore = false))
        source.pages[TraktWatchlistType.SHOW to 1] = TraktWatchlistPageResult.Success(
            TraktWatchlistPage(listOf(show), page = 1, hasMore = false, skippedItems = 2))
        val service = service(account, source)

        assertEquals(TraktWatchlistRefreshResult.Success(2, skipped = 2), service.refresh())
        assertEquals(setOf(movie, show), service.state.value.items)
        assertTrue(service.state.value.loaded)
        val readsAfterInitial = source.reads.toList()
        assertEquals(TraktWatchlistRefreshResult.Success(2), service.refresh())
        assertEquals(readsAfterInitial, source.reads)

        assertEquals(TraktWatchlistRefreshResult.Success(2, skipped = 2), service.refresh(force = true))
        assertEquals(6, source.reads.size)
    }

    @Test fun `set action changes only the exact item after successful remote write`() = runBlocking {
        val account = Account()
        val source = Source()
        val service = service(account, source)
        val movie = TraktWatchlistIdentity(TraktWatchlistType.MOVIE, 603)
        val show = TraktWatchlistIdentity(TraktWatchlistType.SHOW, 1396)
        assertTrue(service.refresh() is TraktWatchlistRefreshResult.Success)

        assertEquals(com.streamvault.domain.trakt.TraktWatchlistActionResult.Success,
            service.setWatchlisted(movie, true))
        assertEquals(setOf(movie), service.state.value.items)
        assertEquals(com.streamvault.domain.trakt.TraktWatchlistActionResult.Success,
            service.setWatchlisted(show, false))
        assertEquals(listOf(movie to true), source.writes)
        assertEquals(com.streamvault.domain.trakt.TraktWatchlistActionResult.Success,
            service.setWatchlisted(movie, false))
        assertTrue(service.state.value.items.isEmpty())
        assertEquals(listOf(movie to true, movie to false), source.writes)
    }

    @Test fun `incognito prevents watchlist reads and writes`() = runBlocking {
        val source = Source()
        val service = service(Account(), source, incognito = true)
        assertEquals(TraktWatchlistRefreshResult.Failure(com.streamvault.domain.trakt.TraktWatchlistFailure.INCOGNITO),
            service.refresh())
        assertEquals(com.streamvault.domain.trakt.TraktWatchlistActionResult.Failure(
            com.streamvault.domain.trakt.TraktWatchlistFailure.INCOGNITO), service.setWatchlisted(
            TraktWatchlistIdentity(TraktWatchlistType.MOVIE, 603), true))
        assertTrue(source.reads.isEmpty())
        assertTrue(source.writes.isEmpty())
    }

    @Test fun `account change during pagination discards staged items`() = runBlocking {
        val account = Account()
        val source = Source()
        source.pages[TraktWatchlistType.MOVIE to 1] = TraktWatchlistPageResult.Success(TraktWatchlistPage(
            listOf(TraktWatchlistIdentity(TraktWatchlistType.MOVIE, 603)), 1, hasMore = true))
        source.onRead = { account.mutableState.value = account.mutableState.value.copy(username = "other") }
        val service = service(account, source)

        assertEquals(TraktWatchlistRefreshResult.Failure(com.streamvault.domain.trakt.TraktWatchlistFailure.ACCOUNT_CHANGED),
            service.refresh())
        assertTrue(service.state.value.items.isEmpty())
        assertFalse(service.state.value.loaded)
    }

    @Test fun `failed pagination leaves the staged list unavailable`() = runBlocking {
        val source = Source()
        source.pages[TraktWatchlistType.MOVIE to 1] = TraktWatchlistPageResult.Success(TraktWatchlistPage(
            listOf(TraktWatchlistIdentity(TraktWatchlistType.MOVIE, 603)), 1, hasMore = true))
        source.pages[TraktWatchlistType.MOVIE to 2] = TraktWatchlistPageResult.Failed
        val service = service(Account(), source)

        assertEquals(TraktWatchlistRefreshResult.Failure(com.streamvault.domain.trakt.TraktWatchlistFailure.REQUEST_FAILED),
            service.refresh())
        assertTrue(service.state.value.items.isEmpty())
        assertFalse(service.state.value.loaded)
    }

    @Test fun `failed write clears busy state and does not claim the remote change`() = runBlocking {
        val source = Source().apply { writeResult = TraktWatchlistWriteResult.Failed }
        val service = service(Account(), source)
        val movie = TraktWatchlistIdentity(TraktWatchlistType.MOVIE, 603)
        assertTrue(service.refresh() is TraktWatchlistRefreshResult.Success)

        assertEquals(com.streamvault.domain.trakt.TraktWatchlistActionResult.Failure(
            com.streamvault.domain.trakt.TraktWatchlistFailure.REQUEST_FAILED), service.setWatchlisted(movie, true))
        assertTrue(service.state.value.items.isEmpty())
        assertFalse(service.state.value.loaded)
        assertTrue(service.state.value.updating.isEmpty())
    }
}
