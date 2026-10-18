package com.streamvault.data.trakt

import com.streamvault.domain.trakt.TraktDiscoveryCategory
import com.streamvault.domain.trakt.TraktDiscoveryKind
import com.streamvault.domain.trakt.TraktDiscoveryMedia
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktDiscoveryApiTest {
    private fun api(server: MockWebServer) = TraktApiClient(
        TraktConfig("client", "https://example.org/callback"), OkHttpClient(), server.url("/")
    )

    @Test fun `trending movie wrappers and bare popular show rows preserve order and use public endpoints`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[
                {"watchers":20,"movie":{"title":"First","ids":{"tmdb":603}}},
                {"watchers":8,"movie":{"title":"Second","ids":{"tmdb":550}}}
            ]"""))
            server.enqueue(MockResponse().setBody("""[
                {"title":"Show A","ids":{"tmdb":1396}},
                {"title":"Show B","ids":{"tmdb":4607}}
            ]"""))
            val api = api(server)

            val trending = api.discover(
                TraktDiscoveryCategory(TraktDiscoveryMedia.MOVIE, TraktDiscoveryKind.TRENDING), 1
            )
            val popular = api.discover(
                TraktDiscoveryCategory(TraktDiscoveryMedia.SHOW, TraktDiscoveryKind.POPULAR), 1
            )

            assertEquals(listOf(603L, 550L), trending.tmdbIds)
            assertEquals(listOf(1396L, 4607L), popular.tmdbIds)
            assertEquals("/movies/trending?page=1&limit=100", server.takeRequest().path)
            val popularRequest = server.takeRequest()
            assertEquals("/shows/popular?page=1&limit=100", popularRequest.path)
            assertEquals("client", popularRequest.getHeader("trakt-api-key"))
            assertNull(popularRequest.getHeader("Authorization"))
        }
    }

    @Test fun `invalid and duplicate ids are skipped or deduplicated without losing Trakt rank`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[
                {"movie":{"ids":{"tmdb":603}}},
                {"movie":{"ids":{"tmdb":603}}},
                {"movie":{"ids":{"tmdb":603.5}}},
                {"movie":{"ids":{"trakt":2}}},
                {"movie":{"ids":{"tmdb":-5}}}
            ]"""))

            val page = api(server).discover(
                TraktDiscoveryCategory(TraktDiscoveryMedia.MOVIE, TraktDiscoveryKind.TRENDING), 1
            )

            assertEquals(listOf(603L), page.tmdbIds)
            assertEquals(3, page.skippedItems)
        }
    }
}
