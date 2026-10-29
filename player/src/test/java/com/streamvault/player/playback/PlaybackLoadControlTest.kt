@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlaybackLoadControlTest {
    private val policy = PlaybackBufferPolicies.forPlayback(
        ResolvedStreamType.HLS, compatibilityMode = false, lowMemoryDevice = true
    )
    private val timeline = SinglePeriodTimeline(
        300_000_000L, true, false, false, null,
        MediaItem.fromUri("https://example.invalid/fixture.m3u8")
    )

    @Test
    fun `allocation diagnostics remain safe after the playback thread releases the player`() {
        val control = PlaybackLoadControl(policy, 192L * MIB, false)
        val id = PlayerId.UNSET
        val selection = fixedVideo(20_000_000)
        val parameters = LoadControl.Parameters(
            id, timeline, MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
            0L, 0L, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET
        )
        control.onPrepared(id)
        control.onTracksSelected(parameters, TrackGroupArray(selection.trackGroup), arrayOf(selection))
        control.shouldContinueLoading(parameters)
        assertThat(control.describeAllocation()).contains("allocatedBytes=0")
        control.onReleased(id)
        // Transition callbacks may run after playback-thread release. They must not query its allocator.
        assertThat(control.describeAllocation()).contains("allocatedBytes=unknown")
    }

    @Test
    fun `high bitrate playback keeps loading past the old 32 MiB restriction`() {
        assertThat(loadingAfter(48, 192, fixedVideo(80_000_000))).isTrue()
    }

    @Test
    fun `byte target increases with the selected stream bitrate`() {
        assertThat(loadingAfter(40, 192, fixedVideo(20_000_000))).isFalse()
        assertThat(loadingAfter(40, 192, fixedVideo(40_000_000))).isTrue()
    }

    @Test
    fun `memory protection stops loading even when buffered time is missing`() {
        assertThat(loadingAfter(73, 192, fixedVideo(80_000_000))).isFalse()
    }

    @Test
    fun `larger device heap permits a larger high bitrate buffer`() {
        assertThat(loadingAfter(100, 192, fixedVideo(80_000_000))).isFalse()
        assertThat(loadingAfter(100, 384, fixedVideo(80_000_000))).isTrue()
    }

    @Test
    fun `unknown bitrate uses a bounded Media3 track target`() {
        assertThat(loadingAfter(48, 192, fixedVideo(C.RATE_UNSET_INT))).isTrue()
        assertThat(loadingAfter(73, 192, fixedVideo(C.RATE_UNSET_INT))).isFalse()
    }

    @Test
    fun `adaptive sizing allows the eligible higher rendition without a player restart`() {
        val group = TrackGroup(video(20_000_000), video(80_000_000))
        val selection = AdaptiveTrackSelection(
            group, intArrayOf(0, 1), DefaultBandwidthMeter.Builder(RuntimeEnvironment.getApplication()).build()
        )
        assertThat(loadingAfter(48, 192, selection)).isTrue()
        // A manual selection of the lower track does not allocate for an unselected alternative.
        assertThat(loadingAfter(40, 192, FixedTrackSelection(group, 0))).isFalse()
    }

    @Test
    fun `multiview shares the device budget across four players`() {
        assertThat(loadingAfter(20, 192, fixedVideo(80_000_000), multiview = true)).isFalse()
        assertThat(loadingAfter(20, 192, fixedVideo(80_000_000))).isTrue()
    }

    @Test
    fun `preloading cannot consume the foreground memory allowance`() {
        assertThat(loadingAfter(25, 192, fixedVideo(80_000_000), playerId = PlayerId.PRELOAD)).isFalse()
    }

    @Test
    fun `an existing player updates its budget when moving into or out of multiview`() {
        assertThat(loadingAfter(20, 192, fixedVideo(80_000_000), updateMultiview = true)).isFalse()
        assertThat(loadingAfter(20, 192, fixedVideo(80_000_000), multiview = true, updateMultiview = false)).isTrue()
    }

    @Test
    fun `releasing one shared preload keeps surviving budget responsive to multiview`() {
        assertThat(loadingAfter(10, 192, fixedVideo(80_000_000),
            playerId = PlayerId.PRELOAD, updateMultiview = true, releaseSharedPreload = true)).isFalse()
    }

    @Test
    fun `stopping one shared preload keeps surviving budget responsive to multiview`() {
        assertThat(loadingAfter(10, 192, fixedVideo(80_000_000),
            playerId = PlayerId.PRELOAD, updateMultiview = true, stopSharedPreload = true)).isFalse()
    }

    private fun loadingAfter(
        allocatedMiB: Int,
        heapMiB: Int,
        selection: ExoTrackSelection,
        multiview: Boolean = false,
        playerId: PlayerId = PlayerId.UNSET,
        updateMultiview: Boolean? = null,
        releaseSharedPreload: Boolean = false,
        stopSharedPreload: Boolean = false
    ): Boolean {
        val control = PlaybackLoadControl(policy, heapMiB.toLong() * MIB, multiview)
        control.onPrepared(playerId)
        if (releaseSharedPreload || stopSharedPreload) control.onPrepared(playerId)
        val parameters = LoadControl.Parameters(
            playerId, timeline, MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
            0L, 0L, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET
        )
        control.onTracksSelected(parameters, TrackGroupArray(selection.trackGroup), arrayOf(selection))
        control.shouldContinueLoading(parameters)
        val allocator = control.getAllocator(playerId)
        val blocks = List(allocatedMiB * MIB / allocator.individualAllocationLength) { allocator.allocate() }
        if (releaseSharedPreload) control.onReleased(playerId)
        if (stopSharedPreload) control.onStopped(playerId)
        updateMultiview?.let(control::setMultiview)
        return try {
            control.shouldContinueLoading(parameters)
        } finally {
            blocks.forEach(allocator::release)
            control.onReleased(playerId)
        }
    }

    private fun fixedVideo(bitrate: Int) = FixedTrackSelection(TrackGroup(video(bitrate)), 0)
    private fun video(bitrate: Int) = Format.Builder().setSampleMimeType("video/avc")
        .setWidth(3840).setHeight(2160).setAverageBitrate(bitrate).build()

    private companion object {
        const val MIB = 1024 * 1024
    }
}
