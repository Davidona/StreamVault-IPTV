package com.streamvault.app.ui.screens.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.app.ui.model.applyProviderCategoryDisplayPreferences
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LibraryFilterBy
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ContinueWatchingResult
import com.streamvault.domain.usecase.ContinueWatchingScope
import com.streamvault.domain.usecase.GetContinueWatching
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.app.ui.screens.vod.createVodGroup
import com.streamvault.app.ui.screens.vod.incrementVodSelectedCategoryLoadLimit
import com.streamvault.app.ui.screens.vod.buildVodPreviewCatalog
import com.streamvault.app.ui.screens.vod.buildVodSearchCatalog
import com.streamvault.app.ui.screens.vod.loadVodReorderItems
import com.streamvault.app.ui.screens.vod.matchesVodGroupMembership
import com.streamvault.app.ui.screens.vod.moveVodItemDown
import com.streamvault.app.ui.screens.vod.moveVodItemUp
import com.streamvault.app.ui.screens.vod.selectVodCategory
import com.streamvault.app.ui.screens.vod.saveVodReorder
import com.streamvault.app.ui.screens.vod.setVodLibraryFilterType
import com.streamvault.app.ui.screens.vod.setVodLibrarySortBy
import com.streamvault.app.ui.screens.vod.setVodSearchQuery
import com.streamvault.app.ui.screens.vod.setVodFavorite
import com.streamvault.app.ui.screens.vod.updateVodGroupMembership
import com.streamvault.app.ui.screens.vod.VodBrowseDefaults
import com.streamvault.app.util.isPlaybackComplete
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MovieLibraryLens {
    FAVORITES,
    CONTINUE,
    TOP_RATED,
    FRESH
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MoviesViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val movieRepository: MovieRepository,
    private val preferencesRepository: PreferencesRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val favoriteRepository: FavoriteRepository,
    private val getContinueWatching: GetContinueWatching,
    private val getCustomCategories: GetCustomCategories,
    private val parentalControlManager: ParentalControlManager
) : ViewModel() {
    private companion object {
        const val UNCATEGORIZED = "Uncategorized"
        const val MIN_SEARCH_QUERY_LENGTH = 2
        const val FAVORITE_ID_FETCH_BUFFER = 80
        const val INITIAL_PREVIEW_BATCH_SIZE = 6
    }

    private val _uiState = MutableStateFlow(MoviesUiState())
    val uiState: StateFlow<MoviesUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val searchQueryForBrowse = _searchQuery
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce { query ->
            if (query.isBlank() || query.length < MIN_SEARCH_QUERY_LENGTH) 0L else 300L
        }
    private val _selectedCategoryLoadLimit = MutableStateFlow(VodBrowseDefaults.SELECTED_CATEGORY_PAGE_SIZE)
    private val _selectedLibraryFilterType = MutableStateFlow(LibraryFilterType.ALL)
    private val _selectedLibrarySortBy = MutableStateFlow(LibrarySortBy.LIBRARY)
    private val _previewBatchSize = MutableStateFlow(INITIAL_PREVIEW_BATCH_SIZE)
    private var activeProviderId: Long? = null

    private data class PreviewLoadResult(
        val snapshot: MovieCatalogSnapshot,
        val isLoadingPreviewRows: Boolean,
        val hasMorePreviewRows: Boolean
    )

    init {
        viewModelScope.launch {
            providerRepository.getProviders().collectLatest { providers ->
                _uiState.update {
                    it.copy(
                        hasProviders = providers.isNotEmpty(),
                        isLoading = if (providers.isEmpty()) false else it.isLoading
                    )
                }
            }
        }

        viewModelScope.launch {
            providerRepository.getActiveProvider().collectLatest { provider ->
                activeProviderId = provider?.id
                _uiState.update {
                    it.copy(
                        hasActiveProvider = provider != null,
                        isLoading = if (provider == null) false else it.isLoading,
                        isLoadingSelectedCategory = if (provider == null) false else it.isLoadingSelectedCategory,
                        isLoadingPreviewRows = if (provider == null) false else it.isLoadingPreviewRows
                    )
                }
            }
        }

        viewModelScope.launch {
            try {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .flatMapLatest { provider ->
                    activeProviderId = provider.id
                    combine(
                        favoriteRepository.getAllFavorites(provider.id, ContentType.MOVIE),
                        getCustomCategories(provider.id, ContentType.MOVIE),
                        movieRepository.getCategories(provider.id),
                        movieRepository.getCategoryItemCounts(provider.id),
                        movieRepository.getLibraryCount(provider.id),
                        preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.MOVIE),
                        preferencesRepository.getCategorySortMode(provider.id, ContentType.MOVIE),
                        preferencesRepository.getPinnedCategoryIds(provider.id, ContentType.MOVIE)
                    ) { values ->
                        val allFavorites = values[0] as List<com.streamvault.domain.model.Favorite>
                        val customCategories = values[1] as List<Category>
                        val providerCategories = values[2] as List<Category>
                        val providerCategoryCounts = values[3] as Map<Long, Int>
                        val libraryCount = values[4] as Int
                        val hiddenCategoryIds = values[5] as Set<Long>
                        val sortMode = values[6] as CategorySortMode
                        val pinnedCategoryIds = values[7] as Set<Long>
                        val visibleProviderCategories = applyProviderCategoryDisplayPreferences(
                            categories = providerCategories,
                            hiddenCategoryIds = hiddenCategoryIds,
                            sortMode = sortMode
                        ).let { categories ->
                            if (provider.type == ProviderType.STALKER_PORTAL) {
                                categories.filterNot(::isLikelyProviderWideStalkerCategory)
                            } else {
                                categories
                            }
                        }
                        val pinnedProviderCategories = visibleProviderCategories.filter { it.id in pinnedCategoryIds }
                        val unpinnedProviderCategories = visibleProviderCategories.filterNot { it.id in pinnedCategoryIds }
                        MovieCatalogDependencies(
                            allFavorites = allFavorites,
                            customCategories = customCategories,
                            providerCategories = visibleProviderCategories,
                            pinnedProviderCategories = pinnedProviderCategories,
                            unpinnedProviderCategories = unpinnedProviderCategories,
                            providerCategoryCounts = providerCategoryCounts,
                            libraryCount = libraryCount,
                            hiddenCategoryIds = hiddenCategoryIds,
                            pinnedCategoryIds = pinnedCategoryIds,
                            categorySortMode = sortMode
                        )
                    }.combine(searchQueryForBrowse) { dependencies, query ->
                        MovieCatalogParams(
                            providerId = provider.id,
                            allFavorites = dependencies.allFavorites,
                            customCategories = dependencies.customCategories,
                            providerCategories = dependencies.providerCategories,
                            pinnedProviderCategories = dependencies.pinnedProviderCategories,
                            unpinnedProviderCategories = dependencies.unpinnedProviderCategories,
                            providerCategoryCounts = dependencies.providerCategoryCounts,
                            libraryCount = dependencies.libraryCount,
                            hiddenCategoryIds = dependencies.hiddenCategoryIds,
                            pinnedCategoryIds = dependencies.pinnedCategoryIds,
                            categorySortMode = dependencies.categorySortMode,
                            query = query
                        )
                    }
                    .distinctUntilChangedBy { params ->
                        params.copy(
                            providerCategoryCounts = emptyMap(),
                            libraryCount = 0
                        )
                    }
                }
                .flatMapLatest { params ->
                    _previewBatchSize.flatMapLatest { batchSize ->
                        if (params.query.isBlank()) {
                            val categoryIds = params.providerCategories.take(batchSize).map { it.id }
                            if (categoryIds.isEmpty()) {
                                flow {
                                    emit(PreviewLoadResult(buildPreviewCatalog(params, emptyMap()), false, false))
                                }
                            } else {
                                movieRepository.getCategoryPreviewRows(
                                    providerId = params.providerId,
                                    categoryIds = categoryIds,
                                    limitPerCategory = VodBrowseDefaults.PREVIEW_ROW_LIMIT
                                ).map { providerPreviews ->
                                    val isLoading = categoryIds.all { id -> providerPreviews[id].isNullOrEmpty() }
                                    val hasMore = params.providerCategories.size > batchSize
                                    PreviewLoadResult(buildPreviewCatalog(params, providerPreviews), isLoading, hasMore)
                                }
                            }
                        } else if (params.query.length < MIN_SEARCH_QUERY_LENGTH) {
                            flow {
                                emit(PreviewLoadResult(
                                    buildSearchCatalog(
                                        movies = emptyList(),
                                        allFavorites = params.allFavorites,
                                        customCategories = params.customCategories,
                                        providerCategories = params.providerCategories,
                                        hiddenCategoryIds = params.hiddenCategoryIds
                                    ).copy(libraryCount = 0),
                                    false, false
                                ))
                            }
                        } else {
                            flow {
                                val searchResults = movieRepository.searchMovies(params.providerId, params.query).first()
                                emit(PreviewLoadResult(
                                    buildSearchCatalog(
                                        movies = searchResults,
                                        allFavorites = params.allFavorites,
                                        customCategories = params.customCategories,
                                        providerCategories = params.providerCategories,
                                        hiddenCategoryIds = params.hiddenCategoryIds
                                    ).copy(libraryCount = searchResults.size),
                                    false, false
                                ))
                            }
                        }
                    }
                }
                .collect { result ->
                    val snapshot = result.snapshot
                    val isReordering = _uiState.value.isReorderMode
                    val currentSelected = _uiState.value.selectedCategory
                    val preserveSelectedCategory = currentSelected != null && _searchQuery.value.isNotBlank()
                    val resolvedSelected = currentSelected?.takeIf { selected ->
                        val customCategoryNames = _uiState.value.categories.mapTo(linkedSetOf()) { it.name }
                        val providerCategoryNames = snapshot.providerCategories.mapTo(linkedSetOf()) { it.name }
                        preserveSelectedCategory ||
                            selected == _uiState.value.fullLibraryCategoryName ||
                            selected in snapshot.categoryNames ||
                            selected in providerCategoryNames ||
                            selected in customCategoryNames
                    }
                    val currentPinnedIds = _uiState.value.pinnedCategoryIds
                    _uiState.update {
                        it.copy(
                            moviesByCategory = snapshot.grouped,
                            categoryNames = snapshot.categoryNames,
                            categoryCounts = snapshot.categoryCounts,
                            libraryCount = snapshot.libraryCount,
                            providerCategories = snapshot.providerCategories,
                            pinnedCategoryIds = currentPinnedIds.ifEmpty { snapshot.pinnedCategoryIds },
                            selectedCategory = resolvedSelected,
                            selectedCategoryItems = if (resolvedSelected == null) emptyList() else it.selectedCategoryItems,
                            selectedCategoryLoadedCount = if (resolvedSelected == null) 0 else it.selectedCategoryLoadedCount,
                            selectedCategoryTotalCount = if (resolvedSelected == null) 0 else it.selectedCategoryTotalCount,
                            canLoadMoreSelectedCategory = if (resolvedSelected == null) false else it.canLoadMoreSelectedCategory,
                            filteredMovies = if (isReordering) it.filteredMovies else emptyList(),
                            isLoading = false,
                            isLoadingPreviewRows = result.isLoadingPreviewRows,
                            hasMorePreviewRows = result.hasMorePreviewRows,
                            errorMessage = null
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load movies") }
            }
        }

        viewModelScope.launch {
            preferencesRepository.vodViewMode.collectLatest { mode ->
                _uiState.update { it.copy(vodViewMode = VodViewMode.fromStorage(mode)) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.vodInfiniteScroll.collectLatest { enabled ->
                _uiState.update { it.copy(vodInfiniteScroll = enabled) }
            }
        }

        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .flatMapLatest { provider ->
                    combine(
                        favoriteRepository.getAllFavorites(provider.id, ContentType.MOVIE),
                        getCustomCategories(provider.id, ContentType.MOVIE),
                        movieRepository.getCategories(provider.id),
                        preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.MOVIE),
                        preferencesRepository.getCategorySortMode(provider.id, ContentType.MOVIE)
                    ) { values ->
                        val allFavorites = values[0] as List<com.streamvault.domain.model.Favorite>
                        val customCategories = values[1] as List<Category>
                        val providerCategories = values[2] as List<Category>
                        val hiddenCategoryIds = values[3] as Set<Long>
                        val sortMode = values[4] as CategorySortMode
                        MovieCategorySelectionDependencies(
                            allFavorites = allFavorites,
                            customCategories = customCategories,
                            providerCategories = applyProviderCategoryDisplayPreferences(
                                categories = providerCategories,
                                hiddenCategoryIds = hiddenCategoryIds,
                                sortMode = sortMode
                            ),
                            hiddenCategoryIds = hiddenCategoryIds
                        )
                    }.combine(
                        combine(
                            _uiState.map { it.selectedCategory }.distinctUntilChanged(),
                            _selectedCategoryLoadLimit,
                            searchQueryForBrowse,
                            _selectedLibraryFilterType,
                            _selectedLibrarySortBy
                        ) { selectedCategory, loadLimit, query, filterType, sortBy ->
                            SelectedMovieBrowseSelection(
                                selectedCategory = selectedCategory,
                                loadLimit = loadLimit,
                                query = query,
                                filterType = filterType,
                                sortBy = sortBy
                            )
                        }
                    ) { dependencies, selection ->
                        SelectedMovieCategoryRequest(
                            providerId = provider.id,
                            selectedCategory = selection.selectedCategory,
                            loadLimit = selection.loadLimit,
                            query = selection.query,
                            filterType = selection.filterType,
                            sortBy = selection.sortBy,
                            allFavorites = dependencies.allFavorites,
                            customCategories = dependencies.customCategories,
                            providerCategories = dependencies.providerCategories,
                            hiddenCategoryIds = dependencies.hiddenCategoryIds
                        )
                    }
                }
                .flatMapLatest { request ->
                    flow {
                        emit(loadSelectedCategoryItems(request))
                    }
                }
                .collect { snapshot ->
                    _uiState.update {
                        it.copy(
                            selectedCategoryItems = snapshot.items,
                            selectedCategoryLoadedCount = snapshot.loadedCount,
                            selectedCategoryTotalCount = snapshot.totalCount,
                            canLoadMoreSelectedCategory = snapshot.canLoadMore,
                            isLoadingSelectedCategory = false
                        )
                    }
                }
        }

        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .collectLatest { provider ->
                    launch {
                        getContinueWatching(
                            providerId = provider.id,
                            limit = 20,
                            scope = ContinueWatchingScope.MOVIES
                        )
                            .collect { result ->
                                _uiState.update {
                                    it.copy(
                                        continueWatching = when (result) {
                                            is ContinueWatchingResult.Items -> result.items
                                            ContinueWatchingResult.Degraded -> emptyList()
                                        }
                                    )
                                }
                            }
                    }
                }
        }

        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .flatMapLatest { provider ->
                    combine(
                        favoriteRepository.getAllFavorites(provider.id, ContentType.MOVIE),
                        playbackHistoryRepository.getRecentlyWatchedByProvider(provider.id, limit = 24),
                        movieRepository.getTopRatedPreview(provider.id, VodBrowseDefaults.PREVIEW_ROW_LIMIT),
                        movieRepository.getFreshPreview(provider.id, VodBrowseDefaults.PREVIEW_ROW_LIMIT)
                    ) { allFavorites, history, topRated, fresh ->
                        MovieLibraryLensDependencies(
                            providerId = provider.id,
                            allFavorites = allFavorites,
                            history = history,
                            topRated = topRated,
                            fresh = fresh
                        )
                    }
                }
                .collectLatest { dependencies ->
                    val globalFavoriteIds = dependencies.allFavorites
                        .asSequence()
                        .filter { it.groupId == null }
                        .map { it.contentId }
                        .toSet()
                    val favoriteIds = dependencies.allFavorites
                        .asSequence()
                        .filter { it.groupId == null }
                        .sortedBy { it.position }
                        .map { it.contentId }
                        .take(VodBrowseDefaults.PREVIEW_ROW_LIMIT)
                        .toList()
                    val continueIds = dependencies.history
                        .asSequence()
                        .filter { it.contentType == ContentType.MOVIE }
                        .sortedByDescending { it.lastWatchedAt }
                        .distinctBy { it.contentId }
                        .map { it.contentId }
                        .take(VodBrowseDefaults.PREVIEW_ROW_LIMIT)
                        .toList()

                    val favoritePreview = if (favoriteIds.isEmpty()) {
                        emptyList()
                    } else {
                        movieRepository.getMoviesByIds(favoriteIds).first().orderByIds(favoriteIds)
                    }.markMovieFavorites(globalFavoriteIds)
                    val continuePreview = if (continueIds.isEmpty()) {
                        emptyList()
                    } else {
                        movieRepository.getMoviesByIds(continueIds).first().orderByIds(continueIds)
                    }.markMovieFavorites(globalFavoriteIds)

                    _uiState.update {
                        it.copy(
                            libraryLensRows = mapOf(
                                MovieLibraryLens.FAVORITES to favoritePreview,
                                MovieLibraryLens.CONTINUE to continuePreview,
                                MovieLibraryLens.TOP_RATED to dependencies.topRated.markMovieFavorites(globalFavoriteIds),
                                MovieLibraryLens.FRESH to dependencies.fresh.markMovieFavorites(globalFavoriteIds)
                            ).filterValues { rows -> rows.isNotEmpty() }
                        )
                    }
                }
        }

        viewModelScope.launch {
            preferencesRepository.parentalControlLevel.collect { level ->
                _uiState.update { it.copy(parentalControlLevel = level) }
            }
        }

        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .flatMapLatest { provider ->
                    parentalControlManager.unlockedCategoriesForProvider(provider.id)
                }
                .collectLatest { unlockedIds ->
                    _uiState.update { it.copy(unlockedCategoryIds = unlockedIds) }
                }
        }

        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .flatMapLatest { provider -> getCustomCategories(provider.id, ContentType.MOVIE) }
                .collect { categories ->
                    _uiState.update { it.copy(categories = categories) }
                }
        }

        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .flatMapLatest { provider ->
                    preferencesRepository.getPinnedCategoryIds(provider.id, ContentType.MOVIE)
                }
                .collect { pinnedIds ->
                    _uiState.update { it.copy(pinnedCategoryIds = pinnedIds) }
                }
        }
    }

    fun selectCategory(categoryName: String?) {
        _previewBatchSize.value = INITIAL_PREVIEW_BATCH_SIZE
        activeProviderId?.let { providerId ->
            parentalControlManager.retainUnlockedCategory(
                providerId = providerId,
                categoryId = resolveProviderCategoryId(categoryName)
            )
        }
        selectVodCategory(
            categoryName = categoryName,
            selectedCategoryLoadLimit = _selectedCategoryLoadLimit,
            selectedLibraryFilterType = _selectedLibraryFilterType,
            selectedLibrarySortBy = _selectedLibrarySortBy,
            uiState = _uiState
        ) { selectedCategory, filterType, sortBy, isLoadingSelectedCategory ->
            copy(
                selectedCategory = selectedCategory,
                selectedLibraryFilterType = filterType,
                selectedLibrarySortBy = sortBy,
                selectedCategoryItems = emptyList(),
                selectedCategoryLoadedCount = 0,
                selectedCategoryTotalCount = 0,
                canLoadMoreSelectedCategory = false,
                isLoadingSelectedCategory = isLoadingSelectedCategory
            )
        }
    }

    fun selectFullLibraryBrowse() {
        selectCategory(VodBrowseDefaults.FULL_LIBRARY_CATEGORY)
    }

    fun loadMoreSelectedCategory() {
        incrementVodSelectedCategoryLoadLimit(
            canLoadMore = _uiState.value.canLoadMoreSelectedCategory,
            selectedCategoryLoadLimit = _selectedCategoryLoadLimit
        )
    }

    fun loadMorePreviewRows() {
        if (_uiState.value.hasMorePreviewRows && !_uiState.value.isLoadingPreviewRows) {
            _previewBatchSize.update { it + INITIAL_PREVIEW_BATCH_SIZE }
        }
    }

    fun setSearchQuery(query: String) {
        _previewBatchSize.value = INITIAL_PREVIEW_BATCH_SIZE
        setVodSearchQuery(query, _searchQuery, _uiState) { updatedQuery ->
            copy(searchQuery = updatedQuery)
        }
    }

    fun resetPreviewRowsForScreenEntry() {
        _previewBatchSize.value = INITIAL_PREVIEW_BATCH_SIZE
        _uiState.update { state ->
            if (state.selectedCategory != null || state.searchQuery.isNotBlank()) {
                state
            } else {
                val providerNames = state.providerCategories.mapTo(linkedSetOf()) { it.name }
                val initialProviderNames = state.providerCategories
                    .take(INITIAL_PREVIEW_BATCH_SIZE)
                    .mapTo(linkedSetOf()) { it.name }
                fun keepRow(name: String): Boolean = name !in providerNames || name in initialProviderNames
                state.copy(
                    moviesByCategory = state.moviesByCategory.filterKeys(::keepRow),
                    categoryNames = state.categoryNames.filter(::keepRow),
                    categoryCounts = state.categoryCounts.filterKeys(::keepRow),
                    hasMorePreviewRows = state.providerCategories.size > INITIAL_PREVIEW_BATCH_SIZE
                )
            }
        }
    }

    fun setSelectedLibraryFilterType(filterType: LibraryFilterType) {
        setVodLibraryFilterType(
            filterType = filterType,
            selectedLibraryFilterType = _selectedLibraryFilterType,
            selectedCategoryLoadLimit = _selectedCategoryLoadLimit,
            uiState = _uiState,
            hasSelectedCategory = { it.selectedCategory != null }
        ) { updatedFilterType, isLoadingSelectedCategory ->
            copy(
                selectedLibraryFilterType = updatedFilterType,
                selectedCategoryItems = emptyList(),
                selectedCategoryLoadedCount = 0,
                selectedCategoryTotalCount = 0,
                canLoadMoreSelectedCategory = false,
                isLoadingSelectedCategory = isLoadingSelectedCategory
            )
        }
    }

    fun setSelectedLibrarySortBy(sortBy: LibrarySortBy) {
        setVodLibrarySortBy(
            sortBy = sortBy,
            selectedLibrarySortBy = _selectedLibrarySortBy,
            selectedCategoryLoadLimit = _selectedCategoryLoadLimit,
            uiState = _uiState,
            hasSelectedCategory = { it.selectedCategory != null }
        ) { updatedSortBy, isLoadingSelectedCategory ->
            copy(
                selectedLibrarySortBy = updatedSortBy,
                selectedCategoryItems = emptyList(),
                selectedCategoryLoadedCount = 0,
                selectedCategoryTotalCount = 0,
                canLoadMoreSelectedCategory = false,
                isLoadingSelectedCategory = isLoadingSelectedCategory
            )
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        return preferencesRepository.verifyParentalPin(pin)
    }

    fun unlockCategory(category: Category) {
        viewModelScope.launch {
            val activeProviderId = providerRepository.getActiveProvider().first()?.id ?: return@launch
            parentalControlManager.unlockCategory(activeProviderId, kotlin.math.abs(category.id))
            if (_uiState.value.selectedCategory != category.name) {
                selectCategory(category.name)
            }
        }
    }

    fun onShowDialog(movie: Movie) {
        viewModelScope.launch {
            val rawMovieIds = movie.rawMovieIdsForActions()
            val memberships = rawMovieIds
                .flatMap { rawMovieId -> favoriteRepository.getGroupMemberships(movie.providerId, rawMovieId, ContentType.MOVIE) }
                .map { groupId -> -kotlin.math.abs(groupId) }
                .distinct()
            val isFavorite = rawMovieIds.any { rawMovieId ->
                favoriteRepository.isFavorite(movie.providerId, rawMovieId, ContentType.MOVIE)
            }
            _uiState.update {
                it.copy(
                    showDialog = true,
                    selectedMovieForDialog = movie.copy(isFavorite = isFavorite),
                    dialogGroupMemberships = memberships
                )
            }
        }
    }

    fun onDismissDialog() {
        _uiState.update { it.copy(showDialog = false, selectedMovieForDialog = null) }
    }

    fun addFavorite(movie: Movie) {
        viewModelScope.launch {
            setVodFavorite(movie.providerId, movie.selectedRawMovieId(), ContentType.MOVIE, true, favoriteRepository)
            _uiState.update { it.copy(selectedMovieForDialog = movie.copy(isFavorite = true)) }
        }
    }

    fun removeFavorite(movie: Movie) {
        viewModelScope.launch {
            movie.rawMovieIdsForActions().forEach { rawMovieId ->
                setVodFavorite(movie.providerId, rawMovieId, ContentType.MOVIE, false, favoriteRepository)
            }
            _uiState.update { it.copy(selectedMovieForDialog = movie.copy(isFavorite = false)) }
        }
    }

    fun addToGroup(movie: Movie, group: Category) {
        viewModelScope.launch {
            val memberships = updateVodGroupMembership(
                providerId = movie.providerId,
                itemId = movie.selectedRawMovieId(),
                groupId = group.id,
                contentType = ContentType.MOVIE,
                shouldBeMember = true,
                favoriteRepository = favoriteRepository
            )
            _uiState.update { it.copy(dialogGroupMemberships = memberships) }
        }
    }

    fun removeFromGroup(movie: Movie, group: Category) {
        viewModelScope.launch {
            movie.rawMovieIdsForActions().forEach { rawMovieId ->
                updateVodGroupMembership(
                    providerId = movie.providerId,
                    itemId = rawMovieId,
                    groupId = group.id,
                    contentType = ContentType.MOVIE,
                    shouldBeMember = false,
                    favoriteRepository = favoriteRepository
                )
            }
            val memberships = movie.rawMovieIdsForActions()
                .flatMap { rawMovieId -> favoriteRepository.getGroupMemberships(movie.providerId, rawMovieId, ContentType.MOVIE) }
                .map { groupId -> -kotlin.math.abs(groupId) }
                .distinct()
            _uiState.update { it.copy(dialogGroupMemberships = memberships) }
        }
    }

    fun createCustomGroup(name: String) {
        val normalizedName = name.trim()
        val validationError = validateGroupName(normalizedName)
        if (validationError != null) {
            _uiState.update { it.copy(userMessage = validationError) }
            return
        }

        viewModelScope.launch {
            val providerId = activeProviderId ?: return@launch
            when (val result = createVodGroup(providerId, normalizedName, ContentType.MOVIE, favoriteRepository)) {
                is Result.Success -> {
                    val selectedMovie = _uiState.value.selectedMovieForDialog
                    val memberships = if (selectedMovie != null) {
                        updateVodGroupMembership(
                            providerId = selectedMovie.providerId,
                            itemId = selectedMovie.selectedRawMovieId(),
                            groupId = result.data.id,
                            contentType = ContentType.MOVIE,
                            shouldBeMember = true,
                            favoriteRepository = favoriteRepository
                        )
                    } else {
                        _uiState.value.dialogGroupMemberships
                    }
                    _uiState.update {
                        it.copy(
                            dialogGroupMemberships = memberships,
                            userMessage = if (selectedMovie != null) {
                                "Created group $normalizedName and added ${selectedMovie.name}"
                            } else {
                                "Created group $normalizedName"
                            }
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update { it.copy(userMessage = result.message) }
                }
                Result.Loading -> Unit
            }
        }
    }

    fun showCategoryOptions(categoryName: String) {
        val matchedCategory = _uiState.value.categories.find { it.name == categoryName }
            ?: _uiState.value.providerCategories.find { it.name == categoryName }
            ?: if (categoryName == VodBrowseDefaults.FAVORITES_CATEGORY) {
                Category(
                    id = VodBrowseDefaults.FAVORITES_SENTINEL_ID,
                    name = VodBrowseDefaults.FAVORITES_CATEGORY,
                    type = ContentType.MOVIE,
                    isVirtual = true
                )
            } else {
                null
            }

        if (matchedCategory != null) {
            _uiState.update { it.copy(selectedCategoryForOptions = matchedCategory) }
        }
    }

    fun dismissCategoryOptions() {
        _uiState.update { it.copy(selectedCategoryForOptions = null) }
    }

    private fun resolveProviderCategoryId(categoryName: String?): Long? =
        _uiState.value.providerCategories
            .firstOrNull { it.name == categoryName && !it.isVirtual && it.id > 0L }
            ?.id

    fun hideCategory(category: Category) {
        if (category.isVirtual) return
        viewModelScope.launch {
            val providerId = providerRepository.getActiveProvider().first()?.id ?: return@launch
            preferencesRepository.setCategoryHidden(
                providerId = providerId,
                type = ContentType.MOVIE,
                categoryId = category.id,
                hidden = true
            )
            if (_uiState.value.selectedCategory == category.name) {
                selectCategory(null)
            } else {
                dismissCategoryOptions()
            }
            _uiState.update { it.copy(userMessage = "Hidden category ${category.name}") }
        }
    }

    fun toggleCategoryPinned(category: Category) {
        if (category.isVirtual) return
        val providerId = activeProviderId ?: return
        val shouldPin = category.id !in _uiState.value.pinnedCategoryIds
        viewModelScope.launch {
            preferencesRepository.setCategoryPinned(
                providerId = providerId,
                type = ContentType.MOVIE,
                categoryId = category.id,
                pinned = shouldPin
            )
            dismissCategoryOptions()
            _uiState.update {
                it.copy(
                    userMessage = if (shouldPin) "Pinned ${category.name}" else "Unpinned ${category.name}"
                )
            }
        }
    }

    fun requestRenameGroup(category: Category) {
        if (!category.isVirtual || category.id == VodBrowseDefaults.FAVORITES_SENTINEL_ID) return
        _uiState.update {
            it.copy(
                selectedCategoryForOptions = null,
                showRenameGroupDialog = true,
                groupToRename = category,
                renameGroupError = null
            )
        }
    }

    fun cancelRenameGroup() {
        _uiState.update {
            it.copy(
                showRenameGroupDialog = false,
                groupToRename = null,
                renameGroupError = null
            )
        }
    }

    fun confirmRenameGroup(name: String) {
        val category = _uiState.value.groupToRename ?: return
        val normalizedName = name.trim()
        val validationError = validateGroupName(normalizedName, currentGroupId = category.id)
        if (validationError != null) {
            _uiState.update { it.copy(renameGroupError = validationError) }
            return
        }

        viewModelScope.launch {
            favoriteRepository.renameGroup(-category.id, normalizedName)
            _uiState.update {
                it.copy(
                    showRenameGroupDialog = false,
                    groupToRename = null,
                    renameGroupError = null,
                    userMessage = "Renamed group to $normalizedName"
                )
            }
        }
    }

    fun requestDeleteGroup(category: Category) {
        if (!category.isVirtual || category.id == VodBrowseDefaults.FAVORITES_SENTINEL_ID) return
        _uiState.update {
            it.copy(
                selectedCategoryForOptions = null,
                showDeleteGroupDialog = true,
                groupToDelete = category
            )
        }
    }

    fun cancelDeleteGroup() {
        _uiState.update { it.copy(showDeleteGroupDialog = false, groupToDelete = null) }
    }

    fun confirmDeleteGroup() {
        val category = _uiState.value.groupToDelete ?: return
        viewModelScope.launch {
            favoriteRepository.deleteGroup(-category.id)
            _uiState.update {
                it.copy(
                    showDeleteGroupDialog = false,
                    groupToDelete = null,
                    userMessage = "Deleted group ${category.name}"
                )
            }
        }
    }

    fun userMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun enterCategoryReorderMode(category: Category) {
        dismissCategoryOptions()
        viewModelScope.launch {
            val moviesInView = loadReorderMovies(category)
            _uiState.update {
                it.copy(
                    isReorderMode = true,
                    reorderCategory = category,
                    filteredMovies = moviesInView
                )
            }
        }
    }

    fun exitCategoryReorderMode() {
        _uiState.update {
            it.copy(
                isReorderMode = false,
                reorderCategory = null,
                filteredMovies = emptyList()
            )
        }
    }

    fun moveItemUp(movie: Movie) {
        val reordered = moveVodItemUp(_uiState.value.filteredMovies, movie)
        if (reordered !== _uiState.value.filteredMovies) {
            _uiState.update { it.copy(filteredMovies = reordered) }
        }
    }

    fun moveItemDown(movie: Movie) {
        val reordered = moveVodItemDown(_uiState.value.filteredMovies, movie)
        if (reordered !== _uiState.value.filteredMovies) {
            _uiState.update { it.copy(filteredMovies = reordered) }
        }
    }

    fun saveReorder() {
        val state = _uiState.value
        val category = state.reorderCategory ?: return
        val currentList = state.filteredMovies

        exitCategoryReorderMode()

        viewModelScope.launch {
            try {
                saveVodReorder(
                    providerId = activeProviderId ?: return@launch,
                    category = category,
                    currentItems = currentList,
                    contentType = ContentType.MOVIE,
                    favoriteRepository = favoriteRepository,
                    itemId = Movie::id
                )
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun loadReorderMovies(category: Category): List<Movie> {
        return loadVodReorderItems(
            providerId = activeProviderId ?: return emptyList(),
            category = category,
            contentType = ContentType.MOVIE,
            favoriteRepository = favoriteRepository,
            loadByIds = { ids -> movieRepository.getMoviesByIds(ids).first() },
            itemId = Movie::id
        )
    }

    private suspend fun buildPreviewCatalog(
        params: MovieCatalogParams,
        providerPreviews: Map<Long?, List<Movie>>
    ): MovieCatalogSnapshot {
        val snapshot = buildVodPreviewCatalog(
            allFavorites = params.allFavorites,
            customCategories = params.customCategories,
            providerCategories = params.providerCategories,
            providerCategoryCounts = params.providerCategoryCounts,
            libraryCount = params.libraryCount,
            hiddenProviderCategoryIds = params.hiddenCategoryIds,
            loadItemsByIds = { ids -> movieRepository.getMoviesByIds(ids).first() },
            providerPreviews = providerPreviews,
            itemIds = { movie -> movie.rawMovieIdsForActions() },
            itemCategoryId = Movie::categoryId,
            copyWithFavorite = { movie, isFavorite -> movie.copy(isFavorite = isFavorite) }
        )
        return MovieCatalogSnapshot(
            grouped = snapshot.grouped,
            categoryNames = snapshot.categoryNames,
            categoryCounts = snapshot.categoryCounts,
            libraryCount = snapshot.libraryCount,
            providerCategories = params.providerCategories
        )
    }

    private fun buildSearchCatalog(
        movies: List<Movie>,
        allFavorites: List<com.streamvault.domain.model.Favorite>,
        customCategories: List<Category>,
        providerCategories: List<Category>,
        hiddenCategoryIds: Set<Long>
    ): MovieCatalogSnapshot {
        val snapshot = buildVodSearchCatalog(
            items = movies,
            allFavorites = allFavorites,
            customCategories = customCategories,
            providerCategories = providerCategories,
            hiddenProviderCategoryIds = hiddenCategoryIds,
            itemIds = { movie -> movie.rawMovieIdsForActions() },
            itemCategoryId = Movie::categoryId,
            itemCategoryName = Movie::categoryName,
            copyWithFavorite = { movie, isFavorite -> movie.copy(isFavorite = isFavorite) },
            uncategorizedName = UNCATEGORIZED
        )
        return MovieCatalogSnapshot(
            grouped = snapshot.grouped,
            categoryNames = snapshot.categoryNames,
            categoryCounts = snapshot.categoryCounts,
            libraryCount = snapshot.libraryCount,
            providerCategories = providerCategories
        )
    }

    private fun isLikelyProviderWideStalkerCategory(category: Category): Boolean {
        val name = category.name.trim().lowercase()
        return name == "*" ||
            name == "all" ||
            name == "all movies" ||
            name == "all vod" ||
            name == "all videos" ||
            name == "all categories"
    }

    private suspend fun loadSelectedCategoryItems(
        request: SelectedMovieCategoryRequest
    ): SelectedMovieCategorySnapshot {
        if (request.selectedCategory.isNullOrBlank()) {
            return SelectedMovieCategorySnapshot()
        }
        val effectiveQuery = request.query.takeIf { it.trim().length >= MIN_SEARCH_QUERY_LENGTH }.orEmpty()

        val globalFavoriteIds = request.allFavorites
            .asSequence()
            .filter { it.groupId == null }
            .map { it.contentId }
            .toSet()

        val (selectedItems, totalCount, hasMoreRemote) = when (request.selectedCategory) {
            VodBrowseDefaults.FULL_LIBRARY_CATEGORY -> {
                val result = movieRepository
                    .browseMovies(
                        LibraryBrowseQuery(
                            providerId = request.providerId,
                            sortBy = request.sortBy,
                            filterBy = LibraryFilterBy(type = request.filterType),
                            searchQuery = effectiveQuery,
                            limit = request.loadLimit,
                            offset = 0
                        )
                    )
                    .first()
                Triple(
                    result.items.filterNot { movie -> movie.categoryId in request.hiddenCategoryIds },
                    result.totalCount,
                    result.hasMoreRemote
                )
            }
            VodBrowseDefaults.FAVORITES_CATEGORY -> {
                val ids = request.allFavorites
                    .asSequence()
                    .filter { it.groupId == null }
                    .sortedBy { it.position }
                    .map { it.contentId }
                    .toList()
                val fetchIds = if (effectiveQuery.isBlank() && request.filterType == LibraryFilterType.ALL && request.sortBy == LibrarySortBy.LIBRARY) {
                    ids.take(request.loadLimit + FAVORITE_ID_FETCH_BUFFER)
                } else {
                    ids
                }
                val items = if (ids.isEmpty()) {
                    emptyList()
                } else {
                    movieRepository.getMoviesByIds(fetchIds).first()
                        .filterNot { movie -> movie.categoryId in request.hiddenCategoryIds }
                        .orderByIds(fetchIds)
                }
                val filteredItems = applyLocalBrowseToMovies(
                    items,
                    request.filterType,
                    request.sortBy,
                    effectiveQuery
                )
                Triple(
                    filteredItems.take(request.loadLimit),
                    if (fetchIds.size == ids.size) filteredItems.size else ids.size,
                    false
                )
            }
            else -> {
                val customCategory = request.customCategories.firstOrNull { it.name == request.selectedCategory }
                if (customCategory != null) {
                    val ids = request.allFavorites
                        .asSequence()
                        .filter { matchesVodGroupMembership(it.groupId, customCategory.id) }
                        .sortedBy { it.position }
                        .map { it.contentId }
                        .toList()
                    val fetchIds = if (effectiveQuery.isBlank() && request.filterType == LibraryFilterType.ALL && request.sortBy == LibrarySortBy.LIBRARY) {
                        ids.take(request.loadLimit + FAVORITE_ID_FETCH_BUFFER)
                    } else {
                        ids
                    }
                    val items = if (ids.isEmpty()) {
                        emptyList()
                    } else {
                        movieRepository.getMoviesByIds(fetchIds).first()
                            .filterNot { movie -> movie.categoryId in request.hiddenCategoryIds }
                            .orderByIds(fetchIds)
                    }
                    val filteredItems = applyLocalBrowseToMovies(
                        items,
                        request.filterType,
                        request.sortBy,
                        effectiveQuery
                    )
                    Triple(
                        filteredItems.take(request.loadLimit),
                        if (fetchIds.size == ids.size) filteredItems.size else ids.size,
                        false
                    )
                } else {
                    val providerCategory = request.providerCategories.firstOrNull { it.name == request.selectedCategory }
                    if (providerCategory != null) {
                        val result = movieRepository
                            .browseMovies(
                                LibraryBrowseQuery(
                                    providerId = request.providerId,
                                    categoryId = providerCategory.id,
                                    sortBy = request.sortBy,
                                    filterBy = LibraryFilterBy(type = request.filterType),
                                    searchQuery = effectiveQuery,
                                    limit = request.loadLimit,
                                    offset = 0
                                )
                            )
                            .first()
                        Triple(result.items, result.totalCount, result.hasMoreRemote)
                    } else {
                        Triple(emptyList<Movie>(), 0, false)
                    }
                }
            }
        }

        val enrichedItems = selectedItems.markMovieFavorites(globalFavoriteIds)
        return SelectedMovieCategorySnapshot(
            items = enrichedItems,
            loadedCount = enrichedItems.size,
            totalCount = totalCount,
            canLoadMore = totalCount > enrichedItems.size || hasMoreRemote
        )
    }

    private fun validateGroupName(name: String, currentGroupId: Long? = null): String? {
        if (name.isBlank()) return "Enter a group name"
        if (name.equals("favorites", ignoreCase = true)) return "Favorites is reserved"

        val duplicate = _uiState.value.categories.any { category ->
            category.id != currentGroupId && category.name.equals(name, ignoreCase = true)
        }
        return if (duplicate) "A movie group with that name already exists" else null
    }

    private fun List<Movie>.orderByIds(ids: List<Long>): List<Movie> {
        val movieMap = associateBy { it.id }
        return ids.mapNotNull { movieMap[it] }
    }

    private fun List<Movie>.markMovieFavorites(globalFavoriteIds: Set<Long>): List<Movie> = map { movie ->
        movie.copy(isFavorite = movie.rawMovieIdsForActions().any { rawMovieId -> rawMovieId in globalFavoriteIds })
    }

    private fun Movie.selectedRawMovieId(): Long = selectedVariantId ?: id

    private fun Movie.rawMovieIdsForActions(): List<Long> =
        variants.map { it.rawMovieId }.ifEmpty { listOf(selectedRawMovieId()) }

    private fun applyLocalBrowseToMovies(
        items: List<Movie>,
        filterType: LibraryFilterType,
        sortBy: LibrarySortBy,
        query: String
    ): List<Movie> {
        val normalizedQuery = query.trim().lowercase()
        val searched = if (normalizedQuery.isBlank()) {
            items
        } else {
            items.filter { movie ->
                movie.name.contains(normalizedQuery, ignoreCase = true) ||
                    (movie.plot?.contains(normalizedQuery, ignoreCase = true) == true) ||
                    (movie.genre?.contains(normalizedQuery, ignoreCase = true) == true)
            }
        }
        val filtered = when (filterType) {
            LibraryFilterType.ALL -> searched
            LibraryFilterType.FAVORITES -> searched.filter { it.isFavorite }
            LibraryFilterType.IN_PROGRESS -> searched.filter { movie ->
                movie.watchProgress > 0L && !isPlaybackComplete(
                    movie.watchProgress,
                    movie.durationSeconds.takeIf { it > 0 }?.times(1000L) ?: 0L
                )
            }
            LibraryFilterType.UNWATCHED -> searched.filter { it.watchProgress <= 0L }
            LibraryFilterType.RECENTLY_UPDATED -> searched.filter { movieAddedScore(it) > 0L }
            LibraryFilterType.TOP_RATED -> searched.filter { it.rating > 0f }
        }
        return when (sortBy) {
            LibrarySortBy.LIBRARY -> filtered
            LibrarySortBy.TITLE -> filtered.sortedBy { it.name.lowercase() }
            LibrarySortBy.RELEASE -> filtered.sortedByDescending(::movieReleaseScore)
            LibrarySortBy.UPDATED -> filtered.sortedByDescending(::movieAddedScore)
            LibrarySortBy.RATING -> filtered.sortedByDescending { it.rating }
            LibrarySortBy.WATCH_COUNT -> filtered.sortedByDescending { it.lastWatchedAt }
        }
    }

    private fun movieReleaseScore(movie: Movie): Long =
        movie.releaseDate?.filter { it.isDigit() }?.take(8)?.toLongOrNull()
            ?: movie.year?.toLongOrNull()
            ?: movie.addedAt.takeIf { it > 0L }
            ?: 0L

    private fun movieAddedScore(movie: Movie): Long = movie.addedAt.takeIf { it > 0L } ?: 0L
}

private data class MovieCatalogParams(
    val providerId: Long,
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val pinnedProviderCategories: List<Category> = emptyList(),
    val unpinnedProviderCategories: List<Category> = emptyList(),
    val providerCategoryCounts: Map<Long, Int>,
    val libraryCount: Int,
    val hiddenCategoryIds: Set<Long>,
    val pinnedCategoryIds: Set<Long> = emptySet(),
    val categorySortMode: CategorySortMode,
    val query: String
)

private data class MovieCatalogDependencies(
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val pinnedProviderCategories: List<Category> = emptyList(),
    val unpinnedProviderCategories: List<Category> = emptyList(),
    val providerCategoryCounts: Map<Long, Int>,
    val libraryCount: Int,
    val hiddenCategoryIds: Set<Long>,
    val pinnedCategoryIds: Set<Long> = emptySet(),
    val categorySortMode: CategorySortMode
)

private data class MovieCatalogSnapshot(
    val grouped: Map<String, List<Movie>>,
    val categoryNames: List<String>,
    val categoryCounts: Map<String, Int>,
    val libraryCount: Int,
    val providerCategories: List<Category>,
    val pinnedCategoryIds: Set<Long> = emptySet()
)

private data class MovieLibraryLensDependencies(
    val providerId: Long,
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val history: List<PlaybackHistory>,
    val topRated: List<Movie>,
    val fresh: List<Movie>
)

private data class MovieCategorySelectionDependencies(
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val hiddenCategoryIds: Set<Long>
)

private data class SelectedMovieCategoryRequest(
    val providerId: Long,
    val selectedCategory: String?,
    val loadLimit: Int,
    val query: String,
    val filterType: LibraryFilterType,
    val sortBy: LibrarySortBy,
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val hiddenCategoryIds: Set<Long>
)

private data class SelectedMovieBrowseSelection(
    val selectedCategory: String?,
    val loadLimit: Int,
    val query: String,
    val filterType: LibraryFilterType,
    val sortBy: LibrarySortBy
)

private data class SelectedMovieCategorySnapshot(
    val items: List<Movie> = emptyList(),
    val loadedCount: Int = 0,
    val totalCount: Int = 0,
    val canLoadMore: Boolean = false
)

data class MoviesUiState(
    val moviesByCategory: Map<String, List<Movie>> = emptyMap(),
    val categoryNames: List<String> = emptyList(),
    val categoryCounts: Map<String, Int> = emptyMap(),
    val libraryCount: Int = 0,
    val favoriteCategoryName: String = "\u2605 Favorites",
    val fullLibraryCategoryName: String = "__full_library__",
    val libraryLensRows: Map<MovieLibraryLens, List<Movie>> = emptyMap(),
    val selectedCategory: String? = null,
    val selectedCategoryItems: List<Movie> = emptyList(),
    val selectedCategoryLoadedCount: Int = 0,
    val selectedCategoryTotalCount: Int = 0,
    val canLoadMoreSelectedCategory: Boolean = false,
    val isLoadingSelectedCategory: Boolean = false,
    val searchQuery: String = "",
    val selectedLibraryFilterType: LibraryFilterType = LibraryFilterType.ALL,
    val selectedLibrarySortBy: LibrarySortBy = LibrarySortBy.LIBRARY,
    val vodViewMode: VodViewMode = VodViewMode.MODERN,
    val vodInfiniteScroll: Boolean = true,
    val continueWatching: List<PlaybackHistory> = emptyList(),
    val hasProviders: Boolean = false,
    val hasActiveProvider: Boolean = false,
    val isLoading: Boolean = true,
    val isLoadingPreviewRows: Boolean = false,
    val hasMorePreviewRows: Boolean = false,
    val parentalControlLevel: Int = 0,
    val unlockedCategoryIds: Set<Long> = emptySet(),
    val showDialog: Boolean = false,
    val selectedMovieForDialog: Movie? = null,
    val categories: List<Category> = emptyList(),
    val providerCategories: List<Category> = emptyList(),
    val pinnedCategoryIds: Set<Long> = emptySet(),
    val dialogGroupMemberships: List<Long> = emptyList(),
    val userMessage: String? = null,
    val selectedCategoryForOptions: Category? = null,
    val showRenameGroupDialog: Boolean = false,
    val groupToRename: Category? = null,
    val renameGroupError: String? = null,
    val showDeleteGroupDialog: Boolean = false,
    val groupToDelete: Category? = null,
    val isReorderMode: Boolean = false,
    val reorderCategory: Category? = null,
    val filteredMovies: List<Movie> = emptyList(),
    val errorMessage: String? = null
)
