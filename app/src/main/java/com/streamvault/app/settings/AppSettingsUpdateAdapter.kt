package com.streamvault.app.settings

import com.streamvault.app.update.AppUpdateDownloadStatus
import com.streamvault.app.update.AppUpdateInstaller
import com.streamvault.app.update.GitHubReleaseChecker
import com.streamvault.app.update.GitHubReleaseInfo
import com.streamvault.app.update.isRemoteVersionNewer
import com.streamvault.domain.model.Result
import com.streamvault.feature.settings.api.SettingsAppUpdatePort
import com.streamvault.feature.settings.api.SettingsReleaseInfo
import com.streamvault.feature.settings.api.SettingsUpdateDownloadState
import com.streamvault.feature.settings.api.SettingsUpdateDownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettingsUpdateAdapter @Inject constructor(
    private val releaseChecker: GitHubReleaseChecker,
    private val updateInstaller: AppUpdateInstaller,
) : SettingsAppUpdatePort {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mappedDownloadState = MutableStateFlow(mapDownloadState(updateInstaller.downloadState.value))

    init {
        scope.launch {
            updateInstaller.downloadState.collect { state ->
                mappedDownloadState.value = mapDownloadState(state)
            }
        }
    }

    override val downloadState: StateFlow<SettingsUpdateDownloadState> = mappedDownloadState

    override fun isRemoteVersionNewer(
        remoteVersionCode: Int?,
        remoteVersionName: String,
        remotePublishedAt: String?,
    ): Boolean = isRemoteVersionNewer(remoteVersionCode, remoteVersionName, remotePublishedAt)

    override suspend fun fetchLatestRelease(): Result<SettingsReleaseInfo> =
        releaseChecker.fetchLatestRelease().map(::mapRelease)

    override suspend fun refreshDownloadState(): SettingsUpdateDownloadState =
        mapDownloadState(updateInstaller.refreshState())

    override suspend fun startDownload(release: SettingsReleaseInfo): Result<Unit> =
        updateInstaller.startDownload(
            GitHubReleaseInfo(
                versionName = release.versionName,
                versionCode = release.versionCode,
                releaseUrl = release.releaseUrl,
                downloadUrl = release.downloadUrl,
                downloadSha256 = release.downloadSha256,
                releaseNotes = release.releaseNotes,
                publishedAt = release.publishedAt,
            )
        )

    override suspend fun installDownloadedUpdate(expectedSha256: String?): Result<Unit> =
        updateInstaller.installDownloadedUpdate(expectedSha256)

    private fun mapRelease(release: GitHubReleaseInfo): SettingsReleaseInfo =
        SettingsReleaseInfo(
            versionName = release.versionName,
            versionCode = release.versionCode,
            releaseUrl = release.releaseUrl,
            downloadUrl = release.downloadUrl,
            downloadSha256 = release.downloadSha256,
            releaseNotes = release.releaseNotes,
            publishedAt = release.publishedAt,
        )

    private fun mapDownloadState(
        state: com.streamvault.app.update.AppUpdateDownloadState,
    ): SettingsUpdateDownloadState =
        SettingsUpdateDownloadState(
            status = when (state.status) {
                AppUpdateDownloadStatus.Idle -> SettingsUpdateDownloadStatus.IDLE
                AppUpdateDownloadStatus.Downloading -> SettingsUpdateDownloadStatus.DOWNLOADING
                AppUpdateDownloadStatus.Downloaded -> SettingsUpdateDownloadStatus.DOWNLOADED
                AppUpdateDownloadStatus.Failed -> SettingsUpdateDownloadStatus.FAILED
            },
            versionName = state.versionName,
            downloadId = state.downloadId,
            installPermissionRequired = state.installPermissionRequired,
        )
}
