package com.streamvault.app.ui.screens.settings

import com.streamvault.app.R
import com.streamvault.feature.settings.api.SettingsUpdateActionState
import com.streamvault.feature.settings.api.SettingsUpdateDownloadStatus
import com.streamvault.feature.settings.presentation.latestActionState
import java.text.DateFormat

internal fun formatLatestReleaseLabel(update: AppUpdateUiModel, context: android.content.Context): String {
    val versionName = update.latestVersionName ?: return context.getString(R.string.settings_update_not_checked)
    val versionCodeSuffix = update.latestVersionCode?.let { " ($it)" }.orEmpty()
    return "$versionName$versionCodeSuffix"
}

internal fun formatUpdateStatusLabel(update: AppUpdateUiModel, context: android.content.Context): String {
    return when {
        update.errorMessage != null -> context.getString(R.string.settings_update_status_check_failed)
        update.downloadStatus == SettingsUpdateDownloadStatus.DOWNLOADING -> context.getString(R.string.settings_update_status_downloading)
        update.latestActionState() == SettingsUpdateActionState.INSTALL_PERMISSION_REQUIRED -> {
            context.getString(R.string.settings_update_status_permission_required)
        }
        update.latestActionState() == SettingsUpdateActionState.INSTALL_LATEST -> {
            context.getString(R.string.settings_update_status_ready_to_install)
        }
        update.latestVersionName == null -> context.getString(R.string.settings_update_not_checked)
        update.isUpdateAvailable -> context.getString(R.string.settings_update_status_available)
        else -> context.getString(R.string.settings_update_status_current)
    }
}

internal fun formatUpdateCheckTimeLabel(timestamp: Long?, context: android.content.Context): String {
    if (timestamp == null || timestamp <= 0L) {
        return context.getString(R.string.settings_update_not_checked)
    }
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(java.util.Date(timestamp))
}

internal fun shouldShowUpdateDownloadAction(update: AppUpdateUiModel): Boolean {
    return update.latestActionState() != SettingsUpdateActionState.NONE
}

internal fun formatUpdateDownloadLabel(update: AppUpdateUiModel, context: android.content.Context): String {
    return when (update.latestActionState()) {
        SettingsUpdateActionState.DOWNLOADING -> context.getString(R.string.settings_update_download_in_progress)
        SettingsUpdateActionState.INSTALL_LATEST -> context.getString(R.string.settings_update_install_action)
        SettingsUpdateActionState.INSTALL_PERMISSION_REQUIRED -> context.getString(R.string.settings_update_install_permission_action)
        SettingsUpdateActionState.DOWNLOAD_LATEST -> context.getString(R.string.settings_update_download_action)
        SettingsUpdateActionState.NONE -> context.getString(R.string.settings_update_download_action)
    }
}
