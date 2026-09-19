package com.streamvault.feature.settings.presentation

import android.content.Context
import com.streamvault.feature.settings.api.SettingsAppUpdatePort
import com.streamvault.domain.settings.SettingsOperations
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.GetCustomCategories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun registerSettingsAppUpdateObservers(
    scope: CoroutineScope,
    preferencesRepository: SettingsPreferences,
    appUpdateActions: SettingsAppUpdateActions,
    appUpdatePort: SettingsAppUpdatePort,
    uiState: MutableStateFlow<SettingsUiState>
) {
    scope.launch {
        combine(
            preferencesRepository.autoCheckAppUpdates,
            preferencesRepository.lastAppUpdateCheckTimestamp,
            preferencesRepository.lastAppUpdateFailureTimestamp,
            preferencesRepository.autoDownloadAppUpdates
        ) { autoCheckEnabled, lastSuccessfulCheckAt, lastFailedCheckAt, autoDownload ->
            UpdateCheckPreferences(autoCheckEnabled, lastSuccessfulCheckAt, lastFailedCheckAt, autoDownload)
        }.distinctUntilChanged().collect { preferences ->
            if (preferences.autoCheckEnabled && appUpdateActions.shouldAutoCheckForUpdates(
                    preferences.lastSuccessfulCheckAt,
                    preferences.lastFailedCheckAt
                )) {
                appUpdateActions.checkForAppUpdates(
                    scope = scope,
                    manual = false,
                    isRemoteVersionNewer = appUpdatePort::isRemoteVersionNewer,
                    autoDownload = preferences.autoDownload
                )
            }
        }
    }

    scope.launch {
        appUpdatePort.downloadState.collect { downloadState ->
            uiState.update {
                it.copy(appUpdate = it.appUpdate.withDownloadState(downloadState))
            }
        }
    }

    scope.launch {
        appUpdatePort.refreshDownloadState()
    }
}

private data class UpdateCheckPreferences(
    val autoCheckEnabled: Boolean,
    val lastSuccessfulCheckAt: Long?,
    val lastFailedCheckAt: Long?,
    val autoDownload: Boolean,
)

internal fun registerCombinedProfileObservers(
    scope: CoroutineScope,
    combinedM3uRepository: CombinedM3uRepository,
    uiState: MutableStateFlow<SettingsUiState>
) {
    scope.launch {
        combinedM3uRepository.getProfiles().collect { profiles ->
            uiState.update { it.copy(combinedProfiles = profiles) }
        }
    }

    scope.launch {
        combinedM3uRepository.getAvailableM3uProviders().collect { providers ->
            uiState.update { it.copy(availableM3uProviders = providers) }
        }
    }

    scope.launch {
        combinedM3uRepository.getActiveLiveSource().collect { activeSource ->
            uiState.update { it.copy(activeLiveSource = activeSource) }
        }
    }
}

internal fun registerRecordingObservers(
    scope: CoroutineScope,
    recordingManager: RecordingManager,
    preferencesRepository: SettingsPreferences,
    uiState: MutableStateFlow<SettingsUiState>
) {
    scope.launch {
        recordingManager.observeRecordingItems().collect { items ->
            uiState.update { it.copy(recordingItems = items.sortedByDescending(RecordingItem::scheduledStartMs)) }
        }
    }

    scope.launch {
        recordingManager.observeStorageState().collect { storage ->
            uiState.update { it.copy(recordingStorageState = storage) }
        }
    }

    scope.launch {
        preferencesRepository.recordingWifiOnly.collect { wifiOnly ->
            uiState.update { it.copy(wifiOnlyRecording = wifiOnly) }
        }
    }

    scope.launch {
        preferencesRepository.recordingPaddingBeforeMinutes.collect { minutes ->
            uiState.update { it.copy(recordingPaddingBeforeMinutes = minutes) }
        }
    }

    scope.launch {
        preferencesRepository.recordingPaddingAfterMinutes.collect { minutes ->
            uiState.update { it.copy(recordingPaddingAfterMinutes = minutes) }
        }
    }
}

internal fun registerEpgObservers(
    scope: CoroutineScope,
    epgSourceRepository: EpgSourceRepository,
    preferencesRepository: SettingsPreferences,
    uiState: MutableStateFlow<SettingsUiState>
) {
    scope.launch {
        epgSourceRepository.getAllSources().collect { sources ->
            uiState.update { it.copy(epgSources = sources) }
        }
    }
    scope.launch {
        preferencesRepository.epgTimeShiftsByProvider.collect { map ->
            uiState.update { it.copy(epgTimeShiftMinutesByProvider = map) }
        }
    }
}

internal fun registerDerivedStateObservers(
    scope: CoroutineScope,
    providerRepository: ProviderRepository,
    syncMetadataRepository: SyncMetadataRepository,
    movieRepository: MovieRepository,
    seriesRepository: SeriesRepository,
    settingsOperations: SettingsOperations,
    application: Context,
    preferencesRepository: SettingsPreferences,
    activeProviderIdFlow: Flow<Long?>,
    categoryRepository: CategoryRepository,
    combinedM3uRepository: CombinedM3uRepository,
    channelRepository: ChannelRepository,
    getCustomCategories: GetCustomCategories,
    uiState: MutableStateFlow<SettingsUiState>
) {
    scope.launch {
        observeProviderDiagnostics(
            providerRepository = providerRepository,
            syncMetadataRepository = syncMetadataRepository,
            movieRepository = movieRepository,
            seriesRepository = seriesRepository,
            settingsOperations = settingsOperations,
            application = application
        ).collect { diagnosticsByProvider ->
            uiState.update { it.copy(diagnosticsByProvider = diagnosticsByProvider) }
        }
    }

    scope.launch {
        preferencesRepository.lastMaintenanceSnapshot.collect { snapshot ->
            uiState.update { it.copy(databaseMaintenance = snapshot?.toUiModel()) }
        }
    }

    scope.launch {
        observeCategoryManagement(
            activeProviderIdFlow = activeProviderIdFlow,
            preferencesRepository = preferencesRepository,
            categoryRepository = categoryRepository
        ).collect { snapshot ->
            uiState.update {
                it.copy(
                    categorySortModes = snapshot.categorySortModes,
                    hiddenCategories = snapshot.hiddenCategories
                )
            }
        }
    }

    scope.launch {
        observeGuideDefaultCategoryOptions(
            combinedM3uRepository = combinedM3uRepository,
            channelRepository = channelRepository,
            preferencesRepository = preferencesRepository,
            getCustomCategories = getCustomCategories
        ).collect { categories ->
            uiState.update { it.copy(guideDefaultCategoryOptions = categories) }
        }
    }
}
