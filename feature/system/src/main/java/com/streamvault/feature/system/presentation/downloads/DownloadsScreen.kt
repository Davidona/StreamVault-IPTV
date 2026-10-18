package com.streamvault.feature.system.presentation.downloads

import com.streamvault.core.ui.design.contentOnColor
import com.streamvault.core.ui.design.BrowseShapes
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.streamvault.feature.system.R
import com.streamvault.core.ui.image.rememberCrossfadeImageModel
import com.streamvault.core.ui.design.BrowseColors as AppColors
import com.streamvault.core.navigation.AppDestination
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.feature.system.api.SystemScaffoldContent

@Composable
fun DownloadsScreen(
    scaffold: SystemScaffoldContent,
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::onFolderSelected)
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.refreshTorrentCacheStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    HandleDownloadsUserMessage(
        userMessage = uiState.userMessage,
        snackbarHostState = snackbarHostState,
        onShown = viewModel::clearUserMessage
    )

    Box(modifier = Modifier.fillMaxSize()) {
        DownloadsContent(
            uiState = uiState,
            scaffold = scaffold,
            onChangeFolder = { folderPicker.launch(null) },
            onOpen = { download -> viewModel.playDownload(download)?.let(context::startActivity) },
            onResume = viewModel::resumeDownload,
            onDelete = viewModel::showDeleteConfirm,
            onConfirmDelete = viewModel::confirmDelete,
            onDismissDelete = viewModel::dismissDeleteConfirm,
            onClearTorrentCache = viewModel::showClearTorrentCacheConfirm,
            onConfirmClearTorrentCache = viewModel::confirmClearTorrentCache,
            onDismissClearTorrentCache = viewModel::dismissClearTorrentCacheConfirm
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
        )
    }

}

@Composable
internal fun DownloadsContent(
    uiState: DownloadsUiState,
    scaffold: SystemScaffoldContent,
    onChangeFolder: () -> Unit,
    onOpen: (DownloadItem) -> Unit,
    onResume: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onClearTorrentCache: () -> Unit = {},
    onConfirmClearTorrentCache: () -> Unit = {},
    onDismissClearTorrentCache: () -> Unit = {},
) {
    scaffold(
        AppDestination.Downloads,
        stringResource(R.string.nav_downloads),
        uiState.storageConfig.displayName,
        true,
        false
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
                Button(onClick = onChangeFolder) {
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

            TorrentCacheCard(
                status = uiState.torrentCacheStatus,
                isClearing = uiState.isClearingTorrentCache,
                onClear = onClearTorrentCache
            )

            when {
                uiState.isLoading -> DownloadsLoadingState(Modifier.weight(1f))
                uiState.downloads.isEmpty() -> DownloadsEmptyState(Modifier.weight(1f))
                else -> DownloadsGrid(
                    downloads = uiState.downloads,
                    onOpenClick = onOpen,
                    onResumeClick = onResume,
                    onDeleteClick = onDelete,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    uiState.deleteConfirmItem?.let { item ->
        DeleteConfirmDialog(
            item = item,
            onConfirm = onConfirmDelete,
            onDismiss = onDismissDelete
        )
    }

    if (uiState.showClearTorrentCacheConfirm) {
        AlertDialog(
            onDismissRequest = onDismissClearTorrentCache,
            title = { Text(stringResource(R.string.torrent_cache_title)) },
            text = { Text(stringResource(R.string.torrent_cache_confirm_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmClearTorrentCache) {
                    Text(stringResource(R.string.torrent_cache_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissClearTorrentCache) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
}

@Composable
private fun TorrentCacheCard(
    status: com.streamvault.domain.model.TorrentCacheStatus?,
    isClearing: Boolean,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = AppColors.Surface,
        shape = BrowseShapes.Control
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.torrent_cache_title),
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.TextPrimary
            )
            Text(
                text = stringResource(R.string.torrent_cache_description),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )
            if (status == null) {
                Text(
                    text = stringResource(R.string.downloads_loading),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.torrent_cache_usage,
                        android.text.format.Formatter.formatShortFileSize(context, status.cachedBytes),
                        android.text.format.Formatter.formatShortFileSize(context, status.cacheLimitBytes),
                        status.availableStorageBytes?.let {
                            android.text.format.Formatter.formatShortFileSize(context, it)
                        } ?: "—"
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppColors.TextPrimary
                )
                if (status.activeSessionCount > 0) {
                    Text(
                        text = stringResource(R.string.torrent_cache_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextTertiary
                    )
                }
            }
            Button(
                onClick = onClear,
                enabled = status != null && status.activeSessionCount == 0 && !isClearing,
                modifier = Modifier.align(Alignment.End)
            ) {
                if (isClearing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text(text = stringResource(R.string.torrent_cache_clear))
            }
        }
    }
}

@Composable
private fun DownloadsLoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
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
private fun DownloadsEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
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
    onDeleteClick: (DownloadItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val columns = if (LocalConfiguration.current.screenWidthDp < 700) {
        GridCells.Adaptive(180.dp)
    } else {
        GridCells.Adaptive(250.dp)
    }

    LazyVerticalGrid(
        columns = columns,
        modifier = modifier.fillMaxSize(),
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
            .clip(BrowseShapes.Control)
            .clickable(enabled = download.status == DownloadStatus.COMPLETED, onClick = onOpenClick),
        color = AppColors.Surface,
        shape = BrowseShapes.Control
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
                    text = formatDownloadFileSize(size),
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
            color = contentOnColor(color),
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

internal fun formatDownloadFileSize(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        else -> "${bytes / (1024L * 1024L * 1024L)} GB"
    }
}
