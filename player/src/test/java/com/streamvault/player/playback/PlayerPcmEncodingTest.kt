package com.streamvault.player.playback

import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlayerPcmEncoding
import org.junit.Test

class PlayerPcmEncodingTest {
    @Test
    fun `16 bit Media3 PCM maps to player 16 bit encoding`() {
        assertThat(toPlayerPcmEncoding(C.ENCODING_PCM_16BIT))
            .isEqualTo(PlayerPcmEncoding.PCM_16_BIT)
    }

    @Test
    fun `all other Media3 encodings map to unsupported`() {
        assertThat(toPlayerPcmEncoding(C.ENCODING_PCM_FLOAT))
            .isEqualTo(PlayerPcmEncoding.UNSUPPORTED)
        assertThat(toPlayerPcmEncoding(C.ENCODING_INVALID))
            .isEqualTo(PlayerPcmEncoding.UNSUPPORTED)
    }
}
