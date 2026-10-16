@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Proxy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveVideoStallRecoveryTest {
    @Test
    fun `confirmed live video stall seeks once then stops a persistently stalled source`() {
        val fake = Playback()
        val recovery = LiveVideoStallRecovery()
        assertThat(recovery.recover(fake.player, true, false)).isEqualTo(LiveVideoStallRecovery.Result.SEEKED)
        assertThat(fake.seeks).isEqualTo(1)
        assertThat(fake.stops).isEqualTo(0)
        assertThat(recovery.recover(fake.player, true, false)).isEqualTo(LiveVideoStallRecovery.Result.STOPPED)
        assertThat(fake.seeks).isEqualTo(1)
        assertThat(fake.stops).isEqualTo(1)
        assertThat(fake.prepares).isEqualTo(0)
    }

    @Test
    fun `reset gives a new playback session its own single recovery attempt`() {
        val fake = Playback()
        val recovery = LiveVideoStallRecovery()
        recovery.recover(fake.player, true, false)
        recovery.reset()
        assertThat(recovery.recover(fake.player, true, false)).isEqualTo(LiveVideoStallRecovery.Result.SEEKED)
        assertThat(fake.seeks).isEqualTo(2)
    }

    @Test
    fun `active Media3 retry retains ownership without consuming the seek attempt`() {
        val fake = Playback()
        val recovery = LiveVideoStallRecovery()
        assertThat(recovery.recover(fake.player, true, true)).isEqualTo(LiveVideoStallRecovery.Result.NONE)
        assertThat(fake.seeks).isEqualTo(0)
        assertThat(recovery.recover(fake.player, true, false)).isEqualTo(LiveVideoStallRecovery.Result.SEEKED)
    }

    @Test
    fun `buffering pause and audio-only playback do not trigger this recovery`() {
        for (fake in listOf(Playback(state = Player.STATE_BUFFERING), Playback(playing = false), Playback(video = false))) {
            assertThat(LiveVideoStallRecovery().recover(fake.player, true, false)).isEqualTo(LiveVideoStallRecovery.Result.NONE)
            assertThat(fake.seeks + fake.stops).isEqualTo(0)
        }
    }

    @Test
    fun `finite HLS and catch-up retain their positions`() {
        for ((fake, liveIntent) in listOf(Playback(live = false) to true, Playback() to false)) {
            assertThat(LiveVideoStallRecovery().recover(fake.player, liveIntent, false)).isEqualTo(LiveVideoStallRecovery.Result.NONE)
            assertThat(fake.seeks + fake.stops).isEqualTo(0)
        }
    }

    @Test
    fun `unseekable live stall stops loading rather than requesting an invalid seek`() {
        val fake = Playback(seekable = false)
        assertThat(LiveVideoStallRecovery().recover(fake.player, true, false)).isEqualTo(LiveVideoStallRecovery.Result.STOPPED)
        assertThat(fake.seeks).isEqualTo(0)
        assertThat(fake.stops).isEqualTo(1)
    }

    @Test
    fun `a seek followed by persistent buffering stops the source`() {
        val fake = Playback()
        val recovery = LiveVideoStallRecovery()
        recovery.recover(fake.player, true, false)
        fake.state = Player.STATE_BUFFERING
        assertThat(recovery.recover(fake.player, true, false)).isEqualTo(LiveVideoStallRecovery.Result.STOPPED)
        assertThat(fake.stops).isEqualTo(1)
    }

    @Test
    fun `recovery uses a different position when already at the timeline default`() {
        val fake = Playback()
        LiveVideoStallRecovery().recover(fake.player, true, false)
        assertThat(fake.seekPositionMs).isEqualTo(1L)
    }

    private class Playback(
        state: Int = Player.STATE_READY,
        playing: Boolean = true,
        video: Boolean = true,
        live: Boolean = true,
        seekable: Boolean = true
    ) {
        var state = state
        var seeks = 0
        var stops = 0
        var prepares = 0
        var seekPositionMs = -1L
        private val timeline = SinglePeriodTimeline(
            300_000_000L, true, false, false, null, MediaItem.fromUri("https://example.invalid/live.m3u8")
        )
        private val tracks = Tracks(listOf(Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType("video/avc").build()),
            false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(video)
        )))
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getPlaybackState" -> this.state
                "getPlayWhenReady" -> playing
                "getCurrentTracks" -> tracks
                "isCurrentMediaItemLive" -> live
                "isCurrentMediaItemSeekable" -> seekable
                "getCurrentTimeline" -> timeline
                "getCurrentMediaItemIndex" -> 0
                "getCurrentPosition" -> 0L
                "seekToDefaultPosition" -> { seeks++; null }
                "seekTo" -> { seeks++; seekPositionMs = args.last() as Long; null }
                "stop" -> { stops++; null }
                "prepare" -> { prepares++; null }
                else -> error("Unexpected player call: ${method.name}")
            }
        } as Player
    }
}
