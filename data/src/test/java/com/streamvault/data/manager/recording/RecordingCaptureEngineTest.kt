package com.streamvault.data.manager.recording

import android.content.ContentResolver
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.RecordingSourceType
import com.streamvault.domain.model.LivePlaybackIdentity
import com.streamvault.domain.util.LivePlaybackByteTap
import com.streamvault.domain.util.effectiveHttpRequestProperties
import com.streamvault.domain.util.livePlaybackRequestFingerprint
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock

class RecordingCaptureEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun hlsCaptureRetriesTransientSegmentFailureOnNextPlaylistRefresh() {
        runTest {
            val playlistBodies = listOf(
                """
                    #EXTM3U
                    #EXT-X-VERSION:3
                    #EXT-X-TARGETDURATION:2
                    #EXTINF:2.0,
                    https://example.com/segment-1.ts
                """.trimIndent(),
                """
                    #EXTM3U
                    #EXT-X-VERSION:3
                    #EXT-X-TARGETDURATION:2
                    #EXTINF:2.0,
                    https://example.com/segment-1.ts
                    #EXT-X-ENDLIST
                """.trimIndent()
            )
            val playlistAttempts = AtomicInteger(0)
            val segmentAttempts = AtomicInteger(0)
            val client = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    when (chain.request().url.toString()) {
                        "https://example.com/live.m3u8" -> {
                            val attempt = (playlistAttempts.incrementAndGet() - 1)
                                .coerceAtMost(playlistBodies.lastIndex)
                            response(chain, 200, playlistBodies[attempt])
                        }
                        "https://example.com/segment-1.ts" -> {
                            if (segmentAttempts.incrementAndGet() == 1) {
                                throw IOException("socket timeout")
                            }
                            response(chain, 200, "segment-payload")
                        }
                        else -> error("Unexpected URL ${chain.request().url}")
                    }
                })
                .build()
            val engine = HlsLiveCaptureEngine(client)
            val outputFile = tempFolder.newFile("capture.ts")

            engine.capture(
                source = ResolvedRecordingSource(
                    url = "https://example.com/live.m3u8",
                    sourceType = RecordingSourceType.HLS
                ),
                outputTarget = RecordingOutputTarget.FileTarget(outputFile),
                contentResolver = mock<ContentResolver>(),
                scheduledEndMs = System.currentTimeMillis() + 60_000,
                onProgress = {}
            )

            assertThat(segmentAttempts.get()).isEqualTo(2)
            assertThat(outputFile.readText()).isEqualTo("segment-payload")
        }
    }

    private fun response(chain: Interceptor.Chain, code: Int, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
            .build()

    @Test
    fun sharedRecordingStartsAtAvailableNewSegmentWithoutWaitingForOldPlaylistEntries() = runTest {
        sharedStartupScenario(waitForMiddle = false)
    }

    @Test
    fun sharedRecordingWaitsForMissingSegmentAfterItsFirstWrite() = runTest {
        sharedStartupScenario(waitForMiddle = true)
    }

    @Test
    fun sharedRecordingKeepsWarmEncryptionKeyAndInitializationMap() = runTest {
        sharedStartupScenario(waitForMiddle = false, encrypted = true)
    }

    @Test
    fun sequentialSharedRecordingsReuseWarmHlsDependenciesWithoutUpstreamRequests() = runTest {
        val url = "https://fixture.invalid/live/index.m3u8"
        val identity = LivePlaybackIdentity(1, 2)
        val source = ResolvedRecordingSource(url, RecordingSourceType.HLS, livePlaybackIdentity = identity)
        val fingerprint = livePlaybackRequestFingerprint(
            url,
            effectiveHttpRequestProperties(source.headers, source.userAgent)
        )
        val tap = LivePlaybackByteTap()
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { 1 }
        fun encrypt(value: String): ByteArray = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            .apply {
                init(
                    javax.crypto.Cipher.ENCRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(key, "AES"),
                    javax.crypto.spec.IvParameterSpec(iv)
                )
            }
            .doFinal(value.toByteArray())
        fun publish(name: String, bytes: ByteArray, playlist: Boolean = false) {
            val resourceUrl = java.net.URI(url).resolve(name).toString()
            tap.beginHlsResource(identity, fingerprint, resourceUrl, resourceUrl, playlist).use { publisher ->
                publisher.publish(bytes, 0, bytes.size)
                publisher.complete()
            }
        }
        fun manifest(segments: List<String>, endList: Boolean = false): ByteArray = buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:2\n")
            append("#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x")
            append(iv.joinToString("") { "%02x".format(it) })
            append("\n#EXT-X-MAP:URI=\"init\"\n")
            segments.forEach { append("#EXTINF:2,\n$it\n") }
            if (endList) append("#EXT-X-ENDLIST\n")
        }.toByteArray()

        publish("index.m3u8", manifest(listOf("old.ts")), playlist = true)
        publish("key", key)
        publish("init", encrypt("INIT"))
        publish("old.ts", encrypt("OLD"))

        val upstreamRequests = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor {
                upstreamRequests.incrementAndGet()
                throw IOException("Shared recording unexpectedly opened an upstream request")
            }
            .build()
        val engine = HlsLiveCaptureEngine(RecordingHttpClientFactory { client }, tap)
        val firstOutput = tempFolder.newFile("shared-first.ts")
        val firstSegmentWritten = CompletableDeferred<Unit>()
        val firstUpdater = launch {
            delay(100)
            publish("index.m3u8", manifest(listOf("old.ts", "first.ts")), playlist = true)
            publish("first.ts", encrypt("A"))
        }
        val firstCapture = launch {
            engine.capture(
                source,
                RecordingOutputTarget.FileTarget(firstOutput),
                mock<ContentResolver>(),
                System.currentTimeMillis() + 60_000,
                onProgress = { firstSegmentWritten.complete(Unit) }
            )
        }
        withTimeout(5_000) { firstSegmentWritten.await() }
        firstCapture.cancelAndJoin()
        firstUpdater.join()
        assertThat(firstOutput.readText()).isEqualTo("INITA")

        val secondOutput = tempFolder.newFile("shared-second.ts")
        val secondUpdater = launch {
            delay(100)
            publish(
                "index.m3u8",
                manifest(listOf("old.ts", "first.ts", "second.ts"), endList = true),
                playlist = true
            )
            publish("second.ts", encrypt("B"))
        }
        withTimeout(10_000) {
            engine.capture(
                source,
                RecordingOutputTarget.FileTarget(secondOutput),
                mock<ContentResolver>(),
                System.currentTimeMillis() + 60_000,
                onProgress = {}
            )
        }
        secondUpdater.join()

        assertThat(secondOutput.readText()).isEqualTo("INITB")
        assertThat(upstreamRequests.get()).isEqualTo(0)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.sharedStartupScenario(
        waitForMiddle: Boolean,
        encrypted: Boolean = false
    ) {
        val url = "https://fixture.invalid/live/index.m3u8"
        val identity = LivePlaybackIdentity(1, 2)
        val source = ResolvedRecordingSource(url, RecordingSourceType.HLS, livePlaybackIdentity = identity)
        val fingerprint = livePlaybackRequestFingerprint(url, effectiveHttpRequestProperties(source.headers, source.userAgent))
        val tap = LivePlaybackByteTap()
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { 1 }
        fun publishBytes(name: String, bytes: ByteArray, playlist: Boolean = false) {
            val resourceUrl = java.net.URI(url).resolve(name).toString()
            tap.beginHlsResource(identity, fingerprint, resourceUrl, resourceUrl, playlist).use { publisher ->
                publisher.publish(bytes, 0, bytes.size)
                publisher.complete()
            }
        }
        fun publish(name: String, body: String, playlist: Boolean = false) {
            val bytes = if (encrypted && !playlist) {
                javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                    init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                        javax.crypto.spec.IvParameterSpec(iv))
                }.doFinal(body.toByteArray())
            } else body.toByteArray()
            publishBytes(name, bytes, playlist)
        }
        val encryption = if (encrypted) {
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x${iv.joinToString("") { "%02x".format(it) }}\n" +
                "#EXT-X-MAP:URI=\"init\"\n"
        } else ""
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n" + encryption +
            listOf("old", "a", "b", "c").joinToString("") { "#EXTINF:2,\n$it.ts\n" }
        publish("index.m3u8", playlist, true)
        if (encrypted) {
            publishBytes("key", key)
            publish("init", "INIT")
        }
        publish("old.ts", "old")
        val outputFile = tempFolder.newFile()
        val client = OkHttpClient.Builder().addInterceptor { error("Recording opened a duplicate upstream request") }.build()
        launch {
            delay(100)
            publish("a.ts", "A")
            if (!waitForMiddle) publish("b.ts", "B")
            publish("c.ts", "C")
            if (waitForMiddle) {
                delay(1500)
                assertThat(outputFile.readText()).isEqualTo("A")
                publish("b.ts", "B")
            }
            publish("index.m3u8", playlist + "#EXT-X-ENDLIST\n", true)
        }
        HlsLiveCaptureEngine(RecordingHttpClientFactory { client }, tap).capture(
            source, RecordingOutputTarget.FileTarget(outputFile), mock<ContentResolver>(),
            System.currentTimeMillis() + 60_000, {}
        )
        assertThat(outputFile.readText()).isEqualTo(if (encrypted) "INITABC" else "ABC")
    }
}
