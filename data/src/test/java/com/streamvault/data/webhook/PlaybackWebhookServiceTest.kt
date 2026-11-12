package com.streamvault.data.webhook

import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.playback.events.*
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlaybackWebhookServiceTest {
    private class Crypto : CredentialCrypto {
        override fun encryptIfNeeded(value: String) = "enc:" + Base64.getEncoder().encodeToString(value.toByteArray())
        override fun decryptIfNeeded(value: String) = String(Base64.getDecoder().decode(value.removePrefix("enc:")))
    }
    private fun observation(phase: PlaybackEventPhase = PlaybackEventPhase.PLAYING) = PlaybackObservation(1,
        PlaybackMedia(PlaybackMediaType.MOVIE, "Movie", ids = PlaybackExternalIds(tmdb = 603)),
        phase, 10_000, 100_000, 1000, Instant.now())

    @Test fun `configuration and pending history encrypt secrets and survive recreation`() {
        val file = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "store-test")
        val destination = PlaybackWebhookDestination(name = "Tracker", url = "http://example.org/private-token",
            headerName = "Authorization", headerValue = "Bearer private-secret")
        val state = StoredWebhooks(configuration = PlaybackWebhookConfiguration(true, listOf(destination)),
            pending = listOf(PendingWebhook(destination.id, destination.revision, "event", "session", PlaybackEventType.STOP,
                "private-title", System.currentTimeMillis() + 1000, true)))
        PlaybackWebhookStore(file, Crypto()).write(state)
        assertFalse(file.readText().contains("private"))
        val restored = PlaybackWebhookStore(file, Crypto()).read()
        assertEquals(state.configuration, restored.configuration)
        assertEquals(state.pending, restored.pending)
        assertFalse(destination.toString().contains("secret"))
    }

    @Test fun `slow destination does not block another and disabling clears history`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        File(context.noBackupFilesDir, "playback_webhooks").delete()
        MockWebServer().use { slow -> MockWebServer().use { fast ->
            slow.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            fast.enqueue(MockResponse().setResponseCode(204))
            val first = PlaybackWebhookDestination(name = "Slow", url = slow.url("/").toString(), delivery = PlaybackWebhookDelivery.HISTORY)
            val second = PlaybackWebhookDestination(name = "Fast", url = fast.url("/").toString())
            val service = PlaybackWebhookService(context, Crypto())
            try {
                service.save(PlaybackWebhookConfiguration(true, listOf(first, second)))
                service.report("player", observation())
                withTimeout(5000) { service.diagnostics.first { it[second.id]?.status == PlaybackWebhookStatus.SENT } }
                assertNotNull(fast.takeRequest(1, TimeUnit.SECONDS))
                service.save(service.configuration.value.copy(enabled = false))
                assertTrue(PlaybackWebhookStore(File(context.noBackupFilesDir, "playback_webhooks"), Crypto()).read().pending.isEmpty())
                service.report("player", observation(PlaybackEventPhase.STOPPED))
                assertEquals(1, fast.requestCount)
            } finally { service.shutdown() }
        } }
    }

    @Test fun `native test is explicit and tracker test does not write fake history`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        File(context.noBackupFilesDir, "playback_webhooks").delete()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val destination = PlaybackWebhookDestination(name = "Test", url = server.url("/").toString())
            val service = PlaybackWebhookService(context, Crypto())
            try {
                service.save(PlaybackWebhookConfiguration(false, listOf(destination)))
                service.report("player", observation())
                assertEquals(0, server.requestCount)
                service.test(destination.id)
                assertTrue(server.takeRequest().body.readUtf8().contains("\"event\":\"test\""))
                service.save(service.configuration.value.copy(destinations = service.configuration.value.destinations.map { it.copy(format = PlaybackWebhookFormat.YAMTRACK) }))
                service.test(destination.id)
                assertEquals(1, server.requestCount)
                assertEquals(PlaybackWebhookStatus.SKIPPED, service.diagnostics.value[destination.id]?.status)
            } finally { service.shutdown() }
        }
    }

    @Test fun `adapter uses series identity and never substitutes it for episode identity`() {
        val event = PlaybackEvent("event", "session", 1, PlaybackEventType.STOP, Instant.EPOCH,
            PlaybackMedia(PlaybackMediaType.EPISODE, "Pilot", "Show", 0, 1, seriesIds = PlaybackExternalIds(tmdb = 1396)),
            90, 100, 30, true)
        assertNull(PlaybackWebhookPayload.encode(PlaybackWebhookFormat.YAMTRACK, event, "install"))
        val identified = event.copy(media = event.media.copy(ids = PlaybackExternalIds(imdb = "tt0959621")))
        val json = com.google.gson.JsonParser.parseString(PlaybackWebhookPayload.encode(PlaybackWebhookFormat.YAMTRACK, identified, "install")).asJsonObject
        assertEquals("Stop", json["Event"].asString)
        assertEquals(0, json["Item"].asJsonObject["ParentIndexNumber"].asInt)
        assertEquals(1396L, json["Series"].asJsonObject["ProviderIds"].asJsonObject["Tmdb"].asLong)
        assertFalse(json["Item"].asJsonObject["ProviderIds"].asJsonObject.has("Tmdb"))
        assertNull(PlaybackWebhookPayload.encode(PlaybackWebhookFormat.JELLYFIN, event.copy(event = PlaybackEventType.WATCHED), "install"))
        assertNull(PlaybackWebhookPayload.encode(PlaybackWebhookFormat.YAMTRACK,
            event.copy(media = event.media.copy(seriesIds = PlaybackExternalIds())), "install"))
    }

    @Test fun `editing another destination preserves the existing playback session`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        File(context.noBackupFilesDir, "playback_webhooks").delete()
        MockWebServer().use { server ->
            repeat(4) { server.enqueue(MockResponse().setResponseCode(204)) }
            val first = PlaybackWebhookDestination(name = "First", url = server.url("/first").toString())
            val second = PlaybackWebhookDestination(name = "Second", url = server.url("/second").toString())
            val service = PlaybackWebhookService(context, Crypto())
            try {
                service.save(PlaybackWebhookConfiguration(true, listOf(first, second)))
                service.report("player", observation())
                withTimeout(5000) { service.diagnostics.first { it[first.id]?.status == PlaybackWebhookStatus.SENT && it[second.id]?.status == PlaybackWebhookStatus.SENT } }
                repeat(2) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
                service.save(service.configuration.value.copy(destinations = service.configuration.value.destinations.map {
                    if (it.id == second.id) it.copy(name = "Renamed") else it
                }))
                service.report("player", observation(PlaybackEventPhase.PAUSED))
                val pause = server.takeRequest(3, TimeUnit.SECONDS)!!
                assertEquals("/first", pause.path)
                assertEquals("pause", com.google.gson.JsonParser.parseString(pause.body.readUtf8()).asJsonObject["event"].asString)
            } finally { service.shutdown() }
        }
    }

    @Test fun `a recreated service drains persisted history with the original event identity`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.noBackupFilesDir, "playback_webhooks")
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val destination = PlaybackWebhookDestination(name = "Recovered", url = server.url("/").toString(),
                delivery = PlaybackWebhookDelivery.HISTORY)
            val event = PlaybackEventPlanner().observe(observation()).single()
            val item = PendingWebhook(destination.id, destination.revision, event.eventId, event.sessionId,
                event.event, NativePlaybackEventJson.encode(event), System.currentTimeMillis() + 60_000, true)
            PlaybackWebhookStore(file, Crypto()).write(StoredWebhooks(
                configuration = PlaybackWebhookConfiguration(true, listOf(destination)), pending = listOf(item)))
            val service = PlaybackWebhookService(context, Crypto())
            try {
                withTimeout(5000) { service.diagnostics.first { it[destination.id]?.status == PlaybackWebhookStatus.SENT } }
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals(event.eventId, request.getHeader("Idempotency-Key"))
                assertEquals(item.body, request.body.readUtf8())
                assertTrue(service.awaitRecovery())
                assertTrue(PlaybackWebhookStore(file, Crypto()).read().pending.isEmpty())
                assertEquals(1, server.requestCount)
            } finally { service.shutdown() }
        }
    }
}

