package com.streamvault.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.di.AuxiliaryPlayerEngine
import com.streamvault.app.player.LivePreviewHandoffManager
import com.streamvault.app.player.PreviewHandoffSource
import com.streamvault.app.plugins.StreamVaultPluginManager
import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.app.ui.screens.multiview.MultiViewManager
import com.streamvault.app.ui.model.GuideCachePolicy
import com.streamvault.app.ui.model.applyProviderCategoryDisplayPreferences
import com.streamvault.app.ui.model.orderedByRequestedRawIds
import com.streamvault.app.ui.model.guideLookupKey
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.app.ui.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.ProviderSyncStateSource
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ActiveLiveSourceOption
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.CombinedCategory
import com.streamvault.domain.model.CombinedM3uProfileMember
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.LiveStreamProgramRequest
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.M3uClassificationRepository
import com.streamvault.domain.repository.M3uCategoryItem
import com.streamvault.domain.repository.M3uClassificationTarget
import com.streamvault.domain.repository.M3uSeriesAssignment
import com.streamvault.domain.util.AdultContentVisibilityPolicy
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.usecase.UnlockParentalCategory
import com.streamvault.domain.usecase.UnlockParentalCategoryCommand
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import com.streamvault.app.R
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.app.Application
import javax.inject.Provider as InjectProvider

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel @Inject constructor(
    application: Application,
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val channelRepository: ChannelRepository,
    private val categoryRepository: CategoryRepository,
    private val favoriteRepository: FavoriteRepository,
    private val preferencesRepository: PreferencesRepository,
    private val epgRepository: EpgRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val m3uClassificationRepository: M3uClassificationRepository,
    private val getCustomCategories: GetCustomCategories,
    private val unlockParentalCategory: UnlockParentalCategory,
    private val parentalControlManager: ParentalControlManager,
    private val syncManager: ProviderSyncStateSource,
    private val tvInputChannelSyncManager: TvInputChannelSyncManager,
    private val multiViewManager: MultiViewManager,
    private val livePreviewHandoffManager: LivePreviewHandoffManager,
    private val pluginManager: StreamVaultPluginManager,
    @param:AuxiliaryPlayerEngine
    private val playerEngineProvider: InjectProvider<PlayerEngine>
) : ViewModel() {
    private companion object {
        const val MIN_CHANNEL_SEARCH_QUERY_LENGTH = 2
        const val CHANNEL_PAGE_SIZE = 200
        const val CHANNEL_SEARCH_PAGE_SIZE = 300
        const val LOAD_MORE_THRESHOLD = 5
        const val ADAPTIVE_PREVIEW_REPRIME_DELAY_MS = 2_500L
    }

    private val appContext = application

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    val remoteShortcutPreferences = preferencesRepository.remoteShortcutPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), com.streamvault.domain.model.RemoteShortcutPreferences())

    private val _localChannels = MutableStateFlow<List<Channel>>(emptyList())
    private val _channelBrowseLimit = MutableStateFlow(CHANNEL_PAGE_SIZE)
    private val _channelSearchLimit = MutableStateFlow(CHANNEL_SEARCH_PAGE_SIZE)
    private val _preferredInitialCategoryId = MutableStateFlow<Long?>(null)
    private val _visibleChannelWindow = MutableStateFlow<Set<Long>>(emptySet())
    private val _epgProgramMap = MutableStateFlow<Map<String, Program>>(emptyMap())
    // Guide keys that returned successfully-but-empty this session; skipped by later
    // now-playing fallback runs so the same paced requests are not repeated.
    private val homeSessionEmptyGuideKeys = mutableSetOf<String>()
    private var epgJob: Job? = null
    private var loadChannelsJob: Job? = null
    private var categoriesJob: Job? = null
    private var recentChannelsJob: Job? = null
    private var previewPlaybackJob: Job? = null
    private var previewErrorJob: Job? = null
    private var previewPlayerEngine: PlayerEngine? = null
    private var previewSessionVersion: Long = 0L
    private var combinedCategoriesById: Map<Long, CombinedCategory> = emptyMap()

    init {
        loadAllProviders()
        viewModelScope.launch {
            livePreviewHandoffManager.reverseSessionFlow.collect { session ->
                if (session != null && session.source == PreviewHandoffSource.HOME) {
                    resumePreviewFromHandoff()
                }
            }
        }
        viewModelScope.launch {
            combine(
                multiViewManager.slots,
                preferencesRepository.multiViewCenterTwoSlotLayout
            ) { slots, centeredCompactLayoutEnabled ->
                val slotLimit = if (centeredCompactLayoutEnabled) 2 else MultiViewManager.MAX_SLOTS
                slots.count { it != null } to slotLimit
            }.collect { (slotCount, slotLimit) ->
                _uiState.update {
                    it.copy(
                        multiviewChannelCount = slotCount,
                        multiviewSlotCapacity = slotLimit
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(
                combinedM3uRepository.getActiveLiveSource(),
                providerRepository.getActiveProvider()
            ) { activeSource, activeProvider ->
                Pair(
                    activeSource ?: activeProvider?.id?.let { ActiveLiveSource.ProviderSource(it) },
                    activeProvider
                )
            }.distinctUntilChanged { old, new ->
                old.first == new.first && old.second?.id == new.second?.id
            }.collectLatest { (activeSource, activeProvider) ->
                when (activeSource) {
                    is ActiveLiveSource.CombinedM3uSource -> {
                        val profile = combinedM3uRepository.getProfile(activeSource.profileId)
                        combinedCategoriesById = emptyMap()
                        _uiState.update {
                            it.copy(
                                provider = null,
                                activeLiveSource = activeSource,
                                activeLiveSourceTitle = profile?.name ?: "Combined M3U",
                                isCombinedLiveSource = true,
                                categories = emptyList(),
                                recentChannels = emptyList(),
                                lastVisitedCategory = null,
                                selectedCategory = null,
                                filteredChannels = emptyList(),
                                hasChannels = false,
                                isLoading = false,
                                isCategoriesLoading = true,
                                errorMessage = null,
                                currentCombinedProfileMembers = profile?.members.orEmpty(),
                                selectedCombinedSourceProviderId = null
                            )
                        }
                        recentChannelsJob?.cancel()
                        recentChannelsJob = null
                        _localChannels.value = emptyList()
                        observeCombinedRecentChannels(currentCombinedProviderIds())
                        loadCombinedCategoriesAndChannels(activeSource.profileId)
                    }
                    is ActiveLiveSource.ProviderSource -> {
                        val provider = activeProvider?.takeIf { it.id == activeSource.providerId }
                            ?: providerRepository.getProvider(activeSource.providerId)
                            ?: return@collectLatest
                        parentalControlManager.clearUnlockedCategories(provider.id)
                        combinedCategoriesById = emptyMap()
                        _uiState.update {
                            it.copy(
                                provider = provider,
                                activeLiveSource = activeSource,
                                activeLiveSourceTitle = provider.name,
                                isCombinedLiveSource = false,
                                categories = emptyList(),
                                recentChannels = emptyList(),
                                lastVisitedCategory = null,
                                selectedCategory = null,
                                filteredChannels = emptyList(),
                                hasChannels = false,
                                isLoading = false,
                                isCategoriesLoading = true,
                                errorMessage = null,
                                currentCombinedProfileMembers = emptyList(),
                                selectedCombinedSourceProviderId = null
                            )
                        }
                        _localChannels.value = emptyList()
                        loadCategoriesAndChannels(provider.id)
                        observeRecentChannels(provider.id)
                        preferencesRepository.setLastActiveProviderId(provider.id)
                    }
                    null -> {
                        recentChannelsJob?.cancel()
                        recentChannelsJob = null
                        combinedCategoriesById = emptyMap()
                        _localChannels.value = emptyList()
                        _uiState.update {
                            it.copy(
                                provider = null,
                                activeLiveSource = null,
                                activeLiveSourceTitle = "",
                                isCombinedLiveSource = false,
                                categories = emptyList(),
                                recentChannels = emptyList(),
                                lastVisitedCategory = null,
                                selectedCategory = null,
                                filteredChannels = emptyList(),
                                hasChannels = false,
                                isLoading = false,
                                isCategoriesLoading = false,
                                errorMessage = null,
                                currentCombinedProfileMembers = emptyList(),
                                selectedCombinedSourceProviderId = null
                            )
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            combinedM3uRepository.getActiveLiveSourceOptions().collectLatest { options ->
                _uiState.update { state ->
                    state.copy(
                        liveSourceOptions = options.filter { option ->
                            when (val source = option.source) {
                                is ActiveLiveSource.ProviderSource -> {
                                    state.allProviders.firstOrNull { it.id == source.providerId }?.type == ProviderType.M3U
                                }
                                is ActiveLiveSource.CombinedM3uSource -> true
                            }
                        }
                    )
                }
            }
        }

        // Observe channels, search query, and favorites to update UI
        viewModelScope.launch {
            combine(
                _localChannels,
                observeCurrentLiveFavorites(),
                _epgProgramMap
            ) { channels: List<Channel>, favorites: List<Favorite>, epgProgramMap: Map<String, Program> ->
                Triple(channels, favorites, epgProgramMap)
            }.collectLatest { (channels, favorites, epgProgramMap) ->
                val favoriteIds = favorites.map { it.contentId }.toSet()
                val markedChannels = channels.map { channel ->
                    val program = channel.guideLookupKey()?.let { lookupKey -> epgProgramMap[lookupKey] }
                    if (favoriteIds.contains(channel.id)) {
                        channel.copy(isFavorite = true, currentProgram = program)
                    } else {
                        channel.copy(isFavorite = false, currentProgram = program)
                    }
                }

                _uiState.update { state ->
                    if (state.isChannelReorderMode) {
                        state
                    } else {
                        state.copy(filteredChannels = markedChannels)
                    }
                }
                val previewChannelId = _uiState.value.previewChannelId
                if (previewChannelId != null && markedChannels.none { it.id == previewChannelId }) {
                    clearPreview()
                }
            }
        }

        viewModelScope.launch {
            combine(
                _uiState.map { it.filteredChannels.map(Channel::providerId).toSet() }.distinctUntilChanged(),
                _uiState.map { it.filteredChannels }.distinctUntilChanged(),
                _visibleChannelWindow.debounce(120)
            ) { providerIds, channels, visibleIds ->
                Triple(providerIds, channels, visibleIds)
            }.collectLatest { (_, channels, visibleIds) ->
                if (channels.isEmpty()) {
                    epgJob?.cancel()
                    _epgProgramMap.value = emptyMap()
                    return@collectLatest
                }

                val candidateIds = if (visibleIds.isEmpty()) {
                    channels.take(12).map { it.id }.toSet()
                } else {
                    visibleIds
                }

                fetchEpgForChannels(channels.filter { it.id in candidateIds })
            }
        }

        viewModelScope.launch {
            preferencesRepository.parentalControlLevel.collectLatest { level ->
                _uiState.update { it.copy(parentalControlLevel = level) }
            }
        }

        viewModelScope.launch {
            _uiState
                .map { state ->
                    if (state.isCombinedLiveSource)
                        state.currentCombinedProfileMembers.filter { it.enabled }.map { it.providerId }
                    else
                        state.provider?.id?.let(::listOf).orEmpty()
                }
                .distinctUntilChanged()
                .flatMapLatest { providerIds ->
                    when (providerIds.size) {
                        0 -> flowOf(emptySet())
                        1 -> parentalControlManager.unlockedCategoriesForProvider(providerIds.first())
                        else -> combine(
                            providerIds.map { id -> parentalControlManager.unlockedCategoriesForProvider(id) }
                        ) { arrays -> arrays.toList().flatten().toSet() }
                    }
                }
                .collectLatest { unlockedIds ->
                    _uiState.update { it.copy(unlockedCategoryIds = unlockedIds) }
                }
        }

        viewModelScope.launch {
            preferencesRepository.liveTvChannelMode
                .map(LiveTvChannelMode::fromStorage)
                .distinctUntilChanged()
                .collectLatest { mode ->
                    _uiState.update { it.copy(liveTvChannelMode = mode) }
                    if (mode != LiveTvChannelMode.PRO) {
                        clearPreview()
                    }
                }
        }

        viewModelScope.launch {
            preferencesRepository.liveTvCategoryFilters.collectLatest { filters ->
                _uiState.update { state ->
                    val activeFilter = state.activeCategoryFilter
                        ?.takeIf { selected -> filters.any { it.equals(selected, ignoreCase = true) } }
                        ?: filters.firstOrNull { it == state.categorySearchQuery }
                    state.copy(
                        savedCategoryFilters = filters,
                        activeCategoryFilter = activeFilter
                    )
                }
            }
        }

        viewModelScope.launch {
            preferencesRepository.liveTvQuickFilterVisibility
                .map(LiveTvQuickFilterVisibilityMode::fromStorage)
                .distinctUntilChanged()
                .collectLatest { mode ->
                    _uiState.update { it.copy(liveTvQuickFilterVisibilityMode = mode) }
                }
        }

        viewModelScope.launch {
            preferencesRepository.showLiveSourceSwitcher
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { it.copy(showLiveSourceSwitcher = enabled) }
                }
        }

        viewModelScope.launch {
            _uiState.map { it.provider?.id }
                .distinctUntilChanged()
                .flatMapLatest { providerId ->
                    providerId?.let { syncManager.syncStateForProvider(it) } ?: flowOf(SyncState.Idle)
                }
                .collectLatest { syncState ->
                    val isSyncing = syncState is SyncState.Syncing
                    _uiState.update { state ->
                        state.copy(
                            isSyncing = isSyncing,
                            isLoading = when {
                                state.hasChannels -> false
                                isSyncing && state.selectedCategory != null -> true
                                else -> state.isLoading && isSyncing
                            }
                        )
                    }
                }
        }
    }

    private fun loadAllProviders() {
        viewModelScope.launch {
            providerRepository.getProviders().collect { providers ->
                _uiState.update { state ->
                    state.copy(
                        allProviders = providers,
                        liveSourceOptions = state.liveSourceOptions.filter { option ->
                            when (val source = option.source) {
                                is ActiveLiveSource.ProviderSource ->
                                    providers.firstOrNull { it.id == source.providerId }?.type == ProviderType.M3U
                                is ActiveLiveSource.CombinedM3uSource -> true
                            }
                        }
                    )
                }
            }
        }
    }

    fun switchProvider(providerId: Long) {
        switchLiveSource(ActiveLiveSource.ProviderSource(providerId))
    }

    fun switchLiveSource(source: ActiveLiveSource) {
        viewModelScope.launch {
            combinedM3uRepository.setActiveLiveSource(source)
            when (source) {
                is ActiveLiveSource.ProviderSource -> providerRepository.setActiveProvider(source.providerId)
                is ActiveLiveSource.CombinedM3uSource -> Unit
            }
        }
    }

    private fun loadCategoriesAndChannels(providerId: Long) {
        categoriesJob?.cancel()
        categoriesJob = viewModelScope.launch {
            try {
                _uiState.update { it.copy(isCategoriesLoading = true, errorMessage = null) }
                combine(
                    channelRepository.getCategories(providerId),
                    getCustomCategories(providerId, ContentType.LIVE),
                    preferencesRepository.defaultCategoryId,
                    preferencesRepository.getLastLiveCategoryId(providerId),
                    preferencesRepository.getHiddenCategoryIds(providerId, ContentType.LIVE),
                    preferencesRepository.getCategorySortMode(providerId, ContentType.LIVE),
                    preferencesRepository.getPinnedCategoryIds(providerId, ContentType.LIVE)
                ) { values ->
                    val providerCats = values[0] as List<Category>
                    val customCats = values[1] as List<Category>
                    val defaultId = values[2] as Long?
                    val lastVisitedCategoryId = values[3] as Long?
                    val hiddenCategoryIds = values[4] as Set<Long>
                    val sortMode = values[5] as CategorySortMode
                    val pinnedCategoryIds = values[6] as Set<Long>
                    val recentCategory = Category(
                        id = VirtualCategoryIds.RECENT,
                        name = "Recent",
                        type = ContentType.LIVE,
                        isVirtual = true,
                        count = _uiState.value.recentChannels.size
                    )
                    val allChannelsCategory = providerCats.firstOrNull { it.id == ChannelRepository.ALL_CHANNELS_ID }
                        ?.copy(count = providerCats.filter { it.id != ChannelRepository.ALL_CHANNELS_ID && it.id !in hiddenCategoryIds }.sumOf(Category::count))
                        ?: Category(
                            id = ChannelRepository.ALL_CHANNELS_ID,
                            name = "All Channels",
                            type = ContentType.LIVE,
                            count = providerCats.filter { it.id != ChannelRepository.ALL_CHANNELS_ID && it.id !in hiddenCategoryIds }.sumOf(Category::count)
                        )
                    val visibleProviderCategories = applyProviderCategoryDisplayPreferences(
                        categories = providerCats.filter { it.id != ChannelRepository.ALL_CHANNELS_ID },
                        hiddenCategoryIds = hiddenCategoryIds,
                        sortMode = sortMode
                    )
                    val pinnedProviderCategories = visibleProviderCategories.filter { it.id in pinnedCategoryIds }
                    val unpinnedProviderCategories = visibleProviderCategories.filterNot { it.id in pinnedCategoryIds }

                    val orderedCategories = buildList {
                        val favoritesCategory = customCats.find { it.id == VirtualCategoryIds.FAVORITES }
                        if (favoritesCategory != null) {
                            add(favoritesCategory)
                        }
                        add(recentCategory)
                        addAll(customCats.filter { it.id != VirtualCategoryIds.FAVORITES })
                        add(allChannelsCategory)
                        addAll(pinnedProviderCategories)
                        addAll(unpinnedProviderCategories)
                    }

                    val hiddenLiveCategoriesList = providerCats
                        .filter { it.id != ChannelRepository.ALL_CHANNELS_ID && it.id in hiddenCategoryIds }
                        .sortedBy { it.name }
                    CategorySelectionContext(
                        categories = orderedCategories,
                        defaultCategoryId = defaultId,
                        lastVisitedCategoryId = lastVisitedCategoryId,
                        pinnedCategoryIds = pinnedCategoryIds,
                        hiddenLiveCategories = hiddenLiveCategoriesList
                    )
                }.combine(preferencesRepository.showFavoritesCategory) { ctx, showFavorites ->
                    if (!showFavorites) ctx.copy(categories = ctx.categories.filter { it.id != VirtualCategoryIds.FAVORITES }) else ctx
                }.combine(preferencesRepository.showRecentChannelsCategory) { ctx, showRecent ->
                    if (!showRecent) ctx.copy(categories = ctx.categories.filter { it.id != VirtualCategoryIds.RECENT }) else ctx
                }.combine(preferencesRepository.showAllChannelsCategory) { ctx, showAll ->
                    if (!showAll) ctx.copy(categories = ctx.categories.filter { it.id != ChannelRepository.ALL_CHANNELS_ID }) else ctx
                }.collect { selectionContext ->
                    val categories = selectionContext.categories
                    val defaultId = selectionContext.defaultCategoryId
                    val preferredCategoryId = _preferredInitialCategoryId.value
                    val lastVisitedCategory = selectionContext.lastVisitedCategoryId?.let { lastVisitedId ->
                        categories.find { it.id == lastVisitedId && it.id != VirtualCategoryIds.RECENT }
                    }
                    _uiState.update {
                        it.copy(
                            categories = categories,
                            lastVisitedCategory = lastVisitedCategory,
                            isCategoriesLoading = false,
                            pinnedCategoryIds = selectionContext.pinnedCategoryIds,
                            hiddenLiveCategories = selectionContext.hiddenLiveCategories
                        )
                    }

                    val currentSelected = _uiState.value.selectedCategory
                    val preferredCategory = preferredCategoryId?.let { categoryId ->
                        categories.find { it.id == categoryId }
                    }

                    if (preferredCategory != null && currentSelected?.id != preferredCategory.id) {
                        _preferredInitialCategoryId.value = null
                        selectCategory(preferredCategory)
                        return@collect
                    }

                    if (currentSelected == null && categories.isNotEmpty()) {
                        val defaultCat = defaultId?.let { id -> categories.find { it.id == id } }
                        val favoritesCat = categories.find { it.id == VirtualCategoryIds.FAVORITES }

                        if (defaultCat != null) {
                            _uiState.update { it.copy(shouldAutoFocusFirstChannelOnEntry = true) }
                            selectCategory(defaultCat)
                        }
                        else if (favoritesCat != null) selectCategory(favoritesCat)
                        else selectCategory(categories.first())
                    } else if (currentSelected != null) {
                        val reselectedCat = categories.find { it.id == currentSelected.id }

                        if (reselectedCat != null) {
                            if (reselectedCat != currentSelected) {
                                _uiState.update { it.copy(selectedCategory = reselectedCat) }
                            }
                        } else {
                            val defaultCat = defaultId?.let { id -> categories.find { it.id == id } }
                            val favoritesCat = categories.find { it.id == VirtualCategoryIds.FAVORITES }

                            if (defaultCat != null) {
                                _uiState.update { it.copy(shouldAutoFocusFirstChannelOnEntry = true) }
                                selectCategory(defaultCat)
                            }
                            else if (favoritesCat != null) selectCategory(favoritesCat)
                            else if (categories.isNotEmpty()) selectCategory(categories.first())
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isCategoriesLoading = false,
                        errorMessage = appContext.getString(R.string.home_error_load_failed)
                    )
                }
            }
        }
    }

    private fun loadCombinedCategoriesAndChannels(profileId: Long) {
        categoriesJob?.cancel()
        categoriesJob = viewModelScope.launch {
            try {
                _uiState.update { it.copy(isCategoriesLoading = true, errorMessage = null, pinnedCategoryIds = emptySet()) }
                val providerIds = currentCombinedProviderIds()
                combine(
                    combinedM3uRepository.getCombinedCategories(profileId),
                    getCustomCategories(providerIds, ContentType.LIVE)
                ) { combinedCategories, customCats ->
                    combinedCategoriesById = combinedCategories.associateBy { it.category.id }
                    val recentCategory = Category(
                        id = VirtualCategoryIds.RECENT,
                        name = "Recent",
                        type = ContentType.LIVE,
                        isVirtual = true,
                        count = _uiState.value.recentChannels.size
                    )
                    val allChannelsCategory = Category(
                        id = ChannelRepository.ALL_CHANNELS_ID,
                        name = "All Channels",
                        type = ContentType.LIVE,
                        count = combinedCategories.sumOf { it.category.count }
                    )
                    CategorySelectionContext(
                        categories = buildList {
                            val favoritesCategory = customCats.find { it.id == VirtualCategoryIds.FAVORITES }
                            if (favoritesCategory != null) add(favoritesCategory)
                            add(recentCategory)
                            addAll(customCats.filter { it.id != VirtualCategoryIds.FAVORITES })
                            add(allChannelsCategory)
                            addAll(combinedCategories.map { it.category })
                        },
                        defaultCategoryId = null,
                        lastVisitedCategoryId = null,
                        pinnedCategoryIds = emptySet(),
                        hiddenLiveCategories = emptyList()
                    )
                }.combine(preferencesRepository.showFavoritesCategory) { ctx, showFavorites ->
                    if (!showFavorites) ctx.copy(categories = ctx.categories.filter { it.id != VirtualCategoryIds.FAVORITES }) else ctx
                }.combine(preferencesRepository.showRecentChannelsCategory) { ctx, showRecent ->
                    if (!showRecent) ctx.copy(categories = ctx.categories.filter { it.id != VirtualCategoryIds.RECENT }) else ctx
                }.combine(preferencesRepository.showAllChannelsCategory) { ctx, showAll ->
                    if (!showAll) ctx.copy(categories = ctx.categories.filter { it.id != ChannelRepository.ALL_CHANNELS_ID }) else ctx
                }.collect { selectionContext ->
                    val categories = selectionContext.categories
                    _uiState.update {
                        it.copy(
                            categories = categories,
                            lastVisitedCategory = null,
                            isCategoriesLoading = false,
                            pinnedCategoryIds = emptySet()
                        )
                    }

                    val currentSelected = _uiState.value.selectedCategory
                    if (currentSelected == null && categories.isNotEmpty()) {
                        val favoritesCat = categories.find { it.id == VirtualCategoryIds.FAVORITES }
                        if (favoritesCat != null) selectCategory(favoritesCat) else selectCategory(categories.first())
                    } else if (currentSelected != null && categories.none { it.id == currentSelected.id }) {
                        val favoritesCat = categories.find { it.id == VirtualCategoryIds.FAVORITES }
                        if (favoritesCat != null) selectCategory(favoritesCat) else if (categories.isNotEmpty()) selectCategory(categories.first())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isCategoriesLoading = false,
                        errorMessage = appContext.getString(R.string.home_error_load_failed)
                    )
                }
            }
        }
    }

    fun selectCategory(category: Category) {
        if (_uiState.value.selectedCategory?.id == category.id) return
        if (!_uiState.value.isCombinedLiveSource) {
            _uiState.value.provider?.id?.let { providerId ->
                parentalControlManager.retainUnlockedCategory(
                    providerId = providerId,
                    categoryId = category.id.takeIf { !category.isVirtual && it > 0L }
                )
            }
        }
        clearPreview()
        _localChannels.value = emptyList()
        _uiState.update {
            it.copy(
                selectedCategory = category,
                filteredChannels = emptyList(),
                hasChannels = false,
                isLoading = true,
                errorMessage = null
            )
        }
        _preferredInitialCategoryId.value = null
        if (!_uiState.value.isCombinedLiveSource && category.id != VirtualCategoryIds.RECENT) {
            val providerId = _uiState.value.provider?.id
            if (providerId != null) {
                viewModelScope.launch {
                    if (!preferencesRepository.isIncognitoMode.first()) {
                        preferencesRepository.setLastLiveCategoryId(providerId, category.id)
                    }
                }
            }
        }
        loadChannelsForCategory(category)
    }

    fun setPreferredInitialCategory(categoryId: Long?) {
        if (categoryId == null) return

        val matchingCategory = _uiState.value.categories.firstOrNull { it.id == categoryId }
        if (matchingCategory != null) {
            if (_uiState.value.selectedCategory?.id != matchingCategory.id) {
                selectCategory(matchingCategory)
            }
            return
        }

        _preferredInitialCategoryId.value = categoryId
    }

    private fun loadChannelsForCategory(category: Category) {
        loadChannelsJob?.cancel()
        _channelBrowseLimit.value = CHANNEL_PAGE_SIZE
        _channelSearchLimit.value = CHANNEL_SEARCH_PAGE_SIZE
        loadChannelsJob = viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        errorMessage = null
                    )
                }

                val queryFlow = _uiState.map { it.channelSearchQuery }
                    .debounce(150)
                    .distinctUntilChanged()

                val channelsFlow = if (_uiState.value.isCombinedLiveSource) {
                    val profileId = (_uiState.value.activeLiveSource as? ActiveLiveSource.CombinedM3uSource)?.profileId
                    if (profileId == null) {
                        flowOf(emptyList())
                    } else if (category.id == ChannelRepository.ALL_CHANNELS_ID) {
                        val allFlows = combinedCategoriesById.values.map { combinedCategory ->
                            combinedM3uRepository.getCombinedChannels(profileId, combinedCategory)
                        }
                        if (allFlows.isEmpty()) flowOf(emptyList())
                        else combine(allFlows) { arrays -> arrays.toList().flatMap { it } }
                    } else if (category.isVirtual) {
                        val providerIds = currentLiveProviderIds()
                        when (category.id) {
                            VirtualCategoryIds.RECENT -> {
                                observeRecentLiveIds(providerIds, limit = 24)
                                    .flatMapLatest(::loadChannelsByOrderedIds)
                            }
                            VirtualCategoryIds.FAVORITES -> {
                                observeLiveFavorites(providerIds)
                                    .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                                    .flatMapLatest(::loadChannelsByOrderedIds)
                            }
                            else -> {
                                val groupId = -category.id
                                favoriteRepository.getFavoritesByGroup(groupId)
                                    .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                                    .flatMapLatest(::loadChannelsByOrderedIds)
                            }
                        }
                    } else {
                        val combinedCategory = combinedCategoriesById[category.id]
                        if (combinedCategory == null) {
                            flowOf(emptyList())
                        } else {
                            queryFlow.flatMapLatest { query ->
                                val trimmedQuery = query.trim()
                                if (trimmedQuery.isBlank()) {
                                    combinedM3uRepository.getCombinedChannels(profileId, combinedCategory)
                                } else if (trimmedQuery.length < MIN_CHANNEL_SEARCH_QUERY_LENGTH) {
                                    flowOf(emptyList())
                                } else {
                                    combinedM3uRepository.searchCombinedChannels(profileId, combinedCategory, trimmedQuery)
                                }
                            }
                        }
                    }
                } else if (category.isVirtual) {
                    val providerId = _uiState.value.provider?.id ?: return@launch
                    val orderedFlow = if (category.id == VirtualCategoryIds.RECENT) {
                        playbackHistoryRepository.getRecentlyWatchedByProvider(providerId, limit = 24)
                            .map { it.toRecentLiveContentIds() }
                            .flatMapLatest { ids -> loadChannelsByOrderedIds(ids) }
                    } else if (category.id == VirtualCategoryIds.FAVORITES) {
                        favoriteRepository.getFavorites(providerId, ContentType.LIVE)
                            .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                            .flatMapLatest { ids -> loadChannelsByOrderedIds(ids) }
                    } else {
                        val groupId = -category.id
                        favoriteRepository.getFavoritesByGroup(groupId)
                            .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                            .flatMapLatest { ids -> loadChannelsByOrderedIds(ids) }
                    }

                    combine(orderedFlow, queryFlow) { channels, query ->
                        val trimmedQuery = query.trim()
                        if (trimmedQuery.isBlank()) {
                            channels
                        } else if (trimmedQuery.length < MIN_CHANNEL_SEARCH_QUERY_LENGTH) {
                            emptyList()
                        } else {
                            channels.filter { it.name.contains(trimmedQuery, ignoreCase = true) }
                        }
                    }
                } else {
                    val providerId = _uiState.value.provider?.id ?: return@launch
                    queryFlow.flatMapLatest { query ->
                        val trimmedQuery = query.trim()
                        if (trimmedQuery.isBlank()) {
                            _channelBrowseLimit.flatMapLatest { limit ->
                                channelRepository.getChannelsByCategoryPage(providerId, category.id, limit)
                            }
                        } else if (trimmedQuery.length < MIN_CHANNEL_SEARCH_QUERY_LENGTH) {
                            flowOf(emptyList())
                        } else {
                            _channelSearchLimit.flatMapLatest { limit ->
                                channelRepository.searchChannelsByCategoryPaged(providerId, category.id, trimmedQuery, limit)
                            }
                        }
                    }
                }

                combine(
                    channelsFlow,
                    preferencesRepository.liveChannelNumberingMode,
                    _uiState.map { it.selectedCombinedSourceProviderId }.distinctUntilChanged(),
                    _uiState.map { it.parentalControlLevel }.distinctUntilChanged()
                ) { channels, numberingMode, selectedCombinedSourceProviderId, level ->
                    val byProvider = selectedCombinedSourceProviderId?.let { selectedProviderId ->
                        channels.filter { it.providerId == selectedProviderId }
                    } ?: channels
                    val numbered = when (numberingMode) {
                        ChannelNumberingMode.GROUP -> byProvider.mapIndexed { index, channel ->
                            channel.copy(number = index + 1)
                        }
                        ChannelNumberingMode.PROVIDER -> byProvider
                        ChannelNumberingMode.HIDDEN -> byProvider.map { it.copy(number = 0) }
                    }
                    val isAggregatedSurface = category.id == ChannelRepository.ALL_CHANNELS_ID ||
                        category.id == VirtualCategoryIds.RECENT
                    if (isAggregatedSurface) {
                        AdultContentVisibilityPolicy.filterForAggregatedSurface(
                            numbered, level
                        ) { isAdult || isUserProtected }
                    } else numbered
                }.collect { displayedChannels ->
                    val currentQuery = _uiState.value.channelSearchQuery.trim()
                    val currentLimit = if (currentQuery.length < MIN_CHANNEL_SEARCH_QUERY_LENGTH) {
                        _channelBrowseLimit.value
                    } else {
                        _channelSearchLimit.value
                    }
                    _localChannels.value = displayedChannels
                    _uiState.update {
                        it.copy(
                            hasChannels = displayedChannels.isNotEmpty(),
                            isLoading = displayedChannels.isEmpty() && it.isSyncing,
                            errorMessage = null,
                            hasMoreChannels = displayedChannels.size >= currentLimit
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = appContext.getString(R.string.home_error_load_failed)
                    )
                }
            }
        }
    }

    fun updateCategorySearchQuery(query: String) {
        if (_uiState.value.isChannelReorderMode) return
        _uiState.update { state ->
            state.copy(
                categorySearchQuery = query,
                activeCategoryFilter = state.savedCategoryFilters.firstOrNull {
                    it.equals(query, ignoreCase = true)
                }
            )
        }
        if (query.isNotBlank()) {
            val state = _uiState.value
            val selectedId = state.selectedCategory?.id
            if (selectedId != null) {
                val visibleCategoryIds = state.categories
                    .filter { it.name.contains(query, ignoreCase = true) }
                    .map { it.id }.toSet()
                if (selectedId !in visibleCategoryIds) {
                    clearVisibleChannelsForFilteredCategories()
                }
            }
        }
    }

    fun applySavedCategoryFilter(filter: String) {
        if (_uiState.value.isChannelReorderMode) return
        _uiState.update { state ->
            val shouldClear = state.activeCategoryFilter == filter && state.categorySearchQuery == filter
            state.copy(
                categorySearchQuery = if (shouldClear) "" else filter,
                activeCategoryFilter = if (shouldClear) null else filter
            )
        }
        val state = _uiState.value
        val query = state.categorySearchQuery
        if (query.isNotBlank()) {
            val selectedId = state.selectedCategory?.id
            if (selectedId != null) {
                val visibleCategoryIds = state.categories
                    .filter { it.name.contains(query, ignoreCase = true) }
                    .map { it.id }.toSet()
                if (selectedId !in visibleCategoryIds) {
                    clearVisibleChannelsForFilteredCategories()
                }
            }
        }
    }

    fun clearCategorySearchQuery() {
        if (_uiState.value.isChannelReorderMode) return
        _uiState.update {
            it.copy(
                categorySearchQuery = "",
                activeCategoryFilter = null
            )
        }
    }

    fun setCombinedSourceFilter(providerId: Long?) {
        _uiState.update { it.copy(selectedCombinedSourceProviderId = providerId) }
    }

    fun addLiveTvCategoryFilter(filter: String) {
        viewModelScope.launch {
            val normalized = filter.trim()
            when {
                normalized.isBlank() -> {
                    _uiState.update {
                        it.copy(userMessage = appContext.getString(R.string.settings_live_tv_quick_filter_blank))
                    }
                }
                _uiState.value.savedCategoryFilters.any { existing ->
                    existing.equals(normalized, ignoreCase = true)
                } -> {
                    _uiState.update {
                        it.copy(
                            userMessage = appContext.getString(
                                R.string.settings_live_tv_quick_filter_duplicate,
                                normalized
                            )
                        )
                    }
                }
                preferencesRepository.addLiveTvCategoryFilter(normalized) -> {
                    _uiState.update {
                        it.copy(
                            userMessage = appContext.getString(
                                R.string.settings_live_tv_quick_filter_added,
                                normalized
                            )
                        )
                    }
                }
            }
        }
    }

    fun clearVisibleChannelsForFilteredCategories() {
        clearPreview()
        _localChannels.value = emptyList()
        _uiState.update {
            it.copy(
                filteredChannels = emptyList(),
                hasChannels = false,
                isLoading = false,
                errorMessage = null
            )
        }
    }

    fun updateChannelSearchQuery(query: String) {
        if (_uiState.value.isChannelReorderMode) return
        _channelSearchLimit.value = CHANNEL_SEARCH_PAGE_SIZE
        _uiState.update { it.copy(channelSearchQuery = query) }
    }

    fun loadMoreChannels() {
        val currentQuery = _uiState.value.channelSearchQuery.trim()
        if (currentQuery.length < MIN_CHANNEL_SEARCH_QUERY_LENGTH) {
            _channelBrowseLimit.update { it + CHANNEL_PAGE_SIZE }
        } else {
            _channelSearchLimit.update { it + CHANNEL_SEARCH_PAGE_SIZE }
        }
    }

    fun previewChannel(channel: Channel) {
        if (_uiState.value.liveTvChannelMode != LiveTvChannelMode.PRO) return
        if (_uiState.value.previewChannelId == channel.id && _uiState.value.previewPlayerEngine != null) return

        val previewVersion = ++previewSessionVersion
        val engine = previewPlayerEngine ?: playerEngineProvider.get().also { previewPlayerEngine = it }
        previewPlaybackJob?.cancel()
        previewErrorJob?.cancel()

        _uiState.update {
            it.copy(
                previewChannelId = channel.id,
                previewPlayerEngine = engine,
                isPreviewLoading = true,
                previewErrorMessage = null
            )
        }

        previewPlaybackJob = viewModelScope.launch {
            engine.playbackState.collectLatest { playbackState ->
                if (!isActivePreviewSession(previewVersion, channel.id)) return@collectLatest
                _uiState.update { state ->
                    state.copy(
                        isPreviewLoading = playbackState == PlaybackState.IDLE || playbackState == PlaybackState.BUFFERING,
                        previewErrorMessage = when {
                            playbackState == PlaybackState.ERROR && state.previewErrorMessage.isNullOrBlank() ->
                                appContext.getString(R.string.live_preview_failed)
                            playbackState != PlaybackState.ERROR -> null
                            else -> state.previewErrorMessage
                        }
                    )
                }
            }
        }

        previewErrorJob = viewModelScope.launch {
            engine.error.collectLatest { error ->
                if (!isActivePreviewSession(previewVersion, channel.id)) return@collectLatest
                if (error != null) {
                    _uiState.update {
                        it.copy(
                            isPreviewLoading = false,
                            previewErrorMessage = error.message.ifBlank { appContext.getString(R.string.live_preview_failed) }
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            when (val result = channelRepository.getStreamInfo(channel)) {
                is Result.Success -> {
                    if (!isActivePreviewSession(previewVersion, channel.id)) return@launch
                    val preparedStreamInfo = when (val pluginResult = pluginManager.preparePlaybackStreamInfo(result.data)) {
                        is Result.Success -> pluginResult.data
                        is Result.Error -> {
                            if (!isActivePreviewSession(previewVersion, channel.id)) return@launch
                            _uiState.update {
                                it.copy(
                                    isPreviewLoading = false,
                                    previewErrorMessage = pluginResult.message.ifBlank { appContext.getString(R.string.live_preview_failed) }
                                )
                            }
                            return@launch
                        }
                        Result.Loading -> result.data
                    }
                    if (!isActivePreviewSession(previewVersion, channel.id)) return@launch
                    engine.stop()
                    engine.setDecoderModes(
                        audioMode = preferencesRepository.playerAudioDecoderMode.first(),
                        videoMode = preferencesRepository.playerVideoDecoderMode.first()
                    )
                    engine.setSurfaceMode(preferencesRepository.playerSurfaceMode.first())
                    engine.setPlaybackBufferMode(preferencesRepository.playerPlaybackBufferMode.first())
                    engine.setFastRetryOnTransientFailures(
                        preferencesRepository.playerFastRetryOnTransientFailures.first()
                    )
                    engine.prepare(preparedStreamInfo)
                    engine.setVolume(1f)
                    engine.play()
                    livePreviewHandoffManager.registerPreviewSession(
                        channel = channel,
                        streamInfo = preparedStreamInfo,
                        engine = engine,
                        source = PreviewHandoffSource.HOME
                    )
                    scheduleAdaptivePreviewReprime(
                        previewVersion = previewVersion,
                        channel = channel,
                        streamInfo = preparedStreamInfo,
                        engine = engine
                    )
                }
                is Result.Error -> {
                    if (!isActivePreviewSession(previewVersion, channel.id)) return@launch
                    _uiState.update {
                        it.copy(
                            isPreviewLoading = false,
                            previewErrorMessage = result.message
                        )
                    }
                }
                Result.Loading -> Unit
            }
        }
    }

    private fun scheduleAdaptivePreviewReprime(
        previewVersion: Long,
        channel: Channel,
        streamInfo: StreamInfo,
        engine: PlayerEngine
    ) {
        if (!streamInfo.needsAdaptivePreviewReprime()) return

        viewModelScope.launch {
            delay(ADAPTIVE_PREVIEW_REPRIME_DELAY_MS)
            if (!isActivePreviewSession(previewVersion, channel.id)) return@launch
            if (_uiState.value.previewErrorMessage != null) return@launch
            if (engine.playbackState.value != PlaybackState.READY) return@launch
            if (!engine.isPlaying.value) return@launch
            if (engine.playerStats.value.ttffMs > 0L) return@launch

            engine.renewStreamUrl(streamInfo)
            engine.play()
        }
    }

    private fun StreamInfo.needsAdaptivePreviewReprime(): Boolean {
        if (drmInfo == null) return false
        return streamType == StreamType.DASH || streamType == StreamType.SMOOTH_STREAMING
    }

    fun beginPreviewHandoff(channel: Channel): Boolean {
        val engine = previewPlayerEngine ?: return false
        if (_uiState.value.previewChannelId != channel.id) return false
        if (!livePreviewHandoffManager.beginFullscreenHandoff(channel.id, engine)) return false

        previewSessionVersion++
        previewPlaybackJob?.cancel()
        previewErrorJob?.cancel()
        previewPlaybackJob = null
        previewErrorJob = null
        previewPlayerEngine = null
        _uiState.update {
            it.copy(
                previewChannelId = null,
                previewPlayerEngine = null,
                isPreviewLoading = false,
                previewErrorMessage = null
            )
        }
        return true
    }

    fun resumePreviewFromHandoff() {
        val session = livePreviewHandoffManager.consumeReverseHandoff(PreviewHandoffSource.HOME) ?: return
        val engine = session.engine
        previewSessionVersion++
        val version = previewSessionVersion
        previewPlaybackJob?.cancel()
        previewErrorJob?.cancel()
        previewPlaybackJob = null
        previewErrorJob = null
        previewPlayerEngine?.stop()
        previewPlayerEngine?.release()
        previewPlayerEngine = engine
        // Restore auxiliary-engine defaults that the fullscreen handoff flipped.
        (engine as? com.streamvault.player.Media3PlayerEngine)?.let {
            it.enableMediaSession = false
            it.bypassAudioFocus = true
        }
        engine.play()
        // Re-register the forward-handoff slot so a subsequent click can open fullscreen again.
        livePreviewHandoffManager.registerPreviewSession(
            channelId = session.channelId,
            providerId = session.providerId,
            streamInfo = session.streamInfo,
            engine = engine,
            source = PreviewHandoffSource.HOME
        )
        _uiState.update {
            it.copy(
                previewChannelId = session.channelId,
                previewPlayerEngine = engine,
                isPreviewLoading = false,
                previewErrorMessage = null
            )
        }
        previewPlaybackJob = viewModelScope.launch {
            engine.playbackState.collectLatest { state ->
                if (!isActivePreviewSession(version, session.channelId)) return@collectLatest
                if (state == PlaybackState.ERROR && _uiState.value.previewErrorMessage == null) {
                    _uiState.update { it.copy(previewErrorMessage = appContext.getString(R.string.live_preview_failed)) }
                }
            }
        }
    }

    fun clearPreview() {
        if (previewPlayerEngine == null) return
        previewSessionVersion++
        previewPlaybackJob?.cancel()
        previewErrorJob?.cancel()
        previewPlaybackJob = null
        previewErrorJob = null
        livePreviewHandoffManager.clear(previewPlayerEngine)
        previewPlayerEngine?.stop()
        previewPlayerEngine?.release()
        previewPlayerEngine = null
        _uiState.update {
            it.copy(
                previewChannelId = null,
                previewPlayerEngine = null,
                isPreviewLoading = false,
                previewErrorMessage = null
            )
        }
    }

    private fun isActivePreviewSession(version: Long, channelId: Long): Boolean =
        version == previewSessionVersion && _uiState.value.previewChannelId == channelId

    private fun fetchEpgForChannels(channels: List<Channel>) {
        epgJob?.cancel()
        val lookupKeys = channels.mapNotNull(Channel::guideLookupKey).distinct()

        epgJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val mergedProgramMap = buildMap {
                channels.groupBy { it.providerId }.forEach { (providerId, providerChannels) ->
                    val freshProgramMap = epgRepository.getResolvedProgramsForChannels(
                        providerId = providerId,
                        channelIds = providerChannels.map { it.id },
                        startTime = now - (60L * 60L * 1000L),
                        endTime = now + (2L * 60L * 60L * 1000L)
                    ).mapValues { (_, programs) ->
                        programs.firstOrNull { it.startTime <= now && it.endTime > now }
                            ?: programs.firstOrNull()
                    }.mapNotNull { (lookupKey, program) ->
                        program?.let { lookupKey to it }
                    }.toMap()
                    putAll(freshProgramMap)
                    putAll(
                        fetchXtreamNowPlayingFallback(
                            providerId = providerId,
                            channels = providerChannels,
                            existingPrograms = freshProgramMap
                        )
                    )
                }
            }

            val channelEpgIds = lookupKeys.toSet()
            _epgProgramMap.update { existing ->
                buildMap {
                    putAll(existing)
                    channelEpgIds.forEach { epgId ->
                        if (mergedProgramMap.containsKey(epgId)) {
                            put(epgId, mergedProgramMap.getValue(epgId))
                        } else {
                            remove(epgId)
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchXtreamNowPlayingFallback(
        providerId: Long,
        channels: List<Channel>,
        existingPrograms: Map<String, Program>
    ): Map<String, Program> {
        val providerType = _uiState.value.provider?.type
        if (
            providerType != com.streamvault.domain.model.ProviderType.XTREAM_CODES &&
            providerType != com.streamvault.domain.model.ProviderType.STALKER_PORTAL
        ) {
            return emptyMap()
        }

        // Sentinel keys (shared portal placeholders like "glotv") never carry a schedule;
        // remember them as empty without spending a paced request.
        val sentinelKeys = GuideCachePolicy.sentinelKeysOf(channels)
        if (sentinelKeys.isNotEmpty()) {
            homeSessionEmptyGuideKeys += sentinelKeys
            preferencesRepository.addEmptyGuideKeys(providerId, sentinelKeys)
        }

        val persistedEmpty = preferencesRepository.getEmptyGuideKeys(providerId)
        val missingChannels = GuideCachePolicy.requestableChannels(
            channels = channels,
            sessionEmptyKeys = homeSessionEmptyGuideKeys,
            persistedEmptyAt = persistedEmpty,
            existingProgramsByChannel = existingPrograms.mapValues { (_, program) -> listOf(program) }
        )
        if (missingChannels.isEmpty()) {
            return emptyMap()
        }

        val now = System.currentTimeMillis()
        val fallbackChannels = missingChannels.take(10)
        val programsByRequest = providerRepository.getProgramsForLiveStreams(
            providerId = providerId,
            requests = fallbackChannels.map { channel ->
                LiveStreamProgramRequest(
                    streamId = channel.streamId,
                    epgChannelId = channel.epgChannelId
                )
            },
            limit = 6
        )

        val newlyEmpty = mutableSetOf<String>()
        val recovered = mutableSetOf<String>()
        val result = fallbackChannels.mapNotNull { channel ->
            val lookupKey = channel.guideLookupKey() ?: return@mapNotNull null
            val fetchResult = programsByRequest[
                LiveStreamProgramRequest(
                    streamId = channel.streamId,
                    epgChannelId = channel.epgChannelId
                )
            ]
            val programs = (fetchResult as? Result.Success)?.data.orEmpty()
            if (programs.isEmpty()) {
                // Successful but empty: remember it so later window updates skip it instantly.
                // Errors are left out so transient failures retry on the next emission.
                if (fetchResult is Result.Success) {
                    newlyEmpty += lookupKey
                }
                return@mapNotNull null
            }
            // Fresh data may have appeared since a previous empty result.
            recovered += lookupKey
            val currentProgram = programs.firstOrNull { it.startTime <= now && it.endTime > now }
                ?: programs.firstOrNull()
            currentProgram?.let { lookupKey to it }
        }.toMap()

        if (newlyEmpty.isNotEmpty()) {
            homeSessionEmptyGuideKeys += newlyEmpty
            preferencesRepository.addEmptyGuideKeys(providerId, newlyEmpty)
        }
        if (recovered.isNotEmpty()) {
            homeSessionEmptyGuideKeys -= recovered
            preferencesRepository.removeEmptyGuideKeys(providerId, recovered)
        }
        return result
    }

    fun updateVisibleChannelWindow(channelIds: List<Long>, focusedChannelId: Long? = null) {
        val combinedIds = buildSet {
            addAll(channelIds)
            focusedChannelId?.let(::add)
        }
        if (_visibleChannelWindow.value != combinedIds) {
            _visibleChannelWindow.value = combinedIds
        }
    }

    fun addFavorite(channel: Channel) {
        viewModelScope.launch {
            favoriteRepository.addFavorite(
                providerId = channel.providerId,
                contentId = channel.id,
                contentType = ContentType.LIVE,
                groupId = null
            )
            onDismissDialog()
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.home_added_to_favorites, channel.name)) }
        }
    }

    fun removeFavorite(channel: Channel) {
        viewModelScope.launch {
            favoriteRepository.removeFavorite(
                providerId = channel.providerId,
                contentId = channel.id,
                contentType = ContentType.LIVE,
                groupId = null
            )
            onDismissDialog()
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.home_removed_from_favorites, channel.name)) }
        }
    }

    fun createCustomGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.home_group_name_invalid)) }
            return
        }
        if (trimmed.equals("Favorites", ignoreCase = true)) {
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.home_group_name_reserved)) }
            return
        }
        if (_uiState.value.categories.any { it.name.equals(trimmed, ignoreCase = true) }) {
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.home_group_name_duplicate, trimmed)) }
            return
        }

        viewModelScope.launch {
            val providerId = resolveLiveWriteProviderId()
            if (providerId == null) {
                _uiState.update { it.copy(userMessage = appContext.getString(R.string.home_group_requires_provider_scope)) }
                return@launch
            }
            favoriteRepository.createGroup(providerId, trimmed, contentType = ContentType.LIVE)
            _uiState.update { it.copy(userMessage = appContext.getString(R.string.home_group_created, trimmed)) }
        }
    }

    private fun observeCombinedRecentChannels(providerIds: List<Long>) {
        recentChannelsJob?.cancel()
        recentChannelsJob = viewModelScope.launch {
            combine(
                observeRecentLiveIds(providerIds, limit = 12)
                    .flatMapLatest(::loadChannelsByOrderedIds),
                preferencesRepository.parentalControlLevel
            ) { channels, level ->
                AdultContentVisibilityPolicy.filterForAggregatedSurface(
                    channels, level
                ) { isAdult || isUserProtected }
            }.collect { visible ->
                _uiState.update { it.copy(recentChannels = visible) }
                updateRecentCategoryCount(visible.size)
            }
        }
    }

    private fun observeRecentChannels(providerId: Long) {
        recentChannelsJob?.cancel()
        recentChannelsJob = viewModelScope.launch {
            combine(
                playbackHistoryRepository.getRecentlyWatchedByProvider(providerId, limit = 12)
                    .map { it.toRecentLiveContentIds() }
                    .flatMapLatest { ids -> loadChannelsByOrderedIds(ids) },
                preferencesRepository.parentalControlLevel
            ) { channels, level ->
                AdultContentVisibilityPolicy.filterForAggregatedSurface(
                    channels, level
                ) { isAdult || isUserProtected }
            }.collect { visible ->
                _uiState.update { it.copy(recentChannels = visible) }
                updateRecentCategoryCount(visible.size)
            }
        }
    }

    private fun updateRecentCategoryCount(count: Int) {
        _uiState.update { state ->
            state.copy(
                categories = state.categories.map { category ->
                    if (category.id == VirtualCategoryIds.RECENT) category.copy(count = count) else category
                }
            )
        }
    }

    fun requestRenameGroup(category: Category) {
        if (!category.isVirtual || category.id in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT)) return
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
        val trimmed = name.trim()
        when {
            trimmed.isBlank() -> {
                _uiState.update { it.copy(renameGroupError = appContext.getString(R.string.home_group_name_invalid)) }
                return
            }
            trimmed.equals("Favorites", ignoreCase = true) -> {
                _uiState.update { it.copy(renameGroupError = appContext.getString(R.string.home_group_name_reserved)) }
                return
            }
            _uiState.value.categories.any { existing ->
                existing.id != category.id && existing.name.equals(trimmed, ignoreCase = true)
            } -> {
                _uiState.update { it.copy(renameGroupError = appContext.getString(R.string.home_group_name_duplicate, trimmed)) }
                return
            }
        }

        viewModelScope.launch {
            favoriteRepository.renameGroup(-category.id, trimmed)
            _uiState.update {
                it.copy(
                    showRenameGroupDialog = false,
                    groupToRename = null,
                    renameGroupError = null,
                    userMessage = appContext.getString(R.string.home_group_renamed, trimmed)
                )
            }
        }
    }

    fun requestDeleteGroup(category: Category) {
        if (!category.isVirtual || category.id in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT)) return
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
                    userMessage = appContext.getString(R.string.home_group_deleted, category.name)
                )
            }
        }
    }

    fun addToGroup(channel: Channel, category: Category) {
        if (!category.isVirtual || category.id in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT)) return
        viewModelScope.launch {
            val groupId = -category.id
            favoriteRepository.addFavorite(
                providerId = channel.providerId,
                contentId = channel.id,
                contentType = ContentType.LIVE,
                groupId = groupId
            )
            onDismissDialog()
            _uiState.update { it.copy(userMessage = "Added ${channel.name} to ${category.name}") }
        }
    }

    fun removeFromGroup(channel: Channel, category: Category) {
        if (!category.isVirtual || category.id in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT)) return
        viewModelScope.launch {
            val groupId = -category.id
            favoriteRepository.removeFavorite(
                providerId = channel.providerId,
                contentId = channel.id,
                contentType = ContentType.LIVE,
                groupId = groupId
            )
            onDismissDialog()
            _uiState.update { it.copy(userMessage = "Removed ${channel.name} from ${category.name}") }
        }
    }

    fun moveChannel(channel: Channel, direction: Int) {
        val currentCategory = _uiState.value.selectedCategory ?: return
        if (!currentCategory.isVirtual || currentCategory.id == VirtualCategoryIds.RECENT) return

        val currentList = _localChannels.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == channel.id }
        if (index == -1) return

        val newIndex = index + direction
        if (newIndex < 0 || newIndex >= currentList.size) return

        java.util.Collections.swap(currentList, index, newIndex)
        _localChannels.value = currentList

        viewModelScope.launch {
            val groupId = if (currentCategory.id == VirtualCategoryIds.FAVORITES) null else -currentCategory.id

            val favoritesFlow = if (groupId == null) {
                observeLiveFavorites(currentLiveProviderIds())
            } else {
                favoriteRepository.getFavoritesByGroup(groupId)
            }

            val favorites = favoritesFlow.first()
            val favoriteMap = favorites.associateBy { it.contentId }

            val reorderedFavorites = currentList.mapNotNull { ch ->
                favoriteMap[ch.id]
            }

            favoriteRepository.reorderFavorites(reorderedFavorites)
        }
    }

    fun userMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun refreshData() {
        val provider = _uiState.value.provider ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            providerRepository.refreshProviderData(provider.id, force = true)
            tvInputChannelSyncManager.refreshTvInputCatalog()
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onShowDialog(channel: Channel) {
        _uiState.update { it.copy(showDialog = true, selectedChannelForDialog = channel) }
        viewModelScope.launch {
            val memberships = favoriteRepository.getGroupMemberships(channel.providerId, channel.id, ContentType.LIVE)
            _uiState.update { it.copy(dialogGroupMemberships = memberships) }
        }
    }

    fun onDismissDialog() {
        _uiState.update { it.copy(showDialog = false, selectedChannelForDialog = null) }
    }

    fun moveM3uChannelToMovies(channel: Channel) {
        if (!isM3uUiContext()) return
        viewModelScope.launch {
            when (val result = m3uClassificationRepository.classifyChannel(
                providerId = channel.providerId,
                channelId = channel.id,
                target = M3uClassificationTarget.MOVIE
            )) {
                is Result.Success -> {
                    onDismissDialog()
                    _uiState.update { it.copy(userMessage = "Moved '${channel.name}' to Movies") }
                }
                is Result.Error -> _uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun moveM3uChannelToSeries(channel: Channel, assignment: M3uSeriesAssignment) {
        if (!isM3uUiContext()) return
        viewModelScope.launch {
            when (val result = m3uClassificationRepository.classifyChannel(
                providerId = channel.providerId,
                channelId = channel.id,
                target = M3uClassificationTarget.SERIES,
                series = assignment
            )) {
                is Result.Success -> {
                    onDismissDialog()
                    _uiState.update { it.copy(userMessage = "Moved '${channel.name}' to Series") }
                }
                is Result.Error -> _uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun loadM3uCategoryItems(category: Category, onLoaded: (List<M3uCategoryItem>) -> Unit) {
        if (!isM3uUiContext()) return
        val providerId = _uiState.value.provider?.id ?: return
        viewModelScope.launch {
            when (val result = m3uClassificationRepository.getCategoryItems(providerId, category.id)) {
                is Result.Success -> onLoaded(result.data)
                is Result.Error -> _uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    fun organizeM3uCategory(
        category: Category,
        target: M3uClassificationTarget,
        seriesAssignments: Map<Long, M3uSeriesAssignment> = emptyMap()
    ) {
        if (!isM3uUiContext()) return
        val providerId = _uiState.value.provider?.id ?: return
        viewModelScope.launch {
            when (val result = m3uClassificationRepository.classifyCategory(
                providerId = providerId,
                categoryId = category.id,
                target = target,
                seriesAssignments = seriesAssignments
            )) {
                is Result.Success -> {
                    dismissCategoryOptions()
                    _uiState.update { it.copy(userMessage = "M3U category rule saved") }
                }
                is Result.Error -> _uiState.update { it.copy(userMessage = result.message) }
                Result.Loading -> Unit
            }
        }
    }

    private fun isM3uUiContext(): Boolean =
        !_uiState.value.isCombinedLiveSource && _uiState.value.provider?.type == ProviderType.M3U

    fun onShowHiddenChannelsDialog() {
        _uiState.update { it.copy(showHiddenChannelsDialog = true) }
    }

    fun onDismissHiddenChannelsDialog() {
        _uiState.update { it.copy(showHiddenChannelsDialog = false) }
    }

    suspend fun verifyPin(pin: String): Boolean {
        return preferencesRepository.verifyParentalPin(pin)
    }

    suspend fun unlockCategoryWithPin(category: Category, pin: String): Result<Unit> {
        val providerId = _uiState.value.provider?.id
            ?: combinedCategoriesById[category.id]?.bindings?.firstOrNull()?.providerId
            ?: return Result.error("Locked category context is unavailable.")
        val result = unlockParentalCategory(
            UnlockParentalCategoryCommand(
                providerId = providerId,
                categoryId = category.id,
                pin = pin
            )
        )
        if (result is Result.Success) {
            selectCategory(category)
        }
        return result
    }

    fun setDefaultCategory(category: Category) {
        if (_uiState.value.isCombinedLiveSource) return
        viewModelScope.launch {
            preferencesRepository.setDefaultCategory(category.id)
            _uiState.update { it.copy(userMessage = "Set '${category.name}' as default") }
        }
    }

    fun consumeInitialChannelFocusRequest() {
        if (!_uiState.value.shouldAutoFocusFirstChannelOnEntry) return
        _uiState.update { it.copy(shouldAutoFocusFirstChannelOnEntry = false) }
    }

    fun toggleCategoryLock(category: Category) {
        if (_uiState.value.isCombinedLiveSource) return
        val providerId = _uiState.value.provider?.id ?: return
        viewModelScope.launch {
            val newStatus = !category.isUserProtected
            when (categoryRepository.setCategoryProtection(providerId, category.id, category.type, newStatus)) {
                is Result.Success -> {
                    val msg = if (newStatus) "Locked '${category.name}'" else "Unlocked '${category.name}'"
                    _uiState.update { it.copy(userMessage = msg) }
                }
                is Result.Error -> _uiState.update { it.copy(userMessage = "Failed to update category lock") }
                Result.Loading -> Unit
            }
        }
    }

    fun showCategoryOptions(category: Category) {
        _uiState.update { it.copy(selectedCategoryForOptions = category) }
    }

    fun dismissCategoryOptions() {
        _uiState.update { it.copy(selectedCategoryForOptions = null) }
    }

    fun hideCategory(category: Category) {
        if (_uiState.value.isCombinedLiveSource) return
        if (category.isVirtual || category.id == ChannelRepository.ALL_CHANNELS_ID) return
        val providerId = _uiState.value.provider?.id ?: return
        viewModelScope.launch {
            preferencesRepository.setCategoryHidden(
                providerId = providerId,
                type = ContentType.LIVE,
                categoryId = category.id,
                hidden = true
            )
            if (_uiState.value.selectedCategory?.id == category.id) {
                val fallbackCategory = _uiState.value.categories.firstOrNull { it.id != category.id }
                if (fallbackCategory != null) {
                    selectCategory(fallbackCategory)
                } else {
                    dismissCategoryOptions()
                }
            } else {
                dismissCategoryOptions()
            }
            _uiState.update { it.copy(userMessage = "Hidden category '${category.name}'") }
        }
    }

    fun unhideCategory(category: Category) {
        if (_uiState.value.isCombinedLiveSource) return
        val providerId = _uiState.value.provider?.id ?: return
        viewModelScope.launch {
            preferencesRepository.setCategoryHidden(
                providerId = providerId,
                type = ContentType.LIVE,
                categoryId = category.id,
                hidden = false
            )
        }
    }

    fun unhideAllLiveCategories() {
        if (_uiState.value.isCombinedLiveSource) return
        val providerId = _uiState.value.provider?.id ?: return
        viewModelScope.launch {
            preferencesRepository.setHiddenCategoryIds(
                providerId = providerId,
                type = ContentType.LIVE,
                categoryIds = emptySet()
            )
        }
    }

    /**
     * Surfaces the list of currently-hidden Live channels for the active provider,
     * sorted by name. Mirrors the M5 `hiddenLiveCategories` pattern but uses
     * `getChannelsByIds` to resolve channel metadata (name) from the hidden id
     * set, since HomeViewModel does not retain an "all channels" snapshot in
     * memory.
     */
    val hiddenChannelsLiveTv: StateFlow<List<Channel>> =
        _uiState
            .map { it.provider?.id }
            .distinctUntilChanged()
            .flatMapLatest { providerId ->
                if (providerId == null) flowOf(emptyList())
                else preferencesRepository.getHiddenChannelIds(providerId)
                    .flatMapLatest { hiddenIds ->
                        if (hiddenIds.isEmpty()) flowOf(emptyList())
                        else channelRepository.getChannelsByIds(hiddenIds.toList())
                    }
                    .map { channels -> channels.sortedBy { it.name } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun hideChannel(channel: Channel) {
        val providerId = _uiState.value.provider?.id ?: return
        viewModelScope.launch {
            preferencesRepository.setChannelHidden(providerId, channel.id, true)
            onDismissDialog()
        }
    }

    fun unhideChannel(channel: Channel) {
        val providerId = _uiState.value.provider?.id ?: return
        viewModelScope.launch {
            preferencesRepository.setChannelHidden(providerId, channel.id, false)
        }
    }

    fun unhideAllChannels() {
        val providerId = _uiState.value.provider?.id ?: return
        viewModelScope.launch {
            preferencesRepository.setHiddenChannelIds(providerId, emptySet())
        }
    }

    fun setShowAllChannelsCategory(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setShowAllChannelsCategory(enabled) }
    }

    fun setShowFavoritesCategory(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setShowFavoritesCategory(enabled) }
    }

    fun setShowRecentChannelsCategory(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setShowRecentChannelsCategory(enabled) }
    }

    fun clearRecentChannels() {
        val providerId = _uiState.value.provider?.id ?: return
        viewModelScope.launch {
            playbackHistoryRepository.clearLiveHistoryForProvider(providerId)
        }
    }

    fun removeChannelFromRecent(channel: Channel) {
        val providerId = _uiState.value.provider?.id ?: return
        viewModelScope.launch {
            playbackHistoryRepository.removeFromHistory(channel.id, ContentType.LIVE, providerId)
        }
    }

    fun toggleCategoryPinned(category: Category) {
        if (_uiState.value.isCombinedLiveSource) return
        if (category.isVirtual || category.id == ChannelRepository.ALL_CHANNELS_ID) return
        val providerId = _uiState.value.provider?.id ?: return
        val shouldPin = category.id !in _uiState.value.pinnedCategoryIds
        viewModelScope.launch {
            preferencesRepository.setCategoryPinned(
                providerId = providerId,
                type = ContentType.LIVE,
                categoryId = category.id,
                pinned = shouldPin
            )
            dismissCategoryOptions()
            _uiState.update {
                it.copy(
                    userMessage = appContext.getString(
                        if (shouldPin) R.string.category_pinned else R.string.category_unpinned,
                        category.name
                    )
                )
            }
        }
    }

    fun enterChannelReorderMode(category: Category) {
        dismissCategoryOptions()
        viewModelScope.launch {
            val reorderChannels = loadReorderChannels(category)
            _uiState.update {
                it.copy(
                    isChannelReorderMode = true,
                    reorderCategory = category,
                    filteredChannels = reorderChannels
                )
            }
        }
    }

    fun exitChannelReorderMode() {
        // Discard any unsaved sorting by restoring from the original local snapshot
        _uiState.update { it.copy(
            isChannelReorderMode = false, 
            reorderCategory = null,
            filteredChannels = _localChannels.value
        ) }
    }

    fun moveChannelUp(channel: Channel) {
        val state = _uiState.value
        val list = state.filteredChannels.toMutableList()
        val idx = list.indexOf(channel)
        if (idx > 0) {
            list.removeAt(idx)
            list.add(idx - 1, channel)
            _uiState.update { it.copy(filteredChannels = list) }
        }
    }

    fun moveChannelDown(channel: Channel) {
        val state = _uiState.value
        val list = state.filteredChannels.toMutableList()
        val idx = list.indexOf(channel)
        if (idx >= 0 && idx < list.size - 1) {
            list.removeAt(idx)
            list.add(idx + 1, channel)
            _uiState.update { it.copy(filteredChannels = list) }
        }
    }

    fun saveChannelReorder() {
        val state = _uiState.value
        val category = state.reorderCategory ?: return
        if (category.id == VirtualCategoryIds.RECENT) return
        val currentList = state.filteredChannels

        // Exit reorder mode immediately for responsive UI
        _uiState.update { it.copy(isChannelReorderMode = false, reorderCategory = null) }
        
        // Optimistically update local channels to match the new order before DB flow catches up
        _localChannels.value = currentList

        viewModelScope.launch {
            try {
                // Map the virtual category ID back to the Favorite Group ID
                val groupId = if (category.id == VirtualCategoryIds.FAVORITES) null else -category.id

                val favoritesFlow = if (groupId == null) {
                    observeLiveFavorites(currentLiveProviderIds())
                } else {
                    favoriteRepository.getFavoritesByGroup(groupId)
                }

                // Get current favorites
                val favorites = favoritesFlow.first()
                val favoriteMap = favorites.associateBy { it.contentId }

                // Map the sorted Channel list back to Favorite entities with new positions
                val reorderedFavorites = currentList.mapNotNull { ch ->
                    favoriteMap[ch.id]
                }

                // Persist the new order in DB
                favoriteRepository.reorderFavorites(reorderedFavorites)

                _uiState.update { it.copy(userMessage = "Channel order saved") }
            } catch (e: Exception) {
                _uiState.update { it.copy(userMessage = "Failed to save channel order") }
            }
        }
    }

    private fun loadChannelsByOrderedIds(ids: List<Long>): Flow<List<Channel>> {
        if (ids.isEmpty()) return flowOf(emptyList())

        return channelRepository.getChannelsByIds(ids).map { unsorted ->
            unsorted.orderedByRequestedRawIds(ids)
        }
    }

    private suspend fun loadReorderChannels(category: Category): List<Channel> {
        if (!category.isVirtual || category.id == VirtualCategoryIds.RECENT) return emptyList()

        val groupId = if (category.id == VirtualCategoryIds.FAVORITES) null else -category.id
        val favorites = if (groupId == null) {
            observeLiveFavorites(currentLiveProviderIds()).first()
        } else {
            favoriteRepository.getFavoritesByGroup(groupId).first()
        }

        val orderedIds = favorites
            .sortedBy { it.position }
            .map { it.contentId }

        return loadChannelsByOrderedIds(orderedIds).first()
    }

    private fun List<PlaybackHistory>.toRecentLiveContentIds(): List<Long> =
        asSequence()
            .filter { it.contentType == ContentType.LIVE }
            .sortedByDescending { it.lastWatchedAt }
            .distinctBy { it.contentId }
            .map { it.contentId }
            .toList()

    private fun currentCombinedProviderIds(): List<Long> =
        _uiState.value.currentCombinedProfileMembers
            .filter { it.enabled }
            .map { it.providerId }

    private fun currentLiveProviderIds(state: HomeUiState = _uiState.value): List<Long> = when (val source = state.activeLiveSource) {
        is ActiveLiveSource.ProviderSource -> listOf(source.providerId)
        is ActiveLiveSource.CombinedM3uSource -> state.currentCombinedProfileMembers
            .filter { it.enabled }
            .map { it.providerId }
        null -> state.provider?.id?.let(::listOf).orEmpty()
    }

    private fun observeCurrentLiveFavorites(): Flow<List<Favorite>> =
        _uiState.map(::currentLiveProviderIds)
            .distinctUntilChanged()
            .flatMapLatest(::observeLiveFavorites)

    private fun observeLiveFavorites(providerIds: List<Long>): Flow<List<Favorite>> = when (providerIds.size) {
        0 -> flowOf(emptyList())
        1 -> favoriteRepository.getFavorites(providerIds.first(), ContentType.LIVE)
        else -> favoriteRepository.getFavorites(providerIds, ContentType.LIVE)
    }

    private fun observeRecentLiveIds(providerIds: List<Long>, limit: Int): Flow<List<Long>> = when (providerIds.size) {
        0 -> flowOf(emptyList())
        1 -> playbackHistoryRepository.getRecentlyWatchedByProvider(providerIds.first(), limit)
            .map { history -> history.toRecentLiveContentIds().take(limit) }
        else -> combine(providerIds.map { providerId ->
            playbackHistoryRepository.getRecentlyWatchedByProvider(providerId, limit)
        }) { histories ->
            histories.toList()
                .flatMap { it }
                .asSequence()
                .filter { it.contentType == ContentType.LIVE }
                .sortedByDescending { it.lastWatchedAt }
                .distinctBy { it.providerId to it.contentId }
                .map { it.contentId }
                .take(limit)
                .toList()
        }
    }

    private fun resolveLiveWriteProviderId(preferredProviderId: Long? = null): Long? =
        preferredProviderId
            ?: _uiState.value.provider?.id
            ?: _uiState.value.selectedCombinedSourceProviderId
            ?: currentLiveProviderIds().singleOrNull()

    override fun onCleared() {
        epgJob?.cancel()
        loadChannelsJob?.cancel()
        categoriesJob?.cancel()
        recentChannelsJob?.cancel()
        clearPreview()
        super.onCleared()
    }
}

data class HomeUiState(
    val provider: Provider? = null,
    val activeLiveSource: ActiveLiveSource? = null,
    val activeLiveSourceTitle: String = "",
    val isCombinedLiveSource: Boolean = false,
    val liveSourceOptions: List<ActiveLiveSourceOption> = emptyList(),
    val currentCombinedProfileMembers: List<CombinedM3uProfileMember> = emptyList(),
    val selectedCombinedSourceProviderId: Long? = null,
    val showLiveSourceSwitcher: Boolean = false,
    val allProviders: List<Provider> = emptyList(),
    val categories: List<Category> = emptyList(),
    val recentChannels: List<Channel> = emptyList(),
    val lastVisitedCategory: Category? = null,
    val selectedCategory: Category? = null,
    val filteredChannels: List<Channel> = emptyList(),
    val hasChannels: Boolean = false,
    val hasMoreChannels: Boolean = false,
    val isLoading: Boolean = true,
    val isCategoriesLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val categorySearchQuery: String = "",
    val savedCategoryFilters: List<String> = emptyList(),
    val activeCategoryFilter: String? = null,
    val liveTvQuickFilterVisibilityMode: LiveTvQuickFilterVisibilityMode = LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE,
    val channelSearchQuery: String = "",
    val showDialog: Boolean = false,
    val selectedChannelForDialog: Channel? = null,
    val dialogGroupMemberships: List<Long> = emptyList(),
    val showHiddenChannelsDialog: Boolean = false,
    val userMessage: String? = null,
    val showRenameGroupDialog: Boolean = false,
    val groupToRename: Category? = null,
    val renameGroupError: String? = null,
    val showDeleteGroupDialog: Boolean = false,
    val groupToDelete: Category? = null,
    val parentalControlLevel: Int = 0,
    val unlockedCategoryIds: Set<Long> = emptySet(),
    val pinnedCategoryIds: Set<Long> = emptySet(),
    val hiddenLiveCategories: List<Category> = emptyList(),
    val shouldAutoFocusFirstChannelOnEntry: Boolean = false,
    val selectedCategoryForOptions: Category? = null,
    val isChannelReorderMode: Boolean = false,
    val reorderCategory: Category? = null,
    val liveTvChannelMode: LiveTvChannelMode = LiveTvChannelMode.PRO,
    val previewChannelId: Long? = null,
    val previewPlayerEngine: PlayerEngine? = null,
    val isPreviewLoading: Boolean = false,
    val previewErrorMessage: String? = null,
    val errorMessage: String? = null,
    val multiviewChannelCount: Int = 0,
    val multiviewSlotCapacity: Int = MultiViewManager.MAX_SLOTS
)

private data class CategorySelectionContext(
    val categories: List<Category>,
    val defaultCategoryId: Long?,
    val lastVisitedCategoryId: Long?,
    val pinnedCategoryIds: Set<Long>,
    val hiddenLiveCategories: List<Category>
)
