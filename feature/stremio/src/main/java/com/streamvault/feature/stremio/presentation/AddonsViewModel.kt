package com.streamvault.feature.stremio.presentation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.StremioAddon
import com.streamvault.domain.model.stremio.StremioCatalogRef
import com.streamvault.domain.repository.StremioAddonRepository
import com.streamvault.feature.stremio.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AddonsViewModel @Inject constructor(
    private val repository: StremioAddonRepository
) : ViewModel() {

    data class AddonEntry(
        val addon: StremioAddon,
        val catalogs: List<StremioCatalogRef>
    )

    data class UiState(
        val loading: Boolean = true,
        val addons: List<AddonEntry> = emptyList(),
        val installDialogVisible: Boolean = false,
        val installUrl: String = "",
        val installing: Boolean = false,
        val expandedAddonId: Long? = null,
        @StringRes val statusMessageRes: Int? = null,
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAddons().collect { addons ->
                val entries = addons.map { addon ->
                    AddonEntry(
                        addon = addon,
                        catalogs = repository.getManifest(addon.id)?.catalogs.orEmpty()
                    )
                }
                _state.update { it.copy(loading = false, addons = entries) }
            }
        }
    }

    fun showInstallDialog() {
        _state.update { it.copy(installDialogVisible = true, errorMessage = null) }
    }

    fun dismissInstallDialog() {
        _state.update { it.copy(installDialogVisible = false, installUrl = "", installing = false) }
    }

    fun updateInstallUrl(value: String) {
        _state.update { it.copy(installUrl = value, errorMessage = null) }
    }

    fun install() {
        val url = _state.value.installUrl.trim()
        if (url.isEmpty() || _state.value.installing) return
        _state.update { it.copy(installing = true, errorMessage = null) }
        viewModelScope.launch {
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
                    it.copy(installing = false, errorMessage = result.message)
                }
                Result.Loading -> Unit
            }
        }
    }

    fun setEnabled(entry: AddonEntry, enabled: Boolean) {
        viewModelScope.launch {
            repository.setAddonEnabled(entry.addon.id, enabled).errorMessageOrNull()?.let { message ->
                _state.update { it.copy(errorMessage = message) }
            }
        }
    }

    fun removeAddon(entry: AddonEntry) {
        viewModelScope.launch {
            repository.removeAddon(entry.addon.id).errorMessageOrNull()?.let { message ->
                _state.update { it.copy(errorMessage = message) }
            } ?: _state.update {
                it.copy(
                    expandedAddonId = it.expandedAddonId.takeUnless { id -> id == entry.addon.id },
                    statusMessageRes = R.string.stremio_status_removed
                )
            }
        }
    }

    fun moveAddon(entry: AddonEntry, offset: Int) {
        viewModelScope.launch {
            repository.moveAddon(entry.addon.id, offset).errorMessageOrNull()?.let { message ->
                _state.update { it.copy(errorMessage = message) }
            }
        }
    }

    fun toggleExpanded(addonId: Long) {
        _state.update {
            it.copy(expandedAddonId = if (it.expandedAddonId == addonId) null else addonId)
        }
    }

    fun setCatalogEnabled(entry: AddonEntry, catalogKey: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setCatalogEnabled(entry.addon.id, catalogKey, enabled).errorMessageOrNull()
                ?.let { message -> _state.update { it.copy(errorMessage = message) } }
        }
    }

    fun consumeStatusMessage() {
        _state.update { it.copy(statusMessageRes = null) }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
