package com.streamvault.player.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl

@UnstableApi
internal fun createLivePlaybackSpeedControl(): DefaultLivePlaybackSpeedControl =
    DefaultLivePlaybackSpeedControl.Builder().build()
