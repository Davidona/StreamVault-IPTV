package com.streamvault.feature.playback.translation

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.LiveAudioPcmBuffer
import com.streamvault.player.PlayerPcmEncoding
import org.junit.Test

class LiveTranslationPcmConversionTest {
    @Test
    fun `translation rejects unsupported player PCM encoding`() {
        val result = convertToPcm16Mono16k(
            buffer = LiveAudioPcmBuffer(
                data = byteArrayOf(0, 0),
                presentationTimeUs = 0,
                sampleRate = 16_000,
                channelCount = 1,
                encoding = PlayerPcmEncoding.UNSUPPORTED
            ),
            fallbackStartMs = 0
        )

        assertThat(result).isNull()
    }

    @Test
    fun `translation accepts player 16 bit PCM`() {
        val result = convertToPcm16Mono16k(
            buffer = LiveAudioPcmBuffer(
                data = byteArrayOf(1, 0),
                presentationTimeUs = 0,
                sampleRate = 16_000,
                channelCount = 1,
                encoding = PlayerPcmEncoding.PCM_16_BIT
            ),
            fallbackStartMs = 123
        )

        assertThat(result).isNotNull()
        assertThat(result!!.data).isEqualTo(byteArrayOf(1, 0))
        assertThat(result.startMs).isEqualTo(123)
    }
}
