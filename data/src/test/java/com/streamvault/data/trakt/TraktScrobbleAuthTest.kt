package com.streamvault.data.trakt

import com.streamvault.domain.trakt.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TraktScrobbleAuthTest {
    private class Store(var session: TraktStoredSession) : TraktTokenStore {
        override fun read() = session
        override fun write(session: TraktStoredSession) { this.session = session }
    }
    private class Api : TraktApi, TraktPlaybackApi {
        var refreshes = 0
        val writes = mutableListOf<String>()
        var failure: TraktApiException? = null
        var rejectOld = false
        override suspend fun exchange(code: String, verifier: String) = error("Not used")
        override suspend fun refresh(refreshToken: String): TraktTokens {
            refreshes++
            delay(10)
            return TraktTokens("new-access", "new-refresh", 1000, 1000)
        }
        override suspend fun username(accessToken: String) = "viewer"
        override suspend fun scrobble(accessToken: String, event: TraktScrobbleEvent): Boolean {
            writes += accessToken
            failure?.let { throw it }
            if (rejectOld && accessToken == "old-access") throw TraktApiException(401)
            return true
        }
    }
    private val event = TraktScrobbleEvent(TraktMediaIdentity.MovieId(603), TraktScrobbleAction.START, 20.0)
    private fun service(store: Store, api: Api) = TraktAuthService(
        TraktConfig("client", "https://example.org/callback"), store, api, now = { 1000 })
    private fun tokens(expired: Boolean = false) = TraktTokens("old-access", "old-refresh", if (expired) 1 else 1000, 1000)

    @Test fun `concurrent playback writes rotate expired pair once and persist replacement`() = runBlocking {
        val store = Store(TraktStoredSession(tokens(true)))
        val api = Api()
        val service = service(store, api)
        val outcomes = coroutineScope { List(3) { async { service.send(event) } }.awaitAll() }
        assertTrue(outcomes.all { it == TraktScrobbleOutcome.SENT })
        assertEquals(1, api.refreshes)
        assertEquals(listOf("new-access", "new-access", "new-access"), api.writes)
        assertTrue(store.read().tokens?.refreshToken == "new-refresh")
    }

    @Test fun `explicit unauthorized response refreshes once then resubmits with replacement`() = runBlocking {
        val store = Store(TraktStoredSession(tokens()))
        val api = Api().apply { rejectOld = true }
        assertEquals(TraktScrobbleOutcome.SENT, service(store, api).send(event))
        assertEquals(1, api.refreshes)
        assertEquals(listOf("old-access", "new-access"), api.writes)
    }

    @Test fun `second unauthorized response clears session without retry loop`() = runBlocking {
        val store = Store(TraktStoredSession(tokens()))
        val api = Api().apply { failure = TraktApiException(401) }
        val service = service(store, api)
        assertEquals(TraktScrobbleOutcome.FAILED, service.send(event))
        assertEquals(2, api.writes.size)
        assertEquals(1, api.refreshes)
        assertNull(store.read().tokens)
        assertEquals(TraktMessage.RECONNECT_REQUIRED, service.state.value.message)
    }

    @Test fun `disconnected and rate limited writes do not retry or refresh`() = runBlocking {
        val api = Api()
        assertEquals(TraktScrobbleOutcome.DISCONNECTED,
            service(Store(TraktStoredSession()), api).send(event))
        assertTrue(api.writes.isEmpty())
        api.failure = TraktApiException(429)
        assertEquals(TraktScrobbleOutcome.FAILED,
            service(Store(TraktStoredSession(tokens())), api).send(event))
        assertEquals(1, api.writes.size)
        assertEquals(0, api.refreshes)
    }

    @Test fun `duplicate stop is terminal and does not repeat a history write`() = runBlocking {
        val api = Api().apply { failure = TraktApiException(409) }
        assertEquals(TraktScrobbleOutcome.DUPLICATE,
            service(Store(TraktStoredSession(tokens())), api).send(event.copy(action = TraktScrobbleAction.STOP)))
        assertEquals(1, api.writes.size)
    }
}
