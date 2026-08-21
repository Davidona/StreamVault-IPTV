package com.streamvault.app.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.BuildConfig
import com.streamvault.app.R
import com.streamvault.app.update.AppUpdateActionState
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.Secondary
import com.streamvault.domain.manager.DriveAuthState

internal fun LazyListScope.settingsBackupSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onCreateBackup: () -> Unit,
    onManageLocalBackups: () -> Unit,
    onShareBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onCreateBackupUsb: (() -> Unit)? = null,
    onRestoreBackupUsb: (() -> Unit)? = null
) {
    item {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsSectionHeader(
                title = stringResource(R.string.settings_backup_restore),
                subtitle = stringResource(R.string.settings_backup_subtitle)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                BackupActionCard(
                    icon = "\u2191",
                    title = stringResource(R.string.settings_backup_data),
                    subtitle = stringResource(R.string.settings_backup_subtitle),
                    accent = Primary,
                    onClick = onCreateBackup,
                    modifier = Modifier.weight(1f)
                )
                BackupActionCard(
                    icon = "\u21aa",
                    title = stringResource(R.string.settings_backup_share_data),
                    subtitle = stringResource(R.string.settings_backup_share_subtitle),
                    accent = Primary,
                    onClick = onShareBackup,
                    modifier = Modifier.weight(1f)
                )
            }
            BackupActionCard(
                icon = "\u2193",
                title = stringResource(R.string.settings_restore_data),
                subtitle = stringResource(R.string.settings_restore_subtitle),
                accent = Secondary,
                onClick = onRestoreBackup,
                modifier = Modifier.fillMaxWidth()
            )
            uiState.backupRestoreJobs
                .filter { job -> job.status != "COMPLETE" || job.providers.any { provider -> provider.items.any { it.status != "APPLIED" && it.status != "DISMISSED" } } }
                .forEach { job ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Restore ${job.jobId.take(8)} · ${job.status}",
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurface
                        )
                        job.providers.forEach { provider ->
                            val waiting = provider.pendingCount + provider.unresolvedCount + provider.failedCount
                            Text(
                                text = "${provider.providerIdentityKey.substringBefore('|')} — " +
                                    "${provider.appliedCount} applied, $waiting waiting (${provider.failedCount} failed)",
                                color = OnSurfaceDim
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                provider.localProviderId?.let { providerId ->
                                    BackupActionCard(
                                        icon = "↻",
                                        title = "Retry",
                                        subtitle = "Try available catalog data",
                                        accent = Primary,
                                        onClick = { viewModel.retryRestoreProvider(providerId) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                BackupActionCard(
                                    icon = "×",
                                    title = "Dismiss provider",
                                    subtitle = "Discard unresolved instructions",
                                    accent = Secondary,
                                    onClick = { viewModel.dismissRestoreProvider(job.jobId, provider.providerIdentityKey) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            provider.items
                                .filter { it.status == "UNRESOLVED" || it.status == "FAILED_RETRYABLE" }
                                .forEach { item ->
                                    BackupActionCard(
                                        icon = "!",
                                        title = "${item.section}${item.contentType?.let { " · $it" }.orEmpty()}",
                                        subtitle = item.lastError ?: item.status,
                                        accent = Secondary,
                                        onClick = { viewModel.dismissRestoreItem(item.id) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                        }
                        BackupActionCard(
                            icon = "×",
                            title = "Dismiss entire restore",
                            subtitle = "Discard all remaining instructions",
                            accent = Secondary,
                            onClick = { viewModel.dismissRestoreJob(job.jobId) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            BackupActionCard(
                icon = "≡",
                title = stringResource(R.string.settings_manage_local_backups),
                subtitle = stringResource(R.string.settings_manage_local_backups_subtitle),
                accent = OnSurface,
                onClick = onManageLocalBackups,
                modifier = Modifier.fillMaxWidth()
            )
            if (onCreateBackupUsb != null && onRestoreBackupUsb != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BackupActionCard(
                        icon = "\u2191",
                        title = stringResource(R.string.settings_backup_usb_data),
                        subtitle = stringResource(R.string.settings_backup_usb_subtitle),
                        accent = Primary,
                        onClick = onCreateBackupUsb,
                        modifier = Modifier.weight(1f)
                    )
                    BackupActionCard(
                        icon = "\u2193",
                        title = stringResource(R.string.settings_restore_usb_data),
                        subtitle = stringResource(R.string.settings_restore_usb_subtitle),
                        accent = Secondary,
                        onClick = onRestoreBackupUsb,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

internal fun LazyListScope.settingsDriveBackupSection(
    uiState: SettingsUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
    onManageBackups: () -> Unit,
) {
    item(key = "settings_drive_section") {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsSectionHeader(
                title = stringResource(R.string.settings_drive_section_title),
                subtitle = stringResource(R.string.settings_drive_section_subtitle)
            )
            when (val auth = uiState.driveAuthState) {
                is DriveAuthState.SignedOut, is DriveAuthState.Pending -> {
                    BackupActionCard(
                        icon = "☁",
                        title = stringResource(R.string.settings_drive_signin),
                        subtitle = stringResource(R.string.settings_drive_signin_description),
                        accent = Primary,
                        onClick = onSignIn,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is DriveAuthState.SignedIn -> {
                    val accountLabel = auth.account.email
                        ?: auth.account.displayName
                        ?: stringResource(R.string.settings_drive_signin)
                    DriveAccountRow(
                        accountLabel = accountLabel,
                        lastPushAtMs = uiState.driveSyncStatus.lastPushAtMs,
                        lastPullAtMs = uiState.driveSyncStatus.lastPullAtMs,
                        onSignOut = onSignOut
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        BackupActionCard(
                            icon = "↑",
                            title = stringResource(R.string.settings_drive_push),
                            subtitle = stringResource(R.string.settings_drive_push_subtitle),
                            accent = Primary,
                            onClick = onPush,
                            modifier = Modifier.weight(1f)
                        )
                        BackupActionCard(
                            icon = "↓",
                            title = stringResource(R.string.settings_drive_pull),
                            subtitle = stringResource(R.string.settings_drive_pull_subtitle),
                            accent = Secondary,
                            onClick = onPull,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    BackupActionCard(
                        icon = "≡",
                        title = stringResource(R.string.settings_manage_drive_backups),
                        subtitle = stringResource(R.string.settings_manage_drive_backups_subtitle),
                        accent = OnSurface,
                        onClick = onManageBackups,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
internal fun formatSnapshotDetails(snapshot: com.streamvault.domain.manager.DriveBackupSnapshot): String {
    val date = snapshot.modifiedAtMs?.let {
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT,
        ).format(java.util.Date(it))
    } ?: stringResource(R.string.settings_drive_unknown_backup_date)
    val size = if (snapshot.sizeBytes > 0L) {
        "${snapshot.sizeBytes / 1024L} KB"
    } else {
        stringResource(R.string.settings_drive_backup_size_unknown)
    }
    return "$date · $size"
}

@androidx.compose.runtime.Composable
private fun DriveAccountRow(
    accountLabel: String,
    lastPushAtMs: Long?,
    lastPullAtMs: Long?,
    onSignOut: () -> Unit
) {
    val syncSummary = formatLastSync(lastPushAtMs, lastPullAtMs)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = accountLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurface,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = syncSummary,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        }
        TvClickableSurface(
            onClick = onSignOut,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.06f),
                focusedContainerColor = Color.White.copy(alpha = 0.18f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Text(
                text = stringResource(R.string.settings_drive_signout),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = OnSurface
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun formatLastSync(pushMs: Long?, pullMs: Long?): String {
    if (pushMs == null && pullMs == null) {
        return stringResource(R.string.settings_drive_never_synced)
    }
    val df = java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT,
        java.text.DateFormat.SHORT
    )
    val parts = mutableListOf<String>()
    pushMs?.let { parts += stringResource(R.string.settings_drive_last_push, df.format(java.util.Date(it))) }
    pullMs?.let { parts += stringResource(R.string.settings_drive_last_pull, df.format(java.util.Date(it))) }
    return parts.joinToString("  ·  ")
}

@androidx.compose.runtime.Composable
private fun BackupActionCard(
    icon: String,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = accent.copy(alpha = 0.12f),
            focusedContainerColor = accent.copy(alpha = 0.28f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = icon,
                modifier = Modifier.width(34.dp),
                style = MaterialTheme.typography.headlineSmall,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                )
            }
        }
    }
}

internal fun LazyListScope.settingsAboutSection(
    uiState: SettingsUiState,
    context: Context,
    buildVerificationLabel: String,
    onOpenUri: (String) -> Unit,
    onCheckForUpdates: () -> Unit,
    onInstallDownloadedUpdate: () -> Unit,
    onDownloadLatestUpdate: () -> Unit,
    onSetAutoCheckAppUpdates: (Boolean) -> Unit,
    onSetAutoDownloadAppUpdates: (Boolean) -> Unit,
    onRefreshDownloadState: () -> Unit,
    onViewCrashReport: () -> Unit,
    onShareCrashReport: () -> Unit,
    onDeleteCrashReport: () -> Unit
) {
    item {
        val downloadStatus = uiState.appUpdate.downloadStatus
        LaunchedEffect(downloadStatus) {
            if (downloadStatus == com.streamvault.app.update.AppUpdateDownloadStatus.Downloading) {
                while (true) {
                    kotlinx.coroutines.delay(2000L)
                    onRefreshDownloadState()
                }
            }
        }
        SettingsSectionHeader(
            title = stringResource(R.string.settings_updates_title),
            subtitle = stringResource(R.string.settings_updates_subtitle)
        )
        SettingsRow(label = stringResource(R.string.settings_app_version), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        SwitchSettingsRow(
            label = stringResource(R.string.settings_update_auto_check),
            value = stringResource(
                if (uiState.autoCheckAppUpdates) R.string.settings_enabled else R.string.settings_disabled
            ),
            checked = uiState.autoCheckAppUpdates,
            onCheckedChange = onSetAutoCheckAppUpdates
        )
        if (uiState.autoCheckAppUpdates) {
            SwitchSettingsRow(
                label = stringResource(R.string.settings_update_auto_download),
                value = stringResource(
                    if (uiState.autoDownloadAppUpdates) R.string.settings_enabled else R.string.settings_disabled
                ),
                checked = uiState.autoDownloadAppUpdates,
                onCheckedChange = onSetAutoDownloadAppUpdates
            )
        }
        SettingsRow(
            label = stringResource(R.string.settings_update_latest_release),
            value = formatLatestReleaseLabel(uiState.appUpdate, context)
        )
        SettingsRow(
            label = stringResource(R.string.settings_update_status),
            value = formatUpdateStatusLabel(uiState.appUpdate, context)
        )
        SettingsRow(
            label = stringResource(R.string.settings_update_last_checked),
            value = formatUpdateCheckTimeLabel(uiState.appUpdate.lastCheckedAt, context)
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_update_check_now),
            value = stringResource(
                if (uiState.isCheckingForUpdates) R.string.settings_update_checking else R.string.settings_update_check_action
            ),
            onClick = {
                if (!uiState.isCheckingForUpdates) {
                    onCheckForUpdates()
                }
            }
        )
        if (shouldShowUpdateDownloadAction(uiState.appUpdate)) {
            ClickableSettingsRow(
                label = stringResource(R.string.settings_update_download),
                value = formatUpdateDownloadLabel(uiState.appUpdate, context),
                onClick = {
                    when (uiState.appUpdate.latestActionState()) {
                        AppUpdateActionState.InstallLatest,
                        AppUpdateActionState.InstallPermissionRequired -> onInstallDownloadedUpdate()
                        AppUpdateActionState.DownloadLatest -> onDownloadLatestUpdate()
                        AppUpdateActionState.Downloading,
                        AppUpdateActionState.None -> Unit
                    }
                }
            )
        }
        if (!uiState.appUpdate.releaseUrl.isNullOrBlank()) {
            ClickableSettingsRow(
                label = stringResource(R.string.settings_update_view_release),
                value = uiState.appUpdate.latestVersionName ?: stringResource(R.string.settings_update_release_notes),
                onClick = { onOpenUri(uiState.appUpdate.releaseUrl.orEmpty()) }
            )
        }
        if (!uiState.appUpdate.errorMessage.isNullOrBlank()) {
            SettingsRow(
                label = stringResource(R.string.settings_update_error),
                value = uiState.appUpdate.errorMessage.orEmpty()
            )
        }
    }

    item {
        SettingsSectionHeader(
            title = stringResource(R.string.settings_crash_reports_title),
            subtitle = stringResource(R.string.settings_crash_reports_subtitle)
        )
        if (uiState.crashReport.hasReport) {
            SettingsRow(
                label = stringResource(R.string.settings_crash_report_latest),
                value = uiState.crashReport.timestamp
            )
            SettingsRow(
                label = stringResource(R.string.settings_crash_report_exception),
                value = uiState.crashReport.exception.substringAfterLast('.')
            )
            ClickableSettingsRow(
                label = stringResource(R.string.settings_crash_report_view),
                value = stringResource(R.string.settings_crash_report_available),
                onClick = onViewCrashReport
            )
            ClickableSettingsRow(
                label = stringResource(R.string.settings_crash_report_share),
                value = uiState.crashReport.fileName,
                onClick = onShareCrashReport
            )
            ClickableSettingsRow(
                label = stringResource(R.string.settings_crash_report_delete),
                value = stringResource(R.string.settings_crash_report_delete_value),
                onClick = onDeleteCrashReport
            )
        } else {
            SettingsRow(
                label = stringResource(R.string.settings_crash_report_latest),
                value = stringResource(R.string.settings_crash_report_none)
            )
        }
    }

    item {
        SettingsRow(label = stringResource(R.string.settings_build), value = stringResource(R.string.settings_build_desc))
        SettingsRow(label = stringResource(R.string.settings_build_verification), value = buildVerificationLabel)
        SettingsRow(label = stringResource(R.string.settings_developed_by), value = stringResource(R.string.settings_developer_name))
        ClickableSettingsRow(
            label = stringResource(R.string.settings_github),
            value = stringResource(R.string.settings_github_url),
            onClick = { onOpenUri(context.getString(R.string.settings_github_url)) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_donate),
            value = stringResource(R.string.settings_donate_url),
            onClick = { onOpenUri(context.getString(R.string.settings_donate_url)) }
        )
    }
}
