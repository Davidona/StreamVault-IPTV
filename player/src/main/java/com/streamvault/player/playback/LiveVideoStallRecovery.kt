@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.Timeline

/** Called only after the frame-silence detector confirms a stall. Main thread only. */
internal class LiveVideoStallRecovery {
    enum class Result { NONE, SEEKED, STOPPED }
    private var attempted = false

    fun reset() {
        attempted = false
    }

    fun recover(player: Player, livePlayback: Boolean, loadRetryPending: Boolean): Result {
        if (loadRetryPending || !livePlayback || !player.playWhenReady ||
            !player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
        ) return Result.NONE

        if (attempted && player.playbackState in listOf(Player.STATE_READY, Player.STATE_BUFFERING)) {
            player.stop()
            return Result.STOPPED
        }
        if (player.playbackState != Player.STATE_READY || !player.isCurrentMediaItemLive) return Result.NONE
        if (!attempted && player.isCurrentMediaItemSeekable) {
            val index = player.currentMediaItemIndex
            val window = player.currentTimeline.getWindow(index, Timeline.Window())
            var positionMs = window.defaultPositionMs
            if (positionMs == C.TIME_UNSET) {
                player.stop()
                return Result.STOPPED
            }
            // A seek at the current millisecond can be skipped by Media3. With the
            // engine's default EXACT seek parameters, a distinct target resets renderers.
            if (positionMs == player.currentPosition) {
                positionMs = if (window.durationMs != C.TIME_UNSET && positionMs >= window.durationMs) {
                    (positionMs - 1L).coerceAtLeast(0L)
                } else positionMs + 1L
            }
            attempted = true
            // Request a seek on the current source; subsequent frames establish success.
            player.seekTo(index, positionMs)
            return Result.SEEKED
        }
        // Persistent/nonseekable decoder stalls must not keep accumulating unused samples.
        player.stop()
        return Result.STOPPED
    }
}
