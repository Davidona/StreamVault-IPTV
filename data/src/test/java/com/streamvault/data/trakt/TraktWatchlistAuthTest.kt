package com.streamvault.data.trakt

import com.streamvault.domain.trakt.TraktWatchlistIdentity
import com.streamvault.domain.trakt.TraktWatchlistPage
import com.streamvault.domain.trakt.TraktWatchlistPageResult
import com.streamvault.domain.trakt.TraktWatchlistType
import com.streamvault.domain.trakt.TraktWatchlistWriteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktWatchlistAuthTest {
    private class Store(var session: TraktStoredSession) : TraktTokenStore {
        override fun read() = session
        override fun write(session: TraktStoredSession) { this.session = session }
    }

    private class Api : TraktApi, TraktWatchlistApi {
        var refreshes = 0
        val reads = mutableListOf<String>()
        val writes = mutableListOf<String>()
        var rejectOld = false
        var unauthorized = false
        var writeFailure: Exception? = null

        override suspend fun exchange(code: String, verifier: String) = error("Not used")
        override suspend fun refresh(refreshToken: String): TraktTokens {
            refreshes++
            return TraktTokens("new-access", "new-refresh", 1000, 1000)
        }
        override suspend fun username(accessToken: String) = "viewer"
        override suspend fun watchlist(accessToken: String, type: TraktWatchlistType, page: Int): TraktWatchlistPage {
            reads += accessToken
            if (unauthorized || (rejectOld && accessToken == "old-access")) throw TraktApiException(401)
            return TraktWatchlistPage(emptyList(), page, false)
        }
        override suspend fun setWatchlisted(
            accessToken: String,
            identity: TraktWatchlistIdentity,
            watchlisted: Boolean,
        ) {
            writes += accessToken
            writeFailure?.let { throw it }
            if (unauthorized || (rejectOld && accessToken == "old-access")) throw TraktApiException(401)
        }
    }

    private fun tokens() = TraktTokens("old-access", "old-refresh", 1000, 1000)
    private fun service(store: Store, api: Api) = TraktAuthService(
        TraktConfig("client", "https://example.org/callback"), store, api, now = { 1000 })
    private val identity = TraktWatchlistIdentity(TraktWatchlistType.MOVIE, 603)

    @Test fun `watchlist read retries exactly once after explicit unauthorized`() = runBlocking {
        val api = Api().apply { rejectOld = true }
        val store = Store(TraktStoredSession(tokens()))
        val source = service(store, api)

        assertTrue(source.watchlist(TraktWatchlistType.MOVIE, 1) is TraktWatchlistPageResult.Success)
        assertEquals(listOf("old-access", "new-access"), api.reads)
        assertEquals(1, api.refreshes)
        assertEquals("new-refresh", store.read().tokens?.refreshToken)
    }

    @Test fun `watchlist write retries a rejected token once but never retries ambiguous failure`() = runBlocking {
        val api = Api().apply { rejectOld = true }
        val store = Store(TraktStoredSession(tokens()))
        val source = service(store, api)
        assertEquals(TraktWatchlistWriteResult.Success, source.setWatchlisted(identity, true))
        assertEquals(listOf("old-access", "new-access"), api.writes)
        assertEquals(1, api.refreshes)

        api.writeFailure = java.io.IOException("timeout")
        assertEquals(TraktWatchlistWriteResult.Failed, source.setWatchlisted(identity, false))
        assertEquals(3, api.writes.size)
        assertEquals(1, api.refreshes)
        assertTrue(store.read().tokens != null)
    }

    @Test fun `second unauthorized watchlist read clears credentials and requires reconnect`() = runBlocking {
        val api = Api().apply { unauthorized = true }
        val store = Store(TraktStoredSession(tokens()))
        val source = service(store, api)

        assertEquals(TraktWatchlistPageResult.Disconnected, source.watchlist(TraktWatchlistType.SHOW, 1))
        assertEquals(2, api.reads.size)
        assertEquals(1, api.refreshes)
        assertNull(store.read().tokens)
    }
}
