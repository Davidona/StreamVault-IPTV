package com.streamvault.feature.settings.api

import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import kotlin.math.max

enum class SettingsUpdateDownloadStatus {
    IDLE,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
}

enum class SettingsUpdateActionState {
    NONE,
    DOWNLOAD_LATEST,
    DOWNLOADING,
    INSTALL_LATEST,
    INSTALL_PERMISSION_REQUIRED,
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

    fun shouldAutoCheckForUpdates(lastSuccessfulCheckAt: Long?, lastFailedCheckAt: Long?): Boolean

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

enum class SettingsUpdateChannel { STABLE, BETA }

fun isSettingsRemoteVersionNewerForBuild(
    remoteVersionCode: Int?,
    remoteVersionName: String,
    remotePublishedAt: String?,
    currentVersionCode: Int,
    currentVersionName: String,
    currentBuildTimestampUtc: Long,
    currentChannel: SettingsUpdateChannel,
): Boolean {
    val remote = parseSettingsVersion(remoteVersionName)
    if (remote.channel != currentChannel) return false
    if (remoteVersionCode != null && remoteVersionCode > currentVersionCode) return true
    val comparison = compareSettingsVersions(remote.baseVersionName, parseSettingsVersion(currentVersionName).baseVersionName)
    if (comparison != 0) return comparison > 0
    if (currentChannel == SettingsUpdateChannel.BETA) {
        return remotePublishedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?.let { it > currentBuildTimestampUtc } == true
    }
    return false
}

private data class ParsedSettingsVersion(val baseVersionName: String, val channel: SettingsUpdateChannel)

private fun parseSettingsVersion(versionName: String): ParsedSettingsVersion {
    val normalized = versionName.removePrefix("v").trim()
    val betaIndex = normalized.indexOf("-beta", ignoreCase = true)
    return if (betaIndex >= 0) {
        ParsedSettingsVersion(normalized.substring(0, betaIndex), SettingsUpdateChannel.BETA)
    } else {
        ParsedSettingsVersion(normalized, SettingsUpdateChannel.STABLE)
    }
}

private fun compareSettingsVersions(left: String, right: String): Int {
    val leftParts = left.removePrefix("v").split('.')
    val rightParts = right.removePrefix("v").split('.')
    val length = max(leftParts.size, rightParts.size)
    for (index in 0 until length) {
        val comparison = (leftParts.getOrNull(index)?.toIntOrNull() ?: 0)
            .compareTo(rightParts.getOrNull(index)?.toIntOrNull() ?: 0)
        if (comparison != 0) return comparison
    }
    return 0
}

fun latestSettingsUpdateAction(
    latestVersionName: String?,
    downloadUrl: String?,
    isUpdateAvailable: Boolean,
    downloadState: SettingsUpdateDownloadState,
): SettingsUpdateActionState {
    if (latestVersionName.isNullOrBlank()) return SettingsUpdateActionState.NONE

    val latestDownloaded = downloadState.status == SettingsUpdateDownloadStatus.DOWNLOADED &&
        downloadState.versionName == latestVersionName
    if (latestDownloaded) {
        return if (downloadState.installPermissionRequired) {
            SettingsUpdateActionState.INSTALL_PERMISSION_REQUIRED
        } else {
            SettingsUpdateActionState.INSTALL_LATEST
        }
    }

    if (downloadState.status == SettingsUpdateDownloadStatus.DOWNLOADING &&
        downloadState.versionName == latestVersionName
    ) {
        return SettingsUpdateActionState.DOWNLOADING
    }

    return if (isUpdateAvailable && !downloadUrl.isNullOrBlank()) {
        SettingsUpdateActionState.DOWNLOAD_LATEST
    } else {
        SettingsUpdateActionState.NONE
    }
}
