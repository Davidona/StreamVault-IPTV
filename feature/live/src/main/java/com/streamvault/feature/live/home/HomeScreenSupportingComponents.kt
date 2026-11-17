package com.streamvault.feature.live.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.device.KeepScreenOn
import com.streamvault.domain.model.Channel
import com.streamvault.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun HomeLoadingPane(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            androidx.compose.material3.CircularProgressIndicator(color = Color.White)
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun HomePreviewHost(
    viewModel: HomeViewModel,
    channels: List<Channel>,
    modifier: Modifier = Modifier,
    hostsVideoExternally: Boolean = false,
    onVideoSlotPositioned: ((LayoutCoordinates?) -> Unit)? = null
) {
    val previewUiState by viewModel.previewUiState.collectAsStateWithLifecycle()
    val previewChannel = remember(channels, previewUiState.previewChannelId) {
        channels.firstOrNull { it.id == previewUiState.previewChannelId }
    }
    val previewEngine = previewUiState.previewPlayerEngine
    val isPreviewPlaying by remember(previewEngine) { previewEngine?.isPlaying ?: MutableStateFlow(false) }
        .collectAsStateWithLifecycle()
    val previewPlaybackState by remember(previewEngine) {
        previewEngine?.playbackState ?: MutableStateFlow(PlaybackState.IDLE)
    }.collectAsStateWithLifecycle()

    // Keep the Fire TV screensaver away while the preview (incl. inline fullscreen) is playing,
    // the same rule the full player uses. (The old `LocalContext as? Activity` lookup was always
    // null because the host wraps LocalContext for in-app language, so the flag was never set.)
    KeepScreenOn(
        enabled = previewEngine != null &&
            (isPreviewPlaying || previewPlaybackState == PlaybackState.BUFFERING)
    )

    LivePreviewPane(
        channel = previewChannel,
        playerEngine = previewUiState.previewPlayerEngine,
        isLoading = previewUiState.isPreviewLoading,
        errorMessage = previewUiState.previewErrorMessage,
        modifier = modifier,
        hostsVideoExternally = hostsVideoExternally,
        onVideoSlotPositioned = onVideoSlotPositioned
    )
}
