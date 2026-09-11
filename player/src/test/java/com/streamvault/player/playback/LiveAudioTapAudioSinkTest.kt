package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.google.common.truth.Truth.assertThat
import com.streamvault.player.LiveAudioPcmBuffer
import com.streamvault.player.LiveAudioTap
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Test

@UnstableApi
class LiveAudioTapAudioSinkTest {

    @Test
    fun `new audio sink configuration reaches delegate and configures pcm tap metadata`() {
        var delegatedConfig: AudioSink.AudioSinkConfig? = null
        val tappedBuffers = mutableListOf<LiveAudioPcmBuffer>()
        val delegate = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java)
        ) { _, method, arguments ->
            when (method.name) {
                "configure" -> {
                    if (arguments?.size == 1) {
                        delegatedConfig = arguments.single() as AudioSink.AudioSinkConfig
                    }
                    Unit
                }
                "handleBuffer" -> {
                    val buffer = arguments?.get(0) as ByteBuffer
                    buffer.position(buffer.limit())
                    true
                }
                "toString" -> "FakeAudioSink"
                else -> defaultValue(method.returnType)
            }
        } as AudioSink
        val sink = LiveAudioTapAudioSink(delegate) { LiveAudioTap(tappedBuffers::add) }
        val format = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setSampleRate(48_000)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()
        val config = AudioSink.AudioSinkConfig.Builder(format).build()

        sink.configure(config)
        sink.handleBuffer(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 250_000L, 1)

        assertThat(delegatedConfig).isSameInstanceAs(config)
        assertThat(tappedBuffers).hasSize(1)
        assertThat(tappedBuffers.single().sampleRate).isEqualTo(48_000)
        assertThat(tappedBuffers.single().channelCount).isEqualTo(2)
        assertThat(tappedBuffers.single().data).isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Void.TYPE -> Unit
        else -> null
    }
}
