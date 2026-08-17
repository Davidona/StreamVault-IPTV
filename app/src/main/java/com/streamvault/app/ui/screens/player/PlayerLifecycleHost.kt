package com.streamvault.app.ui.screens.player

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.streamvault.app.MainActivity
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
    mainActivity: MainActivity?,
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

    DisposableEffect(mainActivity) {
        onDispose {
            mainActivity?.clearPlayerPictureInPictureState()
            viewModel.onPlayerScreenDisposed()
        }
    }

    val playerWindow = mainActivity?.window
    DisposableEffect(Unit) {
        onDispose { playerWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(preventStandbyDuringPlayback, isPlaying, playbackState) {
        if (preventStandbyDuringPlayback) {
            // Keep screen always on while in player — prevents TV OS standby nag
            playerWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else if (isPlaying || playbackState == PlaybackState.BUFFERING) {
            playerWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            playerWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
