package com.streamvault.app.ui.screens.player

import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerStats

internal fun shouldRenewAdoptedPreviewOnFullscreen(
    playbackState: PlaybackState,
    playerStats: PlayerStats
): Boolean = playbackState != PlaybackState.READY || playerStats.ttffMs <= 0L
