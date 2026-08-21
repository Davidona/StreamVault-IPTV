package com.streamvault.app.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamvault.app.ui.screens.player.overlay.DiagnosticsOverlay

/** Collects diagnostics only while the diagnostics overlay can be rendered. */
@Composable
internal fun PlayerDiagnosticsHost(
    viewModel: PlayerViewModel,
    visible: Boolean,
    isInPictureInPictureMode: Boolean,
    modifier: Modifier = Modifier
) {
    if (!visible || isInPictureInPictureMode) return

    val playerStats by viewModel.playerStats.collectAsStateWithLifecycle()
    val playerDiagnostics by viewModel.playerDiagnostics.collectAsStateWithLifecycle()
    DiagnosticsOverlay(
        stats = playerStats,
        diagnostics = playerDiagnostics,
        modifier = modifier
    )
}
