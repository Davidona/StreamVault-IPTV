package com.streamvault.feature.settings.presentation

import com.streamvault.domain.model.Result
import com.streamvault.domain.model.XmltvTimezonePolicy
import com.streamvault.domain.repository.EpgSourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class SettingsEpgActions(
    private val epgSourceRepository: EpgSourceRepository,
    private val uiState: MutableStateFlow<SettingsUiState>
) {
    // Tracks the active assignment-collector job per provider so recompositions
    // cancel the old coroutine rather than leaking it in viewModelScope.
    private val assignmentJobs = ConcurrentHashMap<Long, Job>()

    fun loadEpgAssignments(scope: CoroutineScope, providerId: Long) {
        assignmentJobs[providerId]?.cancel()
        assignmentJobs[providerId] = scope.launch {
            try {
                epgSourceRepository.getAssignmentsForProvider(providerId).collect { assignments ->
                    val summary = epgSourceRepository.getResolutionSummary(providerId)
                    uiState.update {
                        it.copy(
                            epgSourceAssignments = it.epgSourceAssignments + (providerId to assignments),
                            epgResolutionSummaries = it.epgResolutionSummaries + (providerId to summary)
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, "Failed to load EPG assignments")
            }
        }
    }

    fun addEpgSource(
        scope: CoroutineScope,
        name: String,
        url: String,
        timezoneId: String?,
        onSuccess: () -> Unit = {},
        onError: () -> Unit = {}
    ) {
        scope.launch {
            try {
                val normalizedTimezoneId = timezoneId?.trim()?.takeIf(String::isNotEmpty)
                val timezonePolicy = when {
                    normalizedTimezoneId == null -> XmltvTimezonePolicy.REQUIRE_OFFSET
                    normalizedTimezoneId.equals("UTC", ignoreCase = true) -> XmltvTimezonePolicy.UTC
                    else -> XmltvTimezonePolicy.EXPLICIT_ZONE
                }
                val result = epgSourceRepository.addSource(
                    name = name,
                    url = url,
                    timezonePolicy = timezonePolicy,
                    timezoneId = normalizedTimezoneId
                )
                if (result is Result.Error) {
                    uiState.update { it.copy(userMessage = result.message) }
                    onError()
                } else {
                    onSuccess()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, "Failed to add EPG source")
                onError()
            }
        }
    }

    fun updateEpgSourceTimezone(
        scope: CoroutineScope,
        source: com.streamvault.domain.model.EpgSource,
        timezoneId: String?,
        onSuccess: () -> Unit = {},
        onError: () -> Unit = {}
    ) {
        scope.launch {
            try {
                val normalizedTimezoneId = timezoneId?.trim()?.takeIf(String::isNotEmpty)
                val timezonePolicy = when {
                    normalizedTimezoneId == null -> XmltvTimezonePolicy.REQUIRE_OFFSET
                    normalizedTimezoneId.equals("UTC", ignoreCase = true) -> XmltvTimezonePolicy.UTC
                    else -> XmltvTimezonePolicy.EXPLICIT_ZONE
                }
                val updateResult = epgSourceRepository.updateSource(
                    source.copy(
                        timezonePolicy = timezonePolicy,
                        timezoneId = normalizedTimezoneId
                    )
                )
                if (updateResult is Result.Error) {
                    uiState.update { it.copy(userMessage = updateResult.message) }
                    onError()
                    return@launch
                }

                val affectedProviders = epgSourceRepository.getProviderIdsForSource(source.id)
                val refreshResult = epgSourceRepository.refreshSource(source.id)
                if (refreshResult is Result.Error) {
                    uiState.update {
                        it.copy(userMessage = "Timezone saved, but refresh failed: ${refreshResult.message}")
                    }
                    onError()
                } else {
                    refreshLoadedResolutionSummaries(affectedProviders)
                    uiState.update { it.copy(userMessage = "EPG timezone updated") }
                    onSuccess()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, "Failed to update EPG timezone")
                onError()
            }
        }
    }

    fun deleteEpgSource(scope: CoroutineScope, sourceId: Long) {
        scope.launch {
            try {
                uiState.update { it.copy(epgPendingDeleteSourceId = null) }
                // Query from the repository BEFORE deletion so we capture all affected
                // providers, not just those already loaded into the UI state.
                val affectedProviders = epgSourceRepository.getProviderIdsForSource(sourceId)
                epgSourceRepository.deleteSource(sourceId)
                refreshLoadedResolutionSummaries(affectedProviders)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, "Failed to delete EPG source")
            }
        }
    }

    fun toggleEpgSourceEnabled(scope: CoroutineScope, sourceId: Long, enabled: Boolean) {
        scope.launch {
            try {
                // Query from the repository so all assigned providers are refreshed regardless
                // of which ones have been opened in the UI.
                val affectedProviders = epgSourceRepository.getProviderIdsForSource(sourceId)
                epgSourceRepository.setSourceEnabled(sourceId, enabled)
                refreshLoadedResolutionSummaries(affectedProviders)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, "Failed to update EPG source")
            }
        }
    }

    fun refreshEpgSource(scope: CoroutineScope, sourceId: Long) {
        scope.launch {
            uiState.update { it.copy(refreshingEpgSourceIds = it.refreshingEpgSourceIds + sourceId) }
            try {
                // Query from the repository BEFORE refresh so all assigned providers are
                // updated, not just those already loaded into the UI state.
                val affectedProviders = epgSourceRepository.getProviderIdsForSource(sourceId)
                val result = epgSourceRepository.refreshSource(sourceId)
                if (result !is Result.Error) {
                    refreshLoadedResolutionSummaries(affectedProviders)
                }
                uiState.update {
                    it.copy(
                        refreshingEpgSourceIds = it.refreshingEpgSourceIds - sourceId,
                        userMessage = if (result is Result.Error) result.message else "EPG source refreshed"
                    )
                }
            } catch (cancelled: CancellationException) {
                uiState.update { it.copy(refreshingEpgSourceIds = it.refreshingEpgSourceIds - sourceId) }
                throw cancelled
            } catch (error: Exception) {
                uiState.update { it.copy(refreshingEpgSourceIds = it.refreshingEpgSourceIds - sourceId) }
                showUnexpectedError(error, "Failed to refresh EPG source")
            }
        }
    }

    fun assignEpgSourceToProvider(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            try {
                val existingAssignments = uiState.value.epgSourceAssignments[providerId].orEmpty()
                val nextPriority = (existingAssignments.maxOfOrNull { it.priority } ?: 0) + 1
                val result = epgSourceRepository.assignSourceToProvider(providerId, epgSourceId, nextPriority)
                if (result is Result.Error) {
                    uiState.update { it.copy(userMessage = result.message) }
                } else {
                    refreshProviderEpgSummary(providerId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, "Failed to assign EPG source")
            }
        }
    }

    fun unassignEpgSourceFromProvider(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            try {
                epgSourceRepository.unassignSourceFromProvider(providerId, epgSourceId)
                refreshProviderEpgSummary(providerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, "Failed to remove EPG source")
            }
        }
    }

    fun moveEpgSourceAssignmentUp(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            try {
                val assignments = uiState.value.epgSourceAssignments[providerId].orEmpty().sortedBy { it.priority }
                val index = assignments.indexOfFirst { it.epgSourceId == epgSourceId }
                if (index <= 0) return@launch
                val current = assignments[index]
                val previous = assignments[index - 1]
                epgSourceRepository.swapAssignmentPriorities(
                    providerId,
                    current.epgSourceId, previous.priority,
                    previous.epgSourceId, current.priority
                )
                refreshProviderEpgSummary(providerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, "Failed to reorder EPG source")
            }
        }
    }

    fun moveEpgSourceAssignmentDown(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            try {
                val assignments = uiState.value.epgSourceAssignments[providerId].orEmpty().sortedBy { it.priority }
                val index = assignments.indexOfFirst { it.epgSourceId == epgSourceId }
                if (index == -1 || index >= assignments.lastIndex) return@launch
                val current = assignments[index]
                val next = assignments[index + 1]
                epgSourceRepository.swapAssignmentPriorities(
                    providerId,
                    current.epgSourceId, next.priority,
                    next.epgSourceId, current.priority
                )
                refreshProviderEpgSummary(providerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, "Failed to reorder EPG source")
            }
        }
    }

    private suspend fun refreshProviderEpgSummary(providerId: Long) {
        val summary = epgSourceRepository.getResolutionSummary(providerId)
        uiState.update {
            it.copy(epgResolutionSummaries = it.epgResolutionSummaries + (providerId to summary))
        }
    }

    /**
     * Cancels the assignment collector job for each provider in [removedIds] and purges their
     * entries from [SettingsUiState]. Called when the provider list shrinks so that the
     * [assignmentJobs] map doesn't grow unboundedly for providers that no longer exist.
     */
    fun cleanupEpgAssignmentsFor(removedIds: Set<Long>) {
        for (id in removedIds) {
            assignmentJobs.remove(id)?.cancel()
        }
        uiState.update { state ->
            state.copy(
                epgSourceAssignments = state.epgSourceAssignments - removedIds,
                epgResolutionSummaries = state.epgResolutionSummaries - removedIds
            )
        }
    }

    private fun loadedProvidersForSource(sourceId: Long): List<Long> =
        uiState.value.epgSourceAssignments
            .filterValues { assignments -> assignments.any { it.epgSourceId == sourceId } }
            .keys
            .toList()

    private suspend fun refreshLoadedResolutionSummaries(providerIds: Iterable<Long>) {
        providerIds.asSequence().distinct().forEach { providerId ->
            refreshProviderEpgSummary(providerId)
        }
    }

    private fun showUnexpectedError(error: Exception, fallbackMessage: String) {
        uiState.update { it.copy(userMessage = error.message?.takeIf(String::isNotBlank) ?: fallbackMessage) }
    }
}
