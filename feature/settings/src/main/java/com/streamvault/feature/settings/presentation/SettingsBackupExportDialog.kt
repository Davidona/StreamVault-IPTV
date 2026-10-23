package com.streamvault.feature.settings.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.device.rememberIsTelevisionDevice
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.domain.manager.BackupExportPlan
import com.streamvault.feature.settings.R

internal val BackupExportPlanSaver = listSaver<BackupExportPlan, Boolean>(
    save = { listOf(it.exportPreferences, it.exportProviders, it.exportSavedLibrary, it.exportPlaybackHistory,
        it.exportMultiViewPresets, it.exportRecordingSchedules, it.exportStremioAddons) },
    restore = { BackupExportPlan(it[0], it[1], it[2], it[3], it[4], it[5], it[6]) }
)

@Composable
internal fun BackupExportDialog(
    initialPlan: BackupExportPlan,
    onDismiss: () -> Unit,
    onConfirm: (BackupExportPlan) -> Unit
) {
    var plan by rememberSaveable(stateSaver = BackupExportPlanSaver) { mutableStateOf(initialPlan) }
    val firstFocus = remember { FocusRequester() }
    val isTv = rememberIsTelevisionDevice()
    PremiumDialog(
        title = stringResource(R.string.settings_backup_data),
        subtitle = stringResource(R.string.settings_backup_export_sections),
        onDismissRequest = onDismiss,
        widthFraction = 0.58f,
        heightFraction = 0.78f,
        bodyHeightFraction = 0.58f,
        bodyScrollHint = stringResource(R.string.settings_backup_preview_scroll_hint),
        initialBodyFocusRequester = if (isTv) firstFocus else null,
        content = {
            BackupToggleRow(stringResource(R.string.settings_backup_section_preferences), plan.exportPreferences,
                { plan = plan.copy(exportPreferences = it) }, modifier = Modifier.focusRequester(firstFocus))
            BackupToggleRow(stringResource(R.string.settings_backup_section_providers), plan.exportProviders,
                { plan = plan.copy(exportProviders = it) })
            BackupToggleRow(stringResource(R.string.settings_backup_section_saved), plan.exportSavedLibrary,
                { plan = plan.copy(exportSavedLibrary = it) })
            BackupToggleRow(stringResource(R.string.settings_backup_section_history), plan.exportPlaybackHistory,
                { plan = plan.copy(exportPlaybackHistory = it) })
            BackupToggleRow(stringResource(R.string.settings_backup_section_multiview), plan.exportMultiViewPresets,
                { plan = plan.copy(exportMultiViewPresets = it) })
            BackupToggleRow(stringResource(R.string.settings_backup_section_recordings), plan.exportRecordingSchedules,
                { plan = plan.copy(exportRecordingSchedules = it) })
            BackupToggleRow(stringResource(R.string.settings_backup_section_stremio_addons), plan.exportStremioAddons,
                { plan = plan.copy(exportStremioAddons = it) })
            if (plan.exportProviders || plan.exportStremioAddons || plan.exportSavedLibrary ||
                plan.exportPlaybackHistory || plan.exportMultiViewPresets || plan.exportRecordingSchedules) {
                Text(stringResource(R.string.settings_backup_credentials_notice),
                    style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            }
        },
        footer = {
            PremiumDialogFooterButton(label = stringResource(R.string.settings_cancel), onClick = onDismiss,
                modifier = Modifier.focusProperties { up = firstFocus })
            PremiumDialogFooterButton(label = stringResource(R.string.settings_backup_data),
                onClick = { if (plan.hasSections) onConfirm(plan) }, enabled = plan.hasSections, emphasized = true,
                modifier = Modifier.focusProperties { up = firstFocus })
        }
    )
}
