package com.streamvault.feature.settings.presentation

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.Secondary
import com.streamvault.domain.manager.DriveAuthState

public fun LazyListScope.settingsBackupSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onCreateBackup: () -> Unit,
    onManageLocalBackups: () -> Unit,
    onShareBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onCreateBackupUsb: (() -> Unit)? = null,
    onRestoreBackupUsb: (() -> Unit)? = null,
    targetItemId: String? = null,
    targetFocusModifier: Modifier = Modifier,
) {
    item {
        Column(
            verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8),
                modifier = Modifier.fillMaxWidth()
            ) {
                BackupActionCard(
                    icon = "\u2191",
                    title = stringResource(R.string.settings_backup_data),
                    subtitle = stringResource(R.string.settings_backup_subtitle),
                    accent = Primary,
                    onClick = onCreateBackup,
                    modifier = Modifier.fillMaxWidth().then(
                        if (targetItemId == "backup.create") targetFocusModifier else Modifier
                    )
                )
                BackupActionCard(
                    icon = "\u21aa",
                    title = stringResource(R.string.settings_backup_share_data),
                    subtitle = stringResource(R.string.settings_backup_share_subtitle),
                    accent = Primary,
                    onClick = onShareBackup,
                    modifier = Modifier.fillMaxWidth().then(
                        if (targetItemId == "backup.share") targetFocusModifier else Modifier
                    )
                )
            }
            BackupActionCard(
                icon = "\u2193",
                title = stringResource(R.string.settings_restore_data),
                subtitle = stringResource(R.string.settings_restore_subtitle),
                accent = Secondary,
                onClick = onRestoreBackup,
                modifier = Modifier.fillMaxWidth().then(
                    if (targetItemId == "backup.restore") targetFocusModifier else Modifier
                )
            )
            uiState.backupRestoreJobs
                .filter { job -> job.status != "COMPLETE" || job.providers.any { provider -> provider.items.any { it.status != "APPLIED" && it.status != "DISMISSED" } } }
                .forEach { job ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.settings_restore_job_status, job.jobId.take(8), job.status),
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurface
                        )
                        job.providers.forEach { provider ->
                            val waiting = provider.pendingCount + provider.unresolvedCount + provider.failedCount
                            Text(
                                text = stringResource(
                                    R.string.settings_restore_provider_status,
                                    provider.providerIdentityKey.substringBefore('|'),
                                    provider.appliedCount,
                                    waiting,
                                    provider.failedCount,
                                ),
                                color = OnSurfaceDim
                            )
                            Column(
                                verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                provider.localProviderId?.let { providerId ->
                                    BackupActionCard(
                                        icon = "\u21aa",
                                        title = stringResource(R.string.settings_restore_retry),
                                        subtitle = stringResource(R.string.settings_restore_retry_description),
                                        accent = Primary,
                                        onClick = { viewModel.retryRestoreProvider(providerId) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                    BackupActionCard(
                                        icon = "×",
                                    title = stringResource(R.string.settings_restore_dismiss_provider),
                                    subtitle = stringResource(R.string.settings_restore_dismiss_provider_description),
                                    accent = Secondary,
                                    onClick = { viewModel.dismissRestoreProvider(job.jobId, provider.providerIdentityKey) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            provider.items
                                .filter { it.status == "UNRESOLVED" || it.status == "FAILED_RETRYABLE" }
                                .forEach { item ->
                                    BackupActionCard(
                                        icon = "!",
                                        title = item.contentType?.let {
                                            stringResource(R.string.settings_restore_item_title, item.section, it)
                                        } ?: item.section,
                                        subtitle = item.lastError ?: item.status,
                                        accent = Secondary,
                                        onClick = { viewModel.dismissRestoreItem(item.id) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                        }
                        BackupActionCard(
                            icon = "×",
                            title = stringResource(R.string.settings_restore_dismiss_job),
                            subtitle = stringResource(R.string.settings_restore_dismiss_job_description),
                            accent = Secondary,
                            onClick = { viewModel.dismissRestoreJob(job.jobId) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            BackupActionCard(
                icon = "☁",
                title = stringResource(R.string.settings_manage_local_backups),
                subtitle = stringResource(R.string.settings_manage_local_backups_subtitle),
                accent = OnSurface,
                onClick = onManageLocalBackups,
                modifier = Modifier.fillMaxWidth().then(
                    if (targetItemId == "backup.manage_local") targetFocusModifier else Modifier
                )
            )
            if (onCreateBackupUsb != null && onRestoreBackupUsb != null) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BackupActionCard(
                        icon = "\u2191",
                        title = stringResource(R.string.settings_backup_usb_data),
                        subtitle = stringResource(R.string.settings_backup_usb_subtitle),
                        accent = Primary,
                        onClick = onCreateBackupUsb,
                        modifier = Modifier.fillMaxWidth().then(
                            if (targetItemId == "backup.usb_create") targetFocusModifier else Modifier
                        )
                    )
                    BackupActionCard(
                        icon = "\u2193",
                        title = stringResource(R.string.settings_restore_usb_data),
                        subtitle = stringResource(R.string.settings_restore_usb_subtitle),
                        accent = Secondary,
                        onClick = onRestoreBackupUsb,
                        modifier = Modifier.fillMaxWidth().then(
                            if (targetItemId == "backup.usb_restore") targetFocusModifier else Modifier
                        )
                    )
                }
            }
        }
    }
}

public fun LazyListScope.settingsDriveBackupSection(
    uiState: SettingsUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
    onManageBackups: () -> Unit,
    targetItemId: String? = null,
    targetFocusModifier: Modifier = Modifier,
) {
    item(key = "settings_drive_section") {
        Column(
            verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8),
            modifier = Modifier.fillMaxWidth()
        ) {
            when (val auth = uiState.driveAuthState) {
                is DriveAuthState.SignedOut, is DriveAuthState.Pending -> {
                    BackupActionCard(
                        icon = "↻",
                        title = stringResource(R.string.settings_drive_signin),
                        subtitle = stringResource(R.string.settings_drive_signin_description),
                        accent = Primary,
                        onClick = onSignIn,
                        modifier = Modifier.fillMaxWidth().then(
                            if (targetItemId == "backup.drive_auth") targetFocusModifier else Modifier
                        )
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
                        onSignOut = onSignOut,
                        actionModifier = if (targetItemId == "backup.drive_auth") targetFocusModifier else Modifier,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        BackupActionCard(
                            icon = "\u2191",
                            title = stringResource(R.string.settings_drive_push),
                            subtitle = stringResource(R.string.settings_drive_push_subtitle),
                            accent = Primary,
                            onClick = onPush,
                            modifier = Modifier.weight(1f).then(
                                if (targetItemId == "backup.drive_push") targetFocusModifier else Modifier
                            )
                        )
                        BackupActionCard(
                            icon = "\u2193",
                            title = stringResource(R.string.settings_drive_pull),
                            subtitle = stringResource(R.string.settings_drive_pull_subtitle),
                            accent = Secondary,
                            onClick = onPull,
                            modifier = Modifier.weight(1f).then(
                                if (targetItemId == "backup.drive_pull") targetFocusModifier else Modifier
                            )
                        )
                    }
                    BackupActionCard(
                        icon = "☁",
                        title = stringResource(R.string.settings_manage_drive_backups),
                        subtitle = stringResource(R.string.settings_manage_drive_backups_subtitle),
                        accent = OnSurface,
                        onClick = onManageBackups,
                        modifier = Modifier.fillMaxWidth().then(
                            if (targetItemId == "backup.drive_manage") targetFocusModifier else Modifier
                        )
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
public fun formatSnapshotDetails(snapshot: com.streamvault.domain.manager.DriveBackupSnapshot): String {
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
    onSignOut: () -> Unit,
    actionModifier: Modifier = Modifier,
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
            modifier = actionModifier,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = com.streamvault.core.ui.theme.SurfaceElevated,
                focusedContainerColor = com.streamvault.core.ui.theme.SurfaceHighlight
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
    SettingsActionSurface(onClick = onClick, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsDesignTokens.explanatoryRowMinHeight)
                .padding(horizontal = SettingsDesignTokens.space16, vertical = SettingsDesignTokens.space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space12)
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

public fun LazyListScope.settingsAboutSection(
    uiState: SettingsUiState,
    page: SettingsPage? = null,
    context: Context,
    appVersionLabel: String,
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
    onDeleteCrashReport: () -> Unit,
    onCloseApp: () -> Unit = {},
    targetItemId: String? = null,
    targetFocusModifier: Modifier = Modifier,
) {
    if (page == null || page == SettingsPage.UPDATES) item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val downloadStatus = uiState.appUpdate.downloadStatus
            LaunchedEffect(downloadStatus) {
                if (downloadStatus == com.streamvault.feature.settings.api.SettingsUpdateDownloadStatus.DOWNLOADING) {
                    while (true) {
                        kotlinx.coroutines.delay(2000L)
                        onRefreshDownloadState()
                    }
                }
            }
            SettingsRow(label = stringResource(R.string.settings_app_version), value = appVersionLabel)
            SwitchSettingsRow(
                label = stringResource(R.string.settings_update_auto_check),
                value = stringResource(
                    if (uiState.autoCheckAppUpdates) R.string.settings_enabled else R.string.settings_disabled
                ),
                checked = uiState.autoCheckAppUpdates,
                onCheckedChange = onSetAutoCheckAppUpdates
            ,
                modifier = if (targetItemId == "about.auto_update_check") targetFocusModifier else Modifier,)
            if (uiState.autoCheckAppUpdates) {
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_update_auto_download),
                    value = stringResource(
                        if (uiState.autoDownloadAppUpdates) R.string.settings_enabled else R.string.settings_disabled
                    ),
                    checked = uiState.autoDownloadAppUpdates,
                    onCheckedChange = onSetAutoDownloadAppUpdates
                ,
                    modifier = if (targetItemId == "about.auto_update_download") targetFocusModifier else Modifier,)
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
            ,
                modifier = if (targetItemId == "about.check_update") targetFocusModifier else Modifier,)
            if (shouldShowUpdateDownloadAction(uiState.appUpdate)) {
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_update_download),
                    value = formatUpdateDownloadLabel(uiState.appUpdate, context),
                    onClick = {
                        when (uiState.appUpdate.latestActionState()) {
                            com.streamvault.feature.settings.api.SettingsUpdateActionState.INSTALL_LATEST,
                            com.streamvault.feature.settings.api.SettingsUpdateActionState.INSTALL_PERMISSION_REQUIRED -> onInstallDownloadedUpdate()
                            com.streamvault.feature.settings.api.SettingsUpdateActionState.DOWNLOAD_LATEST -> onDownloadLatestUpdate()
                            com.streamvault.feature.settings.api.SettingsUpdateActionState.DOWNLOADING,
                            com.streamvault.feature.settings.api.SettingsUpdateActionState.NONE -> Unit
                        }
                    }
                ,
                    modifier = if (targetItemId == "about.download_install") targetFocusModifier else Modifier,)
            }
            if (!uiState.appUpdate.releaseUrl.isNullOrBlank()) {
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_update_view_release),
                    value = uiState.appUpdate.latestVersionName ?: stringResource(R.string.settings_update_release_notes),
                    onClick = { onOpenUri(uiState.appUpdate.releaseUrl.orEmpty()) }
                ,
                    modifier = if (targetItemId == "about.release") targetFocusModifier else Modifier,)
            }
            if (!uiState.appUpdate.errorMessage.isNullOrBlank()) {
                SettingsRow(
                    label = stringResource(R.string.settings_update_error),
                    value = uiState.appUpdate.errorMessage.orEmpty()
                )
            }
        }
    }

    if (page == null || page == SettingsPage.REPORTS) item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                ,
                    modifier = if (targetItemId == "support.crash_view") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_crash_report_share),
                    value = uiState.crashReport.fileName,
                    onClick = onShareCrashReport
                ,
                    modifier = if (targetItemId == "support.crash_share") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_crash_report_delete),
                    value = stringResource(R.string.settings_crash_report_delete_value),
                    onClick = onDeleteCrashReport
                ,
                    modifier = if (targetItemId == "support.crash_delete") targetFocusModifier else Modifier,)
            } else {
                SettingsRow(
                    label = stringResource(R.string.settings_crash_report_latest),
                    value = stringResource(R.string.settings_crash_report_none)
                )
            }
        }
    }

    if (page == null || page == SettingsPage.APP_INFO) item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsRow(label = stringResource(R.string.settings_build), value = stringResource(R.string.settings_build_desc))
            SettingsRow(label = stringResource(R.string.settings_build_verification), value = buildVerificationLabel)
            SettingsRow(label = stringResource(R.string.settings_developed_by), value = stringResource(R.string.settings_developer_name))
            ClickableSettingsRow(
                label = stringResource(R.string.settings_github),
                value = stringResource(R.string.settings_github_url),
                onClick = { onOpenUri(context.getString(R.string.settings_github_url)) }
            ,
                modifier = if (targetItemId == "about.github") targetFocusModifier else Modifier,)
            ClickableSettingsRow(
                label = stringResource(R.string.settings_donate),
                value = stringResource(R.string.settings_donate_url),
                onClick = { onOpenUri(context.getString(R.string.settings_donate_url)) }
            ,
                modifier = if (targetItemId == "about.donate") targetFocusModifier else Modifier,)
            ClickableSettingsRow(
                label = stringResource(R.string.settings_close_app),
                value = "",
                onClick = onCloseApp
            ,
                modifier = if (targetItemId == "about.close_app") targetFocusModifier else Modifier,)
        }
    }
}
