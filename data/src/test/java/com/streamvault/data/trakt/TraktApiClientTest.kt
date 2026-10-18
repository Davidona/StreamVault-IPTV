package com.streamvault.data.trakt

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.streamvault.domain.trakt.TraktFavoriteIdentity
import com.streamvault.domain.trakt.TraktFavoriteType
import com.streamvault.domain.trakt.TraktRatingIdentity
import com.streamvault.domain.trakt.TraktRatingPage
import com.streamvault.domain.trakt.TraktRatingType
import com.streamvault.domain.trakt.TraktCustomListItemIdentity
import com.streamvault.domain.trakt.TraktCustomListItemType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TraktApiClientTest {
    @Test fun `custom lists can be deleted and reordered with their Trakt ids`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val api = TraktApiClient(TraktConfig("client", "https://example.com/oauth/trakt"), OkHttpClient(), server.url("/"))

            api.deleteList("access", 42)
            val deletion = server.takeRequest()
            assertEquals("DELETE", deletion.method)
            assertEquals("/users/me/lists/42", deletion.path)
            assertEquals("Bearer access", deletion.getHeader("Authorization"))

            api.reorderLists("access", listOf(42, 17, 99))
            val reorder = server.takeRequest()
            assertEquals("POST", reorder.method)
            assertEquals("/users/me/lists/reorder", reorder.path)
            val body = Gson().fromJson(reorder.body.readUtf8(), JsonObject::class.java)
            assertEquals(listOf(42L, 17L, 99L), body.getAsJsonArray("rank").map { it.asLong })

            api.reorderItems("access", 42, listOf(7002, 7001))
            val itemReorder = server.takeRequest()
            assertEquals("POST", itemReorder.method)
            assertEquals("/users/me/lists/42/items/reorder", itemReorder.path)
            assertEquals(listOf(7002L, 7001L), Gson().fromJson(itemReorder.body.readUtf8(), JsonObject::class.java)
                .getAsJsonArray("rank").map { it.asLong })
        }
    }

    @Test fun `season and episode list items resolve exact Trakt media ids`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"type":"show","show":{"ids":{"tmdb":1396,"trakt":1}}}]"""))
            server.enqueue(MockResponse().setBody("""{"number":2,"ids":{"trakt":2002,"tmdb":111}}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            server.enqueue(MockResponse().setBody("""[{"type":"show","show":{"ids":{"tmdb":1396,"trakt":1}}}]"""))
            server.enqueue(MockResponse().setBody("""{"season":2,"number":5,"ids":{"trakt":2055,"tmdb":222}}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val api = TraktApiClient(TraktConfig("client", "https://example.com/oauth/trakt"), OkHttpClient(), server.url("/"))

            api.setItem("access", 88, TraktCustomListItemIdentity(TraktCustomListItemType.SEASON, 1396, 2), true)
            val seasonSearch = server.takeRequest()
            assertEquals("/search/tmdb/1396?type=show", seasonSearch.path)
            assertEquals("/shows/1/seasons/2", server.takeRequest().path)
            val seasonWrite = server.takeRequest()
            assertEquals("/users/me/lists/88/items", seasonWrite.path)
            assertEquals(2002L, Gson().fromJson(seasonWrite.body.readUtf8(), JsonObject::class.java)
                .getAsJsonArray("seasons")[0].asJsonObject.getAsJsonObject("ids")["trakt"].asLong)

            api.setItem("access", 88, TraktCustomListItemIdentity(TraktCustomListItemType.EPISODE, 1396, 2, 5), true)
            assertEquals("/search/tmdb/1396?type=show", server.takeRequest().path)
            assertEquals("/shows/1/seasons/2/episodes/5", server.takeRequest().path)
            val episodeWrite = server.takeRequest()
            assertEquals("/users/me/lists/88/items", episodeWrite.path)
            assertEquals(2055L, Gson().fromJson(episodeWrite.body.readUtf8(), JsonObject::class.java)
                .getAsJsonArray("episodes")[0].asJsonObject.getAsJsonObject("ids")["trakt"].asLong)
        }
    }

    @Test fun `custom list season and episode responses keep parent show identity`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[
                {"id":7001,"rank":1,"type":"season","season":{"number":2,"ids":{"trakt":2002}},"show":{"title":"Example","ids":{"tmdb":1396}}}
            ]"""))
            server.enqueue(MockResponse().setBody("""[
                {"id":7002,"rank":2,"type":"episode","episode":{"season":2,"number":5,"title":"The Test","ids":{"trakt":2055}},"show":{"title":"Example","ids":{"tmdb":1396}}}
            ]"""))
            val api = TraktApiClient(TraktConfig("client", "https://example.com/oauth/trakt"), OkHttpClient(), server.url("/"))

            val seasons = api.items("access", 88, TraktCustomListItemType.SEASON, 1)
            assertEquals("/users/me/lists/88/items/seasons?page=1&limit=250&extended=full", server.takeRequest().path)
            assertEquals(1, seasons.items.size)
            assertEquals(TraktCustomListItemIdentity(TraktCustomListItemType.SEASON, 1396, 2), seasons.items.single().identity)
            assertEquals("Example · Season 2", seasons.items.single().title)
            assertEquals(7001L, seasons.items.single().listItemId)
            val episodes = api.items("access", 88, TraktCustomListItemType.EPISODE, 1)
            assertEquals("/users/me/lists/88/items/episodes?page=1&limit=250&extended=full", server.takeRequest().path)
            assertEquals(TraktCustomListItemIdentity(TraktCustomListItemType.EPISODE, 1396, 2, 5), episodes.items.single().identity)
            assertEquals("Example · S2E5 · The Test", episodes.items.single().title)
            assertEquals(7002L, episodes.items.single().listItemId)
        }
    }

    @Test fun `ratings reads return only exact supported TMDB matches`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[
                {"type":"movie","rating":8,"movie":{"ids":{"tmdb":603}}},
                {"type":"movie","rating":7,"movie":{"ids":{"tmdb":null}}},
                {"type":"movie","rating":0,"movie":{"ids":{"tmdb":11}}}
            ]"""))
            val api = TraktApiClient(TraktConfig("client", "https://example.com/oauth/trakt"), OkHttpClient(), server.url("/"))

            val page = api.ratings("access", TraktRatingType.MOVIE, 1)
            val request = server.takeRequest()
            assertEquals("/users/me/ratings/movies?page=1&limit=100", request.path)
            assertEquals("Bearer access", request.getHeader("Authorization"))
            assertEquals(1, page.items.size)
            assertEquals(TraktRatingIdentity(TraktRatingType.MOVIE, 603L) to 8, page.items.single())
            assertEquals(2, page.skippedItems)
        }
    }

    @Test fun `ratings use the sync ratings endpoint and exact TMDB identity`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val api = TraktApiClient(TraktConfig("client", "https://example.com/oauth/trakt"), OkHttpClient(), server.url("/"))

            api.setRating("access", TraktRatingIdentity(TraktRatingType.MOVIE, 603), 8)
            val movie = server.takeRequest()
            assertEquals("POST", movie.method)
            assertEquals("/sync/ratings", movie.path)
            assertEquals("Bearer access", movie.getHeader("Authorization"))
            val movieItem = Gson().fromJson(movie.body.readUtf8(), JsonObject::class.java)
                .getAsJsonArray("movies")[0].asJsonObject
            assertEquals(8, movieItem["rating"].asInt)
            assertEquals(603L, movieItem.getAsJsonObject("ids")["tmdb"].asLong)
            assertTrue(java.time.Instant.parse(movieItem["rated_at"].asString) != null)

            api.setRating("access", TraktRatingIdentity(TraktRatingType.SHOW, 1396), 10)
            val show = server.takeRequest()
            val showItem = Gson().fromJson(show.body.readUtf8(), JsonObject::class.java)
                .getAsJsonArray("shows")[0].asJsonObject
            assertEquals("/sync/ratings", show.path)
            assertEquals(10, showItem["rating"].asInt)
            assertEquals(1396L, showItem.getAsJsonObject("ids")["tmdb"].asLong)
        }
    }

    @Test fun `favorite writes use Trakt sync add and remove endpoints with TMDB ids`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val api = TraktApiClient(TraktConfig("client", "https://example.com/oauth/trakt"), OkHttpClient(), server.url("/"))

            api.setFavorited("access", TraktFavoriteIdentity(TraktFavoriteType.MOVIE, 603), true)
            val add = server.takeRequest()
            assertEquals("POST", add.method)
            assertEquals("/sync/favorites", add.path)
            assertEquals("Bearer access", add.getHeader("Authorization"))
            val addBody = Gson().fromJson(add.body.readUtf8(), JsonObject::class.java)
            assertEquals(603L, addBody.getAsJsonArray("movies")[0].asJsonObject
                .getAsJsonObject("ids")["tmdb"].asLong)

            api.setFavorited("access", TraktFavoriteIdentity(TraktFavoriteType.SHOW, 1396), false)
            val remove = server.takeRequest()
            assertEquals("POST", remove.method)
            assertEquals("/sync/favorites/remove", remove.path)
            val removeBody = Gson().fromJson(remove.body.readUtf8(), JsonObject::class.java)
            assertEquals(1396L, removeBody.getAsJsonArray("shows")[0].asJsonObject
                .getAsJsonObject("ids")["tmdb"].asLong)
        }
    }

    @Test fun `exchange sends exact redirect and verifier without client secret`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"test-access","refresh_token":"test-refresh","created_at":2000000,"expires_in":604800}"""))
            val api = TraktApiClient(TraktConfig("client", "https://example.com/oauth/trakt"), OkHttpClient(), server.url("/"))
            val result = api.exchange("code", "verifier")
            val request = server.takeRequest()
            assertEquals("/oauth/token", request.path)
            assertEquals("client", request.getHeader("trakt-api-key"))
            assertEquals("2", request.getHeader("trakt-api-version"))
            assertNull(request.getHeader("Authorization"))
            val body = Gson().fromJson(request.body.readUtf8(), JsonObject::class.java)
            assertFalse(body.has("client_secret"))
            assertEquals("verifier", body["code_verifier"].asString)
            assertEquals("https://example.com/oauth/trakt", body["redirect_uri"].asString)
            assertEquals("test-refresh", result.refreshToken)
        }
    }

    @Test fun `authenticated profile sends bearer and preserves OAuth error identifier`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"user":{"username":"viewer"}}"""))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"session not found"}"""))
            val api = TraktApiClient(TraktConfig("client", "https://example.com/oauth/trakt"), OkHttpClient(), server.url("/"))
            assertEquals("viewer", api.username("test-access"))
            assertEquals("Bearer test-access", server.takeRequest().getHeader("Authorization"))
            try { api.refresh("test-refresh"); fail("Expected failure") } catch (failure: TraktApiException) {
                assertEquals(400, failure.status)
                assertEquals("invalid_grant", failure.oauthError)
                assertFalse(failure.toString().contains("test-refresh"))
            }
        }
    }

    @Test fun `429 respects Retry After by suppressing further calls during cooldown`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            val api = TraktApiClient(TraktConfig("client", "https://example.com/oauth/trakt"), OkHttpClient(), server.url("/"))
            repeat(2) {
                try { api.username("test-access"); fail("Expected rate limit") } catch (failure: TraktApiException) {
                    assertEquals(429, failure.status)
                    assertTrue(failure.retryAfterSeconds!! >= 119)
                }
            }
            assertEquals(1, server.requestCount)
        }
    }
}
