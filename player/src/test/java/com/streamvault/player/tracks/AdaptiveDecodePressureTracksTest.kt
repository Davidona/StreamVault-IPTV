package com.streamvault.player.tracks

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import com.google.common.truth.Truth.assertThat
import com.streamvault.player.playback.DecodePressureRendition
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AdaptiveDecodePressureTracksTest {
    private val formats = listOf(format("high", 2160, 41_000_000), format("mid", 1080, 9_000_000), format("low", 720, 3_000_000))
    private val group = TrackGroup("video", *formats.toTypedArray())
    private fun format(id: String, height: Int, bitrate: Int) = Format.Builder().setId(id).setSampleMimeType("video/avc")
        .setHeight(height).setWidth(height * 16 / 9).setAverageBitrate(bitrate).build()
    private fun parameters() = TrackSelectionParameters.Builder(RuntimeEnvironment.getApplication()).setViewportSize(3840, 2160, true).build()
    private fun tracks(adaptive: Boolean = true, support: IntArray = intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED)) =
        Tracks(listOf(Tracks.Group(group, adaptive, support, booleanArrayOf(true, false, false))))
    private val context get() = DecodePressureTrackContext(group, listOf(0, 1, 2), listOf(
        DecodePressureRendition(2160, 41_000_000), DecodePressureRendition(1080, 9_000_000), DecodePressureRendition(720, 3_000_000)))

    @Test fun `narrowing keeps ABR inside the original group and preserves other parameters`() {
        val binding = AdaptiveDecodePressureTracks()
        val audio = TrackSelectionOverride(TrackGroup("audio", Format.Builder().setSampleMimeType("audio/aac").build()), 0)
        val original = parameters().buildUpon().setOverrideForType(audio).setPreferredTextLanguage("es").setMaxVideoBitrate(45_000_000).build()
        val narrowed = binding.constrain(original, context, 1080)
        assertThat(narrowed.overrides[group]?.trackIndices).containsExactly(1, 2).inOrder()
        assertThat(narrowed.overrides[audio.mediaTrackGroup]).isEqualTo(audio)
        assertThat(narrowed.preferredTextLanguages).containsExactly("es")
        assertThat(narrowed.maxVideoBitrate).isEqualTo(45_000_000)
        assertThat(binding.context(tracks(), formats[1], narrowed, true)).isNotNull()
        assertThat(binding.clear(narrowed)).isEqualTo(original)
    }

    @Test fun `manual override is neither narrowed nor removed on cleanup`() {
        val binding = AdaptiveDecodePressureTracks()
        val manual = TrackSelectionOverride(group, 0)
        assertThat(binding.context(tracks(), formats[0], parameters().buildUpon().setOverrideForType(manual).build(), true)).isNull()
        assertThat(binding.context(tracks(), formats[0], parameters(), false)).isNull()
        val narrowed = binding.constrain(parameters(), context, 1080)
        val selected = narrowed.buildUpon().setOverrideForType(manual).build()
        assertThat(binding.clear(selected)).isEqualTo(selected)
    }

    @Test fun `unsupported alternatives and nonadaptive groups are excluded`() {
        val binding = AdaptiveDecodePressureTracks()
        assertThat(binding.context(tracks(), formats[0], parameters(), true)?.indices).containsExactly(0, 1, 2)
        assertThat(binding.context(tracks(false), formats[0], parameters(), true)).isNull()
        assertThat(binding.context(tracks(support = intArrayOf(C.FORMAT_HANDLED, C.FORMAT_EXCEEDS_CAPABILITIES, C.FORMAT_UNSUPPORTED_SUBTYPE)), formats[0], parameters(), true)).isNull()
    }

    @Test fun `saved quality limits still bound the eligible adaptive subset`() {
        val binding = AdaptiveDecodePressureTracks()
        val params = parameters().buildUpon().setMaxVideoSize(Int.MAX_VALUE, 1080).build()
        assertThat(binding.context(tracks(), formats[1], params, true)?.indices).containsExactly(1, 2)
        val narrowed = binding.constrain(params, binding.context(tracks(), formats[1], params, true)!!, 720)
        assertThat(narrowed.overrides[group]?.trackIndices).containsExactly(2)
        assertThat(narrowed.maxVideoHeight).isEqualTo(1080)
        assertThat(binding.clear(narrowed).maxVideoHeight).isEqualTo(1080)
    }

    @Test fun `nonmonotonic bitrate ladder excludes more expensive lower resolution`() {
        val odd = TrackGroup("odd", format("high", 2160, 10_000_000), format("mid", 1080, 8_000_000), format("low", 720, 20_000_000))
        val ctx = DecodePressureTrackContext(odd, listOf(0, 1, 2), emptyList())
        val narrowed = AdaptiveDecodePressureTracks().constrain(parameters(), ctx, 1080, 8_000_000)
        assertThat(narrowed.overrides[odd]?.trackIndices.orEmpty()).containsExactly(1)
    }

    @Test fun `frame rate minimums and explicit viewport remain eligibility constraints`() {
        val binding = AdaptiveDecodePressureTracks()
        val fast = formats[1].buildUpon().setFrameRate(120f).build()
        val g = TrackGroup("fps", formats[0].buildUpon().setFrameRate(60f).build(), fast, formats[2].buildUpon().setFrameRate(60f).build())
        val t = Tracks(listOf(Tracks.Group(g, true, IntArray(3) { C.FORMAT_HANDLED }, booleanArrayOf(true, false, false))))
        val params = parameters().buildUpon().setViewportSize(3840, 2160, true).setMaxVideoFrameRate(60).build()
        assertThat(binding.context(t, g.getFormat(0), params, true)?.indices.orEmpty()).containsExactly(0, 2)
        val minimum = parameters().buildUpon().setMinVideoSize(1920, 1080).build()
        assertThat(binding.context(tracks(), formats[0], minimum, true)?.indices.orEmpty()).containsExactly(0, 1)
        val viewport = parameters().buildUpon().setViewportSize(1280, 720, true).build()
        assertThat(binding.context(tracks(), formats[0], viewport, true)).isNull()
    }
}
