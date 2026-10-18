package com.streamvault.player.tracks

import androidx.media3.common.C
import androidx.media3.common.Format
import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PLAYER_TRACK_AUTO_ID
import org.junit.Test

class PlayerTrackControllerSelectionTest {

    @Test
    fun `video track id survives a rebuilt group with the same representation`() {
        val format = Format.Builder()
            .setSampleMimeType("video/avc")
            .setWidth(1920)
            .setHeight(1080)
            .setAverageBitrate(9_500_000)
            .setFrameRate(25f)
            .setCodecs("avc1.640028")
            .build()

        val before = playerTrackId(format, C.TRACK_TYPE_VIDEO, 112233, 1)
        val rebuilt = playerTrackId(format.buildUpon().build(), C.TRACK_TYPE_VIDEO, 998877, 1)

        assertThat(rebuilt).isEqualTo(before)
    }

    @Test
    fun `nonvideo fallback track id remains group scoped`() {
        val format = Format.Builder().setSampleMimeType("audio/aac").build()

        assertThat(playerTrackId(format, C.TRACK_TYPE_AUDIO, 112233, 0)).isNotEqualTo(
            playerTrackId(format, C.TRACK_TYPE_AUDIO, 998877, 0)
        )
    }

    @Test
    fun `stale selected video track falls back to auto when multiple tracks remain`() {
        assertThat(
            normalizeSelectedVideoTrackId(
                selectedTrackId = "stale-id",
                availableTrackIds = listOf("720p", "1080p")
            )
        ).isEqualTo(PLAYER_TRACK_AUTO_ID)
    }

    @Test
    fun `stale selected video track falls back to only available track`() {
        assertThat(
            normalizeSelectedVideoTrackId(
                selectedTrackId = "stale-id",
                availableTrackIds = listOf("1080p")
            )
        ).isEqualTo("1080p")
    }

    @Test
    fun `current selected video track is preserved when still available`() {
        assertThat(
            normalizeSelectedVideoTrackId(
                selectedTrackId = "720p",
                availableTrackIds = listOf("720p", "1080p")
            )
        ).isEqualTo("720p")
    }

    @Test
    fun `current selected video track survives a temporary empty track list`() {
        assertThat(
            normalizeSelectedVideoTrackId(
                selectedTrackId = "1080p",
                availableTrackIds = emptyList()
            )
        ).isEqualTo("1080p")
    }
}
