package com.streamvault.data.webhook

import com.google.gson.JsonParser
import com.streamvault.domain.playback.events.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class PlaybackWebhookSenderTest {
    private val event = PlaybackEvent("event-1", "session-1", 2, PlaybackEventType.STOP, Instant.EPOCH,
        PlaybackMedia(PlaybackMediaType.EPISODE, "A \"quoted\" episode\nالعربية", "Show", 0, 1,
            seriesIds = PlaybackExternalIds(tmdb = 1396)),
        90_000, 100_000, 30_000, true, PlaybackStopReason.USER)

    @Test fun `native JSON escapes titles preserves namespaces and omits unknown IDs`() {
        val json = JsonParser.parseString(NativePlaybackEventJson.encode(event)).asJsonObject
        assertEquals(1, json["schema_version"].asInt)
        assertEquals(event.media.title, json["title"].asString)
        assertEquals("episode", json["type"].asString)
        assertEquals("stop", json["event"].asString)
        assertEquals("user", json["stop_reason"].asString)
        assertEquals(0, json["season"].asInt)
        assertEquals(1396L, json["series_ids"].asJsonObject["tmdb"].asLong)
        assertFalse(json.has("ids"))
        assertEquals(90.0, json["progress_percent"].asDouble, .001)
        assertEquals(30_000L, json["played_ms"].asLong)
        val live = event.copy(media = PlaybackMedia(PlaybackMediaType.LIVE, "Channel"), durationMs = null)
        val liveJson = JsonParser.parseString(NativePlaybackEventJson.encode(live)).asJsonObject
        assertTrue(liveJson["duration_ms"].isJsonNull)
        assertTrue(liveJson["progress_percent"].isJsonNull)
        assertFalse(liveJson.has("series"))
    }

    @Test fun `posts authenticated JSON and retries retain the same identity`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(204))
            val endpoint = PlaybackWebhookEndpoint(server.url("/secret-token").toString(),
                mapOf("Authorization" to "Bearer secret", "X-API-Key" to "key"))
            val sender = PlaybackWebhookSender()
            assertEquals(WebhookDeliveryCategory.RETRYABLE, sender.send(endpoint, event).category)
            assertEquals(WebhookDeliveryCategory.SUCCESS, sender.send(endpoint, event).category)
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("POST", first.method)
            assertEquals("Bearer secret", first.getHeader("Authorization"))
            assertEquals("key", first.getHeader("X-API-Key"))
            assertEquals("event-1", first.getHeader("Idempotency-Key"))
            assertEquals(first.getHeader("X-StreamVault-Event-Id"), second.getHeader("X-StreamVault-Event-Id"))
            assertEquals(first.body.readUtf8(), second.body.readUtf8())
            assertTrue(first.getHeader("Content-Type")!!.startsWith("application/json"))
            assertFalse(endpoint.toString().contains("secret"))
        }
    }

    @Test fun `redirects are rejected without forwarding tokens`() = runBlocking {
        MockWebServer().use { source ->
            MockWebServer().use { target ->
                source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/")))
                val result = PlaybackWebhookSender().send(PlaybackWebhookEndpoint(source.url("/").toString(),
                    mapOf("Authorization" to "Bearer secret")), event)
                assertEquals(WebhookDeliveryCategory.REJECTED, result.category)
                assertEquals(307, result.httpStatus)
                assertEquals(0, target.requestCount)
            }
        }
    }

    @Test fun `auth rejection has no remote body and rate limiting has bounded retry delay`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive remote content"))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "999999"))
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "Thu, 01 Jan 1970 00:00:10 GMT"))
            val sender = PlaybackWebhookSender { Instant.EPOCH }
            val endpoint = PlaybackWebhookEndpoint(server.url("/").toString())
            val rejected = sender.send(endpoint, event)
            assertEquals(WebhookDeliveryCategory.REJECTED, rejected.category)
            assertFalse(rejected.toString().contains("sensitive"))
            assertEquals(300_000L, sender.send(endpoint, event).retryAfterMs)
            assertEquals(10_000L, sender.send(endpoint, event).retryAfterMs)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun `caller cancellation interrupts an unresponsive endpoint`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            try {
                withTimeout(250) {
                    PlaybackWebhookSender().send(PlaybackWebhookEndpoint(server.url("/").toString()), event)
                }
                fail("Expected cancellation")
            } catch (_: TimeoutCancellationException) {
                // Cancellation must escape rather than becoming a retryable network result.
            }
        }
    }

    @Test fun `configuration rejects userinfo unsupported schemes and injected transport headers`() {
        val invalid = listOf<() -> Unit>(
            { PlaybackWebhookEndpoint("https://user:password@example.org/hook") },
            { PlaybackWebhookEndpoint("file:///tmp/hook") },
            { PlaybackWebhookEndpoint("https://example.org/hook#fragment") },
            { PlaybackWebhookEndpoint("https://example.org", mapOf("Host" to "other")) },
            { PlaybackWebhookEndpoint("https://example.org", mapOf("Content-Type" to "text/plain")) },
            { PlaybackWebhookEndpoint("https://example.org", mapOf("X-Key" to "value\r\nInjected: yes")) },
        )
        invalid.forEach { create ->
            try { create(); fail("Expected invalid configuration") } catch (_: IllegalArgumentException) { }
        }
    }
}
