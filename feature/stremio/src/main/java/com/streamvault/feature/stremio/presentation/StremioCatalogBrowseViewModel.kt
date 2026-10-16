package com.streamvault.feature.stremio.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.stremio.StremioAddon
import com.streamvault.domain.model.stremio.StremioCatalogPage
import com.streamvault.domain.model.stremio.StremioCatalogQuery
import com.streamvault.domain.model.stremio.StremioCatalogRef
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioMetaPreview
import com.streamvault.feature.stremio.presentation.normalizeCategoryName
import com.streamvault.domain.repository.StremioAddonRepository
import com.streamvault.feature.stremio.R
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class StremioCatalogRowKey(val addonId: Long, val catalogKey: String)

data class StremioCatalogRowState(
    val key: StremioCatalogRowKey,
    val addonName: String,
    val addonFingerprint: String,
    val addonUpdatedAt: Long,
    val catalog: StremioCatalogRef,
    val metas: List<StremioMetaPreview> = emptyList(),
    val search: String = "",
    val genre: String = "",
    val loadedSearch: String? = null,
    val loadedGenre: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val failedPageSkip: Int? = null,
    val failedRequestBypassCache: Boolean = false,
    val nextSkip: Int = 0,
    val pageCount: Int = 0,
    val canLoadMore: Boolean = false,
    val error: String? = null,
    val blockedReason: String? = null,
    val waitingForInput: String? = null,
    val isLocked: Boolean = false,
    val protectedCategoryIds: Set<Long> = emptySet(),
    val parentalProviderId: Long? = null,
    val hiddenGenreOptions: Set<String> = emptySet(),
    val lockedGenreCategoryIds: Map<String, Set<Long>> = emptyMap(),
    val selectedGenreLocked: Boolean = false
) {
    val supportsSearch: Boolean get() = catalog.extras.any { it.name.equals("search", ignoreCase = true) }
    val searchRequired: Boolean get() = catalog.extras.any {
        it.name.equals("search", ignoreCase = true) && it.isRequired
    }
    val supportsSkip: Boolean get() = catalog.supportsSkip
    val supportsGenre: Boolean get() = catalog.supportsGenre || catalog.extras.any {
        it.name.equals("genre", ignoreCase = true)
    }
    val genreRequired: Boolean get() = catalog.extras.any {
        it.name.equals("genre", ignoreCase = true) && it.isRequired
    }
    val genreOptions: List<String> get() = catalog.extras
        .firstOrNull { it.name.equals("genre", ignoreCase = true) }
        ?.options
        ?.takeIf { it.isNotEmpty() }
        ?: catalog.genres
}

enum class StremioBrowseEmptyReason { NO_INSTALLED_ADDONS, NO_ENABLED_ADDONS, NO_ENABLED_CATALOGS }

enum class StremioCatalogSelectionNotice { ADDON_UNAVAILABLE, CATALOG_UNAVAILABLE }

data class StremioCatalogBrowseState(
    val loading: Boolean = true,
    val rows: List<StremioCatalogRowState> = emptyList(),
    val selectedCatalogKey: StremioCatalogRowKey? = null,
    val selectionNotice: StremioCatalogSelectionNotice? = null,
    val emptyReason: StremioBrowseEmptyReason? = null,
    val loadError: String? = null
)

