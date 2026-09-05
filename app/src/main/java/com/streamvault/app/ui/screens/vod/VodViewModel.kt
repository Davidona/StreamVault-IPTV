package com.streamvault.app.ui.screens.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.VodCatalogItem
import com.streamvault.domain.model.VodCategoryKind
import com.streamvault.domain.model.VodCategoryHydration
import com.streamvault.domain.model.VodCategoryHydrationRequest
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.VodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class VodCategoryRow(
    val category: Category,
    val items: List<VodCatalogItem>,
    val kind: VodCategoryKind = VodCategoryKind.from(items)
)

data class VodUiState(
    val provider: Provider? = null,
    val rows: List<VodCategoryRow> = emptyList(),
    val totalCategoryCount: Int = 0,
    val selectedCategory: Category? = null,
    val selectedItems: List<VodCatalogItem> = emptyList(),
    val selectedLoadedCount: Int = 0,
    val selectedTotalCount: Int = 0,
    val selectedRawPageSize: Int = 0,
    val canLoadMoreSelectedCategory: Boolean = false,
    val isLoadingSelectedCategory: Boolean = false,
    val isLoadingMoreSelectedCategory: Boolean = false,
    val vodInfiniteScroll: Boolean = true,
    val isPortalSearch: Boolean = false,
    val searchQuery: String = "",
    val selectedLibraryFilterType: LibraryFilterType = LibraryFilterType.ALL,
    val selectedLibrarySortBy: LibrarySortBy = LibrarySortBy.LIBRARY,
    val viewMode: VodViewMode = VodViewMode.MODERN,
    val isLoading: Boolean = true
) {
    val canLoadMoreCategories: Boolean get() = rows.size < totalCategoryCount
    val isUnifiedProvider: Boolean get() = provider?.catalogLayout == CatalogLayout.UNIFIED_VOD
}

private data class UnifiedVodCatalogSnapshot(
    val rows: List<VodCategoryRow>,
    val totalCategoryCount: Int
)

