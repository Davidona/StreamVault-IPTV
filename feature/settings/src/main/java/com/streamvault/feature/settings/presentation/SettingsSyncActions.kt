package com.streamvault.feature.settings.presentation

import android.content.Context
import com.streamvault.feature.settings.R
import com.streamvault.feature.settings.api.SettingsSurfaceRefreshPort
import com.streamvault.domain.settings.SettingsOperations
import com.streamvault.domain.settings.SettingsSyncSection
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.sync.ProviderSectionSyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SettingsSyncActions(
    private val appContext: Context,
    private val settingsOperations: SettingsOperations,
    private val surfaceRefreshPort: SettingsSurfaceRefreshPort,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val prepareRefresh: suspend (Long) -> Result<ProviderSectionSyncResult> = {
        Result.success(ProviderSectionSyncResult())
    },
    private val rebuildIndex: (CoroutineScope, Long) -> Job
) {
    private var syncJob: Job? = null

    fun cancelSync() {
        // Keep ownership until cancellation cleanup finishes, preventing an old job from
        // clearing a newly started operation's overlay. Committed continuations are durable.
        syncJob?.cancel()
    }

    fun syncProviderSection(scope: CoroutineScope, providerId: Long, selection: ProviderSyncSelection) {
        if (syncJob != null || uiState.value.isSyncing) return
        if (selection == ProviderSyncSelection.REBUILD_INDEX) {
            val job = rebuildIndex(scope, providerId)
            track(job)
            job.start()
            return
        }
        launchSync(scope) {
            val selections = if (selection == ProviderSyncSelection.SYNC_NOW) {
                uiState.value.providers.firstOrNull { it.id == providerId }
                    ?.let(::availableSyncSelections).orEmpty()
            } else listOf(selection)
            runSectionSync(providerId, selections, queueEpg = selection == ProviderSyncSelection.SYNC_NOW)
        }
    }

    fun syncProviderNow(scope: CoroutineScope, provider: Provider) {
        launchSync(scope) {
            runSectionSync(
                providerId = provider.id,
                selections = availableSyncSelections(provider),
                queueEpg = true,
                providerNameOverride = provider.name
            )
        }
    }

    fun syncProviderCustom(scope: CoroutineScope, providerId: Long, selections: Set<ProviderSyncSelection>) {
        launchSync(scope) {
            val provider = uiState.value.providers.firstOrNull { it.id == providerId }
            val orderedSelections = provider?.let(::availableSyncSelections).orEmpty().filter { it in selections }
            if (orderedSelections.isEmpty()) {
                uiState.update { it.copy(userMessage = appContext.getString(R.string.settings_sync_custom_required)) }
                return@launchSync
            }
            runSectionSync(providerId, orderedSelections)
        }
    }

    fun retryWarningAction(scope: CoroutineScope, providerId: Long, action: ProviderWarningAction) {
        val selection = when (action) {
            ProviderWarningAction.EPG -> ProviderSyncSelection.EPG
            ProviderWarningAction.MOVIES -> ProviderSyncSelection.MOVIES
            ProviderWarningAction.SERIES -> ProviderSyncSelection.SERIES
        }
        // The same result and cancellation handling applies to warning retries.
        syncProviderSection(scope, providerId, selection)
    }

    private fun launchSync(scope: CoroutineScope, block: suspend () -> Unit) {
        if (syncJob != null || uiState.value.isSyncing) return
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        track(job)
        job.start()
    }

    private fun track(job: Job) {
        syncJob = job
        job.invokeOnCompletion {
            if (syncJob === job) syncJob = null
        }
    }

    private suspend fun runSectionSync(
        providerId: Long,
        selections: List<ProviderSyncSelection>,
        queueEpg: Boolean = false,
        providerNameOverride: String? = null
    ) {
        if (selections.isEmpty()) {
            uiState.update { it.copy(userMessage = appContext.getString(R.string.settings_sync_provider_missing)) }
            return
        }
        val providerName = providerNameOverride ?: uiState.value.providers.firstOrNull { it.id == providerId }?.name
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
        var hasQueuedWork = false
        try {
            val failures = mutableListOf<String>()
            val completed = mutableListOf<String>()
            if (queueEpg) {
                // A changed text-decoding preference intentionally rebuilds Xtream indexes;
                // ordinary refreshes retain their existing checkpoints.
                when (val preparation = prepareRefresh(providerId)) {
                    is Result.Success -> {
                        hasQueuedWork = preparation.data.queued
                        failures += preparation.data.warnings
                    }
                    is Result.Error -> failures += preparation.message
                    Result.Loading -> failures += appContext.getString(R.string.settings_sync_no_result)
                }
            }
            selections.forEach { selection ->
                currentCoroutineContext().ensureActive()
                val sectionLabel = selection.label(appContext)
                val section = when (selection) {
                    ProviderSyncSelection.TV -> SettingsSyncSection.LIVE
                    ProviderSyncSelection.MOVIES -> SettingsSyncSection.MOVIES
                    ProviderSyncSelection.SERIES -> SettingsSyncSection.SERIES
                    ProviderSyncSelection.EPG -> SettingsSyncSection.EPG
                    ProviderSyncSelection.SYNC_NOW, ProviderSyncSelection.REBUILD_INDEX -> return@forEach
                }
                uiState.update {
                    it.copy(
                        syncProgress = appContext.getString(R.string.settings_syncing_section, sectionLabel),
                        syncSectionLabel = sectionLabel
                    )
                }
                if (queueEpg && section == SettingsSyncSection.EPG) {
                    settingsOperations.scheduleBackgroundEpgSync(providerId)
                    hasQueuedWork = true
                    completed += appContext.getString(R.string.settings_sync_section_queued, sectionLabel)
                    return@forEach
                }
                when (val result = settingsOperations.syncProviderSection(providerId, section) { progress ->
                    uiState.update { it.copy(syncProgress = progress) }
                }) {
                    is Result.Error -> failures += "$sectionLabel: ${result.message}"
                    Result.Loading -> failures += "$sectionLabel: ${appContext.getString(R.string.settings_sync_no_result)}"
                    is Result.Success -> {
                        val receipt = result.data
                        hasQueuedWork = hasQueuedWork || receipt.queued
                        if (section == SettingsSyncSection.LIVE && receipt.catalogChanged) {
                            // Refresh TV integration immediately, even if a later section is cancelled.
                            surfaceRefreshPort.enqueueTvInputCatalogRefresh()
                        }
                        val warnings = receipt.warnings.ifEmpty {
                            if (receipt.partial) listOf(appContext.getString(R.string.settings_sync_incomplete)) else emptyList()
                        }.map { "$sectionLabel: $it" }
                        uiState.update { state ->
                            val previous = state.syncWarningsByProvider[providerId].orEmpty()
                            val updated = if (receipt.queued) previous + warnings else {
                                previous.filterNot { it.startsWith("$sectionLabel:") } + warnings
                            }.distinct()
                            state.copy(syncWarningsByProvider = if (updated.isEmpty()) {
                                state.syncWarningsByProvider - providerId
                            } else state.syncWarningsByProvider + (providerId to updated))
                        }
                        if (receipt.partial || warnings.isNotEmpty()) failures += warnings
                        if (!receipt.partial) {
                            completed += if (receipt.queued) {
                                appContext.getString(R.string.settings_sync_section_queued, sectionLabel)
                            } else sectionLabel
                        }
                    }
                }
            }
            uiState.update {
                it.copy(userMessage = when {
                    failures.isEmpty() -> appContext.getString(
                        R.string.settings_sync_sections_success, completed.joinToString()
                    )
                    completed.isEmpty() -> appContext.getString(
                        R.string.settings_sync_sections_failed, failures.joinToString()
                    )
                    else -> appContext.getString(
                        R.string.settings_sync_sections_partial, completed.joinToString(), failures.joinToString()
                    )
                })
            }
        } catch (e: CancellationException) {
            uiState.update {
                it.copy(userMessage = appContext.getString(if (hasQueuedWork) {
                    R.string.settings_sync_stopped_queued
                } else R.string.settings_sync_cancelled))
            }
            throw e
        } catch (e: Exception) {
            uiState.update { it.copy(userMessage = appContext.getString(R.string.settings_sync_failed, e.message.orEmpty())) }
        } finally {
            uiState.update {
                it.copy(
                    isSyncing = false, syncProgress = null, syncingProviderName = null,
                    syncStartedAt = 0L, syncSectionLabel = null, syncCanCancel = false
                )
            }
        }
    }
}
