package com.streamvault.app.playback

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.streamvault.app.MainActivity
import com.streamvault.feature.playback.api.PlaybackPictureInPictureState
import com.streamvault.feature.playback.api.PlaybackPlatformHost
import kotlinx.coroutines.flow.StateFlow

@Composable
fun rememberPlaybackPlatformHost(): PlaybackPlatformHost? {
    val mainActivity = LocalContext.current.findMainActivity()
    return remember(mainActivity) {
        mainActivity?.let(::MainActivityPlaybackPlatformHost)
    }
}

class MainActivityPlaybackPlatformHost internal constructor(
    private val operations: MainActivityPlaybackPlatformOperations
) : PlaybackPlatformHost {
    constructor(activity: MainActivity) : this(MainActivityPlaybackPlatformActivity(activity))

    override val pictureInPictureMode: StateFlow<Boolean>
        get() = operations.pictureInPictureMode

    override fun updatePictureInPictureState(state: PlaybackPictureInPictureState) {
        operations.updatePictureInPictureState(state)
    }

    override fun clearPictureInPictureState() {
        operations.clearPictureInPictureState()
    }

    override fun enterPictureInPicture(): Boolean = operations.enterPictureInPicture()

    override fun openCastRouteChooser() {
        operations.openCastRouteChooser()
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        operations.setKeepScreenOn(enabled)
    }
}

internal interface MainActivityPlaybackPlatformOperations {
    val pictureInPictureMode: StateFlow<Boolean>

    fun updatePictureInPictureState(state: PlaybackPictureInPictureState)

    fun clearPictureInPictureState()

    fun enterPictureInPicture(): Boolean

    fun openCastRouteChooser()

    fun setKeepScreenOn(enabled: Boolean)
}

private class MainActivityPlaybackPlatformActivity(
    private val activity: MainActivity
) : MainActivityPlaybackPlatformOperations {
    override val pictureInPictureMode: StateFlow<Boolean>
        get() = activity.pictureInPictureModeFlow

    override fun updatePictureInPictureState(state: PlaybackPictureInPictureState) {
        activity.updatePlayerPictureInPictureState(
            enabled = state.enabled,
            isPlaying = state.isPlaying,
            videoWidth = state.videoWidth,
            videoHeight = state.videoHeight,
            pixelWidthHeightRatio = state.pixelWidthHeightRatio
        )
    }

    override fun clearPictureInPictureState() {
        activity.clearPlayerPictureInPictureState()
    }

    override fun enterPictureInPicture(): Boolean = activity.enterPlayerPictureInPictureModeFromPlayer()

    override fun openCastRouteChooser() {
        activity.openCastRouteChooser()
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
