package com.streamvault.app.ui.screens.downloads

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.streamvault.app.R
import com.streamvault.core.ui.image.rememberCrossfadeImageModel
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.core.ui.design.AppColors
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadStatus

@Composable
fun DownloadsScreen(
    onNavigate: (String) -> Unit,
    currentRoute: String,
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::onFolderSelected)
    }

    HandleDownloadsUserMessage(
        userMessage = uiState.userMessage,
        snackbarHostState = snackbarHostState,
        onShown = viewModel::clearUserMessage
    )

    Box(modifier = Modifier.fillMaxSize()) {
        AppScreenScaffold(
            currentRoute = currentRoute,
            onNavigate = onNavigate,
            title = stringResource(R.string.nav_downloads),
            subtitle = uiState.storageConfig.displayName,
            navigationChrome = AppNavigationChrome.TopBar,
            compactHeader = true,
            showScreenHeader = false
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                val downloadFolderLabel = uiState.storageConfig.displayName
                    ?: uiState.storageConfig.treeUri
                    ?: stringResource(R.string.download_folder_default)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Button(onClick = { folderPicker.launch(null) }) {
                        Text(text = stringResource(R.string.download_folder_change))
                    }
                    Text(
                        text = downloadFolderLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .padding(top = 4.dp)
                    )
                }

                when {
                    uiState.isLoading -> DownloadsLoadingState()
                    uiState.downloads.isEmpty() -> DownloadsEmptyState()
                    else -> DownloadsGrid(
                        downloads = uiState.downloads,
                        onOpenClick = { download ->
                            viewModel.playDownload(download)?.let(context::startActivity)
                        },
                        onResumeClick = viewModel::resumeDownload,
                        onDeleteClick = viewModel::showDeleteConfirm
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
        )
    }

    uiState.deleteConfirmItem?.let { item ->
        DeleteConfirmDialog(
            item = item,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDeleteConfirm
        )
    }
}

@Composable
private fun DownloadsLoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = AppColors.Brand
            )
            Text(
                text = stringResource(R.string.downloads_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.TextPrimary
            )
        }
    }
}

@Composable
private fun DownloadsEmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.downloads_empty_title),
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary
            )
            Text(
                text = stringResource(R.string.downloads_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
private fun DownloadsGrid(
    downloads: List<DownloadItem>,
    onOpenClick: (DownloadItem) -> Unit,
    onResumeClick: (DownloadItem) -> Unit,
    onDeleteClick: (DownloadItem) -> Unit
) {
    val columns = if (LocalConfiguration.current.screenWidthDp < 700) {
        GridCells.Adaptive(180.dp)
    } else {
        GridCells.Adaptive(250.dp)
    }

    LazyVerticalGrid(
        columns = columns,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(downloads, key = { it.id }) { download ->
            DownloadCard(
                download = download,
                onOpenClick = { onOpenClick(download) },
                onResumeClick = { onResumeClick(download) },
                onDeleteClick = { onDeleteClick(download) }
            )
        }
    }
}

@Composable
private fun DownloadCard(
    download: DownloadItem,
    onOpenClick: () -> Unit,
    onResumeClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val progress = download.totalBytes?.takeIf { it > 0L }?.let { total ->
        (download.bytesWritten.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } ?: 0f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = download.status == DownloadStatus.COMPLETED, onClick = onOpenClick),
        color = AppColors.Surface,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.SurfaceElevated)
            ) {
                if (download.posterUrl != null) {
                    AsyncImage(
                        model = rememberCrossfadeImageModel(download.posterUrl),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = stringResource(R.string.downloads_no_thumb),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextTertiary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                StatusBadge(
                    status = download.status,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = download.contentName.ifBlank { stringResource(R.string.downloads_item_title) },
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            download.outputDisplayPath?.let { path ->
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            DownloadProgress(status = download.status, progress = progress)

            download.totalBytes?.let { size ->
                Text(
                    text = formatFileSize(size),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (download.status == DownloadStatus.FAILED) {
                    TextButton(onClick = onResumeClick) {
                        Text(text = stringResource(R.string.download_resume))
                    }
                }
                TextButton(onClick = onDeleteClick) {
                    Text(text = stringResource(R.string.download_delete))
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: DownloadStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        DownloadStatus.COMPLETED -> AppColors.Success
        DownloadStatus.DOWNLOADING -> AppColors.Brand
        DownloadStatus.PAUSED -> AppColors.Warning
        DownloadStatus.FAILED -> AppColors.Live
        DownloadStatus.PENDING -> AppColors.TextTertiary
        DownloadStatus.CANCELLED -> AppColors.TextDisabled
    }

    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.9f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = statusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun DownloadProgress(status: DownloadStatus, progress: Float) {
    when (status) {
        DownloadStatus.DOWNLOADING -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp),
                    color = AppColors.Brand,
                    trackColor = AppColors.SurfaceElevated
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextSecondary
                )
            }
        }

        DownloadStatus.FAILED,
        DownloadStatus.PAUSED,
        DownloadStatus.CANCELLED,
        DownloadStatus.PENDING,
        DownloadStatus.COMPLETED -> {
            Text(
                text = statusLabel(status),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun statusLabel(status: DownloadStatus): String {
    return when (status) {
        DownloadStatus.COMPLETED -> stringResource(R.string.downloads_status_completed)
        DownloadStatus.DOWNLOADING -> stringResource(R.string.downloads_status_downloading)
        DownloadStatus.FAILED -> stringResource(R.string.downloads_status_failed)
        DownloadStatus.PENDING -> stringResource(R.string.downloads_status_pending)
        DownloadStatus.PAUSED -> stringResource(R.string.downloads_status_paused)
        DownloadStatus.CANCELLED -> stringResource(R.string.downloads_status_cancelled)
    }
}

@Composable
private fun DeleteConfirmDialog(
    item: DownloadItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.downloads_delete_confirm_title))
        },
        text = {
            Text(
                text = stringResource(
                    R.string.downloads_delete_confirm_msg,
                    item.contentName.ifBlank { stringResource(R.string.downloads_item_title) }
                )
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Live)
            ) {
                Text(text = stringResource(R.string.downloads_delete_confirm_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_cancel))
            }
        }
    )
}

@Composable
fun HandleDownloadsUserMessage(
    userMessage: String?,
    snackbarHostState: SnackbarHostState,
    onShown: () -> Unit
) {
    LaunchedEffect(userMessage) {
        userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onShown()
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        else -> "${bytes / (1024L * 1024L * 1024L)} GB"
    }
}