@HiltViewModel
class StremioCatalogBrowseViewModel @Inject constructor(
    private val repository: StremioAddonRepository,
    private val contentAccess: StremioContentAccess,
    private val parentalAccess: StremioCatalogParentalAccess = NoopStremioCatalogParentalAccess,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(StremioCatalogBrowseState())
    val state: StateFlow<StremioCatalogBrowseState> = _state.asStateFlow()

    private val jobs = mutableMapOf<StremioCatalogRowKey, Job>()
    private val generations = mutableMapOf<StremioCatalogRowKey, Long>()
    private val seenPageFingerprints = mutableMapOf<StremioCatalogRowKey, MutableSet<String>>()
    private val pageCache = CatalogPageMemoryCache()
    private val requestSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val unlockedCatalogKeys = mutableSetOf<StremioCatalogRowKey>()
    private val unlockedGenreKeys = mutableSetOf<Pair<StremioCatalogRowKey, String>>()
    private var currentParentalState = StremioCatalogParentalState()
    init {
        viewModelScope.launch {
            combine(repository.observeAddons(), parentalAccess.state) { addons, parental ->
                addons to parental
            }.collectLatest { (addons, parental) -> refreshRows(addons, parental) }
        }
    }

    private suspend fun refreshRows(addons: List<StremioAddon>, parental: StremioCatalogParentalState) {
        currentParentalState = parental
        val oldRows = _state.value.rows.associateBy { it.key }
        val parentalClearedKeys = mutableSetOf<StremioCatalogRowKey>()
        val rows = buildList {
            addons.asSequence().filter { it.enabled }.forEach addonLoop@{ addon ->
                val manifest = try {
                    repository.getManifest(addon.id)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                } ?: return@addonLoop
                manifest.catalogs.forEach catalogLoop@{ catalog ->
                    if (!addon.isCatalogEnabled(catalog.settingsKey)) return@catalogLoop
                    val key = StremioCatalogRowKey(addon.id, catalog.settingsKey)
                    val catalogAccess = parental.accessFor(catalog)
                    if (catalogAccess.hidden) return@catalogLoop
                    val isCatalogLocked = catalogAccess.locked && key !in unlockedCatalogKeys
                    val addonFingerprint = fingerprint(addon.installUrl, addon.manifestJson)
                    val blockedReason = when (catalog.type) {
                        StremioMediaType.MOVIE, StremioMediaType.SERIES ->
                            catalog.extras.firstOrNull { extra ->
                                extra.isRequired && extra.name.lowercase() !in SUPPORTED_EXTRAS
                            }?.let { context.getString(R.string.stremio_catalog_filter_unsupported, it.name) }
                        else -> context.getString(R.string.stremio_catalog_type_unsupported, catalog.type.typeName)
                    }
                    val old = oldRows[key]?.takeIf { it.addonFingerprint == addonFingerprint }
                    val row = old?.let { previous ->
                            if (previous.addonUpdatedAt == addon.updatedAt) {
                                previous.copy(
                                    addonName = addon.name,
                                    addonFingerprint = addonFingerprint,
                                    addonUpdatedAt = addon.updatedAt,
                                    catalog = catalog,
                                    blockedReason = blockedReason
                                )
                            } else {
                                previous.copy(
                                    addonName = addon.name,
                                    addonFingerprint = addonFingerprint,
                                    addonUpdatedAt = addon.updatedAt,
                                    catalog = catalog,
                                    metas = emptyList(),
                                    loadedSearch = null,
                                    loadedGenre = null,
                                    loading = false,
                                    loadingMore = false,
                                    failedPageSkip = null,
                                    failedRequestBypassCache = false,
                                    nextSkip = 0,
                                    pageCount = 0,
                                    canLoadMore = false,
                                    error = null,
                                    blockedReason = blockedReason,
                                    waitingForInput = null
                                )
                            }
                        } ?: StremioCatalogRowState(
                            key = key,
                            addonName = addon.name,
                            addonFingerprint = addonFingerprint,
                            addonUpdatedAt = addon.updatedAt,
                            catalog = catalog,
                            blockedReason = blockedReason
                        )
                    val genreAccess = row.genreOptions.associateWith { genre ->
                        parental.accessForGenre(catalog, genre)
                    }
                    val selectedGenreAccess = row.genre.takeIf(String::isNotBlank)
                        ?.let { parental.accessForGenre(catalog, it) }
                        ?: StremioCatalogCategoryAccess()
                    val clearHiddenGenre = selectedGenreAccess.hidden
                    val selectedGenreLocked = !clearHiddenGenre && selectedGenreAccess.locked &&
                        !isGenreRuntimeUnlocked(key, row.genre)
                    val lockedGenreAccess = genreAccess.filter { (genre, access) ->
                        access.locked && !isGenreRuntimeUnlocked(key, genre)
                    }
                    val clearResults = isCatalogLocked || clearHiddenGenre || selectedGenreLocked
                    if (clearResults) parentalClearedKeys += key
                    val updatedGenre = if (clearHiddenGenre) "" else row.genre
                    val updatedRow = row.copy(
                        genre = updatedGenre,
                        isLocked = isCatalogLocked,
                        protectedCategoryIds = catalogAccess.protectedCategoryIds,
                        parentalProviderId = catalogAccess.providerId,
                        hiddenGenreOptions = genreAccess.filterValues { it.hidden }.keys,
                        lockedGenreCategoryIds = lockedGenreAccess
                            .mapValues { (_, access) -> access.protectedCategoryIds },
                        selectedGenreLocked = selectedGenreLocked,
                        metas = if (clearResults) emptyList() else row.metas,
                        loadedSearch = if (clearResults) null else row.loadedSearch,
                        loadedGenre = if (clearResults) null else row.loadedGenre,
                        loading = if (clearResults) false else row.loading,
                        loadingMore = if (clearResults) false else row.loadingMore,
                        nextSkip = if (clearResults) 0 else row.nextSkip,
                        pageCount = if (clearResults) 0 else row.pageCount,
                        canLoadMore = if (clearResults) false else row.canLoadMore,
                        error = if (clearResults) null else row.error,
                        waitingForInput = if (clearResults) null else row.waitingForInput
                    )
                    add(
                        if (clearHiddenGenre) updatedRow.copy(waitingForInput = missingRequiredInput(updatedRow))
                        else updatedRow
                    )
                }
            }
        }
        rows.filter { it.isLocked || it.selectedGenreLocked || it.key in parentalClearedKeys }.forEach { row ->
            jobs.remove(row.key)?.cancel()
            generations.remove(row.key)
            seenPageFingerprints.remove(row.key)
            pageCache.removeCatalog(row.addonFingerprint, row.catalog.settingsKey)
        }
        val activeKeys = rows.mapTo(mutableSetOf()) { it.key }
        val removedRows = oldRows.values.filter { it.key !in activeKeys }
        removedRows.forEach { row ->
            jobs.remove(row.key)?.cancel()
            generations.remove(row.key)
            seenPageFingerprints.remove(row.key)
            pageCache.removeCatalog(row.addonFingerprint, row.catalog.settingsKey)
        }
        val refreshedKeys = rows.mapNotNull { row ->
            row.key.takeIf {
                oldRows[it]?.let { old ->
                    old.addonFingerprint != row.addonFingerprint || old.addonUpdatedAt != row.addonUpdatedAt
                } == true
            }
        }.toSet()
        rows.forEach { row ->
            oldRows[row.key]?.takeIf { row.key in refreshedKeys }?.let { old ->
                jobs.remove(row.key)?.cancel()
                seenPageFingerprints.remove(row.key)
                pageCache.removeCatalog(old.addonFingerprint, old.catalog.settingsKey)
            }
        }
        val previousSelectedKey = _state.value.selectedCatalogKey
        val previousSelectionUnavailable = previousSelectedKey != null &&
            rows.none { it.key == previousSelectedKey && it.blockedReason == null }
        val selectedKey = previousSelectedKey
            ?.takeIf { key -> rows.any { it.key == key && it.blockedReason == null } }
            ?: rows.firstOrNull { it.blockedReason == null }?.key
            ?: rows.firstOrNull()?.key
        val previousSelectionWasHidden = previousSelectedKey?.let { key ->
            _state.value.rows.firstOrNull { it.key == key }?.catalog?.let(parental::accessFor)?.hidden
        } == true
        val selectionNotice = if (previousSelectionUnavailable && !previousSelectionWasHidden) {
            val originAddon = addons.firstOrNull { it.id == previousSelectedKey?.addonId }
            if (originAddon == null || !originAddon.enabled) {
                StremioCatalogSelectionNotice.ADDON_UNAVAILABLE
            } else {
                StremioCatalogSelectionNotice.CATALOG_UNAVAILABLE
            }
        } else {
            _state.value.selectionNotice
        }
        val emptyReason = when {
            addons.isEmpty() -> StremioBrowseEmptyReason.NO_INSTALLED_ADDONS
            addons.none { it.enabled } -> StremioBrowseEmptyReason.NO_ENABLED_ADDONS
            rows.isEmpty() -> StremioBrowseEmptyReason.NO_ENABLED_CATALOGS
            else -> null
        }
        _state.value = StremioCatalogBrowseState(
            loading = false,
            rows = rows,
            selectedCatalogKey = selectedKey,
            selectionNotice = selectionNotice,
            emptyReason = emptyReason
        )
        selectedKey?.let { key ->
            val selected = rows.firstOrNull { it.key == key } ?: return@let
            if (selected.blockedReason == null && !selected.isLocked && selected.pageCount == 0 &&
                !selected.loading && selected.error == null && selected.waitingForInput == null
            ) {
                startRequest(key, reset = true, debounceMs = 0L)
            }
        }
    }

    fun selectCatalog(key: StremioCatalogRowKey) {
        val row = _state.value.rows.firstOrNull { it.key == key } ?: return
        if (row.isLocked) return
        _state.update { it.copy(selectedCatalogKey = key, selectionNotice = null) }
        if (row.blockedReason == null && row.pageCount == 0 && !row.loading && row.error == null) {
            startRequest(key, reset = true, debounceMs = 0L)
        }
    }

    suspend fun verifyParentalPin(pin: String): Boolean = parentalAccess.verifyPin(pin)

    fun unlockCatalog(key: StremioCatalogRowKey) {
        val row = _state.value.rows.firstOrNull { it.key == key } ?: return
        if (!row.isLocked) {
            selectCatalog(key)
            return
        }
        unlockedCatalogKeys += key
        row.parentalProviderId?.takeIf { row.protectedCategoryIds.isNotEmpty() }?.let { providerId ->
            parentalAccess.unlockCategories(providerId, row.protectedCategoryIds)
            rememberUnlockedCategories(providerId, row.protectedCategoryIds)
        }
        updateRow(key) { it.copy(isLocked = false) }
        selectCatalog(key)
    }

    fun unlockGenre(key: StremioCatalogRowKey, genre: String) {
        val row = _state.value.rows.firstOrNull { it.key == key } ?: return
        val categoryIds = row.lockedGenreCategoryIds[genre].orEmpty()
        unlockedGenreKeys += key to normalizeCategoryName(genre)
        row.parentalProviderId?.takeIf { categoryIds.isNotEmpty() }?.let { providerId ->
            parentalAccess.unlockCategories(providerId, categoryIds)
            rememberUnlockedCategories(providerId, categoryIds)
        }
        updateRow(key) {
            it.copy(
                lockedGenreCategoryIds = it.lockedGenreCategoryIds - genre,
                selectedGenreLocked = it.selectedGenreLocked && !it.genre.equals(genre, ignoreCase = true)
            )
        }
    }

    private fun rememberUnlockedCategories(providerId: Long, categoryIds: Set<Long>) {
        if (currentParentalState.providerId != providerId) return
        currentParentalState = currentParentalState.copy(
            unlockedCategoryIds = currentParentalState.unlockedCategoryIds +
                categoryIds.map { kotlin.math.abs(it) }
        )
    }

    private fun isGenreRuntimeUnlocked(key: StremioCatalogRowKey, genre: String): Boolean =
        (key to normalizeCategoryName(genre)) in unlockedGenreKeys

    fun setSearch(key: StremioCatalogRowKey, search: String) {
        val row = _state.value.rows.firstOrNull { it.key == key } ?: return
        if (!row.supportsSearch || row.isLocked || row.selectedGenreLocked) return
        updateRow(key) { it.copy(search = search, error = null, waitingForInput = null) }
        startRequest(key, reset = true, debounceMs = SEARCH_DEBOUNCE_MS)
    }

    fun setGenre(key: StremioCatalogRowKey, genre: String) {
        val row = _state.value.rows.firstOrNull { it.key == key } ?: return
        if (!row.supportsGenre || row.isLocked || genre in row.hiddenGenreOptions) return
        val categoryAccess = currentParentalState.accessForGenre(row.catalog, genre)
        if (categoryAccess.hidden) return
        if (categoryAccess.locked && !isGenreRuntimeUnlocked(key, genre)) {
            generations[key] = (generations[key] ?: 0L) + 1L
            jobs.remove(key)?.cancel()
            pageCache.removeCatalog(row.addonFingerprint, row.catalog.settingsKey)
            updateRow(key) {
                it.copy(
                    genre = genre,
                    selectedGenreLocked = true,
                    lockedGenreCategoryIds = it.lockedGenreCategoryIds + (genre to categoryAccess.protectedCategoryIds),
                    metas = emptyList(),
                    loadedSearch = null,
                    loadedGenre = null,
                    loading = false,
                    loadingMore = false,
                    nextSkip = 0,
                    pageCount = 0,
                    canLoadMore = false,
                    error = null,
                    waitingForInput = null
                )
            }
            return
        }
        updateRow(key) { it.copy(genre = genre, selectedGenreLocked = false, error = null, waitingForInput = null) }
        startRequest(key, reset = true, debounceMs = 0L)
    }

    fun retry(key: StremioCatalogRowKey) {
        val row = _state.value.rows.firstOrNull { it.key == key } ?: return
        val failedSkip = row.failedPageSkip
        val bypassCache = row.failedRequestBypassCache
        if (failedSkip != null && failedSkip > 0) {
            startRequest(key, reset = false, debounceMs = 0L, bypassCache = bypassCache, skipOverride = failedSkip)
        } else {
            startRequest(key, reset = true, debounceMs = 0L, bypassCache = bypassCache)
        }
    }

    fun refresh(key: StremioCatalogRowKey) {
        startRequest(key, reset = true, debounceMs = 0L, bypassCache = true)
    }

    fun loadMore(key: StremioCatalogRowKey) {
        val row = _state.value.rows.firstOrNull { it.key == key } ?: return
        if (!row.supportsSkip || !row.canLoadMore || row.loading || row.loadingMore || row.blockedReason != null ||
            row.isLocked || row.selectedGenreLocked
        ) return
        startRequest(key, reset = false, debounceMs = 0L)
    }

    private fun startRequest(
        key: StremioCatalogRowKey,
        reset: Boolean,
        debounceMs: Long,
        bypassCache: Boolean = false,
        skipOverride: Int? = null
    ) {
        if (!contentAccess.canAccessContentNow()) return
        val row = _state.value.rows.firstOrNull { it.key == key } ?: return
        if (row.blockedReason != null || row.isLocked || row.selectedGenreLocked) return
        jobs.remove(key)?.cancel()
        val generation = (generations[key] ?: 0L) + 1L
        generations[key] = generation
        if (reset) {
            seenPageFingerprints[key] = mutableSetOf()
            updateRow(key) {
                it.copy(
                    metas = if (rowCanRequest(it)) it.metas else emptyList(),
                    loading = rowCanRequest(it),
                    loadingMore = false,
                    nextSkip = 0,
                    pageCount = 0,
                    canLoadMore = false,
                    error = null,
                    failedPageSkip = null,
                    failedRequestBypassCache = false,
                    waitingForInput = missingRequiredInput(it)
                )
            }
        } else {
            updateRow(key) { it.copy(loadingMore = true, error = null, failedPageSkip = null, failedRequestBypassCache = false) }
        }
        if (!rowCanRequest(_state.value.rows.first { it.key == key })) return
        jobs[key] = viewModelScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            requestPage(key, generation, reset, bypassCache, skipOverride)
        }
    }

    private suspend fun requestPage(
        key: StremioCatalogRowKey,
        generation: Long,
        reset: Boolean,
        bypassCache: Boolean,
        skipOverride: Int?
    ) {
        if (!contentAccess.canAccessContentNow()) return
        val row = _state.value.rows.firstOrNull { it.key == key } ?: return
        if (row.isLocked || row.selectedGenreLocked) return
        val skip = if (row.supportsSkip) (skipOverride ?: row.nextSkip) else null
        val query = StremioCatalogQuery(
            search = row.search.trim().takeIf { it.isNotEmpty() },
            genre = row.genre.trim().takeIf { it.isNotEmpty() },
            skip = skip
        )
        val cacheKey = CatalogPageCacheKey(
            addonFingerprint = row.addonFingerprint,
            catalogKey = row.catalog.settingsKey,
            search = query.search.orEmpty(),
            genre = query.genre.orEmpty(),
            skip = skip
        )
        try {
            val cachedPage = if (bypassCache) null else pageCache.get(cacheKey)
            val result = if (cachedPage != null) {
                null
            } else {
                requestSemaphore.withPermit {
                    repository.fetchCatalog(key.addonId, row.catalog, query)
                        .also { result -> result.getOrNull()?.let { pageCache.put(cacheKey, it) } }
                }
            }
            val page = cachedPage ?: result?.getOrNull()
            if (generations[key] != generation) return
            if (page == null) {
                val error = context.getString(R.string.stremio_error_catalog_load)
                updateRow(key) {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        error = error,
                        failedPageSkip = skip ?: 0,
                        failedRequestBypassCache = bypassCache,
                        canLoadMore = (skip ?: 0) > 0 || it.canLoadMore
                    )
                }
                return
            }
            applyPage(key, row, page, skip ?: 0, reset)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            if (generations[key] == generation) {
                updateRow(key) {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        error = context.getString(R.string.stremio_error_catalog_load),
                        failedPageSkip = skip ?: 0,
                        failedRequestBypassCache = bypassCache,
                        canLoadMore = (skip ?: 0) > 0 || it.canLoadMore
                    )
                }
            }
        }
    }

    private fun applyPage(
        key: StremioCatalogRowKey,
        requestedRow: StremioCatalogRowState,
        page: StremioCatalogPage,
        skip: Int,
        reset: Boolean
    ) {
        val fingerprint = buildString {
            append(page.rawItemCount)
            page.metas.forEach { append('|').append(it.type.typeName).append(':').append(it.id) }
        }
        val seen = seenPageFingerprints.getOrPut(key) { mutableSetOf() }
        val repeated = !seen.add(fingerprint)
        val current = _state.value.rows.firstOrNull { it.key == key } ?: return
        val known = if (reset) emptySet() else current.metas.mapTo(mutableSetOf()) { it.type.typeName to it.id }
        val uniqueNew = page.metas.distinctBy { it.type.typeName to it.id }
            .filterNot { (it.type.typeName to it.id) in known }
        val nextSkip = skip + page.rawItemCount
        val rawHasMore = page.hasMore ?: (page.rawItemCount >= PAGE_SIZE)
        val canLoadMore = requestedRow.supportsSkip && rawHasMore && page.rawItemCount > 0 &&
            !repeated && current.pageCount + 1 < MAX_PAGES_PER_QUERY
        updateRow(key) {
            it.copy(
                metas = if (reset) uniqueNew else it.metas + uniqueNew,
                loadedSearch = requestedRow.search.trim(),
                loadedGenre = requestedRow.genre.trim(),
                loading = false,
                loadingMore = false,
                nextSkip = nextSkip,
                pageCount = if (reset) 1 else it.pageCount + 1,
                canLoadMore = canLoadMore,
                failedPageSkip = null,
                failedRequestBypassCache = false,
                error = when {
                    repeated -> context.getString(R.string.stremio_catalog_repeated_page)
                    rawHasMore && !canLoadMore && page.rawItemCount > 0 &&
                        requestedRow.supportsSkip && current.pageCount + 1 >= MAX_PAGES_PER_QUERY ->
                        context.getString(R.string.stremio_catalog_page_limit)
                    else -> null
                }
            )
        }
    }

    private fun missingRequiredInput(row: StremioCatalogRowState): String? = when {
        row.searchRequired && row.search.isBlank() -> context.getString(R.string.stremio_catalog_waiting_search)
        row.genreRequired && row.genre.isBlank() -> context.getString(R.string.stremio_catalog_waiting_genre)
        else -> null
    }

    private fun rowCanRequest(row: StremioCatalogRowState): Boolean =
        row.blockedReason == null && missingRequiredInput(row) == null

    private fun updateRow(key: StremioCatalogRowKey, transform: (StremioCatalogRowState) -> StremioCatalogRowState) {
        _state.update { state -> state.copy(rows = state.rows.map { row -> if (row.key == key) transform(row) else row }) }
    }

    private fun fingerprint(url: String, manifestJson: String): String = MessageDigest.getInstance("SHA-256")
        .digest("stremio-addon-install\u0000$url\u0000$manifestJson".toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    override fun onCleared() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        super.onCleared()
    }

    private data class CatalogPageCacheKey(
        val addonFingerprint: String,
        val catalogKey: String,
        val search: String,
        val genre: String,
        val skip: Int?
    )

    private class CatalogPageMemoryCache {
        private data class Entry(val expiresAtMs: Long, val page: StremioCatalogPage)

        private val entries = object : LinkedHashMap<CatalogPageCacheKey, Entry>(16, 0.75f, true) {}

        @Synchronized
        fun get(key: CatalogPageCacheKey): StremioCatalogPage? {
            val entry = entries[key] ?: return null
            if (entry.expiresAtMs <= System.currentTimeMillis()) {
                entries.remove(key)
                return null
            }
            return entry.page
        }

        @Synchronized
        fun put(key: CatalogPageCacheKey, page: StremioCatalogPage) {
            entries[key] = Entry(System.currentTimeMillis() + CACHE_TTL_MS, page)
            while (entries.size > MAX_CACHED_PAGES) {
                entries.entries.iterator().run { next(); remove() }
            }
        }

        @Synchronized
        fun removeCatalog(addonFingerprint: String, catalogKey: String) {
            entries.keys.removeAll {
                it.addonFingerprint == addonFingerprint && it.catalogKey == catalogKey
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES_PER_QUERY = 100
        const val MAX_CONCURRENT_REQUESTS = 4
        const val MAX_CACHED_PAGES = 200
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        const val SEARCH_DEBOUNCE_MS = 350L
        val SUPPORTED_EXTRAS = setOf("search", "genre", "skip")
    }
}
