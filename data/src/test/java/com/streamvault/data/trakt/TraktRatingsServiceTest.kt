package com.streamvault.data.trakt

import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.domain.trakt.TraktAccountState
import com.streamvault.domain.trakt.TraktRatingActionResult
import com.streamvault.domain.trakt.TraktRatingFailure
import com.streamvault.domain.trakt.TraktRatingIdentity
import com.streamvault.domain.trakt.TraktRatingPage
import com.streamvault.domain.trakt.TraktRatingPageResult
import com.streamvault.domain.trakt.TraktRatingRefreshResult
import com.streamvault.domain.trakt.TraktRatingType
import com.streamvault.domain.trakt.TraktRatingWriteResult
import com.streamvault.domain.trakt.TraktRatingsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TraktRatingsServiceTest {
    private class Account(connected: Boolean = true) : TraktAccount {
        val mutableState = MutableStateFlow(
            TraktAccountState(configured = true, connected = connected, username = if (connected) "viewer" else null),
        )
        override val state = mutableState
        override suspend fun load() = Unit
        override suspend fun beginSignIn() = error("Not used")
        override suspend fun completeSignIn(callback: String) = error("Not used")
        override suspend fun cancelSignIn() = Unit
        override suspend fun disconnect() { mutableState.value = mutableState.value.copy(connected = false, username = null) }
        override suspend fun refreshProfile() = Unit
    }

    private class Source : TraktRatingsSource {
        val writes = mutableListOf<Pair<TraktRatingIdentity, Int>>()
        var result: TraktRatingWriteResult = TraktRatingWriteResult.Success
        var afterWrite: (() -> Unit)? = null
        val reads = mutableListOf<Pair<TraktRatingType, Int>>()
        var pages: MutableMap<Int, TraktRatingPage> = mutableMapOf()
        var failedReadPage: Int? = null

        override suspend fun ratings(type: TraktRatingType, page: Int): TraktRatingPageResult {
            reads += type to page
            if (failedReadPage == page) return TraktRatingPageResult.Failed
            return TraktRatingPageResult.Success(pages[page] ?: TraktRatingPage(emptyList(), page, false))
        }

        override suspend fun setRating(identity: TraktRatingIdentity, rating: Int): TraktRatingWriteResult {
            writes += identity to rating
            afterWrite?.invoke()
            return result
        }
    }

    private fun service(account: Account, source: Source, incognito: Boolean = false): TraktRatingsService {
        val history: PlaybackHistoryRepository = mock()
        runBlocking { whenever(history.isIncognitoModeEnabled()).thenReturn(incognito) }
        return TraktRatingsService(account, source, history)
    }

    @Test fun `writes exact movie and show ratings`() = runBlocking {
        val source = Source()
        val service = service(Account(), source)
        val movie = TraktRatingIdentity(TraktRatingType.MOVIE, 603)
        val show = TraktRatingIdentity(TraktRatingType.SHOW, 1396)

        assertEquals(TraktRatingActionResult.Success, service.setRating(movie, 8))
        assertEquals(TraktRatingActionResult.Success, service.setRating(show, 10))
        assertEquals(listOf(movie to 8, show to 10), source.writes)
    }

    @Test fun `refresh stages all pages before replacing type cache and honors cache`() = runBlocking {
        val first = TraktRatingIdentity(TraktRatingType.MOVIE, 603)
        val second = TraktRatingIdentity(TraktRatingType.MOVIE, 550)
        val show = TraktRatingIdentity(TraktRatingType.SHOW, 1396)
        val source = Source().apply {
            pages[1] = TraktRatingPage(listOf(first to 8), 1, hasMore = true)
            pages[2] = TraktRatingPage(listOf(second to 9), 2, hasMore = false)
        }
        val service = service(Account(), source)

        assertEquals(TraktRatingRefreshResult.Success(2), service.refresh(TraktRatingType.MOVIE))
        assertEquals(8, service.state.value.rating(first))
        assertEquals(9, service.state.value.rating(second))
        assertEquals(TraktRatingRefreshResult.Success(2), service.refresh(TraktRatingType.MOVIE))
        assertEquals(listOf(TraktRatingType.MOVIE to 1, TraktRatingType.MOVIE to 2), source.reads)

        assertEquals(TraktRatingActionResult.Success, service.setRating(show, 10))
        assertEquals(10, service.state.value.rating(show))
    }

    @Test fun `failed later page keeps prior complete snapshot`() = runBlocking {
        val old = TraktRatingIdentity(TraktRatingType.MOVIE, 603)
        val source = Source().apply { pages[1] = TraktRatingPage(listOf(old to 7), 1, false) }
        val service = service(Account(), source)
        service.refresh(TraktRatingType.MOVIE)

        source.pages[1] = TraktRatingPage(
            listOf(TraktRatingIdentity(TraktRatingType.MOVIE, 550) to 8), 1, true,
        )
        source.failedReadPage = 2
        assertEquals(
            TraktRatingRefreshResult.Failure(TraktRatingFailure.REQUEST_FAILED),
            service.refresh(TraktRatingType.MOVIE, force = true),
        )
        assertEquals(7, service.state.value.rating(old))
    }

    @Test fun `disconnected account and incognito do not write`() = runBlocking {
        val source = Source()
        val identity = TraktRatingIdentity(TraktRatingType.MOVIE, 603)
        assertEquals(
            TraktRatingActionResult.Failure(TraktRatingFailure.DISCONNECTED),
            service(Account(connected = false), source).setRating(identity, 7),
        )
        assertEquals(
            TraktRatingActionResult.Failure(TraktRatingFailure.INCOGNITO),
            service(Account(), source, incognito = true).setRating(identity, 7),
        )
        assertTrue(source.writes.isEmpty())
    }

    @Test fun `failed write and account change are reported`() = runBlocking {
        val identity = TraktRatingIdentity(TraktRatingType.SHOW, 1396)
        val failedSource = Source().apply { result = TraktRatingWriteResult.Failed }
        assertEquals(
            TraktRatingActionResult.Failure(TraktRatingFailure.REQUEST_FAILED),
            service(Account(), failedSource).setRating(identity, 4),
        )

        val account = Account()
        val changingSource = Source().apply {
            afterWrite = { account.mutableState.value = account.mutableState.value.copy(username = "other") }
        }
        assertEquals(
            TraktRatingActionResult.Failure(TraktRatingFailure.ACCOUNT_CHANGED),
            service(account, changingSource).setRating(identity, 4),
        )
    }
}
