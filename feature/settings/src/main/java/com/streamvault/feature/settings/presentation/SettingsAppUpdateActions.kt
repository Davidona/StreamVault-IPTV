package com.streamvault.feature.settings.presentation

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.streamvault.feature.settings.R
import com.streamvault.feature.settings.api.SettingsAppUpdatePort
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.model.Result
import com.streamvault.feature.settings.api.SettingsUpdateActionState

internal class SettingsAppUpdateActions(
    private val appContext: Context,
    private val preferencesRepository: SettingsPreferences,
    private val appUpdatePort: SettingsAppUpdatePort,
    private val uiState: MutableStateFlow<SettingsUiState>
) {
    private var updateCheckInFlight = false

    fun shouldAutoCheckForUpdates(lastSuccessfulCheckAt: Long?, lastFailedCheckAt: Long?): Boolean =
        appUpdatePort.shouldAutoCheckForUpdates(lastSuccessfulCheckAt, lastFailedCheckAt)

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
            when (val result = appUpdatePort.fetchLatestRelease()) {
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
                    val refreshedDownloadState = appUpdatePort.refreshDownloadState()
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
            when (val result = appUpdatePort.startDownload(latestRelease)) {
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
            when (val result = appUpdatePort.installDownloadedUpdate(uiState.value.appUpdate.downloadSha256)) {
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                is Result.Success -> uiState.update {
                    it.copy(userMessage = appContext.getString(R.string.settings_update_install_started))
                }
                Result.Loading -> Unit
            }
        }
    }
}
