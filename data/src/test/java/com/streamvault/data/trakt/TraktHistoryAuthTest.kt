package com.streamvault.data.trakt

import com.streamvault.domain.trakt.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TraktHistoryAuthTest {
    private class Store(var session: TraktStoredSession) : TraktTokenStore {
        override fun read() = session
        override fun write(session: TraktStoredSession) { this.session = session }
    }
    private class Api : TraktApi, TraktPlaybackApi, TraktHistoryApi {
        var refreshes = 0
        val reads = mutableListOf<String>()
        val writes = mutableListOf<String>()
        var rejectOld = false
        var failure: TraktApiException? = null
        override suspend fun exchange(code: String, verifier: String) = error("Not used")
        override suspend fun refresh(refreshToken: String): TraktTokens {
            refreshes++
            delay(10)
            return TraktTokens("new-access", "new-refresh", 1000, 1000)
        }
        override suspend fun username(accessToken: String) = "viewer"
        override suspend fun scrobble(accessToken: String, event: TraktScrobbleEvent): Boolean {
            writes += accessToken
            return true
        }
        override suspend fun history(accessToken: String, page: Int): TraktHistoryPage {
            reads += accessToken
            failure?.let { throw it }
            if (rejectOld && accessToken == "old-access") throw TraktApiException(401)
            return TraktHistoryPage(emptyList(), page, false)
        }
        override suspend fun upload(accessToken: String,
            events: List<TraktHistoryUpload>): TraktHistoryUploadResult = TraktHistoryUploadResult.Success(events.size)
    }
    private fun tokens(expired: Boolean = false) = TraktTokens("old-access", "old-refresh", if (expired) 1 else 1000, 1000)
    private fun service(store: Store, api: Api) = TraktAuthService(
        TraktConfig("client", "https://example.org/callback"), store, api, now = { 1000 })

    @Test fun `history reads and playback writes share one rotating token lock`() = runBlocking {
        val api = Api()
        val store = Store(TraktStoredSession(tokens(true)))
        val service = service(store, api)
        coroutineScope {
            val read = async { service.history(1) }
            val write = async { service.send(TraktScrobbleEvent(TraktMediaIdentity.MovieId(603), TraktScrobbleAction.START, 10.0)) }
            assertTrue(read.await() is TraktHistoryResult.Success)
            assertEquals(TraktScrobbleOutcome.SENT, write.await())
        }
        assertEquals(1, api.refreshes)
        assertEquals(listOf("new-access"), api.reads)
        assertEquals(listOf("new-access"), api.writes)
        assertTrue(store.read().tokens?.refreshToken == "new-refresh")
    }

    @Test fun `explicit history unauthorized response refreshes once`() = runBlocking {
        val api = Api().apply { rejectOld = true }
        val result = service(Store(TraktStoredSession(tokens())), api).history(2)
        assertEquals(2, (result as TraktHistoryResult.Success).history.page)
        assertEquals(listOf("old-access", "new-access"), api.reads)
        assertEquals(1, api.refreshes)
    }

    @Test fun `second unauthorized history response clears tokens and requests reconnect`() = runBlocking {
        val api = Api().apply { failure = TraktApiException(401) }
        val store = Store(TraktStoredSession(tokens()))
        assertEquals(TraktHistoryResult.Failure(TraktMessage.RECONNECT_REQUIRED), service(store, api).history(1))
        assertNull(store.read().tokens)
        assertEquals(2, api.reads.size)
        assertEquals(1, api.refreshes)
    }

    @Test fun `disconnected and rate limited reads do not retry`() = runBlocking {
        val api = Api()
        assertEquals(TraktHistoryResult.Disconnected, service(Store(TraktStoredSession()), api).history(1))
        assertTrue(api.reads.isEmpty())
        api.failure = TraktApiException(429)
        val store = Store(TraktStoredSession(tokens()))
        assertEquals(TraktHistoryResult.Failure(TraktMessage.RATE_LIMITED), service(store, api).history(1))
        assertEquals(1, api.reads.size)
        assertEquals(0, api.refreshes)
        assertNotNull(store.read().tokens)
    }
}
