package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.download.OfflineDownloadItem
import com.streamvault.app.download.OfflineDownloadStatus
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.theme.ErrorColor
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated

internal fun LazyListScope.settingsDownloadsSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onPlayDownload: (OfflineDownloadItem) -> Unit
) {
    item {
        DownloadsSummaryCard(
            activeCount = uiState.offlineDownloads.count { it.status.isActive },
            completedCount = uiState.offlineDownloads.count { it.status == OfflineDownloadStatus.SUCCESSFUL },
            totalCount = uiState.offlineDownloads.size,
            onRefresh = viewModel::refreshOfflineDownloads
        )
    }
    if (uiState.offlineDownloads.isEmpty()) {
        item {
            EmptyDownloadsCard()
        }
    } else {
        item {
            DownloadSelectionCard(
                selectedCount = uiState.selectedOfflineDownloadIds.size,
                onDeleteSelected = viewModel::deleteSelectedOfflineDownloads,
                onClearSelection = viewModel::clearOfflineDownloadSelection
            )
        }
        groupedDownloadFolders(uiState.offlineDownloads).forEach { folder ->
            item(key = "folder-${folder.name}") {
                DownloadFolderHeader(
                    folder = folder,
                    selectedCount = folder.items.count { it.id in uiState.selectedOfflineDownloadIds },
                    onSelectAll = { viewModel.selectOfflineDownloads(folder.items.map { it.id }.toSet()) }
                )
            }
            items(folder.items, key = { it.id }) { download ->
                DownloadItemCard(
                    item = download,
                    isSelected = download.id in uiState.selectedOfflineDownloadIds,
                    onToggleSelected = { viewModel.toggleOfflineDownloadSelection(download.id) },
                    onPlay = { onPlayDownload(download) },
                    onPause = { viewModel.pauseOfflineDownload(download) },
                    onResume = { viewModel.resumeOfflineDownload(download) },
                    onDelete = { viewModel.deleteOfflineDownload(download.id) }
                )
            }
        }
    }
}

@Composable
private fun DownloadsSummaryCard(
    activeCount: Int,
    completedCount: Int,
    totalCount: Int,
    onRefresh: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_downloads_title),
                    subtitle = stringResource(R.string.settings_downloads_subtitle)
                )
                TvButton(onClick = onRefresh) {
                    Text(stringResource(R.string.settings_downloads_refresh))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsOverviewStat(
                    label = stringResource(R.string.settings_downloads_active),
                    value = activeCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                SettingsOverviewStat(
                    label = stringResource(R.string.settings_downloads_completed),
                    value = completedCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                SettingsOverviewStat(
                    label = stringResource(R.string.settings_downloads_total),
                    value = totalCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun EmptyDownloadsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_downloads_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDim,
            modifier = Modifier.padding(20.dp)
        )
    }
}

@Composable
private fun DownloadSelectionCard(
    selectedCount: Int,
    onDeleteSelected: () -> Unit,
    onClearSelection: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_downloads_selected_count, selectedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = if (selectedCount > 0) OnBackground else OnSurfaceDim
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(onClick = onClearSelection, enabled = selectedCount > 0) {
                    Text(stringResource(R.string.settings_downloads_clear_selection))
                }
                TvButton(onClick = onDeleteSelected, enabled = selectedCount > 0) {
                    Text(stringResource(R.string.settings_downloads_delete_selected))
                }
            }
        }
    }
}

@Composable
private fun DownloadFolderHeader(
    folder: DownloadFolder,
    selectedCount: Int,
    onSelectAll: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.06f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.settings_downloads_folder_count, folder.items.size, selectedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
            TvButton(onClick = onSelectAll) {
                Text(stringResource(R.string.settings_downloads_select_all))
            }
        }
    }
}

