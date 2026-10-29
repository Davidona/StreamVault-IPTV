@file:androidx.media3.common.util.UnstableApi

package com.streamvault.app.ui

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONObject

/** Opt-in timing observer. Never calls an audio callback synthetically or changes sink policy. */
internal class PlaybackAudioSourceProbe(private val native: ExoPlayer, private val emit: (JSONObject) -> Unit) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private data class Event(val kind: String, val time: Long, val thread: String,
        val bytes: Int, val bufferMs: Long, val feedMs: Long)
    private val pending = ConcurrentLinkedQueue<Event>()
    private lateinit var sink: DefaultAudioSink
    private lateinit var original: AudioSink.Listener
    private lateinit var proxy: AudioSink.Listener
    private var installed = false
    private val field = DefaultAudioSink::class.java.getDeclaredField("listener").apply { isAccessible = true }
    private val analytics = object : AnalyticsListener {
        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int,
            bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            record("audio-callback", bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
        }
    }
    private fun record(kind: String, bytes: Int, bufferMs: Long, feedMs: Long) {
        pending.add(Event(kind, SystemClock.elapsedRealtime(), Thread.currentThread().name, bytes, bufferMs, feedMs))
    }
    private fun playback(block: () -> Unit): Boolean {
        lateinit var message: PlayerMessage
        instrumentation.runOnMainSync { message = native.createMessage { _, _ -> block() }.send() }
        return message.blockUntilDelivered(5_000)
    }
    fun install() {
        instrumentation.runOnMainSync {
            val renderer = (0 until native.rendererCount).map { native.getRenderer(it) }
                .first { it is androidx.media3.exoplayer.audio.MediaCodecAudioRenderer }
            val audioField = androidx.media3.exoplayer.audio.MediaCodecAudioRenderer::class.java
                .getDeclaredField("audioSink").apply { isAccessible = true }
            var current = audioField.get(renderer) as AudioSink
            repeat(5) {
                if (current !is DefaultAudioSink) {
                    val delegate = current.javaClass.declaredFields.firstOrNull {
                        AudioSink::class.java.isAssignableFrom(it.type)
                    } ?: error("Unrecognized audio sink wrapper: ${current.javaClass.name}")
                    current = delegate.apply { isAccessible = true }.get(current) as AudioSink
                }
            }
            sink = current as DefaultAudioSink
            native.addAnalyticsListener(analytics)
        }
        check(playback {
            original = checkNotNull(field.get(sink) as? AudioSink.Listener)
            proxy = Proxy.newProxyInstance(AudioSink.Listener::class.java.classLoader,
                arrayOf(AudioSink.Listener::class.java)) { _, method, args ->
                if (method.name == "onUnderrun") {
                    record("audio-source", args!![0] as Int, args[1] as Long, args[2] as Long)
                }
                try { method.invoke(original, *(args ?: emptyArray())) }
                catch (error: InvocationTargetException) { throw error.targetException }
            } as AudioSink.Listener
            field.set(sink, proxy)
            check(field.get(sink) === proxy)
        }) { "Audio observer installation message was not delivered" }
        installed = true
        emit(JSONObject().put("event", "audio-observer-installed").put("sink", sink.javaClass.name))
    }
    fun drain() {
        while (true) {
            val event = pending.poll() ?: return
            emit(JSONObject().put("event", event.kind).put("eventElapsedMs", event.time)
                .put("eventThread", event.thread).put("bufferSize", event.bytes)
                .put("bufferSizeMs", event.bufferMs).put("elapsedSinceLastFeedMs", event.feedMs))
        }
    }
    fun finish() {
        // Leave the observer installed through Activity cleanup, then restore before stopping our player.
        Thread.sleep(350)
        var released = false
        instrumentation.runOnMainSync { released = native.isReleased }
        var restorationFailure: Throwable? = null
        try {
            if (installed) {
                if (released) {
                    // Released players reject playback messages; restore the detached sink reference.
                    check(field.get(sink) === proxy)
                    field.set(sink, original)
                } else {
                    check(playback { check(field.get(sink) === proxy); field.set(sink, original) }) {
                        "Live audio observer restoration message was not delivered"
                    }
                }
            }
        } catch (failure: Throwable) {
            restorationFailure = failure
            throw failure
        } finally {
            try {
                instrumentation.runOnMainSync { native.removeAnalyticsListener(analytics) }
            } catch (failure: Throwable) {
                restorationFailure?.addSuppressed(failure) ?: throw failure
            } finally {
                drain()
            }
        }
        emit(JSONObject().put("event", "audio-observer-restored").put("playerReleased", released))
    }
}
