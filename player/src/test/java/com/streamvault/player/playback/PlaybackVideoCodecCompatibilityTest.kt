package com.streamvault.player.playback

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackVideoCodecCompatibilityTest {
    @Test
    fun `affected decoder rejects resolution reuse but retains same resolution reuse`() {
        val original = codec()
        assertThat(original.adaptive).isTrue()
        val patched = select(listOf(original)).single()
        val small = format(1280, 720)
        val large = format(3840, 2160)
        assertThat(patched.canReuseCodec(small, large).result)
            .isEqualTo(DecoderReuseEvaluation.REUSE_RESULT_NO)
        assertThat(patched.canReuseCodec(large, small).result)
            .isEqualTo(DecoderReuseEvaluation.REUSE_RESULT_NO)
        assertThat(patched.canReuseCodec(small, small).result)
            .isEqualTo(original.canReuseCodec(small, small).result)
    }

    @Test
    fun `wrapper preserves ordering and unrelated decoder entries`() {
        val affected = codec()
        val vendor = codec("c2.vendor.avc.decoder")
        val hevc = codec("c2.goldfish.hevc.decoder", "video/hevc")
        val software = codec("c2.android.avc.decoder")
        val actual = select(listOf(vendor, affected, hevc, software))
        assertThat(actual.map { it.name }).containsExactly(
            vendor.name, affected.name, hevc.name, software.name
        ).inOrder()
        assertThat(actual[0]).isSameInstanceAs(vendor)
        assertThat(actual[2]).isSameInstanceAs(hevc)
        assertThat(actual[3]).isSameInstanceAs(software)
    }

    @Test
    fun `real device and non avc query retain original entries`() {
        val original = codec()
        assertThat(select(listOf(original), eligible = false).single()).isSameInstanceAs(original)
        assertThat(select(listOf(original), mime = "audio/mp4a-latm").single()).isSameInstanceAs(original)
    }

    @Test
    fun `patched entry preserves capability and security information`() {
        val original = codec(secure = true)
        val actual = select(listOf(original), secure = true, tunneling = true).single()
        assertThat(actual.adaptive).isFalse()
        assertThat(actual.capabilities).isSameInstanceAs(original.capabilities)
        assertThat(actual.codecMimeType).isEqualTo(original.codecMimeType)
        assertThat(actual.mimeType).isEqualTo(original.mimeType)
        assertThat(actual.hardwareAccelerated).isEqualTo(original.hardwareAccelerated)
        assertThat(actual.softwareOnly).isEqualTo(original.softwareOnly)
        assertThat(actual.vendor).isEqualTo(original.vendor)
        assertThat(actual.secure).isTrue()
        assertThat(actual.tunneling).isEqualTo(original.tunneling)
        assertThat(actual.detachedSurfaceSupported).isEqualTo(original.detachedSurfaceSupported)
    }

    @Test
    fun `already nonadaptive codec retains original entry`() {
        val original = codec(nonadaptive = true)
        assertThat(select(listOf(original)).single()).isSameInstanceAs(original)
    }

    private fun select(
        entries: List<MediaCodecInfo>, eligible: Boolean = true,
        mime: String = "video/avc", secure: Boolean = false, tunneling: Boolean = false
    ): List<MediaCodecInfo> {
        val delegate = MediaCodecSelector { queryMime, querySecure, queryTunneling ->
            assertThat(queryMime).isEqualTo(mime)
            assertThat(querySecure).isEqualTo(secure)
            assertThat(queryTunneling).isEqualTo(tunneling)
            entries.toMutableList()
        }
        return PlaybackVideoCodecCompatibility(delegate, eligible)
            .getDecoderInfos(mime, secure, tunneling)
    }

    private fun format(width: Int, height: Int) = Format.Builder()
        .setSampleMimeType("video/avc").setWidth(width).setHeight(height).build()

    private fun codec(
        name: String = "c2.goldfish.h264.decoder", mime: String = "video/avc",
        secure: Boolean = false, nonadaptive: Boolean = false
    ): MediaCodecInfo {
        val mediaFormat = MediaFormat.createVideoFormat(mime, 3840, 2160).apply {
            setFeatureEnabled(CodecCapabilities.FEATURE_AdaptivePlayback, true)
            setFeatureEnabled(CodecCapabilities.FEATURE_TunneledPlayback, true)
        }
        val capabilities = CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(mediaFormat)
            .setColorFormats(intArrayOf(CodecCapabilities.COLOR_FormatSurface))
            .setProfileLevels(emptyArray()).build()
        return MediaCodecInfo.newInstance(
            name, mime, mime, capabilities, true, false, true, nonadaptive, secure
        )
    }
}
