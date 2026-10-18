package com.streamvault.data.trakt

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.streamvault.domain.trakt.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TraktScrobbleApiTest {
    private fun api(server: MockWebServer) = TraktApiClient(
        TraktConfig("client", "https://example.org/callback"), OkHttpClient(), server.url("/"))

    @Test fun `movie uses attached ID fractional progress and required auth headers`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            assertTrue(api(server).scrobble("test-access", TraktScrobbleEvent(
                TraktMediaIdentity.MovieId(603), TraktScrobbleAction.START, 12.5)))
            val request = server.takeRequest()
            assertEquals("/scrobble/start", request.path)
            assertEquals("POST", request.method)
            assertEquals("Bearer test-access", request.getHeader("Authorization"))
            assertEquals("client", request.getHeader("trakt-api-key"))
            assertEquals("2", request.getHeader("trakt-api-version"))
            val body = Gson().fromJson(request.body.readUtf8(), JsonObject::class.java)
            assertEquals(603, body.getAsJsonObject("movie").getAsJsonObject("ids")["tmdb"].asInt)
            assertEquals(12.5, body["progress"].asDouble, .001)
            assertFalse(body.has("episode"))
        }
    }

    @Test fun `episode resolves exact show and numbers once then reuses episode ID`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"type":"show","show":{"ids":{"tmdb":1396,"trakt":1}}}]"""))
            server.enqueue(MockResponse().setBody("""{"season":2,"number":3,"ids":{"trakt":123}}"""))
            repeat(2) { server.enqueue(MockResponse().setResponseCode(201).setBody("{}")) }
            val api = api(server)
            val event = TraktScrobbleEvent(TraktMediaIdentity.EpisodeId(1396, 2, 3), TraktScrobbleAction.START, 5.0)
            assertTrue(api.scrobble("test-access", event))
            assertTrue(api.scrobble("test-access", event.copy(action = TraktScrobbleAction.PAUSE)))
            assertEquals("/search/tmdb/1396?type=show", server.takeRequest().path)
            assertEquals("/shows/1/seasons/2/episodes/3", server.takeRequest().path)
            val post = server.takeRequest()
            val body = Gson().fromJson(post.body.readUtf8(), JsonObject::class.java)
            assertEquals(123, body.getAsJsonObject("episode").getAsJsonObject("ids")["trakt"].asInt)
            assertFalse(body.has("show"))
            assertEquals("/scrobble/pause", server.takeRequest().path)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun `ambiguous show ID never submits a write`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"type":"show","show":{"ids":{"tmdb":1396,"trakt":1}}},{"type":"show","show":{"ids":{"tmdb":1396,"trakt":2}}}]"""))
            assertFalse(api(server).scrobble("test-access", TraktScrobbleEvent(
                TraktMediaIdentity.EpisodeId(1396, 2, 3), TraktScrobbleAction.STOP, 95.0)))
            assertEquals(1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun `episode numbers returned by lookup must match before writing`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"type":"show","show":{"ids":{"tmdb":1396,"trakt":1}}}]"""))
            server.enqueue(MockResponse().setBody("""{"season":2,"number":4,"ids":{"trakt":123}}"""))
            assertFalse(api(server).scrobble("test-access", TraktScrobbleEvent(
                TraktMediaIdentity.EpisodeId(1396, 2, 3), TraktScrobbleAction.START, 5.0)))
            assertEquals(2, server.requestCount)
        }
    }
}