private data class SelectedVodSnapshot(
    val category: Category? = null,
    val items: List<VodCatalogItem> = emptyList(),
    val localTotal: Int = 0,
    val hydration: VodCategoryHydration? = null,
    val isPortalSearch: Boolean = false
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class VodViewModel @Inject constructor(
    providerRepository: ProviderRepository,
    private val vodRepository: VodRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    preferencesRepository: PreferencesRepository
) : ViewModel() {
    private companion object {
        const val INITIAL_CATEGORY_COUNT = 12
        const val CATEGORY_LOAD_INCREMENT = 8
        const val PREVIEW_ITEM_COUNT = 20
        const val SELECTED_PAGE_SIZE = 60
    }

    private val visibleCategoryCount = MutableStateFlow(INITIAL_CATEGORY_COUNT)
    private val selectedCategoryId = MutableStateFlow<Long?>(null)
    private val selectedItemLimit = MutableStateFlow(SELECTED_PAGE_SIZE)
    private val searchQuery = MutableStateFlow("")
    private val searchPage = MutableStateFlow(1)
    private val portalSearchEnabled = preferencesRepository.vodPortalSearch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    private val selectedLibraryFilterType = MutableStateFlow(LibraryFilterType.ALL)
    private val selectedLibrarySortBy = MutableStateFlow(LibrarySortBy.LIBRARY)
    private var remotePageRequestInFlight = false
    private var completeHydrationJob: Job? = null
    private val cachedRows = mutableMapOf<Long, VodCategoryRow>()
    private val completedEmptyCategoryIds = mutableSetOf<Long>()
    private var cachedProviderId: Long? = null

    private val activeProvider = providerRepository.getActiveProvider()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val catalog: Flow<UnifiedVodCatalogSnapshot> = combine(
        activeProvider,
        visibleCategoryCount
    ) { provider, limit -> provider to limit }
        .flatMapLatest { (provider, limit) ->
            if (provider == null || provider.catalogLayout != CatalogLayout.UNIFIED_VOD) {
                flowOf(UnifiedVodCatalogSnapshot(emptyList(), 0))
            } else {
                if (cachedProviderId != provider.id) {
                    cachedRows.clear()
                    completedEmptyCategoryIds.clear()
                    cachedProviderId = provider.id
                }
                vodRepository.getCategories(provider.id).flatMapLatest { categories ->
                    val visible = categories.take(limit)
                    if (visible.isEmpty()) {
                        flowOf(UnifiedVodCatalogSnapshot(emptyList(), categories.size))
                    } else {
                        progressiveCategoryRows(provider.id, visible, categories.size)
                    }
                }
            }
        }

    private val selected: Flow<SelectedVodSnapshot> = combine(
        activeProvider,
        selectedCategoryId,
        catalog,
        selectedItemLimit,
        searchQuery,
        searchPage,
        selectedLibraryFilterType,
        selectedLibrarySortBy,
        portalSearchEnabled
    ) { values -> values }
        .flatMapLatest { values ->
            val provider = values[0] as Provider?
            val categoryId = values[1] as Long?
            val snapshot = values[2] as UnifiedVodCatalogSnapshot
            val limit = values[3] as Int
            val query = values[4] as String
            val page = values[5] as Int
            val filterType = values[6] as LibraryFilterType
            val sortBy = values[7] as LibrarySortBy
            val portalSearchEnabled = values[8] as Boolean
            val category = snapshot.rows.firstOrNull { it.category.id == categoryId }?.category
            val portalSearchActive = provider != null && category != null && query.isNotBlank() &&
                portalSearchEnabled && provider.type == ProviderType.STALKER_PORTAL
            when {
                portalSearchActive -> flow {
                    // STB-style portal search: the portal searches its whole VOD catalog
                    // server-side; results page with the portal's native page size.
                    val providerId = provider?.id ?: return@flow
                    val result = vodRepository.searchVod(providerId, query, page)
                    val searchItems = (result as? Result.Success)?.data?.items.orEmpty()
                    val totalCount = (result as? Result.Success)?.data?.totalCount ?: 0
                    emit(
                        SelectedVodSnapshot(
                            category = category,
                            items = searchItems,
                            localTotal = totalCount,
                            hydration = null,
                            isPortalSearch = true
                        )
                    )
                }
                provider == null || category == null -> flowOf(SelectedVodSnapshot())
                else -> combine(
                    vodRepository.getCategoryItems(provider.id, category.id),
                    vodRepository.observeHydration(provider.id, category.id),
                    playbackHistoryRepository.getRecentlyWatchedByProvider(provider.id, limit = 500)
                ) { items, hydration, history ->
                    val browsed = applyBrowse(items, history, filterType, sortBy, query)
                    SelectedVodSnapshot(category, browsed.take(limit), browsed.size, hydration)
                }
            }
        }

    val uiState: StateFlow<VodUiState> = combine(
        activeProvider,
        catalog,
        selected,
        preferencesRepository.vodViewMode,
        preferencesRepository.vodInfiniteScroll,
        searchQuery,
        selectedLibraryFilterType,
        selectedLibrarySortBy
    ) { values ->
        val provider = values[0] as Provider?
        val snapshot = values[1] as UnifiedVodCatalogSnapshot
        val selectedContent = values[2] as SelectedVodSnapshot
        val viewMode = values[3] as String?
        val infiniteScroll = values[4] as Boolean
        val query = values[5] as String
        val filterType = values[6] as LibraryFilterType
        val sortBy = values[7] as LibrarySortBy
        val requiresCompleteCatalog = query.isNotBlank() ||
            filterType != LibraryFilterType.ALL || sortBy != LibrarySortBy.LIBRARY
        val isCompletingBrowse = !selectedContent.isPortalSearch &&
            selectedContent.category != null && requiresCompleteCatalog &&
            selectedContent.hydration?.isComplete != true
        VodUiState(
            provider = provider,
            rows = snapshot.rows,
            totalCategoryCount = snapshot.totalCategoryCount,
            selectedCategory = selectedContent.category,
            selectedItems = selectedContent.items,
            selectedLoadedCount = selectedContent.items.size,
            selectedTotalCount = selectedContent.localTotal,
            selectedRawPageSize = selectedContent.hydration?.pageSize ?: 0,
            canLoadMoreSelectedCategory = selectedContent.items.size < selectedContent.localTotal ||
                selectedContent.hydration?.hasMoreRemote == true,
            isLoadingSelectedCategory = selectedContent.hydration?.isInitialLoading == true || isCompletingBrowse,
            isLoadingMoreSelectedCategory = selectedContent.hydration?.isAppending == true,
            vodInfiniteScroll = infiniteScroll,
            isPortalSearch = selectedContent.isPortalSearch,
            searchQuery = query,
            selectedLibraryFilterType = filterType,
            selectedLibrarySortBy = sortBy,
            viewMode = VodViewMode.fromStorage(viewMode),
            isLoading = provider == null || provider.catalogLayout == CatalogLayout.UNKNOWN
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodUiState())

    fun loadMoreCategories() {
        visibleCategoryCount.value += CATEGORY_LOAD_INCREMENT
    }

    fun selectCategory(category: Category?) {
        completeHydrationJob?.cancel()
        completeHydrationJob = null
        selectedItemLimit.value = SELECTED_PAGE_SIZE
        selectedCategoryId.value = category?.id
        searchPage.value = 1
        if (category == null || isPortalSearchActive()) return
        val providerId = activeProvider.value?.id ?: return
        viewModelScope.launch {
            if (searchQuery.value.isNotBlank() ||
                selectedLibraryFilterType.value != LibraryFilterType.ALL ||
                selectedLibrarySortBy.value != LibrarySortBy.LIBRARY
            ) {
                vodRepository.hydrateCompletely(providerId, category.id)
            } else {
                vodRepository.requestCategoryHydration(providerId, category.id, VodCategoryHydrationRequest.OPEN)
            }
        }
    }

    fun loadMoreSelectedCategory() {
        val state = uiState.value
        val category = state.selectedCategory ?: return
        val providerId = state.provider?.id ?: return
        if (!state.canLoadMoreSelectedCategory || remotePageRequestInFlight) return
        if (state.isPortalSearch) {
            // Portal search pages through the portal's own search results.
            searchPage.value += 1
            return
        }
        val needsRemotePage = state.selectedLoadedCount >= state.selectedTotalCount
        selectedItemLimit.value += SELECTED_PAGE_SIZE
        if (!needsRemotePage) return
        remotePageRequestInFlight = true
        viewModelScope.launch {
            try {
                vodRepository.requestCategoryHydration(
                    providerId,
                    category.id,
                    VodCategoryHydrationRequest.NEXT_PAGE
                )
            } finally {
                remotePageRequestInFlight = false
            }
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
        searchPage.value = 1
        selectedItemLimit.value = SELECTED_PAGE_SIZE
        if (query.isNotBlank() && !isPortalSearchActive()) ensureCompleteForBrowseOperation()
    }

    fun setSelectedLibraryFilterType(filterType: LibraryFilterType) {
        selectedLibraryFilterType.value = filterType
        selectedItemLimit.value = SELECTED_PAGE_SIZE
        if (filterType != LibraryFilterType.ALL) ensureCompleteForBrowseOperation()
    }

    fun setSelectedLibrarySortBy(sortBy: LibrarySortBy) {
        selectedLibrarySortBy.value = sortBy
        selectedItemLimit.value = SELECTED_PAGE_SIZE
        if (sortBy != LibrarySortBy.LIBRARY) ensureCompleteForBrowseOperation()
    }

    private fun isPortalSearchActive(): Boolean {
        val provider = uiState.value.provider
        return searchQuery.value.isNotBlank() &&
            portalSearchEnabled.value &&
            provider?.type == ProviderType.STALKER_PORTAL
    }

    private fun ensureCompleteForBrowseOperation() {
        val state = uiState.value
        val providerId = state.provider?.id ?: return
        val categoryId = state.selectedCategory?.id ?: return
        if (completeHydrationJob?.isActive == true) return
        completeHydrationJob = viewModelScope.launch {
            vodRepository.hydrateCompletely(providerId, categoryId)
        }
    }

    private fun applyBrowse(
        items: List<VodCatalogItem>,
        history: List<PlaybackHistory>,
        filterType: LibraryFilterType,
        sortBy: LibrarySortBy,
        query: String
    ): List<VodCatalogItem> {
        val normalizedQuery = query.trim()
        val historyIds = history.mapTo(mutableSetOf()) { it.seriesId ?: it.contentId }
        val completedIds = history
            .filter { it.totalDurationMs > 0 && it.resumePositionMs >= (it.totalDurationMs * 0.95f).toLong() }
            .mapTo(mutableSetOf()) { it.seriesId ?: it.contentId }
        val watchCounts = history.groupingBy { it.seriesId ?: it.contentId }.eachCount()
        val searched = if (normalizedQuery.isBlank()) items else items.filter { item ->
            when (item) {
                is VodCatalogItem.MovieItem -> item.movie.name.contains(normalizedQuery, true) ||
                    item.movie.plot?.contains(normalizedQuery, true) == true ||
                    item.movie.genre?.contains(normalizedQuery, true) == true
                is VodCatalogItem.SeriesItem -> item.series.name.contains(normalizedQuery, true) ||
                    item.series.plot?.contains(normalizedQuery, true) == true ||
                    item.series.genre?.contains(normalizedQuery, true) == true
            }
        }
        val filtered = when (filterType) {
            LibraryFilterType.ALL -> searched
            LibraryFilterType.FAVORITES -> searched.filter(::isFavorite)
            LibraryFilterType.IN_PROGRESS -> searched.filter { item -> item.historyId in historyIds }
            LibraryFilterType.UNWATCHED -> searched.filter { item -> item.historyId !in completedIds }
            LibraryFilterType.RECENTLY_UPDATED -> searched.filter { it.sortTimestamp > 0L }
            LibraryFilterType.TOP_RATED -> searched.filter { it.rating > 0f }
        }
        return when (sortBy) {
            LibrarySortBy.LIBRARY -> filtered
            LibrarySortBy.TITLE -> filtered.sortedBy { it.title.lowercase() }
            LibrarySortBy.RELEASE -> filtered.sortedByDescending { it.releaseScore }
            LibrarySortBy.UPDATED -> filtered.sortedByDescending(VodCatalogItem::sortTimestamp)
            LibrarySortBy.RATING -> filtered.sortedByDescending { it.rating }
            LibrarySortBy.WATCH_COUNT -> filtered.sortedByDescending { watchCounts[it.historyId] ?: 0 }
        }
    }

    private val VodCatalogItem.historyId: Long get() = when (this) {
        is VodCatalogItem.MovieItem -> movie.id
        is VodCatalogItem.SeriesItem -> series.id
    }

    private val VodCatalogItem.rating: Float get() = when (this) {
        is VodCatalogItem.MovieItem -> movie.rating
        is VodCatalogItem.SeriesItem -> series.rating
    }

    private val VodCatalogItem.releaseScore: Long get() = when (this) {
        is VodCatalogItem.MovieItem -> movie.releaseDate?.filter(Char::isDigit)?.take(8)?.toLongOrNull() ?: movie.addedAt
        is VodCatalogItem.SeriesItem -> series.releaseDate?.filter(Char::isDigit)?.take(8)?.toLongOrNull() ?: series.lastModified
    }

    private fun isFavorite(item: VodCatalogItem): Boolean = when (item) {
        is VodCatalogItem.MovieItem -> item.movie.isFavorite
        is VodCatalogItem.SeriesItem -> item.series.isFavorite
    }

    private fun progressiveCategoryRows(
        providerId: Long,
        categories: List<Category>,
        totalCategoryCount: Int
    ): Flow<UnifiedVodCatalogSnapshot> = channelFlow {
        val mutex = Mutex()
        val rows = categories.mapNotNull { category ->
            cachedRows[category.id]
                ?.takeUnless { category.id in completedEmptyCategoryIds }
                ?.let { category.id to it }
        }.toMap().toMutableMap()
        send(UnifiedVodCatalogSnapshot(categories.mapNotNull { rows[it.id] }, totalCategoryCount))
        categories.forEach { category ->
            launch {
                combine(
                    vodRepository.getCategoryPreview(providerId, category.id, PREVIEW_ITEM_COUNT),
                    vodRepository.observeHydration(providerId, category.id)
                ) { items, hydration -> items to hydration }
                    .collect { (items, hydration) ->
                        mutex.withLock {
                            val completedEmpty = items.isEmpty() && hydration?.isComplete == true
                            if (completedEmpty) {
                                rows.remove(category.id)
                                cachedRows.remove(category.id)
                                if (completedEmptyCategoryIds.add(category.id) && categories.size < totalCategoryCount) {
                                    visibleCategoryCount.value += 1
                                }
                            } else {
                                val row = VodCategoryRow(category, items, hydration.toCategoryKind(items))
                                rows[category.id] = row
                                cachedRows[category.id] = row
                            }
                            send(
                                UnifiedVodCatalogSnapshot(
                                    rows = categories.mapNotNull { rows[it.id] },
                                    totalCategoryCount = totalCategoryCount
                                )
                            )
                        }
                    }
            }
        }
    }

    private fun VodCategoryHydration?.toCategoryKind(items: List<VodCatalogItem>): VodCategoryKind = when {
        this == null -> VodCategoryKind.from(items)
        hasMovies && hasSeries -> VodCategoryKind.MIXED
        hasMovies -> VodCategoryKind.MOVIES
        hasSeries -> VodCategoryKind.SERIES
        else -> VodCategoryKind.UNKNOWN
    }
}
