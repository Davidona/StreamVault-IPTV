package com.streamvault.feature.settings.presentation

import com.streamvault.feature.settings.api.SettingsSurfaceRefreshPort
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.usecase.SyncProvider
import com.streamvault.domain.usecase.SyncProviderCommand
import com.streamvault.domain.usecase.SyncProviderResult
import com.streamvault.domain.util.PersistedTimestampPolicy
import com.streamvault.data.sync.ProviderSyncCommands
import com.streamvault.data.preferences.PreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours

internal fun shouldAutoSyncProvider(
    lastSyncedAt: Long,
    now: Long,
    staleAfterMillis: Long
): Boolean = !PersistedTimestampPolicy.isFresh(lastSyncedAt, now, staleAfterMillis)

internal class SettingsProviderActions(
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val preferencesRepository: PreferencesRepository,
    private val syncProvider: SyncProvider,
    private val syncManager: ProviderSyncCommands,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val surfaceRefreshPort: SettingsSurfaceRefreshPort,
    private val uiState: MutableStateFlow<SettingsUiState>
) {
    private companion object {
        val AUTO_SWITCH_SYNC_STALE_AFTER_MS = 24.hours.inWholeMilliseconds
    }

    fun setActiveProvider(scope: CoroutineScope, providerId: Long) {
        scope.launch {
            // Validate the provider exists before writing any preferences.
            val provider = providerRepository.getProvider(providerId)
            if (provider == null) {
                uiState.update { it.copy(userMessage = "Could not activate provider: provider not found") }
                return@launch
            }
            // Write to the repository first; only persist UI-layer preferences on success.
            when (val result = providerRepository.setActiveProvider(providerId)) {
                is Result.Error -> {
                    uiState.update { it.copy(userMessage = "Could not activate provider: ${result.message}") }
                    return@launch
                }
                else -> Unit
            }
            // Repository write succeeded – now persist the UI-layer preferences and refresh.
            preferencesRepository.setLastActiveProviderId(providerId)
            combinedM3uRepository.setActiveLiveSource(ActiveLiveSource.ProviderSource(providerId))
            surfaceRefreshPort.refreshWatchNext()
            surfaceRefreshPort.refreshRecommendations()
            surfaceRefreshPort.refreshTvInputCatalog()
            val shouldAutoSync = shouldAutoSyncProvider(
                lastSyncedAt = provider.lastSyncedAt,
                now = System.currentTimeMillis(),
                staleAfterMillis = AUTO_SWITCH_SYNC_STALE_AFTER_MS
            )
            if (shouldAutoSync) {
                refreshProvider(
                    scope = scope,
                    providerId = providerId,
                    syncMode = SettingsProviderSyncMode.SYNC_NOW,
                    progressPrefix = "Refreshing ${provider.name}..."
                )
            } else {
                uiState.update { it.copy(userMessage = "Connected to ${provider.name}") }
            }
        }
    }

    fun setActiveCombinedProfile(scope: CoroutineScope, profileId: Long) {
        scope.launch {
            // Validate the profile exists and has at least one enabled member before activating.
            val profile = combinedM3uRepository.getProfile(profileId)
            when {
                profile == null ->
                    uiState.update { it.copy(userMessage = "Could not activate combined source: profile not found") }
                profile.members.isEmpty() ->
                    uiState.update { it.copy(userMessage = "Add at least one playlist to this combined source before activating it") }
                profile.members.none { it.enabled } ->
                    uiState.update { it.copy(userMessage = "Enable at least one playlist in this combined source before activating it") }
                else -> when (combinedM3uRepository.setActiveLiveSource(ActiveLiveSource.CombinedM3uSource(profileId))) {
                    is Result.Success -> {
                        surfaceRefreshPort.refreshRecommendations()
                        uiState.update { it.copy(userMessage = "Combined M3U source activated") }
                    }
                    is Result.Error -> uiState.update { it.copy(userMessage = "Could not activate combined source") }
                    Result.Loading -> Unit
                }
            }
        }
    }

    fun createCombinedProfile(scope: CoroutineScope, name: String, providerIds: List<Long>, onSuccess: () -> Unit = {}, onError: () -> Unit = {}) {
        scope.launch {
            when (val result = combinedM3uRepository.createProfile(name, providerIds)) {
                is Result.Success -> {
                    combinedM3uRepository.setActiveLiveSource(ActiveLiveSource.CombinedM3uSource(result.data.id))
                    uiState.update { it.copy(userMessage = "Combined M3U source created") }
                    onSuccess()
                }
                is Result.Error -> {
                    uiState.update { it.copy(userMessage = result.message) }
                    onError()
                }
                Result.Loading -> Unit
            }
        }
    }

    fun deleteCombinedProfile(scope: CoroutineScope, profileId: Long) {
        scope.launch {
            when (val result = combinedM3uRepository.deleteProfile(profileId)) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Combined M3U source deleted") }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun addProviderToCombinedProfile(scope: CoroutineScope, profileId: Long, providerId: Long, onSuccess: () -> Unit = {}, onError: () -> Unit = {}) {
        scope.launch {
            when (val result = combinedM3uRepository.addProvider(profileId, providerId)) {
                is Result.Success -> {
                    uiState.update { it.copy(userMessage = "Playlist added to combined source") }
                    onSuccess()
                }
                is Result.Error -> {
                    uiState.update { it.copy(userMessage = result.message) }
                    onError()
                }
                Result.Loading -> Unit
            }
        }
    }

    fun renameCombinedProfile(scope: CoroutineScope, profileId: Long, name: String, onSuccess: () -> Unit = {}, onError: () -> Unit = {}) {
        scope.launch {
            when (val result = combinedM3uRepository.updateProfileName(profileId, name)) {
                is Result.Success -> {
                    uiState.update { it.copy(userMessage = "Combined M3U source renamed") }
                    onSuccess()
                }
                is Result.Error -> {
                    uiState.update { it.copy(userMessage = result.message) }
                    onError()
                }
                Result.Loading -> Unit
            }
        }
    }

    fun removeProviderFromCombinedProfile(scope: CoroutineScope, profileId: Long, providerId: Long) {
        scope.launch {
            val profile = uiState.value.combinedProfiles.firstOrNull { it.id == profileId } ?: return@launch
            val activeSource = uiState.value.activeLiveSource
            if (activeSource is ActiveLiveSource.CombinedM3uSource && activeSource.profileId == profileId) {
                val memberToRemove = profile.members.firstOrNull { it.providerId == providerId }
                val removingEnabledMember = memberToRemove?.enabled == true
                val remainingEnabled = profile.members.count { it.providerId != providerId && it.enabled }
                if (removingEnabledMember && remainingEnabled == 0) {
                    uiState.update { it.copy(userMessage = "Cannot remove the last active playlist from the current live source") }
                    return@launch
                }
            }
            when (val result = combinedM3uRepository.removeProvider(profileId, providerId)) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Playlist removed from combined source") }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun moveCombinedProvider(scope: CoroutineScope, profileId: Long, providerId: Long, moveUp: Boolean) {
        scope.launch {
            val profile = uiState.value.combinedProfiles.firstOrNull { it.id == profileId } ?: return@launch
            val orderedProviderIds = profile.members.sortedBy { it.priority }.map { it.providerId }.toMutableList()
            val currentIndex = orderedProviderIds.indexOf(providerId)
            if (currentIndex == -1) return@launch
            val targetIndex = if (moveUp) currentIndex - 1 else currentIndex + 1
            if (targetIndex !in orderedProviderIds.indices) return@launch
            java.util.Collections.swap(orderedProviderIds, currentIndex, targetIndex)
            when (val result = combinedM3uRepository.reorderMembers(profileId, orderedProviderIds)) {
                is Result.Success -> uiState.update { it.copy(userMessage = "Combined playlist order updated") }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun setCombinedProviderEnabled(scope: CoroutineScope, profileId: Long, providerId: Long, enabled: Boolean) {
        scope.launch {
            if (!enabled) {
                val profile = uiState.value.combinedProfiles.firstOrNull { it.id == profileId }
                val activeSource = uiState.value.activeLiveSource
                if (profile != null &&
                    activeSource is ActiveLiveSource.CombinedM3uSource &&
                    activeSource.profileId == profileId
                ) {
                    val remainingEnabled = profile.members.count { it.providerId != providerId && it.enabled }
                    if (remainingEnabled == 0) {
                        uiState.update { it.copy(userMessage = "Cannot disable the last active playlist in the current live source") }
                        return@launch
                    }
                }
            }
            when (val result = combinedM3uRepository.setMemberEnabled(profileId, providerId, enabled)) {
                is Result.Success -> uiState.update {
                    it.copy(userMessage = if (enabled) "Playlist enabled in combined source" else "Playlist disabled in combined source")
                }
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun setM3uVodClassificationEnabled(scope: CoroutineScope, providerId: Long, enabled: Boolean) {
        scope.launch {
            val provider = providerRepository.getProvider(providerId) ?: return@launch
            if (provider.type != ProviderType.M3U) return@launch
            when (val result = providerRepository.updateProvider(provider.copy(m3uVodClassificationEnabled = enabled))) {
                is Result.Error -> uiState.update { it.copy(userMessage = "Could not save provider setting: ${result.message}") }
                else -> uiState.update {
                    it.copy(
                        userMessage = if (enabled) {
                            "M3U VOD classification enabled. Refresh the playlist to reclassify content."
                        } else {
                            "M3U VOD classification disabled. Refresh the playlist to reclassify content."
                        }
                    )
                }
            }
        }
    }

    fun setGuideSourcePolicy(scope: CoroutineScope, providerId: Long, policy: GuideSourcePolicy) {
        scope.launch {
            val provider = providerRepository.getProvider(providerId) ?: return@launch
            when (val result = providerRepository.updateProvider(provider.copy(guideSourcePolicy = policy))) {
                is Result.Error -> uiState.update { it.copy(userMessage = "Could not save guide source: ${result.message}") }
                else -> uiState.update { it.copy(userMessage = "Guide source updated for ${provider.name}") }
            }
        }
    }

    fun setChannelLogoSourcePolicy(scope: CoroutineScope, providerId: Long, policy: ChannelLogoSourcePolicy) {
        scope.launch {
            val provider = providerRepository.getProvider(providerId) ?: return@launch
            when (val result = providerRepository.updateProvider(provider.copy(channelLogoSourcePolicy = policy))) {
                is Result.Error -> uiState.update { it.copy(userMessage = "Could not save logo source: ${result.message}") }
                else -> uiState.update { it.copy(userMessage = "Channel logo source updated for ${provider.name}") }
            }
        }
    }

    fun refreshProvider(
        scope: CoroutineScope,
        providerId: Long,
        syncMode: SettingsProviderSyncMode = SettingsProviderSyncMode.SYNC_NOW,
        progressPrefix: String? = null,
        startedAt: Long = 0L,
        sectionLabel: String? = null,
        isCancelable: Boolean = false
    ): Job {
        return scope.launch {
            val providerName = providerRepository.getProvider(providerId)?.name
            uiState.update {
                it.copy(
                    isSyncing = true,
                    syncingProviderName = providerName,
                    syncProgress = progressPrefix ?: "Preparing sync...",
                    syncStartedAt = startedAt,
                    syncSectionLabel = sectionLabel,
                    syncCanCancel = isCancelable
                )
            }
            try {
                when (syncMode) {
                    SettingsProviderSyncMode.SYNC_NOW -> runSyncNow(providerId, providerName)
                    SettingsProviderSyncMode.REBUILD_INDEX -> runRebuildIndex(providerId, providerName)
                }
            } catch (e: CancellationException) {
                uiState.update {
                    it.copy(
                        isSyncing = false,
                        syncProgress = null,
                        syncingProviderName = null,
                        syncStartedAt = 0L,
                        syncSectionLabel = null,
                        syncCanCancel = false
                    )
                }
                throw e
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
            }
        }
    }

    private suspend fun runSyncNow(providerId: Long, providerName: String?) {
        val provider = providerRepository.getProvider(providerId)
        val pendingXtreamTextRefreshGeneration = provider
            ?.takeIf { it.type == ProviderType.XTREAM_CODES }
            ?.let { currentProvider ->
                val currentGeneration = preferencesRepository.getXtreamTextImportGeneration()
                val appliedGeneration = preferencesRepository.getXtreamTextImportAppliedGeneration(currentProvider.id)
                currentGeneration.takeIf { it > appliedGeneration }
            }
        val beforeMetadata = syncMetadataRepository.getMetadata(providerId) ?: SyncMetadata(providerId)
        val result = syncProvider(
            SyncProviderCommand(
                providerId = providerId,
                force = pendingXtreamTextRefreshGeneration != null,
                movieFastSyncOverride = null,
                epgSyncModeOverride = ProviderEpgSyncMode.BACKGROUND
            ),
            onProgress = { message ->
                uiState.update { state ->
                    state.copy(
                        syncProgress = mapSyncNowProgress(message),
                        syncingProviderName = providerName
                    )
                }
            }
        )

        if (result !is SyncProviderResult.Error) {
            pendingXtreamTextRefreshGeneration?.let { generation ->
                preferencesRepository.markXtreamTextImportApplied(providerId, generation)
            }
            val afterMetadata = syncMetadataRepository.getMetadata(providerId) ?: beforeMetadata
            val liveRefreshed = afterMetadata.lastLiveSync > beforeMetadata.lastLiveSync ||
                afterMetadata.liveCount != beforeMetadata.liveCount
            val catalogRefreshed = liveRefreshed ||
                afterMetadata.lastMovieSync > beforeMetadata.lastMovieSync ||
                afterMetadata.lastSeriesSync > beforeMetadata.lastSeriesSync

            if (liveRefreshed) {
                uiState.update { state ->
                    state.copy(
                        syncProgress = "Updating TV integration...",
                        syncingProviderName = providerName
                    )
                }
                surfaceRefreshPort.refreshTvInputCatalog()
            } else if (!catalogRefreshed) {
                uiState.update { state ->
                    state.copy(
                        syncProgress = "Library already up to date.",
                        syncingProviderName = providerName
                    )
                }
            }

            uiState.update { state ->
                state.copy(
                    syncProgress = "Scheduling EPG refresh...",
                    syncingProviderName = providerName
                )
            }
            syncManager.scheduleBackgroundEpgSync(providerId)
        }

        uiState.update { state ->
            val partialWarnings = (result as? SyncProviderResult.Success)?.warnings.orEmpty()
            val warningsMessage = partialWarnings.take(3).joinToString(separator = ", ").ifBlank { "Some sections are incomplete." }
            val afterMetadata = syncMetadataRepository.getMetadata(providerId) ?: beforeMetadata
            val catalogRefreshed = afterMetadata.lastLiveSync > beforeMetadata.lastLiveSync ||
                afterMetadata.lastMovieSync > beforeMetadata.lastMovieSync ||
                afterMetadata.lastSeriesSync > beforeMetadata.lastSeriesSync ||
                afterMetadata.liveCount != beforeMetadata.liveCount
            state.copy(
                isSyncing = false,
                syncProgress = null,
                syncingProviderName = null,
                syncStartedAt = 0L,
                syncSectionLabel = null,
                syncCanCancel = false,
                userMessage = when {
                    result is SyncProviderResult.Error -> "Sync failed: ${result.message}"
                    (result as? SyncProviderResult.Success)?.isPartial == true -> "Sync completed with warnings: $warningsMessage"
                    pendingXtreamTextRefreshGeneration != null -> "Sync completed and reapplied Xtream text decoding"
                    !catalogRefreshed -> "Library already up to date"
                    else -> "Sync completed"
                },
                syncWarningsByProvider = when {
                    result is SyncProviderResult.Error -> state.syncWarningsByProvider - providerId
                    (result as? SyncProviderResult.Success)?.isPartial == true -> state.syncWarningsByProvider + (providerId to partialWarnings)
                    else -> state.syncWarningsByProvider - providerId
                }
            )
        }
    }

    private suspend fun runRebuildIndex(providerId: Long, providerName: String?) {
        val provider = providerRepository.getProvider(providerId)
        val pendingXtreamTextRefreshGeneration = provider
            ?.takeIf { it.type == ProviderType.XTREAM_CODES }
            ?.let { currentProvider ->
                val currentGeneration = preferencesRepository.getXtreamTextImportGeneration()
                val appliedGeneration = preferencesRepository.getXtreamTextImportAppliedGeneration(currentProvider.id)
                currentGeneration.takeIf { it > appliedGeneration }
            }
        val result = if (provider?.type == ProviderType.XTREAM_CODES) {
            syncManager.rebuildXtreamIndex(providerId) { message ->
                uiState.update { state ->
                    state.copy(syncProgress = message, syncingProviderName = providerName)
                }
            }
        } else {
            val syncResult = syncProvider(
                SyncProviderCommand(
                    providerId = providerId,
                    force = true,
                    movieFastSyncOverride = null,
                    epgSyncModeOverride = null
                ),
                onProgress = { message ->
                    uiState.update { state ->
                        state.copy(syncProgress = message, syncingProviderName = providerName)
                    }
                }
            )
            when (syncResult) {
                is SyncProviderResult.Success -> Result.success(Unit)
                is SyncProviderResult.Error -> Result.error(syncResult.message, syncResult.exception)
            }
        }
        if (result !is Result.Error) {
            pendingXtreamTextRefreshGeneration?.let { generation ->
                preferencesRepository.markXtreamTextImportApplied(providerId, generation)
            }
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
                    result is Result.Error -> "Rebuild index failed: ${result.message}"
                    else -> "Index rebuild queued"
                },
                syncWarningsByProvider = when {
                    result is Result.Error -> state.syncWarningsByProvider - providerId
                    else -> state.syncWarningsByProvider - providerId
                }
            )
        }
    }

    private fun mapSyncNowProgress(message: String): String = when (message) {
        "Downloading Movies..." -> "Checking Movies..."
        "Downloading Series..." -> "Checking Series..."
        else -> message
    }

    fun deleteProvider(scope: CoroutineScope, providerId: Long, onSuccess: () -> Unit = {}) {
        scope.launch {
            uiState.update {
                it.copy(
                    isDeletingProvider = true,
                    deleteProviderProgressMessage = "Preparing provider deletion...",
                    deleteProviderProgressFraction = 0f
                )
            }
            when (val result = providerRepository.deleteProvider(providerId) { progress ->
                uiState.update {
                    it.copy(
                        deleteProviderProgressMessage = progress.message,
                        deleteProviderProgressFraction = progress.fraction
                    )
                }
            }) {
                is Result.Success -> {
                    val outcome = result.data
                    uiState.update {
                        it.copy(
                            isDeletingProvider = false,
                            deleteProviderProgressMessage = null,
                            deleteProviderProgressFraction = null,
                            userMessage = if (outcome.cleanupPending) {
                                "Provider library deleted; final cleanup continues"
                            } else {
                                "Provider deleted"
                            }
                        )
                    }
                    onSuccess()
                    runCatching { surfaceRefreshPort.refreshWatchNext() }
                    runCatching { surfaceRefreshPort.refreshRecommendations() }
                    runCatching { surfaceRefreshPort.refreshTvInputCatalog() }
                }
                is Result.Error -> uiState.update {
                    it.copy(
                        isDeletingProvider = false,
                        deleteProviderProgressMessage = null,
                        deleteProviderProgressFraction = null,
                        userMessage = "Could not delete provider: ${result.message}"
                    )
                }
                Result.Loading -> Unit
            }
        }
    }
}

enum class SettingsProviderSyncMode {
    SYNC_NOW,
    REBUILD_INDEX
}
