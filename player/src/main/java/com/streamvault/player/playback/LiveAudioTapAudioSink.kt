package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.streamvault.player.LiveAudioPcmBuffer
import com.streamvault.player.LiveAudioTap
import com.streamvault.player.PlayerPcmEncoding
import java.nio.ByteBuffer

internal fun toPlayerPcmEncoding(encoding: Int): PlayerPcmEncoding =
    if (encoding == C.ENCODING_PCM_16BIT) {
        PlayerPcmEncoding.PCM_16_BIT
    } else {
        PlayerPcmEncoding.UNSUPPORTED
    }

@UnstableApi
internal class LiveAudioTapAudioSink(
    private val delegate: AudioSink,
    private val tapProvider: () -> LiveAudioTap?
) : AudioSink by delegate {
    private var sampleRate: Int = Format.NO_VALUE
    private var channelCount: Int = Format.NO_VALUE
    private var encoding: Int = C.ENCODING_INVALID

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val inputFormat = audioSinkConfig.format
        sampleRate = inputFormat.sampleRate
        channelCount = inputFormat.channelCount
        encoding = inputFormat.pcmEncoding
        delegate.configure(audioSinkConfig)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        val startPosition = buffer.position()
        val copySource = buffer.asReadOnlyBuffer()
        val handled = delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        copyConsumedForTap(copySource, startPosition, buffer.position(), presentationTimeUs)
        return handled
    }

    private fun copyConsumedForTap(
        buffer: ByteBuffer,
        startPosition: Int,
        endPosition: Int,
        presentationTimeUs: Long
    ) {
        val tap = tapProvider() ?: return
        val playerEncoding = toPlayerPcmEncoding(encoding)
        if (playerEncoding == PlayerPcmEncoding.UNSUPPORTED || sampleRate <= 0 || channelCount <= 0 || endPosition <= startPosition) {
            return
        }
        val adjustedPresentationTimeUs = presentationTimeUs + consumedOffsetUs(startPosition)
        val duplicate = buffer.apply {
            position(startPosition)
            limit(endPosition)
        }
        val data = ByteArray(duplicate.remaining())
        duplicate.get(data)
        tap.onPcmAudio(
            LiveAudioPcmBuffer(
                data = data,
                presentationTimeUs = adjustedPresentationTimeUs,
                sampleRate = sampleRate,
                channelCount = channelCount,
                encoding = playerEncoding
            )
        )
    }

    private fun consumedOffsetUs(bytePosition: Int): Long {
        val frameSize = channelCount * 2
        if (frameSize <= 0 || sampleRate <= 0) return 0L
        val frames = bytePosition / frameSize
        return frames * 1_000_000L / sampleRate
    }
}
