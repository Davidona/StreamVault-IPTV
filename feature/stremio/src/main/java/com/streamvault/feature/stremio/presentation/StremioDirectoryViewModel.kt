package com.streamvault.feature.stremio.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.stremio.*
import com.streamvault.domain.repository.StremioAddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class StremioDirectorySource(val url: String, val name: String, val catalog: StremioCatalogRef) {
    val key get() = "$url:${catalog.settingsKey}"
}
data class StremioDirectoryState(
    val sources: List<StremioDirectorySource> = emptyList(),
    val selectedKey: String? = null,
    val entries: List<StremioDirectoryEntry> = emptyList(),
    val installedUrls: Set<String> = emptySet(),
    val loading: Boolean = true,
    val failed: Boolean = false
)
@HiltViewModel
class StremioDirectoryViewModel @Inject constructor(
    private val repository: StremioAddonRepository,
    private val contentAccess: StremioContentAccess
) : ViewModel() {
    private val mutableState = MutableStateFlow(StremioDirectoryState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    init {
        val defaultUrl = "https://v3-cinemeta.strem.io/manifest.json"
        val bootstrap = MutableStateFlow<com.streamvault.domain.model.Result<StremioManifest>?>(null)
        viewModelScope.launch {
            if (!contentAccess.canAccessContentNow()) return@launch
            bootstrap.value = try {
                withTimeout(8_000) { repository.inspectAddon(defaultUrl) }
            } catch (_: TimeoutCancellationException) {
                com.streamvault.domain.model.Result.error("Addon request failed")
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { com.streamvault.domain.model.Result.error("Addon request failed") }
        }
        viewModelScope.launch {
            combine(repository.observeAddons(), bootstrap) { addons, default -> addons to default }
                .collectLatest { (addons, default) ->
                if (!contentAccess.canAccessContentNow()) {
                    mutableState.update { it.copy(loading = false, entries = emptyList()) }
                    return@collectLatest
                }
                val manifests = buildList {
                    default?.getOrNull()?.let { add(defaultUrl to it) }
                    for (addon in addons.filter { it.enabled }) {
                        repository.getManifest(addon.id)?.let { add(addon.installUrl to it) }
                    }
                }
                val sources = manifests.flatMap { (url, manifest) ->
                    manifest.addonCatalogs.map { catalog -> StremioDirectorySource(url,
                        listOf(manifest.name, catalog.name ?: catalog.id, catalog.type.typeName).joinToString(" · "), catalog) }
                }.distinctBy { it.key }
                val previousKey = state.value.selectedKey
                mutableState.update { current ->
                    current.copy(sources = sources, installedUrls = addons.map { it.installUrl }.toSet(),
                        selectedKey = current.selectedKey?.takeIf { key -> sources.any { it.key == key } }
                            ?: sources.firstOrNull()?.key)
                }
                if (sources.isEmpty() && default == null) {
                    mutableState.update { it.copy(loading = true) }
                } else if (sources.isEmpty()) {
                    mutableState.update { it.copy(loading = false, failed = default?.getOrNull() == null) }
                } else if (previousKey != state.value.selectedKey || state.value.entries.isEmpty()) {
                    load()
                }
            }
        }
    }
    fun select(key: String) {
        if (state.value.sources.none { it.key == key }) return
        mutableState.update { it.copy(selectedKey = key) }
        load()
    }
    fun load() {
        request?.cancel()
        val version = ++generation
        val source = state.value.sources.firstOrNull { it.key == state.value.selectedKey }
        if (source == null || !contentAccess.canAccessContentNow()) {
            mutableState.update { it.copy(loading = false) }; return
        }
        mutableState.update { it.copy(loading = true, failed = false, entries = emptyList()) }
        request = viewModelScope.launch {
            try {
                val result = repository.fetchAddonDirectory(source.url, source.catalog)
                if (generation == version) mutableState.update {
                    it.copy(loading = false, failed = result.getOrNull() == null, entries = result.getOrNull().orEmpty())
                }
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) {
                if (generation == version) mutableState.update { it.copy(loading = false, failed = true) }
            }
        }
    }
}
