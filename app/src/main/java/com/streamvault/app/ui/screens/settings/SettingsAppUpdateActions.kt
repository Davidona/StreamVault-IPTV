package com.streamvault.app.ui.screens.settings

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.streamvault.app.R
import com.streamvault.app.update.AppUpdateActionState
import com.streamvault.app.update.AppUpdateInstaller
import com.streamvault.app.update.AppUpdateCheckPolicy
import com.streamvault.app.update.GitHubReleaseChecker
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Result
import com.streamvault.feature.settings.api.SettingsReleaseInfo
import com.streamvault.feature.settings.api.SettingsUpdateActionState
import com.streamvault.feature.settings.api.SettingsUpdateDownloadState
import com.streamvault.feature.settings.api.SettingsUpdateDownloadStatus
import com.streamvault.feature.settings.presentation.SettingsUiState
import com.streamvault.feature.settings.presentation.latestActionState
import com.streamvault.feature.settings.presentation.toDownloadState
import com.streamvault.feature.settings.presentation.toReleaseInfoOrNull
import com.streamvault.feature.settings.presentation.withDownloadState

internal class SettingsAppUpdateActions(
    private val appContext: Application,
    private val preferencesRepository: PreferencesRepository,
    private val gitHubReleaseChecker: GitHubReleaseChecker,
    private val appUpdateInstaller: AppUpdateInstaller,
    private val uiState: MutableStateFlow<SettingsUiState>
) {
    private var updateCheckInFlight = false

    fun shouldAutoCheckForUpdates(lastSuccessfulCheckAt: Long?, lastFailedCheckAt: Long?): Boolean =
        AppUpdateCheckPolicy.shouldAutoCheck(System.currentTimeMillis(), lastSuccessfulCheckAt, lastFailedCheckAt)

    fun checkForAppUpdates(
        scope: CoroutineScope,
        manual: Boolean,
        isRemoteVersionNewer: (Int?, String, String?) -> Boolean,
        autoDownload: Boolean = false
    ) {
        if (updateCheckInFlight) return
        updateCheckInFlight = true
        scope.launch {
            val checkedAt = System.currentTimeMillis()
            preferencesRepository.setLastAppUpdateAttemptTimestamp(checkedAt)
            preferencesRepository.setLastAppUpdateOutcome("ATTEMPTED")
            uiState.update {
                it.copy(
                    isCheckingForUpdates = true,
                    appUpdate = it.appUpdate.copy(errorMessage = null)
                )
            }
            when (val result = gitHubReleaseChecker.fetchLatestRelease()) {
                is Result.Error -> {
                    preferencesRepository.setLastAppUpdateFailureTimestamp(checkedAt)
                    preferencesRepository.setLastAppUpdateOutcome("FAILURE: ${result.message}")
                    uiState.update {
                        it.copy(
                            isCheckingForUpdates = false,
                            userMessage = if (manual) result.message else it.userMessage,
                            appUpdate = it.appUpdate.copy(
                                lastCheckedAt = checkedAt,
                                errorMessage = result.message
                            )
                        )
                    }
                }
                is Result.Success -> {
                    val release = result.data
                    preferencesRepository.setCachedAppUpdateRelease(
                        versionName = release.versionName,
                        versionCode = release.versionCode,
                        releaseUrl = release.releaseUrl,
                        downloadUrl = release.downloadUrl,
                        downloadSha256 = release.downloadSha256,
                        releaseNotes = release.releaseNotes,
                        publishedAt = release.publishedAt
                    )
                    preferencesRepository.setLastAppUpdateCheckTimestamp(checkedAt)
                    preferencesRepository.setLastAppUpdateFailureTimestamp(null)
                    preferencesRepository.setLastAppUpdateOutcome("SUCCESS")
                    val updateAvailable = isRemoteVersionNewer(
                        release.versionCode,
                        release.versionName,
                        release.publishedAt
                    )
                    var latestUpdateModel = AppUpdateUiModel(
                        latestVersionName = release.versionName,
                        latestVersionCode = release.versionCode,
                        releaseUrl = release.releaseUrl,
                        downloadUrl = release.downloadUrl,
                        downloadSha256 = release.downloadSha256,
                        releaseNotes = release.releaseNotes,
                        publishedAt = release.publishedAt,
                        isUpdateAvailable = updateAvailable,
                        lastCheckedAt = checkedAt,
                        errorMessage = null
                    )
                    uiState.update {
                        it.copy(
                            isCheckingForUpdates = false,
                            userMessage = if (manual) {
                                if (updateAvailable) {
                                    appContext.getString(R.string.settings_update_available_message, release.versionName)
                                } else {
                                    appContext.getString(R.string.settings_update_current_message)
                                }
                            } else {
                                it.userMessage
                            },
                            appUpdate = latestUpdateModel.withDownloadState(it.appUpdate.toDownloadState())
                        )
                    }
                    val refreshedDownloadState = appUpdateInstaller.refreshState().toSettingsDownloadState()
                    latestUpdateModel = latestUpdateModel.withDownloadState(refreshedDownloadState)
                    uiState.update { it.copy(appUpdate = latestUpdateModel) }
                    if (autoDownload &&
                        updateAvailable &&
                        latestUpdateModel.latestActionState() == SettingsUpdateActionState.DOWNLOAD_LATEST
                    ) {
                        downloadLatestUpdate(scope)
                    }
                }
                Result.Loading -> {
                    uiState.update { it.copy(isCheckingForUpdates = false) }
                }
            }
            updateCheckInFlight = false
        }
    }

    fun downloadLatestUpdate(scope: CoroutineScope) {
        val latestRelease = uiState.value.appUpdate.toReleaseInfoOrNull() ?: run {
            uiState.update {
                it.copy(userMessage = appContext.getString(R.string.settings_update_download_unavailable))
            }
            return
        }

        scope.launch {
            when (val result = appUpdateInstaller.startDownload(latestRelease.toGitHubReleaseInfo())) {
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                is Result.Success -> uiState.update {
                    it.copy(userMessage = appContext.getString(R.string.settings_update_download_started))
                }
                Result.Loading -> Unit
            }
        }
    }

    fun installDownloadedUpdate(scope: CoroutineScope) {
        scope.launch {
            when (val result = appUpdateInstaller.installDownloadedUpdate(uiState.value.appUpdate.downloadSha256)) {
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                is Result.Success -> uiState.update {
                    it.copy(userMessage = appContext.getString(R.string.settings_update_install_started))
                }
                Result.Loading -> Unit
            }
        }
    }
}

internal fun com.streamvault.app.update.AppUpdateDownloadState.toSettingsDownloadState() =
    SettingsUpdateDownloadState(
        status = when (status) {
            com.streamvault.app.update.AppUpdateDownloadStatus.Idle -> SettingsUpdateDownloadStatus.IDLE
            com.streamvault.app.update.AppUpdateDownloadStatus.Downloading -> SettingsUpdateDownloadStatus.DOWNLOADING
            com.streamvault.app.update.AppUpdateDownloadStatus.Downloaded -> SettingsUpdateDownloadStatus.DOWNLOADED
            com.streamvault.app.update.AppUpdateDownloadStatus.Failed -> SettingsUpdateDownloadStatus.FAILED
        },
        versionName = versionName,
        downloadId = downloadId,
        installPermissionRequired = installPermissionRequired,
    )

private fun SettingsReleaseInfo.toGitHubReleaseInfo() = com.streamvault.app.update.GitHubReleaseInfo(
    versionName = versionName,
    versionCode = versionCode,
    releaseUrl = releaseUrl,
    downloadUrl = downloadUrl,
    downloadSha256 = downloadSha256,
    releaseNotes = releaseNotes,
    publishedAt = publishedAt,
)
