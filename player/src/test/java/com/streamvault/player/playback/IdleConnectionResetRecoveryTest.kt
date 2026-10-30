@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlaybackContentKind
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * VOD playback stops reading once the buffer is full (240 s max, resume below 90 s), so the HTTP connection can sit
 * idle long enough to be closed by the server or NAT. The next read then fails ("Connection reset", or "unexpected end
 * of stream" for a clean close). With
 * StreamVault's retry policy that load must be retried in place: Media3 reopens at the byte position with a Range
 * request instead of failing the source (which forces a whole-source re-prepare and a visible pause).
 */
@RunWith(RobolectricTestRunner::class)
class IdleConnectionResetRecoveryTest {

    private val body = adtsStream(sizeBytes = 24 * 1024 * 1024)
    private lateinit var server: IdleResettingHttpServer

    @After fun tearDown() { server.close() }

    @Test
    fun `progressive VOD load reset after an idle pause resumes in place with a Range request`() =
        assertResumesInPlace(abortWithRst = true)

    @Test
    fun `progressive VOD load closed cleanly after an idle pause resumes in place with a Range request`() =
        assertResumesInPlace(abortWithRst = false)

    private fun assertResumesInPlace(abortWithRst: Boolean) {
        server = IdleResettingHttpServer(body, IDLE_RESET_MS, abortWithRst).also { it.start() }
        val errors = CopyOnWriteArrayList<Pair<IOException, Boolean>>()
        var completed = false
        val policy = PlayerRetryPolicy(
            streamContext = PlaybackRetryContext(
                resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
                timeoutProfile = PlayerTimeoutProfile.PROGRESSIVE,
                playbackContentKind = PlaybackContentKind.ON_DEMAND,
            ),
            playbackStarted = { true },
        )
        val source = ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(OkHttpClient()))
            .setLoadErrorHandlingPolicy(policy)
            .setContinueLoadingCheckIntervalBytes(256 * 1024)
            .createMediaSource(MediaItem.fromUri(server.url))
        source.addEventListener(Handler(Looper.getMainLooper()), object : MediaSourceEventListener {
            override fun onLoadError(
                windowIndex: Int,
                mediaPeriodId: MediaSource.MediaPeriodId?,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean,
            ) {
                errors += error to wasCanceled
            }

            override fun onLoadCompleted(
                windowIndex: Int,
                mediaPeriodId: MediaSource.MediaPeriodId?,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                completed = true
            }
        })

        var timeline: Timeline? = null
        source.prepareSource({ _, t -> timeline = t }, PlayerId.UNSET, DefaultBandwidthMeter.Builder(RuntimeEnvironment.getApplication()).build())
        idleUntil { timeline != null }
        val period = source.createPeriod(
            MediaSource.MediaPeriodId(checkNotNull(timeline).getUidOfPeriod(0)),
            DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            0,
        )
        var prepared = false
        var allowLoading = true
        period.prepare(object : MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: MediaPeriod) {
                prepared = true
            }

            override fun onContinueLoadingRequested(source: MediaPeriod) {
                if (allowLoading) source.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(0).build())
            }
        }, 0)
        idleUntil { prepared }
        val groups = period.trackGroups
        val selections = Array<ExoTrackSelection?>(groups.length) { FixedTrackSelection(groups[it], 0) }
        period.selectTracks(selections, BooleanArray(groups.length), arrayOfNulls<SampleStream>(groups.length), BooleanArray(groups.length), 0)

        period.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(0).build())
        idleUntil { (server.requests.firstOrNull()?.bytesSent ?: 0) > 2_000_000 }
        allowLoading = false // Buffer full: the loader blocks with the connection open.
        idleFor(IDLE_RESET_MS * 2) // Longer than the path's idle timeout.
        allowLoading = true // Buffer drained below the resume threshold.
        period.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(0).build())
        idleUntil(timeoutMs = 20_000) { completed || errors.any { it.second } }
        source.releasePeriod(period)
        source.releaseSource { _, _ -> }

        assertThat(errors).hasSize(1)
        val (error, wasCanceled) = errors.single()
        if (abortWithRst) {
            assertThat(generateSequence<Throwable>(error) { it.cause }.any { it is SocketException }).isTrue()
        }
        assertThat(wasCanceled).isFalse()
        assertThat(completed).isTrue()
        assertThat(server.requests).hasSize(2)
        val resume = server.requests[1]
        assertThat(resume.status).isEqualTo(206)
        assertThat(resume.rangeStart).isAtLeast(1_000_000L)
        assertThat(resume.rangeStart).isAtMost(server.requests[0].bytesSent)
    }

    private fun idleFor(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) step()
    }

    private fun idleUntil(timeoutMs: Long = 10_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            check(System.currentTimeMillis() < end) { "timed out; requests=${server.requests.map { it.rangeStart to it.bytesSent }}" }
            step()
        }
    }

    /** Media3 schedules retries with delays, so the paused main looper's clock must advance too. */
    private fun step() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        Thread.sleep(10)
    }

    private companion object {
        const val IDLE_RESET_MS = 1_500L
        const val ADTS_FRAME_BYTES = 1031 // 7-byte header + 1024 bytes of payload

        /**
         * Raw AAC (ADTS) stream: every frame header carries the format, so the period prepares without real audio
         * and the extractor reads strictly forward. [sizeBytes] exceeds the loopback socket buffers, so the client
         * really stops reading when loading pauses.
         */
        fun adtsStream(sizeBytes: Int): ByteArray {
            val frames = sizeBytes / ADTS_FRAME_BYTES
            val out = ByteArray(frames * ADTS_FRAME_BYTES)
            val len = ADTS_FRAME_BYTES
            val header = byteArrayOf(
                0xff.toByte(), 0xf1.toByte(), // sync, MPEG-4, layer 0, no CRC
                0x4c, // AAC LC, 48 kHz, channel configuration bit 2
                (0x80 or ((len shr 11) and 0x03)).toByte(), // channel configuration 2 (stereo), frame length high bits
                ((len shr 3) and 0xff).toByte(),
                (((len and 0x07) shl 5) or 0x1f).toByte(), // frame length low bits, buffer fullness 0x7ff
                0xfc.toByte(), // buffer fullness, one raw data block
            )
            for (i in 0 until frames) header.copyInto(out, i * ADTS_FRAME_BYTES)
            return out
        }
    }
}

