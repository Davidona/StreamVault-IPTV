package com.streamvault.feature.settings.api

import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.StateFlow

enum class SettingsUpdateDownloadStatus {
    IDLE,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
}

data class SettingsUpdateDownloadState(
    val status: SettingsUpdateDownloadStatus = SettingsUpdateDownloadStatus.IDLE,
    val versionName: String? = null,
    val downloadId: Long? = null,
    val installPermissionRequired: Boolean = false,
)

data class SettingsReleaseInfo(
    val versionName: String,
    val versionCode: Int?,
    val releaseUrl: String,
    val downloadUrl: String?,
    val downloadSha256: String?,
    val releaseNotes: String,
    val publishedAt: String?,
)

interface SettingsAppUpdatePort {
    val downloadState: StateFlow<SettingsUpdateDownloadState>

    fun isRemoteVersionNewer(
        remoteVersionCode: Int?,
        remoteVersionName: String,
        remotePublishedAt: String?,
    ): Boolean

    suspend fun fetchLatestRelease(): Result<SettingsReleaseInfo>
    suspend fun refreshDownloadState(): SettingsUpdateDownloadState
    suspend fun startDownload(release: SettingsReleaseInfo): Result<Unit>
    suspend fun installDownloadedUpdate(expectedSha256: String?): Result<Unit>
}
