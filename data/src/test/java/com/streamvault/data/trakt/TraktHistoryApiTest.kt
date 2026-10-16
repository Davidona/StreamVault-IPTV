package com.streamvault.data.trakt

import com.streamvault.domain.trakt.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TraktHistoryApiTest {
    private fun api(server: MockWebServer) = TraktApiClient(
        TraktConfig("client", "https://example.org/callback"), OkHttpClient(), server.url("/"))
    @Test fun `mixed history uses exact IDs timestamps headers and pagination`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("X-Pagination-Page-Count", "3").setBody("""[
                {"id":9007199254740993,"type":"movie","watched_at":"2026-10-11T12:00:00Z","movie":{"ids":{"tmdb":603}}},
                {"id":2,"type":"episode","watched_at":"2026-10-11T12:30:00Z","show":{"ids":{"tmdb":1396}},"episode":{"season":0,"number":3}},
                {"id":3,"type":"movie","watched_at":"bad-date","movie":{"ids":{"tmdb":603}}},
                {"id":4,"type":"movie","watched_at":"2026-10-11T12:00:00Z","movie":{"title":"The Matrix"}}
            ]"""))
            val page = api(server).history("test-access", 2)
            assertEquals(2, page.page)
            assertTrue(page.hasMore)
            assertEquals(2, page.skippedEntries)
            assertEquals(9007199254740993L, page.entries[0].historyId)
            assertEquals(TraktMediaIdentity.MovieId(603), page.entries[0].media)
            assertEquals(Instant.parse("2026-10-11T12:00:00Z").toEpochMilli(), page.entries[0].watchedAtMs)
            assertEquals(TraktMediaIdentity.EpisodeId(1396, 0, 3), page.entries[1].media)
            val request = server.takeRequest()
            assertEquals("/sync/history?page=2&limit=100", request.path)
            assertEquals("GET", request.method)
            assertEquals("Bearer test-access", request.getHeader("Authorization"))
            assertEquals("client", request.getHeader("trakt-api-key"))
        }
    }

    @Test fun `missing pagination header uses raw row count even if every row is skipped`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(List(100) { "{}" }.joinToString(",", "[", "]")))
            server.enqueue(MockResponse().setBody("[]"))
            val api = api(server)
            val first = api.history("test-access", 1)
            assertTrue(first.hasMore)
            assertEquals(100, first.skippedEntries)
            assertFalse(api.history("test-access", 2).hasMore)
        }
    }

    @Test fun `fractional IDs and episode numbers are not truncated into another media identity`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[
                {"id":1,"type":"movie","watched_at":"2026-10-11T12:00:00Z","movie":{"ids":{"tmdb":603.5}}},
                {"id":2,"type":"episode","watched_at":"2026-10-11T12:00:00Z","show":{"ids":{"tmdb":1396}},"episode":{"season":2,"number":3.5}}
            ]"""))
            val page = api(server).history("test-access", 1)
            assertTrue(page.entries.isEmpty())
            assertEquals(2, page.skippedEntries)
        }
    }
}
