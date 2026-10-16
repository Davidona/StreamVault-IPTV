package com.streamvault.data.trakt

import com.streamvault.domain.trakt.TraktWatchlistIdentity
import com.streamvault.domain.trakt.TraktWatchlistType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktWatchlistApiTest {
    private fun api(server: MockWebServer) = TraktApiClient(
        TraktConfig("client", "https://example.org/callback"), OkHttpClient(), server.url("/"))

    @Test fun `watchlist pages parse exact movie and show TMDB ids`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("X-Pagination-Page-Count", "2").setBody("""[
                {"rank":1,"movie":{"title":"Movie","ids":{"tmdb":603}}},
                {"rank":2,"movie":{"title":"Missing id","ids":{"trakt":2}}}
            ]"""))
            server.enqueue(MockResponse().setBody("""[
                {"rank":1,"show":{"title":"Show","ids":{"tmdb":1396}}}
            ]"""))
            val api = api(server)

            val movies = api.watchlist("token", TraktWatchlistType.MOVIE, 1)
            val shows = api.watchlist("token", TraktWatchlistType.SHOW, 1)

            assertEquals(listOf(TraktWatchlistIdentity(TraktWatchlistType.MOVIE, 603)), movies.items)
            assertEquals(1, movies.skippedItems)
            assertTrue(movies.hasMore)
            assertEquals(listOf(TraktWatchlistIdentity(TraktWatchlistType.SHOW, 1396)), shows.items)
            assertFalse(shows.hasMore)
            assertEquals("/sync/watchlist/movies?page=1&limit=100", server.takeRequest().path)
            val showRequest = server.takeRequest()
            assertEquals("/sync/watchlist/shows?page=1&limit=100", showRequest.path)
            assertEquals("Bearer token", showRequest.getHeader("Authorization"))
            assertEquals("client", showRequest.getHeader("trakt-api-key"))
        }
    }

    @Test fun `watchlist writes use only exact TMDB ids and add or remove endpoint`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("{}"))
            val api = api(server)

            api.setWatchlisted("token", TraktWatchlistIdentity(TraktWatchlistType.MOVIE, 603), true)
            api.setWatchlisted("token", TraktWatchlistIdentity(TraktWatchlistType.SHOW, 1396), false)

            val add = server.takeRequest()
            assertEquals("POST", add.method)
            assertEquals("/sync/watchlist", add.path)
            assertEquals("""{"movies":[{"ids":{"tmdb":603}}]}""", add.body.readUtf8())
            assertEquals("Bearer token", add.getHeader("Authorization"))
            val remove = server.takeRequest()
            assertEquals("POST", remove.method)
            assertEquals("/sync/watchlist/remove", remove.path)
            assertEquals("""{"shows":[{"ids":{"tmdb":1396}}]}""", remove.body.readUtf8())
        }
    }

    @Test fun `bad watchlist identifiers are skipped and pagination falls back to raw row count`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(List(100) { "{}" }.joinToString(",", "[", "]")))
            val page = api(server).watchlist("token", TraktWatchlistType.MOVIE, 1)
            assertTrue(page.hasMore)
            assertEquals(100, page.skippedItems)
            assertTrue(page.items.isEmpty())
        }
    }

    @Test fun `fractional TMDB IDs are skipped rather than truncated`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[
                {"rank":1,"movie":{"title":"Fractional","ids":{"tmdb":603.5}}}
            ]"""))
            val page = api(server).watchlist("token", TraktWatchlistType.MOVIE, 1)
            assertTrue(page.items.isEmpty())
            assertEquals(1, page.skippedItems)
        }
    }
}
