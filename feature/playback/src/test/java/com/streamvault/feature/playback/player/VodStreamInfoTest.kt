package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.VideoFormat
import org.junit.Test

class VodStreamInfoTest {

    @Test
    fun `badges read quality and codecs from the decoded format`() {
        val format = VideoFormat(1920, 1080, codecV = "video/avc", codecA = "audio/eac3")
        assertThat(vodStreamBadges(format)).containsExactly("FHD", "H.264", "E-AC-3").inOrder()
    }

    @Test
    fun `hdr badge sits between video and audio codec`() {
        val format = VideoFormat(3840, 2160, codecV = "video/hevc", codecA = "audio/mp4a-latm", isHdr = true)
        assertThat(vodStreamBadges(format)).containsExactly("4K", "HEVC", "HDR", "AAC").inOrder()
    }

    @Test
    fun `letterboxed 16 by 9 content is judged by its width`() {
        assertThat(vodStreamBadges(VideoFormat(1920, 800)).first()).isEqualTo("FHD")
        assertThat(vodStreamBadges(VideoFormat(1280, 536)).first()).isEqualTo("HD")
        assertThat(vodStreamBadges(VideoFormat(640, 360)).first()).isEqualTo("SD")
    }

    @Test
    fun `unknown format and unknown codecs add no badge`() {
        assertThat(vodStreamBadges(VideoFormat(0, 0))).isEmpty()
        assertThat(vodStreamBadges(VideoFormat(1280, 720, codecV = "video/x-weird", codecA = "audio/x-weird")))
            .containsExactly("HD")
    }

    @Test
    fun `eac3 is not reported as ac3`() {
        assertThat(vodStreamBadges(VideoFormat(1280, 720, codecA = "ec-3")).last()).isEqualTo("E-AC-3")
        assertThat(vodStreamBadges(VideoFormat(1280, 720, codecA = "audio/ac3")).last()).isEqualTo("AC-3")
    }

    @Test
    fun `ends at adds the remaining time scaled by playback speed`() {
        assertThat(vodEndsAtMs(nowMs = 1_000L, positionMs = 10_000L, durationMs = 70_000L, playbackSpeed = 1f))
            .isEqualTo(61_000L)
        assertThat(vodEndsAtMs(nowMs = 1_000L, positionMs = 10_000L, durationMs = 70_000L, playbackSpeed = 2f))
            .isEqualTo(31_000L)
    }

    @Test
    fun `ends at is absent without a duration and clamps past the end`() {
        assertThat(vodEndsAtMs(1_000L, 0L, 0L, 1f)).isNull()
        assertThat(vodEndsAtMs(1_000L, 90_000L, 70_000L, 1f)).isEqualTo(1_000L)
    }
}
