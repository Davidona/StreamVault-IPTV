package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.Secondary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview

@Composable
internal fun BackupImportPreviewDialog(
    preview: BackupPreview,
    plan: BackupImportPlan,
    onDismiss: () -> Unit,
    onStrategySelected: (BackupConflictStrategy) -> Unit,
    onImportPreferencesChanged: (Boolean) -> Unit,
    onImportProvidersChanged: (Boolean) -> Unit,
    onImportSavedLibraryChanged: (Boolean) -> Unit,
    onImportPlaybackHistoryChanged: (Boolean) -> Unit,
    onImportMultiViewChanged: (Boolean) -> Unit,
    onImportRecordingSchedulesChanged: (Boolean) -> Unit,
    isImporting: Boolean = false,
    onConfirm: () -> Unit
) {
    val anyEnabled = plan.importPreferences || plan.importProviders || plan.importSavedLibrary ||
        plan.importPlaybackHistory || plan.importMultiViewPresets || plan.importRecordingSchedules
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val firstBodyFocusRequester = remember { FocusRequester() }

    PremiumDialog(
        title = stringResource(R.string.settings_backup_preview_title),
        subtitle = stringResource(R.string.settings_backup_preview_subtitle, preview.version),
        onDismissRequest = if (isImporting) ({}) else onDismiss,
        widthFraction = 0.58f,
        heightFraction = 0.78f,
        bodyHeightFraction = 0.58f,
        bodyScrollHint = stringResource(R.string.settings_backup_preview_scroll_hint),
        initialBodyFocusRequester = if (isTelevisionDevice) firstBodyFocusRequester else null,
        content = {
            BackupPreviewRow(stringResource(R.string.settings_backup_section_preferences), preview.preferenceCount, 0)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_providers), preview.providerCount, preview.providerConflicts)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_saved), preview.favoriteCount + preview.groupCount + preview.protectedCategoryCount, preview.favoriteConflicts + preview.groupConflicts + preview.protectedCategoryConflicts)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_history), preview.playbackHistoryCount, preview.historyConflicts)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_multiview), preview.multiViewPresetCount, 0)
            BackupPreviewRow(stringResource(R.string.settings_backup_section_recordings), preview.scheduledRecordingCount, preview.recordingConflicts)
            Text(
                text = stringResource(R.string.settings_backup_conflict_strategy),
                style = MaterialTheme.typography.titleSmall,
                color = Primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BackupStrategyChip(
                    title = stringResource(R.string.settings_backup_keep_existing),
                    selected = plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING,
                    modifier = Modifier.focusRequester(firstBodyFocusRequester),
                    onClick = { onStrategySelected(BackupConflictStrategy.KEEP_EXISTING) }
                )
                BackupStrategyChip(
                    title = stringResource(R.string.settings_backup_replace_existing),
                    selected = plan.conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING,
                    onClick = { onStrategySelected(BackupConflictStrategy.REPLACE_EXISTING) }
                )
            }
            Text(
                text = stringResource(R.string.settings_backup_import_sections),
                style = MaterialTheme.typography.titleSmall,
                color = Primary
            )
            BackupToggleRow(stringResource(R.string.settings_backup_section_preferences), plan.importPreferences, onImportPreferencesChanged)
            BackupToggleRow(stringResource(R.string.settings_backup_section_providers), plan.importProviders, onImportProvidersChanged)
            BackupToggleRow(stringResource(R.string.settings_backup_section_saved), plan.importSavedLibrary, onImportSavedLibraryChanged)
            BackupToggleRow(stringResource(R.string.settings_backup_section_history), plan.importPlaybackHistory, onImportPlaybackHistoryChanged)
            BackupToggleRow(stringResource(R.string.settings_backup_section_multiview), plan.importMultiViewPresets, onImportMultiViewChanged)
            BackupToggleRow(stringResource(R.string.settings_backup_section_recordings), plan.importRecordingSchedules, onImportRecordingSchedulesChanged)
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss,
                modifier = Modifier.focusProperties { up = firstBodyFocusRequester },
                enabled = !isImporting
            )
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_backup_import_confirm),
                onClick = onConfirm,
                modifier = Modifier.focusProperties { up = firstBodyFocusRequester },
                emphasized = true,
                enabled = anyEnabled && !isImporting
            )
        }
    )
}

@Composable
private fun BackupPreviewRow(
    title: String,
    itemCount: Int,
    conflictCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = OnBackground)
            Text(
                text = if (conflictCount > 0) {
                    stringResource(R.string.settings_backup_conflict_count, conflictCount)
                } else {
                    stringResource(R.string.settings_backup_no_conflicts)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (conflictCount > 0) Secondary else OnSurfaceDim
            )
        }
        Text(
            text = stringResource(R.string.settings_backup_item_count, itemCount),
            style = MaterialTheme.typography.labelLarge,
            color = OnBackground
        )
    }
}

@Composable
private fun BackupStrategyChip(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.2f) else SurfaceElevated,
            focusedContainerColor = Primary.copy(alpha = 0.35f)
        )
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Primary else OnBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun BackupToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = OnBackground)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .onFocusChanged { isFocused = it.isFocused }
                .then(
                    if (isFocused) {
                        Modifier.border(BorderStroke(2.dp, Color.White), CircleShape)
                    } else {
                        Modifier
                    }
                ),
        )
    }
}
