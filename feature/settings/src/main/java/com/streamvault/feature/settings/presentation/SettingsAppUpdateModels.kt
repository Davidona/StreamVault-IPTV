package com.streamvault.feature.settings.presentation

import com.streamvault.feature.settings.api.SettingsAppUpdatePort
import com.streamvault.feature.settings.api.SettingsReleaseInfo
import com.streamvault.feature.settings.api.SettingsUpdateActionState
import com.streamvault.feature.settings.api.SettingsUpdateDownloadState
import com.streamvault.feature.settings.api.SettingsUpdateDownloadStatus
import com.streamvault.feature.settings.api.latestSettingsUpdateAction

data class AppUpdateUiModel(
    val latestVersionName: String? = null,
    val latestVersionCode: Int? = null,
    val releaseUrl: String? = null,
    val downloadUrl: String? = null,
    val downloadSha256: String? = null,
    val releaseNotes: String = "",
    val publishedAt: String? = null,
    val isUpdateAvailable: Boolean = false,
    val lastCheckedAt: Long? = null,
    val errorMessage: String? = null,
    val downloadStatus: SettingsUpdateDownloadStatus = SettingsUpdateDownloadStatus.IDLE,
    val downloadedVersionName: String? = null,
    val installPermissionRequired: Boolean = false,
)

fun AppUpdateUiModel.toReleaseInfoOrNull(): SettingsReleaseInfo? {
    val versionName = latestVersionName ?: return null
    val releaseUrl = releaseUrl ?: return null
    return SettingsReleaseInfo(
        versionName = versionName,
        versionCode = latestVersionCode,
        releaseUrl = releaseUrl,
        downloadUrl = downloadUrl,
        downloadSha256 = downloadSha256,
        releaseNotes = releaseNotes,
        publishedAt = publishedAt,
    )
}

fun AppUpdateUiModel.withDownloadState(downloadState: SettingsUpdateDownloadState): AppUpdateUiModel = copy(
    downloadStatus = downloadState.status,
    downloadedVersionName = downloadState.versionName,
    installPermissionRequired = downloadState.installPermissionRequired,
)

fun AppUpdateUiModel.toDownloadState(): SettingsUpdateDownloadState = SettingsUpdateDownloadState(
    status = downloadStatus,
    versionName = downloadedVersionName,
    installPermissionRequired = installPermissionRequired,
)

fun AppUpdateUiModel.latestActionState(): SettingsUpdateActionState = latestSettingsUpdateAction(
    latestVersionName = latestVersionName,
    downloadUrl = downloadUrl,
    isUpdateAvailable = isUpdateAvailable,
    downloadState = toDownloadState(),
)

fun SettingsPreferenceSnapshot.toCachedAppUpdateUiModel(
    isRemoteVersionNewer: (Int?, String, String?) -> Boolean = { _, _, _ -> false },
): AppUpdateUiModel {
    val versionName = cachedAppUpdateVersionName
    return AppUpdateUiModel(
        latestVersionName = versionName,
        latestVersionCode = cachedAppUpdateVersionCode,
        releaseUrl = cachedAppUpdateReleaseUrl,
        downloadUrl = cachedAppUpdateDownloadUrl,
        downloadSha256 = cachedAppUpdateDownloadSha256,
        releaseNotes = cachedAppUpdateReleaseNotes,
        publishedAt = cachedAppUpdatePublishedAt,
        isUpdateAvailable = versionName?.let {
            isRemoteVersionNewer(cachedAppUpdateVersionCode, it, cachedAppUpdatePublishedAt)
        } ?: false,
        lastCheckedAt = lastAppUpdateCheckAt,
    )
}
