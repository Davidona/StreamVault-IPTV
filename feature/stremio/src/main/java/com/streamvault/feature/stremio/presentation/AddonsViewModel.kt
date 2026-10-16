package com.streamvault.feature.stremio.presentation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.StremioAddon
import com.streamvault.domain.model.stremio.StremioPlaybackHistory
import com.streamvault.domain.model.stremio.StremioCatalogRef
import com.streamvault.domain.model.stremio.StremioManifest
import com.streamvault.domain.model.stremio.StremioResourceKind
import com.streamvault.domain.repository.StremioAddonRepository
import com.streamvault.domain.repository.StremioPlaybackHistoryRepository
import com.streamvault.feature.stremio.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AddonsViewModel @Inject constructor(
    private val repository: StremioAddonRepository,
    private val historyRepository: StremioPlaybackHistoryRepository,
    private val contentAccess: StremioContentAccess
) : ViewModel() {

    data class AddonEntry(
        val addon: StremioAddon,
        val catalogs: List<StremioCatalogRef>,
        val manifest: StremioManifest? = null
    )

    data class UiState(
        val loading: Boolean = true,
        val addons: List<AddonEntry> = emptyList(),
        val continueWatching: List<StremioPlaybackHistory> = emptyList(),
        val installDialogVisible: Boolean = false,
        val installUrl: String = "",
        val installing: Boolean = false,
        val expandedAddonId: Long? = null,
        val refreshingAddonId: Long? = null,
        val busyAddonIds: Set<Long> = emptySet(),
        @param:StringRes val statusMessageRes: Int? = null,
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAddons()
                .catch { error ->
                    _state.update {
                        it.copy(loading = false, errorMessage = safeAddonError(error.message ?: "Add-ons could not be loaded"))
                    }
                }
                .collect { addons ->
                    val entries = addons.map { addon ->
                        val manifest = try {
                            repository.getManifest(addon.id)
                        } catch (cancellation: kotlinx.coroutines.CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            _state.update { it.copy(errorMessage = safeAddonError(error.message ?: "Add-on details could not be loaded")) }
                            null
                        }
                        AddonEntry(addon, manifest?.catalogs.orEmpty(), manifest)
                    }
                    _state.update { it.copy(loading = false, addons = entries) }
                }
        }
        viewModelScope.launch {
            historyRepository.observeContinueWatching()
                .catch { _state.update { state -> state.copy(errorMessage = safeAddonError(it.message ?: "History could not be loaded")) } }
                .collect { history -> _state.update { it.copy(continueWatching = history) } }
        }
    }

    fun showInstallDialog() {
        if (!contentAccess.canAccessContentNow()) return
        _state.update { it.copy(installDialogVisible = true, errorMessage = null) }
    }

    fun dismissInstallDialog() {
        _state.update { it.copy(installDialogVisible = false, installUrl = "", installing = false) }
    }

    fun updateInstallUrl(value: String) {
        _state.update { it.copy(installUrl = value, errorMessage = null) }
    }

    fun install() {
        if (!contentAccess.canAccessContentNow()) return
        val url = _state.value.installUrl.trim()
        if (url.isEmpty() || _state.value.installing) return
        _state.update { it.copy(installing = true, errorMessage = null) }
        viewModelScope.launch {
            if (!contentAccess.canAccessContentNow()) {
                _state.update { it.copy(installing = false) }
                return@launch
            }
            try {
                when (val result = repository.installAddon(url)) {
                is Result.Success -> _state.update {
                    it.copy(
                        installing = false,
                        installDialogVisible = false,
                        installUrl = "",
                        statusMessageRes = R.string.stremio_status_installed
                    )
                }
                is Result.Error -> _state.update {
                    it.copy(installing = false, errorMessage = safeAddonError(result.message, url))
                }
                Result.Loading -> _state.update { it.copy(installing = false, errorMessage = "Addon installation did not complete") }
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _state.update {
                    it.copy(installing = false, errorMessage = safeAddonError(error.message ?: "Addon could not be installed", url))
                }
            }
        }
    }

    fun refreshAddon(entry: AddonEntry) {
        val id = entry.addon.id
        if (id in _state.value.busyAddonIds) return
        _state.update {
            it.copy(refreshingAddonId = id, busyAddonIds = it.busyAddonIds + id, errorMessage = null)
        }
        viewModelScope.launch {
            try {
                when (val result = repository.refreshAddon(id)) {
                    is Result.Success -> _state.update { it.copy(statusMessageRes = R.string.stremio_status_refreshed) }
                    is Result.Error -> _state.update { it.copy(errorMessage = safeAddonError(result.message, entry.addon.installUrl)) }
                    Result.Loading -> _state.update { it.copy(errorMessage = "Add-on refresh did not complete") }
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _state.update {
                    it.copy(errorMessage = safeAddonError(error.message ?: "Add-on manifest could not be refreshed", entry.addon.installUrl))
                }
            } finally {
                _state.update { it.copy(refreshingAddonId = null, busyAddonIds = it.busyAddonIds - id) }
            }
        }
    }

    fun setEnabled(entry: AddonEntry, enabled: Boolean) {
        performAddonAction(entry, action = { repository.setAddonEnabled(entry.addon.id, enabled) })
    }

    fun removeAddon(entry: AddonEntry) {
        performAddonAction(
            entry = entry,
            action = { repository.removeAddon(entry.addon.id) },
            onSuccess = { state ->
                state.copy(
                    expandedAddonId = state.expandedAddonId.takeUnless { id -> id == entry.addon.id },
                    statusMessageRes = R.string.stremio_status_removed
                )
            }
        )
    }

    fun moveAddon(entry: AddonEntry, offset: Int) {
        performAddonAction(entry, action = { repository.moveAddon(entry.addon.id, offset) })
    }

    fun toggleExpanded(addonId: Long) {
        _state.update {
            it.copy(expandedAddonId = if (it.expandedAddonId == addonId) null else addonId)
        }
    }

    fun setCatalogEnabled(entry: AddonEntry, catalogKey: String, enabled: Boolean) {
        performAddonAction(
            entry,
            action = { repository.setCatalogEnabled(entry.addon.id, catalogKey, enabled) }
        )
    }

    fun consumeStatusMessage() {
        _state.update { it.copy(statusMessageRes = null) }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun <T> performAddonAction(
        entry: AddonEntry,
        action: suspend () -> Result<T>,
        onSuccess: (UiState) -> UiState = { it }
    ) {
        val id = entry.addon.id
        if (id in _state.value.busyAddonIds) return
        _state.update { it.copy(busyAddonIds = it.busyAddonIds + id, errorMessage = null) }
        viewModelScope.launch {
            try {
                when (val result = action()) {
                    is Result.Success -> _state.update(onSuccess)
                    is Result.Error -> _state.update { it.copy(errorMessage = safeAddonError(result.message, entry.addon.installUrl)) }
                    Result.Loading -> _state.update { it.copy(errorMessage = "Add-on action did not complete") }
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _state.update {
                    it.copy(errorMessage = safeAddonError(error.message ?: "Add-on action failed", entry.addon.installUrl))
                }
            } finally {
                _state.update { it.copy(busyAddonIds = it.busyAddonIds - id) }
            }
        }
    }

    private fun safeAddonError(message: String, installUrl: String? = null): String {
        val withoutInstallUrl = installUrl?.takeIf(String::isNotEmpty)
            ?.let { message.replace(it, "add-on endpoint", ignoreCase = true) }
            ?: message
        return withoutInstallUrl
            .replace(Regex("(?i)(?:https?://[^\\s\\\"']+|magnet:\\?[^\\s\\\"']+)"), "add-on endpoint")
            .replace(Regex("(?i)([?&](username|password|token|auth|key)=)[^&\\s]+"), "\$1<redacted>")
            .take(220)
            .ifBlank { "Add-on action failed" }
    }
}
