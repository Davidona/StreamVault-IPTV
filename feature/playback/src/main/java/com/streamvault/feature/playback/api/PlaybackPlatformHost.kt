package com.streamvault.feature.playback.api

import kotlinx.coroutines.flow.StateFlow

data class PlaybackPictureInPictureState(
    val enabled: Boolean,
    val isPlaying: Boolean,
    val videoWidth: Int,
    val videoHeight: Int,
    val pixelWidthHeightRatio: Float
)

interface PlaybackPlatformHost {
    val pictureInPictureMode: StateFlow<Boolean>

    fun updatePictureInPictureState(state: PlaybackPictureInPictureState)

    fun clearPictureInPictureState()

    fun enterPictureInPicture(): Boolean

    fun openCastRouteChooser()

    fun setKeepScreenOn(enabled: Boolean)
}
