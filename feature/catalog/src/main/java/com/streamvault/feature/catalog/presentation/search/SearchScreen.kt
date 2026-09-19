package com.streamvault.feature.catalog.presentation.search

import androidx.annotation.StringRes
import com.streamvault.core.ui.interaction.TvClickableSurface

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.tv.material3.*
import com.streamvault.feature.catalog.R
import com.streamvault.feature.catalog.presentation.components.CategoryRow
import com.streamvault.core.ui.components.SearchInput
import com.streamvault.feature.catalog.presentation.components.ChannelCard
import com.streamvault.feature.catalog.presentation.components.CatalogChannelProgressTicker
import com.streamvault.feature.catalog.presentation.components.MovieCard
import com.streamvault.feature.catalog.presentation.components.SeriesCard
import com.streamvault.core.ui.components.TvEmptyState
import com.streamvault.feature.catalog.api.CatalogNavigationChrome
import com.streamvault.feature.catalog.api.CatalogScaffoldContent
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.theme.*
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.CatalogCompleteness
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.SearchHistoryScope
import com.streamvault.domain.model.Series
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.SearchContent
import com.streamvault.domain.usecase.SearchContentScope
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.util.AdultContentVisibilityPolicy
import com.streamvault.core.navigation.AppDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val searchContent: SearchContent,
    private val preferencesRepository: com.streamvault.data.preferences.PreferencesRepository,
    private val parentalControlManager: ParentalControlManager,
    private val favoriteRepository: FavoriteRepository,
    private val categoryRepository: CategoryRepository,
    private val recordingManager: RecordingManager
) : ViewModel() {
    private companion object {
        const val MAX_RESULTS_PER_SECTION = 120
        const val MAX_RECENT_QUERIES = 6
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selectedTab = MutableStateFlow(SearchTab.ALL)
    val selectedTab: StateFlow<SearchTab> = _selectedTab.asStateFlow()

    private val _parentalControlLevel = MutableStateFlow(0)
    private val _activeProviderId = MutableStateFlow<Long?>(null)

    private val _recordingChannelIds = MutableStateFlow<Set<Long>>(emptySet())
    val recordingChannelIds: StateFlow<Set<Long>> = _recordingChannelIds.asStateFlow()

    private val _scheduledChannelIds = MutableStateFlow<Set<Long>>(emptySet())
    val scheduledChannelIds: StateFlow<Set<Long>> = _scheduledChannelIds.asStateFlow()

    private val unlockedCategoryIds = providerRepository.getActiveProvider()
        .onEach { provider -> _activeProviderId.value = provider?.id }
        .flatMapLatest { provider ->
            provider?.let { parentalControlManager.unlockedCategoriesForProvider(it.id) } ?: flowOf(emptySet())
        }

    init {
        viewModelScope.launch {
            preferencesRepository.parentalControlLevel.collect { level ->
                _parentalControlLevel.value = level
            }
        }
        viewModelScope.launch {
            recordingManager.observeRecordingItems().collect { items ->
                _recordingChannelIds.value = items
                    .filter { it.status == RecordingStatus.RECORDING }
                    .map { it.channelId }.toSet()
                _scheduledChannelIds.value = items
                    .filter { it.status == RecordingStatus.SCHEDULED }
                    .map { it.channelId }.toSet()
            }
        }
    }

    val recentQueries: StateFlow<List<String>> = combine(
        _selectedTab,
        _activeProviderId
    ) { tab, providerId ->
        tab.toSearchHistoryScope() to providerId
    }.flatMapLatest { (scope, providerId) ->
        preferencesRepository.getRecentSearchQueries(
            scope = scope,
            providerId = providerId,
            limit = MAX_RECENT_QUERIES
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(FlowPreview::class)
    val uiState: StateFlow<SearchUiState> = combine(
        providerRepository.getActiveProvider(),
        _query.debounce(300),
        _selectedTab,
        _parentalControlLevel,
        unlockedCategoryIds
    ) { provider, query, tab, level, unlockedIds ->
        SearchFilterParams(provider, query, tab, level, unlockedIds)
    }.distinctUntilChanged().flatMapLatest { params ->
        val provider = params.provider
        val query = params.query
        val tab = params.tab
        val level = params.level
        val unlockedIds = params.unlockedCategoryIds

        val trimmedQueryLength = query.trim().length
        if (provider == null || trimmedQueryLength < 2) {
            flowOf(
                SearchUiState(
                    parentalControlLevel = level,
                    hasActiveProvider = provider != null,
                    queryLength = trimmedQueryLength,
                    unlockedCategoryIds = unlockedIds
                )
            )
        } else {
            searchContent(
                providerId = provider.id,
                query = query,
                scope = tab.toSearchScope(),
                maxResultsPerSection = MAX_RESULTS_PER_SECTION
            ).map { results ->
                val filterAdult = !AdultContentVisibilityPolicy.showInAggregatedSurfaces(level)
                SearchUiState(
                    channels = if (filterAdult)
                        results.channels.filterNot { it.isAdult || it.isUserProtected }
                    else results.channels,
                    movies = if (filterAdult)
                        results.movies.filterNot { it.isAdult || it.isUserProtected }
                    else results.movies,
                    series = if (filterAdult)
                        results.series.filterNot { it.isAdult || it.isUserProtected }
                    else results.series,
                    isLoading = false,
                    hasSearched = true,
                    hasSearchError = results.isPartialResult &&
                        results.catalogCompleteness == CatalogCompleteness.COMPLETE,
                    catalogCompleteness = results.catalogCompleteness,
                    parentalControlLevel = level,
                    hasActiveProvider = true,
                    queryLength = trimmedQueryLength,
                    unlockedCategoryIds = unlockedIds
                )
            }.onStart {
                emit(
                    SearchUiState(
                        isLoading = true,
                        hasSearched = true,
                        parentalControlLevel = level,
                        hasActiveProvider = true,
                        queryLength = trimmedQueryLength,
                        unlockedCategoryIds = unlockedIds
                    )
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchUiState())

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun onSearchSubmitted() {
        val normalizedQuery = _query.value.trim()
        if (normalizedQuery.length < 2) return

        _query.value = normalizedQuery
        viewModelScope.launch {
            preferencesRepository.recordRecentSearchQuery(
                query = normalizedQuery,
                scope = _selectedTab.value.toSearchHistoryScope(),
                providerId = _activeProviderId.value
            )
        }
    }

    fun onRecentQuerySelected(query: String) {
        _query.value = query
        onSearchSubmitted()
    }

    fun submitExternalQuery(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 2) return

        _query.value = normalizedQuery
        onSearchSubmitted()
    }

    fun buildCompleteStalkerSearchIndex() {
        val providerId = _activeProviderId.value ?: return
        viewModelScope.launch {
            providerRepository.buildStalkerSearchIndexOnce(providerId)
        }
    }

    fun clearRecentQueries() {
        viewModelScope.launch {
            preferencesRepository.clearRecentSearchQueries(
                scope = _selectedTab.value.toSearchHistoryScope(),
                providerId = _activeProviderId.value
            )
        }
    }

    fun onTabSelected(tab: SearchTab) {
        _selectedTab.value = tab
    }

    suspend fun verifyPin(pin: String): Boolean {
        return preferencesRepository.verifyParentalPin(pin)
    }

    fun unlockCategory(categoryId: Long?) {
        val providerId = _activeProviderId.value ?: return
        val resolvedCategoryId = categoryId ?: return
        parentalControlManager.unlockCategory(providerId, resolvedCategoryId)
    }

    // ג”€ג”€ Search item long-press actions ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€

    fun toggleFavorite(contentId: Long, contentType: ContentType, currentlyFavorite: Boolean) {
        viewModelScope.launch {
            val providerId = _activeProviderId.value ?: return@launch
            if (currentlyFavorite) {
                favoriteRepository.removeFavorite(providerId, contentId, contentType)
            } else {
                favoriteRepository.addFavorite(providerId, contentId, contentType)
            }
        }
    }

    fun hideItemCategory(categoryId: Long, contentType: ContentType) {
        val providerId = _activeProviderId.value ?: return
        viewModelScope.launch {
            preferencesRepository.setCategoryHidden(
                providerId = providerId,
                type = contentType,
                categoryId = categoryId,
                hidden = true
            )
        }
    }

    fun toggleCategoryProtection(categoryId: Long, contentType: ContentType, currentlyProtected: Boolean) {
        val providerId = _activeProviderId.value ?: return
        viewModelScope.launch {
            categoryRepository.setCategoryProtection(
                providerId = providerId,
                categoryId = categoryId,
                type = contentType,
                isProtected = !currentlyProtected
            )
        }
    }
}

private data class SearchFilterParams(
    val provider: com.streamvault.domain.model.LegacyProvider?,
    val query: String,
    val tab: SearchTab,
    val level: Int,
    val unlockedCategoryIds: Set<Long>
)

enum class SearchTab(@get:StringRes val titleRes: Int) {
    ALL(R.string.search_all),
    LIVE(R.string.search_live_tv),
    MOVIES(R.string.search_movies),
    SERIES(R.string.search_series)
}

private fun SearchTab.toSearchScope(): SearchContentScope = when (this) {
    SearchTab.ALL -> SearchContentScope.ALL
    SearchTab.LIVE -> SearchContentScope.LIVE
    SearchTab.MOVIES -> SearchContentScope.MOVIES
    SearchTab.SERIES -> SearchContentScope.SERIES
}

private fun SearchTab.toSearchHistoryScope(): SearchHistoryScope = when (this) {
    SearchTab.ALL -> SearchHistoryScope.ALL
    SearchTab.LIVE -> SearchHistoryScope.LIVE
    SearchTab.MOVIES -> SearchHistoryScope.MOVIE
    SearchTab.SERIES -> SearchHistoryScope.SERIES
}

data class SearchUiState(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val hasSearchError: Boolean = false,
    val parentalControlLevel: Int = 0,
    val hasActiveProvider: Boolean = false,
    val queryLength: Int = 0,
    val unlockedCategoryIds: Set<Long> = emptySet(),
    val catalogCompleteness: CatalogCompleteness = CatalogCompleteness.COMPLETE
) {
    val isEmpty: Boolean get() = hasSearched && channels.isEmpty() && movies.isEmpty() && series.isEmpty()
    val totalResults: Int get() = channels.size + movies.size + series.size
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    initialQuery: String = "",
    onChannelClick: (Channel) -> Unit,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit,
    scaffold: CatalogScaffoldContent,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val recentQueries by viewModel.recentQueries.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recordingChannelIds by viewModel.recordingChannelIds.collectAsStateWithLifecycle()
    val scheduledChannelIds by viewModel.scheduledChannelIds.collectAsStateWithLifecycle()
    val nowMs by CatalogChannelProgressTicker.nowMs.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pendingChannel by remember { mutableStateOf<Channel?>(null) }
    var pendingMovie by remember { mutableStateOf<Movie?>(null) }
    var pendingSeries by remember { mutableStateOf<Series?>(null) }
    val scope = rememberCoroutineScope()
    val selectedStateLabel = stringResource(R.string.a11y_selected)
    val incorrectPinMessage = stringResource(R.string.search_incorrect_pin)

    // ג”€ג”€ Long-press actions dialog state ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€
    var showActionsDialog by remember { mutableStateOf(false) }
    var actionsChannel by remember { mutableStateOf<Channel?>(null) }
    var actionsMovie by remember { mutableStateOf<Movie?>(null) }
    var actionsSeries by remember { mutableStateOf<Series?>(null) }
    var actionsIsFavorite by remember { mutableStateOf(false) }
    // Separate PIN purpose: unlock-to-play vs toggle-parental-protection
    var pinIsForProtectionToggle by remember { mutableStateOf(false) }
    var pendingProtectionCategoryId by remember { mutableStateOf<Long?>(null) }
    var pendingProtectionContentType by remember { mutableStateOf<ContentType?>(null) }
    var pendingProtectionCurrentlyProtected by remember { mutableStateOf(false) }

    fun showChannelActions(channel: Channel) {
        actionsChannel = channel; actionsMovie = null; actionsSeries = null
        actionsIsFavorite = channel.isFavorite
        showActionsDialog = true
    }
    fun showMovieActions(movie: Movie) {
        actionsChannel = null; actionsMovie = movie; actionsSeries = null
        actionsIsFavorite = movie.isFavorite
        showActionsDialog = true
    }
    fun showSeriesActions(series: Series) {
        actionsChannel = null; actionsMovie = null; actionsSeries = series
        actionsIsFavorite = series.isFavorite
        showActionsDialog = true
    }
    val channelRows = remember(uiState.channels) { uiState.channels.chunked(4) }
    val movieRows = remember(uiState.movies) { uiState.movies.chunked(6) }
    val seriesRows = remember(uiState.series) { uiState.series.chunked(6) }

    fun isLocked(categoryId: Long?, isAdult: Boolean, isUserProtected: Boolean): Boolean {
        if (uiState.parentalControlLevel != 1) {
            return false
        }
        if (!isAdult && !isUserProtected) {
            return false
        }
        return categoryId == null || categoryId !in uiState.unlockedCategoryIds
    }

    LaunchedEffect(Unit) {
        searchFocusRequester.requestFocusSafely(tag = "SearchScreen", target = "Search field")
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            viewModel.submitExternalQuery(initialQuery)
            focusManager.clearFocus()
        }
    }

    LaunchedEffect(showPinDialog) {
        if (!showPinDialog) {
            searchFocusRequester.requestFocusSafely(tag = "SearchScreen", target = "Search field")
        }
    }

    if (showPinDialog) {
        com.streamvault.core.ui.components.dialogs.PinDialog(
            title = stringResource(R.string.pin_dialog_title),
            cancelLabel = stringResource(R.string.pin_dialog_cancel),
            onDismissRequest = {
                showPinDialog = false
                pinError = null
                pendingChannel = null
                pendingMovie = null
                pendingSeries = null
                pinIsForProtectionToggle = false
                pendingProtectionCategoryId = null
                pendingProtectionContentType = null
            },
            onPinEntered = { pin ->
                scope.launch {
                    if (viewModel.verifyPin(pin)) {
                        if (pinIsForProtectionToggle) {
                            val catId = pendingProtectionCategoryId
                            val ct = pendingProtectionContentType
                            if (catId != null && ct != null) {
                                viewModel.toggleCategoryProtection(catId, ct, pendingProtectionCurrentlyProtected)
                            }
                            showPinDialog = false
                            pinError = null
                            pinIsForProtectionToggle = false
                            pendingProtectionCategoryId = null
                            pendingProtectionContentType = null
                        } else {
                            pendingChannel?.categoryId?.let(viewModel::unlockCategory)
                            pendingMovie?.categoryId?.let(viewModel::unlockCategory)
                            pendingSeries?.categoryId?.let(viewModel::unlockCategory)
                            showPinDialog = false
                            pinError = null
                            pendingChannel?.let { onChannelClick(it) }
                            pendingMovie?.let { onMovieClick(it) }
                            pendingSeries?.let { onSeriesClick(it) }
                            pendingChannel = null
                            pendingMovie = null
                            pendingSeries = null
                        }
                    } else {
                        pinError = incorrectPinMessage
                    }
                }
            },
            error = pinError
        )
    }

    val selectedTabDescription = selectedStateLabel

    // ג”€ג”€ Long-press actions dialog ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€
    if (showActionsDialog) {
        val actionsTitle = actionsChannel?.name ?: actionsMovie?.name ?: actionsSeries?.name ?: ""
        val actionsCategoryId = actionsChannel?.categoryId ?: actionsMovie?.categoryId ?: actionsSeries?.categoryId
        val actionsIsProtected = actionsChannel?.isUserProtected ?: actionsMovie?.isUserProtected ?: actionsSeries?.isUserProtected ?: false
        val actionsContentType = when {
            actionsChannel != null -> ContentType.LIVE
            actionsMovie != null -> ContentType.MOVIE
            else -> ContentType.SERIES
        }
        val actionsItemId = actionsChannel?.id ?: actionsMovie?.id ?: actionsSeries?.id ?: 0L
        SearchItemActionsDialog(
            title = actionsTitle,
            isFavorite = actionsIsFavorite,
            isProtected = actionsIsProtected,
            hasCategoryId = actionsCategoryId != null,
            onDismiss = { showActionsDialog = false },
            onToggleFavorite = {
                viewModel.toggleFavorite(actionsItemId, actionsContentType, actionsIsFavorite)
                actionsIsFavorite = !actionsIsFavorite
            },
            onHide = if (actionsCategoryId != null) {
                {
                    viewModel.hideItemCategory(actionsCategoryId, actionsContentType)
                    showActionsDialog = false
                }
            } else null,
            onToggleLock = if (actionsCategoryId != null) {
                {
                    showActionsDialog = false
                    pendingProtectionCategoryId = actionsCategoryId
                    pendingProtectionContentType = actionsContentType
                    pendingProtectionCurrentlyProtected = actionsIsProtected
                    pinIsForProtectionToggle = true
                    showPinDialog = true
                }
            } else null
        )
    }

    scaffold(
        AppDestination.Search(),
        stringResource(R.string.search_title),
        stringResource(R.string.search_screen_subtitle),
        CatalogNavigationChrome.TopBar,
        true,
        true,
        false
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                SearchHeroPanel(
                    query = query,
                    selectedTab = selectedTab,
                    recentQueries = recentQueries,
                    totalResults = uiState.totalResults,
                    onQueryChange = viewModel::onQueryChange,
                    onSearch = {
                        viewModel.onSearchSubmitted()
                    },
                    onTabSelected = viewModel::onTabSelected,
                    onRecentQuerySelected = {
                        viewModel.onRecentQuerySelected(it)
                    },
                    onClearRecentQueries = viewModel::clearRecentQueries,
                    focusRequester = searchFocusRequester,
                    selectedStateLabel = selectedTabDescription
                )
            }

            when {
                !uiState.hasActiveProvider -> {
                    item {
                        SearchMessageState(
                            title = stringResource(R.string.search_no_provider_title),
                            subtitle = stringResource(R.string.search_no_provider_subtitle)
                        )
                    }
                }

                uiState.queryLength < 2 -> {
                    item {
                        SearchMessageState(
                            title = stringResource(R.string.search_ready_title),
                            subtitle = stringResource(R.string.search_type_to_search)
                        )
                    }
                }

                uiState.isLoading -> {
                    item {
                        SearchMessageState(
                            title = stringResource(R.string.search_loading_title),
                            subtitle = stringResource(R.string.search_loading_subtitle)
                        )
                    }
                }

                uiState.isEmpty && uiState.hasSearchError -> {
                    item {
                        SearchMessageState(
                            title = stringResource(R.string.search_error_title),
                            subtitle = stringResource(R.string.search_error_subtitle)
                        )
                    }
                }

                uiState.isEmpty -> {
                    item {
                        SearchMessageState(
                            title = stringResource(R.string.search_no_results_title),
                            subtitle = stringResource(R.string.search_no_results, query)
                        )
                    }
                }

                else -> {
                    item {
                        SearchResultsSummaryRow(
                            uiState = uiState,
                            onBuildCompleteIndex = viewModel::buildCompleteStalkerSearchIndex
                        )
                    }

                    if (selectedTab == SearchTab.ALL) {
                        if (uiState.channels.isNotEmpty()) {
                            item {
                                SearchResultRail(
                                    title = stringResource(R.string.search_live_tv),
                                    items = uiState.channels.subList(0, minOf(uiState.channels.size, 18)),
                                    keySelector = { it.id }
                                ) { channel ->
                                    val channelLocked = isLocked(
                                        categoryId = channel.categoryId,
                                        isAdult = channel.isAdult,
                                        isUserProtected = channel.isUserProtected
                                    )
                                    ChannelCard(
                                        channel = channel,
                                        nowMs = nowMs,
                                        isLocked = channelLocked,
                                        isRecording = channel.id in recordingChannelIds,
                                        isScheduledRecording = channel.id in scheduledChannelIds,
                                        onClick = {
                                            if (channelLocked) {
                                                pendingChannel = channel
                                                showPinDialog = true
                                            } else {
                                                onChannelClick(channel)
                                            }
                                        },
                                        onLongClick = { showChannelActions(channel) }
                                    )
                                }
                            }
                        }

                        if (uiState.movies.isNotEmpty()) {
                            item {
                                SearchResultRail(
                                    title = stringResource(R.string.search_movies),
                                    items = uiState.movies.subList(0, minOf(uiState.movies.size, 18)),
                                    keySelector = { it.id }
                                ) { movie ->
                                    val movieLocked = isLocked(
                                        categoryId = movie.categoryId,
                                        isAdult = movie.isAdult,
                                        isUserProtected = movie.isUserProtected
                                    )
                                    MovieCard(
                                        movie = movie,
                                        isLocked = movieLocked,
                                        onClick = {
                                            if (movieLocked) {
                                                pendingMovie = movie
                                                showPinDialog = true
                                            } else {
                                                onMovieClick(movie)
                                            }
                                        },
                                        onLongClick = { showMovieActions(movie) }
                                    )
                                }
                            }
                        }

                        if (uiState.series.isNotEmpty()) {
                            item {
                                SearchResultRail(
                                    title = stringResource(R.string.search_series),
                                    items = uiState.series.subList(0, minOf(uiState.series.size, 18)),
                                    keySelector = { it.id }
                                ) { seriesItem ->
                                    val seriesLocked = isLocked(
                                        categoryId = seriesItem.categoryId,
                                        isAdult = seriesItem.isAdult,
                                        isUserProtected = seriesItem.isUserProtected
                                    )
                                    SeriesCard(
                                        series = seriesItem,
                                        isLocked = seriesLocked,
                                        onClick = {
                                            if (seriesLocked) {
                                                pendingSeries = seriesItem
                                                showPinDialog = true
                                            } else {
                                                onSeriesClick(seriesItem)
                                            }
                                        },
                                        onLongClick = { showSeriesActions(seriesItem) }
                                    )
                                }
                            }
                        }
                    } else {
                        item {
                            SectionHeader(
                                title = when (selectedTab) {
                                    SearchTab.ALL -> stringResource(R.string.search_all)
                                    SearchTab.LIVE -> stringResource(R.string.search_live_tv)
                                    SearchTab.MOVIES -> stringResource(R.string.search_movies)
                                    SearchTab.SERIES -> stringResource(R.string.search_series)
                                }
                            )
                        }

                        when (selectedTab) {
                            SearchTab.ALL -> Unit
                            SearchTab.LIVE -> items(
                                items = channelRows,
                                key = { row -> row.joinToString("-") { it.id.toString() } },
                                contentType = { "search_live_row" }
                            ) { row ->
                                SearchChannelGridRow(
                                    channels = row,
                                    nowMs = nowMs,
                                    recordingChannelIds = recordingChannelIds,
                                    scheduledChannelIds = scheduledChannelIds,
                                    isLocked = { channel ->
                                        isLocked(
                                            categoryId = channel.categoryId,
                                            isAdult = channel.isAdult,
                                            isUserProtected = channel.isUserProtected
                                        )
                                    },
                                    onChannelClick = { channel, locked ->
                                        if (locked) {
                                            pendingChannel = channel
                                            showPinDialog = true
                                        } else {
                                            onChannelClick(channel)
                                        }
                                    },
                                    onChannelLongClick = { channel -> showChannelActions(channel) }
                                )
                            }

                            SearchTab.MOVIES -> items(
                                items = movieRows,
                                key = { row -> row.joinToString("-") { it.id.toString() } },
                                contentType = { "search_movie_row" }
                            ) { row ->
                                SearchMovieGridRow(
                                    movies = row,
                                    isLocked = { movie ->
                                        isLocked(
                                            categoryId = movie.categoryId,
                                            isAdult = movie.isAdult,
                                            isUserProtected = movie.isUserProtected
                                        )
                                    },
                                    onMovieClick = { movie, locked ->
                                        if (locked) {
                                            pendingMovie = movie
                                            showPinDialog = true
                                        } else {
                                            onMovieClick(movie)
                                        }
                                    },
                                    onMovieLongClick = { movie -> showMovieActions(movie) }
                                )
                            }

                            SearchTab.SERIES -> items(
                                items = seriesRows,
                                key = { row -> row.joinToString("-") { it.id.toString() } },
                                contentType = { "search_series_row" }
                            ) { row ->
                                SearchSeriesGridRow(
                                    seriesItems = row,
                                    isLocked = { seriesItem ->
                                        isLocked(
                                            categoryId = seriesItem.categoryId,
                                            isAdult = seriesItem.isAdult,
                                            isUserProtected = seriesItem.isUserProtected
                                        )
                                    },
                                    onSeriesClick = { seriesItem, locked ->
                                        if (locked) {
                                            pendingSeries = seriesItem
                                            showPinDialog = true
                                        } else {
                                            onSeriesClick(seriesItem)
                                        }
                                    },
                                    onSeriesLongClick = { seriesItem -> showSeriesActions(seriesItem) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHeroPanel(
    query: String,
    selectedTab: SearchTab,
    recentQueries: List<String>,
    totalResults: Int,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onTabSelected: (SearchTab) -> Unit,
    onRecentQuerySelected: (String) -> Unit,
    onClearRecentQueries: () -> Unit,
    focusRequester: FocusRequester,
    selectedStateLabel: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated.copy(alpha = 0.92f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.search_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        text = stringResource(R.string.search_command_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 640.dp)
                    )
                }

                SearchStatusCard(
                    title = if (query.length >= 2) {
                        stringResource(R.string.search_results_title, totalResults)
                    } else {
                        stringResource(R.string.search_ready_title)
                    },
                    body = if (query.length >= 2) {
                        stringResource(R.string.search_screen_subtitle)
                    } else {
                        stringResource(R.string.search_type_to_search)
                    },
                    modifier = Modifier.widthIn(min = 220.dp, max = 360.dp)
                )
            }

            SearchInput(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.search_hint),
                focusRequester = focusRequester,
                onSearch = onSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchTab.values().toList(), key = { it.name }) { tab ->
                    SearchPill(
                        text = stringResource(tab.titleRes),
                        selected = tab == selectedTab,
                        onClick = { onTabSelected(tab) },
                        modifier = Modifier.semantics {
                            selected = tab == selectedTab
                            if (tab == selectedTab) {
                                stateDescription = selectedStateLabel
                            }
                        }
                    )
                }
            }

            if (recentQueries.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.search_recent_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recentQueries, key = { it }) { recentQuery ->
                            val recentQueryDescription = stringResource(R.string.a11y_recent_search, recentQuery)
                            SearchPill(
                                text = recentQuery,
                                selected = recentQuery.equals(query, ignoreCase = true),
                                onClick = { onRecentQuerySelected(recentQuery) },
                                modifier = Modifier.semantics {
                                    contentDescription = recentQueryDescription
                                    if (recentQuery.equals(query, ignoreCase = true)) {
                                        selected = true
                                        stateDescription = selectedStateLabel
                                    }
                                }
                            )
                        }
                    }
                    SearchPill(
                        text = stringResource(R.string.search_clear_history),
                        selected = false,
                        compact = true,
                        onClick = onClearRecentQueries
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    TvClickableSurface(
        modifier = modifier,
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.22f) else Surface.copy(alpha = 0.72f),
            focusedContainerColor = if (selected) Primary.copy(alpha = 0.30f) else SurfaceHighlight,
            contentColor = if (selected) Color.White else TextSecondary,
            focusedContentColor = TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (selected) Primary.copy(alpha = 0.65f) else FocusBorder.copy(alpha = 0.28f)
                ),
                shape = CircleShape
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = CircleShape
            )
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 8.dp else 10.dp
            ),
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SearchStatusCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = SurfaceDefaults.colors(containerColor = Surface.copy(alpha = 0.78f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun <T : Any> SearchResultRail(
    title: String,
    items: List<T>,
    keySelector: (T) -> Any,
    itemContent: @Composable (T) -> Unit
) {
    CategoryRow(
        title = title,
        items = items,
        keySelector = keySelector
    ) { item ->
        itemContent(item)
    }
}

@Composable
private fun SearchChannelGridRow(
    channels: List<Channel>,
    nowMs: Long,
    recordingChannelIds: Set<Long> = emptySet(),
    scheduledChannelIds: Set<Long> = emptySet(),
    isLocked: (Channel) -> Boolean,
    onChannelClick: (Channel, Boolean) -> Unit,
    onChannelLongClick: (Channel) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        channels.forEach { channel ->
            val locked = isLocked(channel)
            ChannelCard(
                channel = channel,
                nowMs = nowMs,
                isLocked = locked,
                isRecording = channel.id in recordingChannelIds,
                isScheduledRecording = channel.id in scheduledChannelIds,
                onClick = { onChannelClick(channel, locked) },
                onLongClick = { onChannelLongClick(channel) }
            )
        }
    }
}

@Composable
private fun SearchMovieGridRow(
    movies: List<Movie>,
    isLocked: (Movie) -> Boolean,
    onMovieClick: (Movie, Boolean) -> Unit,
    onMovieLongClick: (Movie) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        movies.forEach { movie ->
            val locked = isLocked(movie)
            MovieCard(
                movie = movie,
                isLocked = locked,
                onClick = { onMovieClick(movie, locked) },
                onLongClick = { onMovieLongClick(movie) }
            )
        }
    }
}

@Composable
private fun SearchSeriesGridRow(
    seriesItems: List<Series>,
    isLocked: (Series) -> Boolean,
    onSeriesClick: (Series, Boolean) -> Unit,
    onSeriesLongClick: (Series) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        seriesItems.forEach { seriesItem ->
            val locked = isLocked(seriesItem)
            SeriesCard(
                series = seriesItem,
                isLocked = locked,
                onClick = { onSeriesClick(seriesItem, locked) },
                onLongClick = { onSeriesLongClick(seriesItem) }
            )
        }
    }
}


@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Primary,
        modifier = Modifier
            .padding(vertical = 8.dp)
            .semantics { heading() }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchResultsSummaryRow(
    uiState: SearchUiState,
    onBuildCompleteIndex: () -> Unit
) {
    val countsSummary = listOf(
        stringResource(R.string.search_results_count, stringResource(R.string.search_live_tv), uiState.channels.size),
        stringResource(R.string.search_results_count, stringResource(R.string.search_movies), uiState.movies.size),
        stringResource(R.string.search_results_count, stringResource(R.string.search_series), uiState.series.size)
    ).joinToString("  •  ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.search_results_title, uiState.totalResults),
            style = MaterialTheme.typography.titleMedium,
            color = OnSurface,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = countsSummary,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (uiState.catalogCompleteness != CatalogCompleteness.COMPLETE) {
            Text(
                text = stringResource(
                    when (uiState.catalogCompleteness) {
                        CatalogCompleteness.INDEXING -> R.string.search_catalog_indexing
                        CatalogCompleteness.TRUNCATED -> R.string.search_catalog_truncated
                        CatalogCompleteness.PARTIAL -> R.string.search_catalog_downloaded_only
                        CatalogCompleteness.COMPLETE -> R.string.search_catalog_complete
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Warning
            )
            if (uiState.catalogCompleteness == CatalogCompleteness.PARTIAL ||
                uiState.catalogCompleteness == CatalogCompleteness.TRUNCATED
            ) {
                Button(onClick = onBuildCompleteIndex) {
                    Text(stringResource(R.string.search_build_complete_index))
                }
            }
        }
    }
}

@Composable
private fun SearchMessageState(
    title: String,
    subtitle: String
) {
    TvEmptyState(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

// ג”€ג”€ Long-press actions dialog ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchItemActionsDialog(
    title: String,
    isFavorite: Boolean,
    isProtected: Boolean,
    hasCategoryId: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onHide: (() -> Unit)?,
    onToggleLock: (() -> Unit)?
) {
    // Ghost-click debounce: ignore select key-up inherited from the long-press gesture
    var canInteract by remember { mutableStateOf(false) }
    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstFocusRequester.requestFocus() }
        delay(500)
        canInteract = true
    }

    val safeDismiss = { if (canInteract) onDismiss() }

    Dialog(
        onDismissRequest = safeDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(containerColor = AppColors.SurfaceElevated),
            modifier = Modifier
                .width(360.dp)
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = safeDismiss,
                        modifier = Modifier.mouseClickable(onClick = safeDismiss)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.search_actions_dismiss))
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // ג”€ג”€ Favorite toggle ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€
                    SearchActionButton(
                        icon = Icons.Default.Star,
                        label = if (isFavorite) stringResource(R.string.search_actions_remove_favorite)
                                else stringResource(R.string.search_actions_add_favorite),
                        isActive = isFavorite,
                        focusRequester = firstFocusRequester,
                        onClick = { if (canInteract) onToggleFavorite() }
                    )

                    // ג”€ג”€ Hide category ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€
                    if (onHide != null) {
                        SearchActionButton(
                            icon = Icons.Default.Close,
                            label = stringResource(R.string.search_actions_hide_category),
                            isActive = false,
                            onClick = { if (canInteract) onHide() }
                        )
                    } else {
                        SearchActionButton(
                            icon = Icons.Default.Close,
                            label = stringResource(R.string.search_actions_hide_no_category),
                            isActive = false,
                            enabled = false,
                            onClick = {}
                        )
                    }

                    // ג”€ג”€ Parental lock toggle ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€ג”€
                    if (onToggleLock != null) {
                        SearchActionButton(
                            icon = Icons.Default.Lock,
                            label = if (isProtected) stringResource(R.string.search_actions_unlock)
                                    else stringResource(R.string.search_actions_lock),
                            isActive = isProtected,
                            onClick = { if (canInteract) onToggleLock() }
                        )
                    } else {
                        SearchActionButton(
                            icon = Icons.Default.Lock,
                            label = stringResource(R.string.search_actions_lock_no_category),
                            isActive = false,
                            enabled = false,
                            onClick = {}
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchActionButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val baseModifier = modifier
        .fillMaxWidth()
        .onFocusChanged { isFocused = it.isFocused }
        .then(
            if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
        )
        .border(
            width = if (isFocused) 2.dp else 0.dp,
            color = if (isFocused) AppColors.Focus else Color.Transparent,
            shape = RoundedCornerShape(12.dp)
        )
        .mouseClickable(onClick = onClick)

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = baseModifier,
        colors = ButtonDefaults.colors(
            containerColor = when {
                !enabled -> AppColors.Surface.copy(alpha = 0.3f)
                isFocused -> AppColors.Focus
                isActive -> AppColors.Warning.copy(alpha = 0.85f)
                else -> AppColors.Brand.copy(alpha = 0.70f)
            },
            contentColor = if (!enabled) AppColors.TextSecondary else Color.Black,
            disabledContainerColor = AppColors.Surface.copy(alpha = 0.3f),
            disabledContentColor = AppColors.TextSecondary
        ),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp))
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
