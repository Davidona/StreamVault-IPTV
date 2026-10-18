package com.streamvault.data.trakt

import com.streamvault.domain.trakt.TraktFavoriteIdentity
import com.streamvault.domain.trakt.TraktFavoriteType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktFavoritesApiTest {
    private fun api(server: MockWebServer) = TraktApiClient(
        TraktConfig("client", "https://example.org/callback"), OkHttpClient(), server.url("/"))

    @Test fun `favorites pages parse exact movie and show TMDB ids`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("X-Pagination-Page-Count", "2").setBody("""[
                {"rank":1,"movie":{"title":"Movie","ids":{"tmdb":603}}},
                {"rank":2,"movie":{"title":"Missing id","ids":{"trakt":2}}}
            ]"""))
            server.enqueue(MockResponse().setBody("""[
                {"rank":1,"show":{"title":"Show","ids":{"tmdb":1396}}}
            ]"""))
            val api = api(server)

            val movies = api.favorites("token", TraktFavoriteType.MOVIE, 1)
            val shows = api.favorites("token", TraktFavoriteType.SHOW, 1)

            assertEquals(listOf(TraktFavoriteIdentity(TraktFavoriteType.MOVIE, 603)), movies.identities)
            assertEquals(1, movies.skippedItems)
            assertTrue(movies.hasMore)
            assertEquals(listOf(TraktFavoriteIdentity(TraktFavoriteType.SHOW, 1396)), shows.identities)
            assertEquals("/sync/favorites/movies?page=1&limit=100", server.takeRequest().path)
            assertEquals("/sync/favorites/shows?page=1&limit=100", server.takeRequest().path)
        }
    }
}
