package com.streamvault.app.ui.screens.home

import com.streamvault.player.PlayerEngine

/**
 * High-frequency state for the Home live-preview pane.
 *
 * Preview playback can change independently of channel browsing state, so it
 * must not be stored in [HomeUiState].
 */
data class HomePreviewUiState(
    val previewChannelId: Long? = null,
    val previewPlayerEngine: PlayerEngine? = null,
    val isPreviewLoading: Boolean = false,
    val previewErrorMessage: String? = null
)
