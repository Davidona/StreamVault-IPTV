package com.streamvault.feature.live.home

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.domain.model.Channel

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
    modifier: Modifier = Modifier
) {
    val previewUiState by viewModel.previewUiState.collectAsStateWithLifecycle()
    val previewChannel = remember(channels, previewUiState.previewChannelId) {
        channels.firstOrNull { it.id == previewUiState.previewChannelId }
    }
    val hasActivePreview = previewUiState.previewPlayerEngine != null
    val context = LocalContext.current

    DisposableEffect(hasActivePreview) {
        val window = (context as? Activity)?.window
        if (hasActivePreview) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (hasActivePreview) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    LivePreviewPane(
        channel = previewChannel,
        playerEngine = previewUiState.previewPlayerEngine,
        isLoading = previewUiState.isPreviewLoading,
        errorMessage = previewUiState.previewErrorMessage,
        modifier = modifier
    )
}
