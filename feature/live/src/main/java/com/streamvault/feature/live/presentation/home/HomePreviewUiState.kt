package com.streamvault.feature.live.presentation.home

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
    val previewErrorMessage: String? = null,
    /** Real-time translation can be offered for this channel (setting on + supported live source). */
    val liveTranslationAvailable: Boolean = false,
    /** The user picked "Real-time translation"; it follows them as they change channel. */
    val liveTranslationActive: Boolean = false,
    /** Last translation service problem, shown briefly in inline fullscreen. */
    val liveTranslationMessage: String? = null
)
