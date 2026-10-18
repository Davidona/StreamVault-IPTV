package com.streamvault.data.trakt

import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.domain.trakt.TraktAccountState
import com.streamvault.domain.trakt.TraktFavoriteActionResult
import com.streamvault.domain.trakt.TraktFavoriteFailure
import com.streamvault.domain.trakt.TraktFavoriteIdentity
import com.streamvault.domain.trakt.TraktFavoriteType
import com.streamvault.domain.trakt.TraktFavoriteWriteResult
import com.streamvault.domain.trakt.TraktFavoritesSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TraktFavoritesServiceTest {
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

    private class Source : TraktFavoritesSource {
        val writes = mutableListOf<Pair<TraktFavoriteIdentity, Boolean>>()
        var result: TraktFavoriteWriteResult = TraktFavoriteWriteResult.Success
        var afterWrite: (() -> Unit)? = null

        override suspend fun setFavorited(identity: TraktFavoriteIdentity, favorited: Boolean): TraktFavoriteWriteResult {
            writes += identity to favorited
            afterWrite?.invoke()
            return result
        }
    }

    private fun service(account: Account, source: Source, incognito: Boolean = false): TraktFavoritesService {
        val history: PlaybackHistoryRepository = mock()
        runBlocking { whenever(history.isIncognitoModeEnabled()).thenReturn(incognito) }
        return TraktFavoritesService(account, source, history)
    }

    @Test fun `writes the requested movie and show state`() = runBlocking {
        val source = Source()
        val service = service(Account(), source)
        val movie = TraktFavoriteIdentity(TraktFavoriteType.MOVIE, 603)
        val show = TraktFavoriteIdentity(TraktFavoriteType.SHOW, 1396)

        assertEquals(TraktFavoriteActionResult.Success, service.setFavorited(movie, true))
        assertEquals(TraktFavoriteActionResult.Success, service.setFavorited(show, false))
        assertEquals(listOf(movie to true, show to false), source.writes)
    }

    @Test fun `disconnected account does not send a write`() = runBlocking {
        val source = Source()
        val service = service(Account(connected = false), source)

        assertEquals(
            TraktFavoriteActionResult.Failure(TraktFavoriteFailure.DISCONNECTED),
            service.setFavorited(TraktFavoriteIdentity(TraktFavoriteType.MOVIE, 603), true),
        )
        assertTrue(source.writes.isEmpty())
    }

    @Test fun `incognito prevents writes`() = runBlocking {
        val source = Source()
        val service = service(Account(), source, incognito = true)

        assertEquals(
            TraktFavoriteActionResult.Failure(TraktFavoriteFailure.INCOGNITO),
            service.setFavorited(TraktFavoriteIdentity(TraktFavoriteType.SHOW, 1396), true),
        )
        assertTrue(source.writes.isEmpty())
    }

    @Test fun `failed remote write reports failure without claiming success`() = runBlocking {
        val source = Source().apply { result = TraktFavoriteWriteResult.Failed }
        val service = service(Account(), source)

        assertEquals(
            TraktFavoriteActionResult.Failure(TraktFavoriteFailure.REQUEST_FAILED),
            service.setFavorited(TraktFavoriteIdentity(TraktFavoriteType.MOVIE, 603), true),
        )
    }

    @Test fun `account change during write is reported`() = runBlocking {
        val account = Account()
        val source = Source().apply {
            afterWrite = { account.mutableState.value = account.mutableState.value.copy(username = "other") }
        }
        val service = service(account, source)

        assertEquals(
            TraktFavoriteActionResult.Failure(TraktFavoriteFailure.ACCOUNT_CHANGED),
            service.setFavorited(TraktFavoriteIdentity(TraktFavoriteType.MOVIE, 603), true),
        )
    }
}
