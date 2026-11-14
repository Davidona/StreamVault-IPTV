package com.streamvault.feature.catalog.presentation.vod

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.VodViewMode
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.VodCatalogItem
import com.streamvault.domain.model.VodCategoryKind
import com.streamvault.domain.model.VodCategoryHydration
import com.streamvault.domain.model.VodCategoryHydrationRequest
import com.streamvault.domain.util.SearchTextNormalizer
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.VodRepository
import com.streamvault.domain.usecase.ContinueWatchingResult
import com.streamvault.domain.usecase.ContinueWatchingScope
import com.streamvault.domain.usecase.GetContinueWatching
import com.streamvault.feature.catalog.R
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
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

data class VodActionDialogState(
    val item: VodCatalogItem,
    val groups: List<Category>,
    val isFavorite: Boolean,
    val memberOfGroups: List<Long>
)

private data class VodFavoriteIds(
    val movies: Set<Long> = emptySet(),
    val series: Set<Long> = emptySet()
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
    val vodPortalSearch: Boolean = true,
    val vodTypeBadgeAsIcon: Boolean = false,
    val searchQuery: String = "",
    val selectedLibraryFilterType: LibraryFilterType = LibraryFilterType.ALL,
    val selectedLibrarySortBy: LibrarySortBy = LibrarySortBy.LIBRARY,
    val viewMode: VodViewMode = VodViewMode.MODERN,
    val portalSearchActive: Boolean = false,
    val portalSearchItems: List<VodCatalogItem> = emptyList(),
    val portalSearchTotalCount: Int = 0,
    val portalSearchPageSize: Int = 0,
    val canLoadMorePortalSearch: Boolean = false,
    val isLoadingPortalSearch: Boolean = false,
    val isAppendingPortalSearch: Boolean = false,
    val portalSearchError: String? = null,
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
    val hydration: VodCategoryHydration? = null
)