@Composable
private fun DownloadItemCard(
    item: OfflineDownloadItem,
    isSelected: Boolean,
    onToggleSelected: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(16.dp),
        border = Border(
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            shape = RoundedCornerShape(16.dp)
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = OnBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.localUri.ifBlank { item.sourceUri },
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusTonePill(
                    label = stringResource(item.status.labelRes),
                    accent = item.status.accentColor
                )
            }

            item.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Primary,
                    trackColor = Color.White.copy(alpha = 0.12f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = downloadSizeLabel(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton(onClick = onToggleSelected) {
                        Text(
                            stringResource(
                                if (isSelected) {
                                    R.string.settings_downloads_selected
                                } else {
                                    R.string.settings_downloads_select
                                }
                            )
                        )
                    }
                    if (item.status == OfflineDownloadStatus.SUCCESSFUL && item.localUri.isNotBlank()) {
                        TvButton(onClick = onPlay) {
                            Text(stringResource(R.string.settings_downloads_play))
                        }
                    }
                    if (item.status == OfflineDownloadStatus.RUNNING || item.status == OfflineDownloadStatus.PENDING) {
                        TvButton(onClick = onPause) {
                            Text(stringResource(R.string.vod_download_pause))
                        }
                    }
                    if (item.status == OfflineDownloadStatus.PAUSED) {
                        TvButton(onClick = onResume) {
                            Text(stringResource(R.string.vod_download_resume))
                        }
                    }
                    TvButton(onClick = onDelete) {
                        Text(stringResource(R.string.settings_downloads_delete))
                    }
                }
            }
        }
    }
}

private fun downloadSizeLabel(item: OfflineDownloadItem): String {
    val downloaded = formatBytes(item.bytesDownloaded)
    val total = item.totalBytes?.let(::formatBytes)
    val sizeLabel = if (total == null) downloaded else "$downloaded / $total"
    return item.progressPercent?.let { "$sizeLabel ($it%)" } ?: sizeLabel
}

private data class DownloadFolder(
    val name: String,
    val items: List<OfflineDownloadItem>
)

private fun groupedDownloadFolders(items: List<OfflineDownloadItem>): List<DownloadFolder> =
    items.groupBy { it.folderName }
        .toSortedMap(compareBy<String> { if (it == MOVIES_FOLDER) "zzzz-$it" else it.lowercase() })
        .map { (name, downloads) ->
            DownloadFolder(
                name = name,
                items = downloads.sortedWith(
                    compareBy<OfflineDownloadItem> { it.status.sortOrder }
                        .thenBy { it.title.lowercase() }
                )
            )
        }

private val OfflineDownloadItem.folderName: String
    get() {
        val match = SERIES_TITLE_PATTERN.find(title)
        return match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: MOVIES_FOLDER
    }

private const val MOVIES_FOLDER = "Movies"
private val SERIES_TITLE_PATTERN = Regex("^(.+?)\\s+-\\s+S\\d{1,2}E\\d{1,2}\\s+-\\s+.+$")

private val OfflineDownloadStatus.isActive: Boolean
    get() = this == OfflineDownloadStatus.RUNNING ||
        this == OfflineDownloadStatus.PENDING ||
        this == OfflineDownloadStatus.PAUSED

private val OfflineDownloadStatus.labelRes: Int
    get() = when (this) {
        OfflineDownloadStatus.RUNNING -> R.string.settings_downloads_status_running
        OfflineDownloadStatus.PENDING -> R.string.settings_downloads_status_pending
        OfflineDownloadStatus.PAUSED -> R.string.settings_downloads_status_paused
        OfflineDownloadStatus.SUCCESSFUL -> R.string.settings_downloads_status_complete
        OfflineDownloadStatus.FAILED -> R.string.settings_downloads_status_failed
        OfflineDownloadStatus.UNKNOWN -> R.string.settings_downloads_status_unknown
    }

private val OfflineDownloadStatus.accentColor: Color
    get() = when (this) {
        OfflineDownloadStatus.RUNNING,
        OfflineDownloadStatus.PENDING -> Primary
        OfflineDownloadStatus.PAUSED -> OnSurface
        OfflineDownloadStatus.SUCCESSFUL -> Color(0xFF66BB6A)
        OfflineDownloadStatus.FAILED -> ErrorColor
        OfflineDownloadStatus.UNKNOWN -> OnSurfaceDim
    }
