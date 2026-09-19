package com.streamvault.feature.provider.api

import androidx.compose.runtime.Composable
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview

typealias ProviderBackupPreviewContent =
    @Composable (ProviderBackupPreviewRequest) -> Unit

data class ProviderBackupPreviewRequest(
    val preview: BackupPreview,
    val plan: BackupImportPlan,
    val onDismiss: () -> Unit,
    val onStrategySelected: (BackupConflictStrategy) -> Unit,
    val onImportPreferencesChanged: (Boolean) -> Unit,
    val onImportProvidersChanged: (Boolean) -> Unit,
    val onImportSavedLibraryChanged: (Boolean) -> Unit,
    val onImportPlaybackHistoryChanged: (Boolean) -> Unit,
    val onImportMultiViewChanged: (Boolean) -> Unit,
    val onImportRecordingSchedulesChanged: (Boolean) -> Unit,
    val isImporting: Boolean,
    val onConfirm: () -> Unit,
)

