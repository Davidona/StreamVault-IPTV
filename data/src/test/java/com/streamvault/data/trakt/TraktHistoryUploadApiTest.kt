package com.streamvault.data.trakt

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.streamvault.domain.trakt.TraktHistoryUpload
import com.streamvault.domain.trakt.TraktHistoryUploadResult
import com.streamvault.domain.trakt.TraktMediaIdentity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TraktHistoryUploadApiTest {
    private fun api(server: MockWebServer) = TraktApiClient(
        TraktConfig("public-client", "https://example.org/callback"), OkHttpClient(), server.url("/"))

    @Test fun posts_canonical_movie_and_exact_episode_ids_and_counts_response() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"type":"show","show":{"ids":{"tmdb":1396,"trakt":902}}}]"""))
            server.enqueue(MockResponse().setBody("""{"season":2,"number":3,"ids":{"trakt":7003}}"""))
            server.enqueue(MockResponse().setBody("""{"added":{"movies":1,"episodes":0},"updated":{"movies":0,"episodes":1},"not_found":{"movies":[{}],"episodes":[]}}"""))
            val watchedAt = Instant.parse("2023-11-14T22:13:59.999Z").toEpochMilli()

            val result = api(server).upload("access-token", listOf(
                TraktHistoryUpload(TraktMediaIdentity.MovieId(603), watchedAt),
                TraktHistoryUpload(TraktMediaIdentity.EpisodeId(1396, 2, 3), watchedAt),
            ))

            assertEquals(TraktHistoryUploadResult.Success(uploaded = 2, unmatched = 1), result)
            val lookup = server.takeRequest()
            assertEquals("/search/tmdb/1396?type=show", lookup.path)
            assertEquals("Bearer access-token", lookup.getHeader("Authorization"))
            val episodeLookup = server.takeRequest()
            assertEquals("/shows/902/seasons/2/episodes/3", episodeLookup.path)
            val upload = server.takeRequest()
            assertEquals("/sync/history", upload.path)
            assertEquals("Bearer access-token", upload.getHeader("Authorization"))

            val payload = Gson().fromJson(upload.body.readUtf8(), JsonObject::class.java)
            val movie = payload.getAsJsonArray("movies").single().asJsonObject
            assertEquals("2023-11-14T22:13:00Z", movie["watched_at"].asString)
            assertEquals(603L, movie.getAsJsonObject("ids")["tmdb"].asLong)
            val episode = payload.getAsJsonArray("episodes").single().asJsonObject
            assertEquals("2023-11-14T22:13:00Z", episode["watched_at"].asString)
            assertEquals(7003L, episode.getAsJsonObject("ids")["trakt"].asLong)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun unresolved_episode_is_counted_without_posting_history() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]"))
            val result = api(server).upload("access-token", listOf(
                TraktHistoryUpload(TraktMediaIdentity.EpisodeId(1396, 2, 3), 1_700_000_000_000L)))

            assertEquals(TraktHistoryUploadResult.Success(uploaded = 0, unmatched = 1), result)
            assertEquals("/search/tmdb/1396?type=show", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        }
    }
}
