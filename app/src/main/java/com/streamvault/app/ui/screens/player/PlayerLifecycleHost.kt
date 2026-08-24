package com.streamvault.app.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.streamvault.feature.playback.api.PlaybackPlatformHost
import com.streamvault.player.PlaybackState

/**
 * Owns player effects that coordinate the Android lifecycle and window state.
 *
 * This host intentionally contains no player rendering. Keeping lifecycle callbacks,
 * Picture-in-Picture state, cleanup, and the keep-screen-on flag together gives the
 * screen coordinator a smaller presentation responsibility without changing effect
 * keys or the existing ViewModel callbacks.
 */
@Composable
internal fun PlayerLifecycleHost(
    playbackPlatformHost: PlaybackPlatformHost?,
    playbackState: PlaybackState,
    isPlaying: Boolean,
    isInPictureInPictureMode: Boolean,
    showControls: Boolean,
    preventStandbyDuringPlayback: Boolean,
    viewModel: PlayerViewModel
) {
    val currentPictureInPictureMode by rememberUpdatedState(isInPictureInPictureMode)

    LaunchedEffect(isInPictureInPictureMode) {
        if (isInPictureInPictureMode) {
            viewModel.closeOverlays()
            if (showControls) {
                viewModel.toggleControls()
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.onAppForegrounded()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (currentPictureInPictureMode) {
            viewModel.closeOverlays()
        } else {
            viewModel.onAppBackgrounded()
        }
    }

    DisposableEffect(playbackPlatformHost) {
        onDispose {
            playbackPlatformHost?.clearPictureInPictureState()
            viewModel.onPlayerScreenDisposed()
        }
    }

    DisposableEffect(Unit) {
        onDispose { playbackPlatformHost?.setKeepScreenOn(false) }
    }

    LaunchedEffect(preventStandbyDuringPlayback, isPlaying, playbackState) {
        if (preventStandbyDuringPlayback) {
            // Keep screen always on while in player — prevents TV OS standby nag
            playbackPlatformHost?.setKeepScreenOn(true)
        } else if (isPlaying || playbackState == PlaybackState.BUFFERING) {
            playbackPlatformHost?.setKeepScreenOn(true)
        } else {
            playbackPlatformHost?.setKeepScreenOn(false)
        }
    }
}
