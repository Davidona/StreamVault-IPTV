package com.streamvault.feature.stremio.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.stremio.*
import com.streamvault.domain.repository.StremioAddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.*
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class StremioGuideCatalog(
    val addonId: Long, val addonName: String, val catalog: StremioCatalogRef,
    val locked: Boolean = false, val providerId: Long? = null, val protectedIds: Set<Long> = emptySet()
) {
    val key get() = "$addonId:${catalog.settingsKey}"
}
data class StremioGuideState(
    val catalogs: List<StremioGuideCatalog> = emptyList(),
    val selectedKey: String? = null,
    val date: LocalDate = LocalDate.now(),
    val channels: List<StremioMetaDetail> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false
)

internal fun guideUtcDates(date: LocalDate, zone: ZoneId): List<LocalDate> {
    val start = date.atStartOfDay(zone).toInstant()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)
    val first = start.atZone(ZoneOffset.UTC).toLocalDate()
    val last = end.atZone(ZoneOffset.UTC).toLocalDate()
    return generateSequence(first) { it.plusDays(1).takeIf { next -> next <= last } }.toList()
}

internal fun mergeGuideChannels(
    pages: List<StremioMetaDetail>, date: LocalDate, zone: ZoneId
): List<StremioMetaDetail> {
    val start = date.atStartOfDay(zone).toInstant()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant()
    return pages.groupBy { it.type to it.id }.values.map { copies ->
        copies.first().copy(videos = copies.flatMap { it.videos }.filter { video ->
            val from = runCatching { Instant.parse(video.startTime) }.getOrNull()
            val to = runCatching { Instant.parse(video.endTime) }.getOrNull()
            from != null && to != null && to > from && to > start && from < end
        }.distinctBy { Triple(it.id, it.startTime, it.endTime) }.sortedBy { Instant.parse(it.startTime) })
    }
}

@HiltViewModel
class StremioGuideViewModel @Inject constructor(
    private val repository: StremioAddonRepository,
    private val contentAccess: StremioContentAccess,
    private val parentalAccess: StremioCatalogParentalAccess
) : ViewModel() {
    private val mutableState = MutableStateFlow(StremioGuideState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    private val unlocked = mutableSetOf<String>()
    private var parentalProvider: Long? = null
    init {
        viewModelScope.launch {
            combine(repository.observeAddons(), parentalAccess.state) { addons, parental -> addons to parental }
                .collectLatest { (addons, parental) ->
                    if (parentalProvider != parental.providerId) {
                        unlocked.clear()
                        parentalProvider = parental.providerId
                    }
                    val catalogs = buildList {
                        for (addon in addons.filter { it.enabled }) {
                            val manifest = repository.getManifest(addon.id) ?: continue
                            if (!manifest.behaviorHints.epgProvider) continue
                            for (catalog in manifest.catalogs) {
                                val access = parental.accessFor(catalog)
                                if (catalog.type == StremioMediaType.TV && addon.isCatalogEnabled(catalog.settingsKey) &&
                                    catalog.extras.any { it.name == "date" } && !access.hidden
                                ) {
                                    val key = "${addon.id}:${catalog.settingsKey}"
                                    add(StremioGuideCatalog(addon.id, addon.name, catalog,
                                        access.locked && key !in unlocked, access.providerId, access.protectedCategoryIds))
                                }
                            }
                        }
                    }
                    mutableState.update { current ->
                        current.copy(catalogs = catalogs,
                            selectedKey = current.selectedKey?.takeIf { key -> catalogs.any { it.key == key && !it.locked } }
                                ?: catalogs.firstOrNull { !it.locked }?.key)
                    }
                    load()
                }
        }
    }
    fun select(key: String) {
        if (state.value.catalogs.none { it.key == key && !it.locked }) return
        mutableState.update { it.copy(selectedKey = key) }
        load()
    }
    suspend fun unlock(key: String, pin: String): Boolean {
        if (!contentAccess.canAccessContentNow() || !parentalAccess.verifyPin(pin)) return false
        val catalog = state.value.catalogs.firstOrNull { it.key == key } ?: return false
        unlocked += key
        catalog.providerId?.let { parentalAccess.unlockCategories(it, catalog.protectedIds) }
        mutableState.update { it.copy(catalogs = it.catalogs.map { row -> if (row.key == key) row.copy(locked = false) else row },
            selectedKey = key) }
        load()
        return true
    }
    fun setDate(date: LocalDate) {
        mutableState.update { it.copy(date = date) }
        load()
    }
    fun load() {
        request?.cancel()
        val version = ++generation
        val selected = state.value.catalogs.firstOrNull { it.key == state.value.selectedKey }
        if (selected == null || selected.locked || !contentAccess.canAccessContentNow()) {
            mutableState.update { it.copy(channels = emptyList(), loading = false) }
            return
        }
        val date = state.value.date
        val zone = ZoneId.systemDefault()
        mutableState.update { it.copy(channels = emptyList(), loading = true, failed = false) }
        request = viewModelScope.launch {
            try {
                val pages = coroutineScope {
                    guideUtcDates(date, zone).map { utcDate ->
                        async {
                            val channels = mutableListOf<StremioMetaDetail>()
                            val fingerprints = mutableSetOf<List<String>>()
                            var skip = 0
                            for (pageIndex in 0 until 100) {
                                ensureActive()
                                if (!contentAccess.canAccessContentNow()) throw CancellationException()
                                val result = repository.fetchCatalog(selected.addonId, selected.catalog,
                                    StremioCatalogQuery(skip = skip.takeIf { selected.catalog.supportsSkip },
                                        extraValues = mapOf("date" to utcDate.toString())))
                                val page = result.getOrNull() ?: error("guide request failed")
                                if (page.rawItemCount > 0 && page.detailedMetas.isEmpty()) error("guide schedule missing")
                                val fingerprint = page.detailedMetas.map { it.id }
                                if (page.rawItemCount == 0 || !fingerprints.add(fingerprint)) break
                                channels += page.detailedMetas
                                if (!selected.catalog.supportsSkip || page.hasMore == false) break
                                skip += page.rawItemCount
                                if (pageIndex == 99) error("guide page limit")
                            }
                            channels
                        }
                    }.awaitAll().flatten()
                }
                if (version == generation) mutableState.update {
                    it.copy(channels = mergeGuideChannels(pages, date, zone), loading = false)
                }
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) {
                if (version == generation) mutableState.update { it.copy(loading = false, failed = true) }
            }
        }
    }
}
