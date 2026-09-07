package com.streamvault.feature.playback.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerRenderSurfaceType
import com.streamvault.player.PlayerSurfaceResizeMode
import com.streamvault.player.ui.PlayerRenderView

/** Keeps the platform-backed video surface at the front of the player composition. */
@Composable
internal fun PlayerVideoSurface(
    playerEngine: PlayerEngine,
    resizeMode: PlayerSurfaceResizeMode,
    surfaceType: PlayerRenderSurfaceType,
    modifier: Modifier = Modifier
) {
    PlayerRenderView(
        playerEngine = playerEngine,
        resizeMode = resizeMode,
        surfaceType = surfaceType,
        modifier = modifier
    )
}
