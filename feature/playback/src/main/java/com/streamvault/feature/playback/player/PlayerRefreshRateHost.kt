package com.streamvault.feature.playback.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import kotlinx.coroutines.delay

/** A single fullscreen vote; background, PiP, previews, and multiview cannot compete for HDMI. */
@Composable
internal fun PlayerRefreshRateHost(
    engine: PlayerEngine,
    enabled: Boolean,
    frameRate: Float,
    playbackSpeed: Float,
    playbackState: PlaybackState,
    pictureInPicture: Boolean,
    casting: Boolean
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val currentEngine by rememberUpdatedState(engine)
    DisposableEffect(engine, lifecycle) {
        // Clear immediately at ON_STOP, including when Compose's frame clock is suspended.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) currentEngine.setDisplayFrameRate(null)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            engine.setDisplayFrameRate(null)
        }
    }
    val active = enabled && lifecycleState.isAtLeast(Lifecycle.State.STARTED) &&
        !pictureInPicture && !casting && playbackState != PlaybackState.ERROR && playbackState != PlaybackState.ENDED
    // User playback speed affects presentation cadence. Live latency correction is intentionally
    // absent: its small, temporary speed corrections must not trigger repeated HDMI mode switches.
    val presentationRate = frameRate * playbackSpeed
    val validPresentationRate = presentationRate.isFinite() && presentationRate in 5f..240f
    // Reapply a known rate after a stop/restart even when the next title has identical FPS.
    // Buffering transitions must not extend the grace period for an unknown cadence.
    LaunchedEffect(engine, active, presentationRate, if (validPresentationRate) playbackState else null) {
        if (!active) {
            engine.setDisplayFrameRate(null)
        } else if (validPresentationRate) {
            // Debounce rapid zapping and adaptive rendition changes before an HDMI resync.
            delay(1_000)
            engine.setDisplayFrameRate(presentationRate)
        } else {
            // Keep the previous mode during short channel transitions, but release it for radio,
            // stopped playback, or a source whose cadence cannot be established.
            delay(5_000)
            engine.setDisplayFrameRate(null)
        }
    }
}