private data class PortalVodSearchSnapshot(
    val providerId: Long? = null,
    val query: String = "",
    val items: List<VodCatalogItem> = emptyList(),
    val totalCount: Int = 0,
    val pageSize: Int = 0,
    val nextPage: Int = 1,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class VodViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    providerRepository: ProviderRepository,
    private val vodRepository: VodRepository,
    private val favoriteRepository: FavoriteRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val getContinueWatching: GetContinueWatching,
    preferencesRepository: PreferencesRepository
) : ViewModel() {
    private companion object {
        const val INITIAL_CATEGORY_COUNT = 12
        const val CATEGORY_LOAD_INCREMENT = 8
        const val PREVIEW_ITEM_COUNT = 20
        const val SELECTED_PAGE_SIZE = 60
        const val PORTAL_SEARCH_DEBOUNCE_MILLIS = 250L
    }

    private val visibleCategoryCount = MutableStateFlow(INITIAL_CATEGORY_COUNT)
    private val selectedCategoryId = MutableStateFlow<Long?>(null)
    private val selectedItemLimit = MutableStateFlow(SELECTED_PAGE_SIZE)
    private val searchQuery = MutableStateFlow("")
    private val selectedLibraryFilterType = MutableStateFlow(LibraryFilterType.ALL)
    private val selectedLibrarySortBy = MutableStateFlow(LibrarySortBy.LIBRARY)
    private var remotePageRequestInFlight = false
    private var completeHydrationJob: Job? = null
    private val cachedRows = mutableMapOf<Long, VodCategoryRow>()
    private val completedEmptyCategoryIds = mutableSetOf<Long>()
    private var cachedProviderId: Long? = null
    private val portalSearch = MutableStateFlow(PortalVodSearchSnapshot())
    private val portalSearchEnabled = preferencesRepository.vodPortalSearch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    private var portalSearchJob: Job? = null
    private var portalSearchRequestInFlight = false
    private var portalSearchGeneration = 0L

    private val activeProvider = providerRepository.getActiveProvider()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val continueWatching: StateFlow<List<PlaybackHistory>> = activeProvider
        .flatMapLatest { provider ->
            if (provider?.catalogLayout != CatalogLayout.UNIFIED_VOD) {
                flowOf(emptyList())
            } else {
                getContinueWatching(
                    providerId = provider.id,
                    limit = 20,
                    scope = ContinueWatchingScope.ALL_VOD
                ).map { result ->
                    when (result) {
                        is ContinueWatchingResult.Items -> result.items
                        ContinueWatchingResult.Degraded -> emptyList()
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val favoriteIds = activeProvider
        .flatMapLatest { provider ->
            if (provider == null) {
                flowOf(VodFavoriteIds())
            } else {
                combine(
                    favoriteRepository.getFavorites(provider.id, ContentType.MOVIE),
                    favoriteRepository.getFavorites(provider.id, ContentType.SERIES)
                ) { movies, series ->
                    VodFavoriteIds(
                        movies = movies.asSequence().map { it.contentId }.toSet(),
                        series = series.asSequence().map { it.contentId }.toSet()
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodFavoriteIds())

    private val _actionDialog = MutableStateFlow<VodActionDialogState?>(null)
    val actionDialog: StateFlow<VodActionDialogState?> = _actionDialog.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()
    private var actionDialogGeneration = 0L

    init {
        viewModelScope.launch {
            activeProvider
                .map { it?.id }
                .distinctUntilChanged()
                .collect {
                    if (_actionDialog.value?.item?.providerId != it) {
                        dismissActionDialog()
                    }
                    resetPortalSearch()
                    if (isPortalSearchQuery(searchQuery.value)) {
                        startPortalSearch(searchQuery.value)
                    } else if (searchQuery.value.isNotBlank()) {
                        ensureCompleteForBrowseOperation()
                    }
                }
        }
        viewModelScope.launch {
            portalSearchEnabled
                .collect { enabled ->
                    if (enabled && isPortalSearchQuery(searchQuery.value)) {
                        startPortalSearch(searchQuery.value)
                    } else {
                        resetPortalSearch()
                        if (!enabled && searchQuery.value.isNotBlank()) {
                            ensureCompleteForBrowseOperation()
                        }
                    }
                }
        }
    }

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
        portalSearchEnabled,
        selectedLibraryFilterType,
        selectedLibrarySortBy,
        favoriteIds
    ) { values -> values }
        .flatMapLatest { values ->
            val provider = values[0] as Provider?
            val categoryId = values[1] as Long?
            val snapshot = values[2] as UnifiedVodCatalogSnapshot
            val limit = values[3] as Int
            val query = values[4] as String
            val portalSearchEnabled = values[5] as Boolean
            val filterType = values[6] as LibraryFilterType
            val sortBy = values[7] as LibrarySortBy
            val currentFavoriteIds = values[8] as VodFavoriteIds
            val category = snapshot.rows.firstOrNull { it.category.id == categoryId }?.category
            val portalSearchActive = isPortalVodSearchQuery(provider?.type, portalSearchEnabled, query)
            if (portalSearchActive) {
                flowOf(SelectedVodSnapshot(category = category))
            } else if (provider == null || category == null) {
                flowOf(SelectedVodSnapshot())
            } else {
                combine(
                    vodRepository.getCategoryItems(provider.id, category.id),
                    vodRepository.observeHydration(provider.id, category.id),
                    playbackHistoryRepository.getRecentlyWatchedByProvider(provider.id, limit = 500)
                ) { items, hydration, history ->
                    val browsed = applyBrowse(
                        items.withFavoriteFlags(currentFavoriteIds),
                        history,
                        filterType,
                        sortBy,
                        query
                    )
                    SelectedVodSnapshot(category, browsed.take(limit), browsed.size, hydration)
                }
            }
        }

    val uiState: StateFlow<VodUiState> = combine(
        activeProvider,
        catalog,
        selected,
        portalSearch,
        preferencesRepository.vodViewMode,
        preferencesRepository.vodTypeBadgeAsIcon,
        preferencesRepository.vodInfiniteScroll,
        portalSearchEnabled,
        searchQuery,
        selectedLibraryFilterType,
        selectedLibrarySortBy,
        favoriteIds
    ) { values ->
        val provider = values[0] as Provider?
        val snapshot = values[1] as UnifiedVodCatalogSnapshot
        val selectedContent = values[2] as SelectedVodSnapshot
        val portalSnapshot = values[3] as PortalVodSearchSnapshot
        val currentPortalSnapshot = if (
            portalVodSearchBelongsToProvider(portalSnapshot.providerId, provider?.id)
        ) {
            portalSnapshot
        } else {
            PortalVodSearchSnapshot()
        }
        val viewMode = values[4] as String?
        val vodTypeBadgeAsIcon = values[5] as Boolean
        val infiniteScroll = values[6] as Boolean
        val portalSearchEnabled = values[7] as Boolean
        val query = values[8] as String
        val filterType = values[9] as LibraryFilterType
        val sortBy = values[10] as LibrarySortBy
        val currentFavoriteIds = values[11] as VodFavoriteIds
        val portalSearchActive = isPortalVodSearchQuery(provider?.type, portalSearchEnabled, query)
        val requiresCompleteCatalog = query.isNotBlank() ||
            filterType != LibraryFilterType.ALL || sortBy != LibrarySortBy.LIBRARY
        val isCompletingBrowse = !portalSearchActive && selectedContent.category != null && requiresCompleteCatalog &&
            selectedContent.hydration?.isComplete != true
        VodUiState(
            provider = provider,
            rows = snapshot.rows.map { row ->
                row.copy(items = row.items.withFavoriteFlags(currentFavoriteIds))
            },
            totalCategoryCount = snapshot.totalCategoryCount,
            selectedCategory = selectedContent.category,
            selectedItems = selectedContent.items.withFavoriteFlags(currentFavoriteIds),
            selectedLoadedCount = selectedContent.items.size,
            selectedTotalCount = selectedContent.localTotal,
            selectedRawPageSize = selectedContent.hydration?.pageSize ?: 0,
            canLoadMoreSelectedCategory = selectedContent.items.size < selectedContent.localTotal ||
                selectedContent.hydration?.hasMoreRemote == true,
            isLoadingSelectedCategory = selectedContent.hydration?.isInitialLoading == true || isCompletingBrowse,
            isLoadingMoreSelectedCategory = selectedContent.hydration?.isAppending == true,
            vodInfiniteScroll = infiniteScroll,
            vodPortalSearch = portalSearchEnabled,
            vodTypeBadgeAsIcon = vodTypeBadgeAsIcon,
            searchQuery = query,
            selectedLibraryFilterType = filterType,
            selectedLibrarySortBy = sortBy,
            viewMode = VodViewMode.fromStorage(viewMode),
            portalSearchActive = portalSearchActive,
            portalSearchItems = currentPortalSnapshot.items.withFavoriteFlags(currentFavoriteIds),
            portalSearchTotalCount = currentPortalSnapshot.totalCount,
            portalSearchPageSize = currentPortalSnapshot.pageSize,
            canLoadMorePortalSearch = portalSearchActive && currentPortalSnapshot.hasMore,
            isLoadingPortalSearch = portalSearchActive && currentPortalSnapshot.isLoading && currentPortalSnapshot.items.isEmpty(),
            isAppendingPortalSearch = portalSearchActive && currentPortalSnapshot.isLoading && currentPortalSnapshot.items.isNotEmpty(),
            portalSearchError = if (portalSearchActive) currentPortalSnapshot.error else null,
            isLoading = provider == null || provider.catalogLayout == CatalogLayout.UNKNOWN
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodUiState())

    fun showItemActions(item: VodCatalogItem) {
        val generation = ++actionDialogGeneration
        viewModelScope.launch {
            if (activeProvider.value?.id != item.providerId) return@launch
            val dialog = loadActionDialog(item)
            if (generation == actionDialogGeneration && activeProvider.value?.id == item.providerId) {
                _actionDialog.value = dialog
            }
        }
    }

    fun dismissActionDialog() {
        actionDialogGeneration++
        _actionDialog.value = null
    }

    fun toggleActionFavorite() {
        val dialog = _actionDialog.value ?: return
        val generation = actionDialogGeneration
        viewModelScope.launch {
            if (dialog.isFavorite) {
                dialog.item.actionIds().forEach { itemId ->
                    setActionFavorite(dialog.item.providerId, itemId, dialog.item.contentType(), false)
                }
            } else {
                setActionFavorite(
                    dialog.item.providerId,
                    dialog.item.selectedActionId(),
                    dialog.item.contentType(),
                    true
                )
            }
            refreshActionDialog(dialog.item, generation)
        }
    }

    fun addActionItemToGroup(group: Category) {
        val dialog = _actionDialog.value ?: return
        val generation = actionDialogGeneration
        viewModelScope.launch {
            updateActionGroupMembership(
                providerId = dialog.item.providerId,
                itemId = dialog.item.selectedActionId(),
                groupId = group.id,
                contentType = dialog.item.contentType(),
                shouldBeMember = true
            )
            refreshActionDialog(dialog.item, generation)
        }
    }

    fun removeActionItemFromGroup(group: Category) {
        val dialog = _actionDialog.value ?: return
        val generation = actionDialogGeneration
        viewModelScope.launch {
            dialog.item.actionIds().forEach { itemId ->
                updateActionGroupMembership(
                    providerId = dialog.item.providerId,
                    itemId = itemId,
                    groupId = group.id,
                    contentType = dialog.item.contentType(),
                    shouldBeMember = false
                )
            }
            refreshActionDialog(dialog.item, generation)
        }
    }

    fun createActionGroup(name: String) {
        val dialog = _actionDialog.value ?: return
        val normalizedName = name.trim()
        validateGroupName(normalizedName, dialog.groups, dialog.item.contentType())?.let { message ->
            _userMessage.value = message
            return
        }
        val generation = actionDialogGeneration
        viewModelScope.launch {
            when (val result = favoriteRepository.createGroup(
                providerId = dialog.item.providerId,
                name = normalizedName,
                contentType = dialog.item.contentType()
            )) {
                is Result.Success -> {
                    updateActionGroupMembership(
                        providerId = dialog.item.providerId,
                        itemId = dialog.item.selectedActionId(),
                        groupId = result.data.id,
                        contentType = dialog.item.contentType(),
                        shouldBeMember = true
                    )
                    refreshActionDialog(dialog.item, generation)
                    _userMessage.value = appContext.getString(
                        if (dialog.item.contentType() == ContentType.SERIES) {
                            R.string.series_group_created
                        } else {
                            R.string.movies_group_created
                        },
                        normalizedName
                    )
                }
                is Result.Error -> _userMessage.value = result.message
                Result.Loading -> Unit
            }
        }
    }

    fun userMessageShown() {
        _userMessage.value = null
    }

    fun removeFromContinueWatching(history: PlaybackHistory) {
        viewModelScope.launch {
            val result = playbackHistoryRepository.removeFromHistory(
                contentId = history.contentId,
                contentType = history.contentType,
                providerId = history.providerId
            )
            _userMessage.value = if (result.isSuccess) {
                appContext.getString(R.string.continue_watching_removed, history.title)
            } else {
                appContext.getString(R.string.continue_watching_remove_failed)
            }
        }
    }

    private suspend fun setActionFavorite(
        providerId: Long,
        itemId: Long,
        contentType: ContentType,
        isFavorite: Boolean
    ) {
        if (isFavorite) {
            favoriteRepository.addFavorite(providerId, itemId, contentType)
        } else {
            favoriteRepository.removeFavorite(providerId, itemId, contentType)
        }
    }

    private suspend fun updateActionGroupMembership(
        providerId: Long,
        itemId: Long,
        groupId: Long,
        contentType: ContentType,
        shouldBeMember: Boolean
    ) {
        val storedGroupId = kotlin.math.abs(groupId)
        if (shouldBeMember) {
            favoriteRepository.addFavorite(providerId, itemId, contentType, groupId = storedGroupId)
        } else {
            favoriteRepository.removeFavorite(providerId, itemId, contentType, groupId = storedGroupId)
        }
    }

    fun loadMoreCategories() {
        visibleCategoryCount.value += CATEGORY_LOAD_INCREMENT
    }

    private suspend fun loadActionDialog(item: VodCatalogItem): VodActionDialogState {
        val contentType = item.contentType()
        val itemIds = item.actionIds()
        val groups = favoriteRepository.getGroups(item.providerId, contentType).first().map { group ->
            Category(
                id = -kotlin.math.abs(group.id),
                name = group.name,
                type = contentType,
                isVirtual = true
            )
        }
        val memberships = itemIds
            .flatMap { itemId -> favoriteRepository.getGroupMemberships(item.providerId, itemId, contentType) }
            .map { groupId -> -kotlin.math.abs(groupId) }
            .distinct()
        val isFavorite = itemIds.any { itemId ->
            favoriteRepository.isFavorite(item.providerId, itemId, contentType)
        }
        return VodActionDialogState(item, groups, isFavorite, memberships)
    }

    private suspend fun refreshActionDialog(item: VodCatalogItem, generation: Long) {
        val updatedDialog = loadActionDialog(item)
        if (generation == actionDialogGeneration && _actionDialog.value?.item?.stableId == item.stableId) {
            _actionDialog.value = updatedDialog
        }
    }

    private fun validateGroupName(name: String, groups: List<Category>, contentType: ContentType): String? {
        val isSeries = contentType == ContentType.SERIES
        if (name.isBlank()) {
            return appContext.getString(
                if (isSeries) R.string.series_group_name_invalid else R.string.movies_group_name_invalid
            )
        }
        if (name.equals("favorites", ignoreCase = true)) {
            return appContext.getString(
                if (isSeries) R.string.series_group_name_reserved else R.string.movies_group_name_reserved
            )
        }
        if (groups.any { it.name.equals(name, ignoreCase = true) }) {
            return appContext.getString(
                if (isSeries) R.string.series_group_name_duplicate else R.string.movies_group_name_duplicate
            )
        }
        return null
    }

    private fun VodCatalogItem.contentType(): ContentType = when (this) {
        is VodCatalogItem.MovieItem -> ContentType.MOVIE
        is VodCatalogItem.SeriesItem -> ContentType.SERIES
    }

    private val VodCatalogItem.providerId: Long
        get() = when (this) {
            is VodCatalogItem.MovieItem -> movie.providerId
            is VodCatalogItem.SeriesItem -> series.providerId
        }

    private fun VodCatalogItem.selectedActionId(): Long = when (this) {
        is VodCatalogItem.MovieItem -> movie.selectedVariantId ?: movie.id
        is VodCatalogItem.SeriesItem -> series.selectedVariantId ?: series.id
    }

    private fun VodCatalogItem.actionIds(): List<Long> = when (this) {
        is VodCatalogItem.MovieItem -> movie.variants.map { it.rawMovieId }
            .ifEmpty { listOf(movie.selectedVariantId ?: movie.id) }
            .distinct()
        is VodCatalogItem.SeriesItem -> series.variants.map { it.rawSeriesId }
            .ifEmpty { listOf(series.selectedVariantId ?: series.id) }
            .distinct()
    }

    private fun List<VodCatalogItem>.withFavoriteFlags(favoriteIds: VodFavoriteIds): List<VodCatalogItem> = map { item ->
        when (item) {
            is VodCatalogItem.MovieItem -> item.copy(
                movie = item.movie.copy(isFavorite = item.movie.actionIds().any(favoriteIds.movies::contains))
            )
            is VodCatalogItem.SeriesItem -> item.copy(
                series = item.series.copy(isFavorite = item.series.actionIds().any(favoriteIds.series::contains))
            )
        }
    }

    private fun Movie.actionIds(): List<Long> = variants.map { it.rawMovieId }
        .ifEmpty { listOf(selectedVariantId ?: id) }
        .distinct()

    private fun Series.actionIds(): List<Long> = variants.map { it.rawSeriesId }
        .ifEmpty { listOf(selectedVariantId ?: id) }
        .distinct()

    fun selectCategory(category: Category?) {
        resetPortalSearch()
        completeHydrationJob?.cancel()
        completeHydrationJob = null
        selectedItemLimit.value = SELECTED_PAGE_SIZE
        selectedCategoryId.value = category?.id
        if (category == null) {
            searchQuery.value = ""
            return
        }
        if (isPortalSearchQuery(searchQuery.value)) {
            startPortalSearch(searchQuery.value)
            return
        }
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
        if (state.portalSearchActive) {
            loadMorePortalSearch()
            return
        }
        val category = state.selectedCategory ?: return
        val providerId = state.provider?.id ?: return
        if (!state.canLoadMoreSelectedCategory || remotePageRequestInFlight) return
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
        selectedItemLimit.value = SELECTED_PAGE_SIZE
        if (isPortalSearchQuery(query)) {
            startPortalSearch(query)
        } else {
            resetPortalSearch()
            if (query.isNotBlank()) {
                ensureCompleteForBrowseOperation()
            }
        }
    }

    private fun isPortalSearchQuery(query: String): Boolean =
        isPortalVodSearchQuery(activeProvider.value?.type, portalSearchEnabled.value, query)

    private fun resetPortalSearch() {
        portalSearchJob?.cancel()
        portalSearchJob = null
        portalSearchRequestInFlight = false
        portalSearchGeneration++
        portalSearch.value = PortalVodSearchSnapshot()
    }

    private fun isPortalSearchActive(): Boolean = isPortalSearchQuery(searchQuery.value)

    private fun startPortalSearch(query: String) {
        resetPortalSearch()
        val providerId = activeProvider.value?.id ?: return
        val normalized = query.trim()
        if (!isPortalSearchQuery(normalized)) return
        val generation = portalSearchGeneration
        portalSearch.value = PortalVodSearchSnapshot(
            providerId = providerId,
            query = normalized
        )
        portalSearchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(PORTAL_SEARCH_DEBOUNCE_MILLIS)
            requestPortalSearchPage(
                query = normalized,
                page = 1,
                generation = generation,
                providerId = providerId
            )
        }
    }

    private fun loadMorePortalSearch() {
        val snapshot = portalSearch.value
        if (portalSearchRequestInFlight || !snapshot.hasMore) return
        val providerId = activeProvider.value?.id ?: return
        if (!portalVodSearchBelongsToProvider(snapshot.providerId, providerId)) return
        val query = snapshot.query
        val generation = portalSearchGeneration
        portalSearchJob = viewModelScope.launch {
            requestPortalSearchPage(query, snapshot.nextPage, generation, providerId)
        }
    }

    private suspend fun requestPortalSearchPage(
        query: String,
        page: Int,
        generation: Long,
        providerId: Long
    ) {
        if (
            generation != portalSearchGeneration ||
            query.isBlank() ||
            activeProvider.value?.id != providerId ||
            !portalVodSearchBelongsToProvider(portalSearch.value.providerId, providerId)
        ) return
        portalSearchRequestInFlight = true
        portalSearch.value = portalSearch.value.copy(
            isLoading = true,
            error = null
        )
        try {
            when (val result = vodRepository.searchVod(providerId, query, page)) {
                is com.streamvault.domain.model.Result.Success -> {
                    if (
                        generation != portalSearchGeneration ||
                        activeProvider.value?.id != providerId
                    ) return
                    val current = portalSearch.value
                    val merged = if (page == 1) {
                        result.data.items
                    } else {
                        (current.items + result.data.items).distinctBy(VodCatalogItem::stableId)
                    }
                    portalSearch.value = current.copy(
                        items = merged,
                        totalCount = result.data.totalCount,
                        pageSize = result.data.pageSize,
                        nextPage = result.data.page + 1,
                        hasMore = result.data.hasMore,
                        isLoading = false,
                        error = null
                    )
                }
                is com.streamvault.domain.model.Result.Error -> {
                    if (
                        generation == portalSearchGeneration &&
                        activeProvider.value?.id == providerId
                    ) {
                        portalSearch.value = portalSearch.value.copy(
                            isLoading = false,
                            error = result.message
                        )
                    }
                }
                is com.streamvault.domain.model.Result.Loading -> Unit
            }
        } finally {
            if (
                generation == portalSearchGeneration &&
                activeProvider.value?.id == providerId
            ) {
                portalSearchRequestInFlight = false
                portalSearch.value = portalSearch.value.copy(isLoading = false)
            }
        }
    }

    fun setSelectedLibraryFilterType(filterType: LibraryFilterType) {
        selectedLibraryFilterType.value = filterType
        selectedItemLimit.value = SELECTED_PAGE_SIZE
        if (filterType != LibraryFilterType.ALL && !isPortalSearchActive()) {
            ensureCompleteForBrowseOperation()
        }
    }

    fun setSelectedLibrarySortBy(sortBy: LibrarySortBy) {
        selectedLibrarySortBy.value = sortBy
        selectedItemLimit.value = SELECTED_PAGE_SIZE
        if (sortBy != LibrarySortBy.LIBRARY && !isPortalSearchActive()) {
            ensureCompleteForBrowseOperation()
        }
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
        val normalizedQuery = SearchTextNormalizer.normalize(query.trim())
        val historyIds = history.mapTo(mutableSetOf()) { it.seriesId ?: it.contentId }
        val completedIds = history
            .filter { it.totalDurationMs > 0 && it.resumePositionMs >= (it.totalDurationMs * 0.95f).toLong() }
            .mapTo(mutableSetOf()) { it.seriesId ?: it.contentId }
        val watchCounts = history.groupingBy { it.seriesId ?: it.contentId }.eachCount()
        val searched = if (normalizedQuery.isBlank()) items else items.filter { item ->
            when (item) {
                is VodCatalogItem.MovieItem -> SearchTextNormalizer.normalize(item.movie.name).contains(normalizedQuery) ||
                    item.movie.plot?.let { SearchTextNormalizer.normalize(it).contains(normalizedQuery) } == true ||
                    item.movie.genre?.let { SearchTextNormalizer.normalize(it).contains(normalizedQuery) } == true
                is VodCatalogItem.SeriesItem -> SearchTextNormalizer.normalize(item.series.name).contains(normalizedQuery) ||
                    item.series.plot?.let { SearchTextNormalizer.normalize(it).contains(normalizedQuery) } == true ||
                    item.series.genre?.let { SearchTextNormalizer.normalize(it).contains(normalizedQuery) } == true
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