/**
 * Minimal HTTP/1.1 file server with Range support and one request per connection. A connection that makes no write
 * progress for [idleResetMs] (the client stopped reading) is closed: with an RST ("Connection reset" on the client's
 * next read) or, if [abortWithRst] is false, cleanly before Content-Length ("unexpected end of stream").
 */
internal class IdleResettingHttpServer(
    private val body: ByteArray,
    private val idleResetMs: Long,
    private val abortWithRst: Boolean = true,
) : AutoCloseable {
    class Request(val rangeStart: Long, val status: Int) {
        /** Absolute file offset written so far. */
        @Volatile var bytesSent = 0L
    }

    val requests = CopyOnWriteArrayList<Request>()
    private val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    val url: String get() = "http://127.0.0.1:${socket.localPort}/movie/fixture.aac"

    fun start() = Thread { while (!socket.isClosed) runCatching { handle(socket.accept()) } }
        .apply { isDaemon = true }
        .start()

    private fun handle(client: Socket) = Thread {
        client.use { s ->
            s.sendBufferSize = 64 * 1024
            val headers = s.getInputStream().bufferedReader(Charsets.ISO_8859_1).let { reader ->
                generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
            }
            val rangeStart = headers.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                ?.substringAfter("bytes=")?.substringBefore('-')?.trim()?.toLongOrNull()
            val start = rangeStart ?: 0L
            val request = Request(start, if (rangeStart != null) 206 else 200).also { requests += it }
            val out = s.getOutputStream()
            out.write(
                buildString {
                    append(if (rangeStart != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: audio/aac\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
                    append("Content-Length: ${body.size - start}\r\n")
                    if (rangeStart != null) append("Content-Range: bytes $start-${body.size - 1}/${body.size}\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.ISO_8859_1)
            )
            val lastProgress = AtomicLong(System.currentTimeMillis())
            val watchdog = Thread {
                while (!s.isClosed) {
                    if (System.currentTimeMillis() - lastProgress.get() > idleResetMs) {
                        if (abortWithRst) s.setSoLinger(true, 0) // RST; otherwise a clean close before Content-Length
                        s.close()
                        return@Thread
                    }
                    Thread.sleep(50)
                }
            }.apply { isDaemon = true; start() }
            var pos = start.toInt()
            try {
                while (pos < body.size) {
                    val n = minOf(16 * 1024, body.size - pos)
                    out.write(body, pos, n)
                    pos += n
                    request.bytesSent = pos.toLong()
                    lastProgress.set(System.currentTimeMillis())
                }
                out.flush()
            } catch (_: IOException) {
            }
            watchdog.interrupt()
        }
    }.apply { isDaemon = true }.start()

    override fun close() = socket.close()
}
