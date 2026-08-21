package com.streamvault.app.ui.screens.settings

import android.app.Application
import com.streamvault.app.R
import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.app.tvinput.TvInputCatalogRefreshWorker
import com.streamvault.data.sync.ProviderSyncCommands
import com.streamvault.data.sync.SyncRepairSection
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SettingsSyncActions(
    private val appContext: Application,
    private val syncManager: ProviderSyncCommands,
    private val tvInputChannelSyncManager: TvInputChannelSyncManager,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val refreshProvider: (CoroutineScope, Long, SettingsProviderSyncMode, String?, Long, String?, Boolean) -> Job
) {
    private var syncJob: Job? = null

    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        uiState.update {
            it.copy(
                isSyncing = false,
                syncProgress = null,
                syncingProviderName = null,
                syncStartedAt = 0L,
                syncSectionLabel = null,
                syncCanCancel = false,
                userMessage = appContext.getString(R.string.settings_sync_cancelled)
            )
        }
    }

    fun syncProviderSection(scope: CoroutineScope, providerId: Long, selection: ProviderSyncSelection) {
        if (selection == ProviderSyncSelection.REBUILD_INDEX) {
            val sectionLabel = selection.label(appContext)
            val job = refreshProvider(
                scope,
                providerId,
                SettingsProviderSyncMode.REBUILD_INDEX,
                appContext.getString(R.string.settings_syncing_section, sectionLabel),
                System.currentTimeMillis(),
                sectionLabel,
                true
            )
            syncJob = job
            job.invokeOnCompletion {
                if (syncJob === job) {
                    syncJob = null
                }
            }
            return
        }

        syncJob = scope.launch {
            when (selection) {
                ProviderSyncSelection.SYNC_NOW -> runSectionSync(
                    providerId = providerId,
                    selections = syncNowSelections(providerId)
                )
                ProviderSyncSelection.REBUILD_INDEX -> Unit
                else -> runSectionSync(providerId, listOf(selection))
            }
        }
    }

    fun syncProviderCustom(scope: CoroutineScope, providerId: Long, selections: Set<ProviderSyncSelection>) {
        syncJob = scope.launch {
            val orderedSelections = listOf(
                ProviderSyncSelection.TV,
                ProviderSyncSelection.MOVIES,
                ProviderSyncSelection.SERIES,
                ProviderSyncSelection.EPG
            ).filter { it in selections }
            if (orderedSelections.isEmpty()) {
                uiState.update {
                    it.copy(userMessage = appContext.getString(R.string.settings_sync_custom_required))
                }
                return@launch
            }
            runSectionSync(providerId, orderedSelections)
        }
    }

    fun retryWarningAction(scope: CoroutineScope, providerId: Long, action: ProviderWarningAction) {
        syncJob = scope.launch {
            val providerName = uiState.value.providers.firstOrNull { it.id == providerId }?.name
            val sectionLabel = when (action) {
                ProviderWarningAction.EPG -> appContext.getString(R.string.settings_sync_option_epg)
                ProviderWarningAction.MOVIES -> appContext.getString(R.string.settings_sync_option_movies)
                ProviderWarningAction.SERIES -> appContext.getString(R.string.settings_sync_option_series)
            }
            uiState.update {
                it.copy(
                    isSyncing = true,
                    syncProgress = appContext.getString(R.string.settings_syncing_preparing),
                    syncingProviderName = providerName,
                    syncStartedAt = System.currentTimeMillis(),
                    syncSectionLabel = sectionLabel,
                    syncCanCancel = true
                )
            }
            val section = when (action) {
                ProviderWarningAction.EPG -> SyncRepairSection.EPG
                ProviderWarningAction.MOVIES -> SyncRepairSection.MOVIES
                ProviderWarningAction.SERIES -> SyncRepairSection.SERIES
            }
            val result = syncManager.retrySection(providerId, section) { progress ->
                uiState.update { state ->
                    state.copy(
                        syncProgress = progress,
                        syncingProviderName = providerName,
                        syncSectionLabel = sectionLabel
                    )
                }
            }
            uiState.update { state ->
                if (result is Result.Error) {
                    state.copy(
                        isSyncing = false,
                        syncProgress = null,
                        syncingProviderName = null,
                        syncStartedAt = 0L,
                        syncSectionLabel = null,
                        syncCanCancel = false,
                        userMessage = "Retry failed: ${result.message}"
                    )
                } else {
                    val currentWarnings = state.syncWarningsByProvider[providerId].orEmpty()
                    val updatedWarnings = currentWarnings.filterNot { warning ->
                        when (action) {
                            ProviderWarningAction.EPG -> warning.contains("EPG", ignoreCase = true)
                            ProviderWarningAction.MOVIES -> warning.contains("Movies", ignoreCase = true)
                            ProviderWarningAction.SERIES -> warning.contains("Series", ignoreCase = true)
                        }
                    }
                    state.copy(
                        isSyncing = false,
                        syncProgress = null,
                        syncingProviderName = null,
                        syncStartedAt = 0L,
                        syncSectionLabel = null,
                        syncCanCancel = false,
                        userMessage = if (updatedWarnings.isEmpty()) {
                            "Section retry succeeded. All current warnings cleared."
                        } else {
                            "Section retry succeeded."
                        },
                        syncWarningsByProvider = if (updatedWarnings.isEmpty()) {
                            state.syncWarningsByProvider - providerId
                        } else {
                            state.syncWarningsByProvider + (providerId to updatedWarnings)
                        }
                    )
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (syncJob === job) {
                    syncJob = null
                }
            }
        }
    }

    private suspend fun runSectionSync(
        providerId: Long,
        selections: List<ProviderSyncSelection>
    ) {
        val provider = uiState.value.providers.firstOrNull { it.id == providerId }
        val providerName = provider?.name
        uiState.update {
            it.copy(
                isSyncing = true,
                syncProgress = appContext.getString(R.string.settings_syncing_preparing),
                syncingProviderName = providerName,
                syncStartedAt = System.currentTimeMillis(),
                syncSectionLabel = null,
                syncCanCancel = true
            )
        }
        try {
            val failures = mutableListOf<String>()
            val completed = mutableListOf<String>()

            selections.forEach { selection ->
                val sectionLabel = selection.label(appContext)
                val section = when (selection) {
                    ProviderSyncSelection.TV -> SyncRepairSection.LIVE
                    ProviderSyncSelection.MOVIES -> SyncRepairSection.MOVIES
                    ProviderSyncSelection.SERIES -> SyncRepairSection.SERIES
                    ProviderSyncSelection.EPG -> SyncRepairSection.EPG
                    ProviderSyncSelection.SYNC_NOW, ProviderSyncSelection.REBUILD_INDEX -> null
                } ?: return@forEach

                uiState.update { state ->
                    state.copy(
                        syncProgress = appContext.getString(
                            R.string.settings_syncing_section,
                            sectionLabel
                        ),
                        syncSectionLabel = sectionLabel,
                        syncingProviderName = providerName
                    )
                }

                when (val result = syncManager.retrySection(providerId, section) { progress ->
                    uiState.update { state ->
                        state.copy(
                            syncProgress = progress,
                            syncingProviderName = providerName,
                            syncSectionLabel = sectionLabel
                        )
                    }
                }) {
                    is Result.Error -> failures += "${sectionLabel}: ${result.message}"
                    else -> completed += selection.completionLabel(
                        isXtream = provider?.type == ProviderType.XTREAM_CODES || provider?.type == ProviderType.STALKER_PORTAL
                    )
                }
            }

            if (completed.any {
                    it == appContext.getString(R.string.settings_sync_option_tv)
                }
            ) {
                TvInputCatalogRefreshWorker.enqueue(appContext)
            }

            uiState.update { state ->
                state.copy(
                    isSyncing = false,
                    syncProgress = null,
                    syncingProviderName = null,
                    syncStartedAt = 0L,
                    syncSectionLabel = null,
                    syncCanCancel = false,
                    userMessage = when {
                        failures.isEmpty() -> appContext.getString(
                            R.string.settings_sync_sections_success,
                            completed.joinToString()
                        )
                        completed.isEmpty() -> appContext.getString(
                            R.string.settings_sync_sections_failed,
                            failures.joinToString()
                        )
                        else -> appContext.getString(
                            R.string.settings_sync_sections_partial,
                            completed.joinToString(),
                            failures.joinToString()
                        )
                    }
                )
            }
        } catch (e: CancellationException) {
            uiState.update {
                it.copy(
                    isSyncing = false,
                    syncProgress = null,
                    syncingProviderName = null,
                    syncStartedAt = 0L,
                    syncSectionLabel = null,
                    syncCanCancel = false,
                    userMessage = appContext.getString(R.string.settings_sync_cancelled)
                )
            }
        } catch (e: Exception) {
            uiState.update {
                it.copy(
                    isSyncing = false,
                    syncProgress = null,
                    syncingProviderName = null,
                    syncStartedAt = 0L,
                    syncSectionLabel = null,
                    syncCanCancel = false,
                    userMessage = "Sync failed: ${e.message}"
                )
            }
        } finally {
            syncJob = null
        }
    }

    private fun syncNowSelections(providerId: Long): List<ProviderSyncSelection> {
        val provider = uiState.value.providers.firstOrNull { it.id == providerId }
        return buildList {
            add(ProviderSyncSelection.TV)
            add(ProviderSyncSelection.MOVIES)
            if (provider?.type == ProviderType.XTREAM_CODES || provider?.type == ProviderType.STALKER_PORTAL) {
                add(ProviderSyncSelection.SERIES)
            }
            add(ProviderSyncSelection.EPG)
        }
    }

    private fun ProviderSyncSelection.completionLabel(isXtream: Boolean): String = when {
        isXtream && this == ProviderSyncSelection.MOVIES -> "Movies index queued"
        isXtream && this == ProviderSyncSelection.SERIES -> "Series index queued"
        else -> label(appContext)
    }
}
