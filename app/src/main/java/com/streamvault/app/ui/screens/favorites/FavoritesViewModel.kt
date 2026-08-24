package com.streamvault.app.ui.screens.favorites

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.R
import com.streamvault.domain.playback.associateByAnyRawId
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.model.VirtualGroup
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.usecase.ContinueWatchingResult
import com.streamvault.domain.usecase.ContinueWatchingScope
import com.streamvault.domain.usecase.GetContinueWatching
import com.streamvault.data.preferences.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoriteUiModel(
    val favorite: Favorite,
    val title: String,
    val subtitle: String? = null,
    val lastWatchedAt: Long = 0L,
    val streamUrl: String = "",
    val providerId: Long = -1L,
    val categoryId: Long? = null,
    val epgChannelId: String? = null,
    val launchCategoryId: Long? = null,
    val launchIsVirtual: Boolean = false
)

data class FavoriteSectionUiModel(
    val key: String,
    val title: String,
    val subtitle: String,
    val items: List<FavoriteUiModel>,
    val reorderable: Boolean
)

enum class SavedLibraryFilter {
    ALL,
    LIVE,
    MOVIE,
    SERIES
}

enum class SavedLibrarySort {
    DEFAULT,
    RECENT,
    TITLE
}

enum class SavedLibraryPreset {
    ALL_SAVED,
    HOME_SHELF,
    WATCH_NEXT,
    LIVE_RECALL,
    MOVIES,
    SERIES,
    CUSTOM_GROUPS
}

enum class SavedLibraryProviderScope {
    CURRENT_PROVIDER,
    ALL_PROVIDERS
}

data class SavedLibrarySummary(
    val totalItems: Int = 0,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val customGroupCount: Int = 0,
    val continueWatchingCount: Int = 0,
    val recentLiveCount: Int = 0
)

data class SavedLibraryPresetSummary(
    val allSavedCount: Int = 0,
    val watchNextCount: Int = 0,
    val liveRecallCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val customGroupCount: Int = 0
)

data class SavedHistoryUiModel(
    val history: PlaybackHistory,
    val title: String,
    val subtitle: String,
    val providerId: Long,
    val categoryId: Long? = null,
    val epgChannelId: String? = null,
    val launchIsVirtual: Boolean = false
)

data class SavedGroupManagementUiModel(
    val group: VirtualGroup,
    val itemCount: Int,
    val isPromotedOnHome: Boolean
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val favoriteRepository: FavoriteRepository,
    private val channelRepository: ChannelRepository,
    private val movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val providerRepository: ProviderRepository,
    private val preferencesRepository: PreferencesRepository,
    private val getContinueWatching: GetContinueWatching
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init {
        val providerIdsFlow = providerRepository.getProviders()
            .map { providers -> providers.map { it.id } }

        val globalSectionFlow = providerIdsFlow.flatMapLatest { providerIds ->
            observeFavoriteItems(favoriteRepository.getFavorites(providerIds))
        }
            .map { items ->
                FavoriteSectionUiModel(
                    key = GLOBAL_SECTION_KEY,
                    title = appContext.getString(R.string.favorites_all_favorites),
                    subtitle = appContext.getString(R.string.favorites_saved_items_format, items.size),
                    items = items,
                    reorderable = items.size > 1
                )
            }

        val allGroupsFlow = providerIdsFlow.flatMapLatest { providerIds ->
            combine(
                favoriteRepository.getGroups(providerIds, ContentType.LIVE),
                favoriteRepository.getGroups(providerIds, ContentType.MOVIE),
                favoriteRepository.getGroups(providerIds, ContentType.SERIES)
            ) { liveGroups, movieGroups, seriesGroups ->
                (liveGroups + movieGroups + seriesGroups)
                    .sortedWith(compareBy<VirtualGroup>({ it.contentType.ordinal }, { it.position }, { it.name.lowercase() }))
            }
        }

        val liveGroupManagementFlow = providerIdsFlow.flatMapLatest { providerIds ->
            combine(
                favoriteRepository.getGroups(providerIds, ContentType.LIVE),
                favoriteRepository.getGroupFavoriteCounts(providerIds, ContentType.LIVE),
                preferencesRepository.promotedLiveGroupIds
            ) { liveGroups, liveCounts, promotedLiveGroupIds ->
                liveGroups.map { group ->
                    SavedGroupManagementUiModel(
                        group = group,
                        itemCount = liveCounts[group.id] ?: 0,
                        isPromotedOnHome = group.id in promotedLiveGroupIds
                    )
                }
            }
        }

        val movieGroupManagementFlow = providerIdsFlow.flatMapLatest { providerIds ->
            combine(
                favoriteRepository.getGroups(providerIds, ContentType.MOVIE),
                favoriteRepository.getGroupFavoriteCounts(providerIds, ContentType.MOVIE)
            ) { movieGroups, movieCounts ->
                movieGroups.map { group ->
                    SavedGroupManagementUiModel(
                        group = group,
                        itemCount = movieCounts[group.id] ?: 0,
                        isPromotedOnHome = false
                    )
                }
            }
        }

        val seriesGroupManagementFlow = providerIdsFlow.flatMapLatest { providerIds ->
            combine(
                favoriteRepository.getGroups(providerIds, ContentType.SERIES),
                favoriteRepository.getGroupFavoriteCounts(providerIds, ContentType.SERIES)
            ) { seriesGroups, seriesCounts ->
                seriesGroups.map { group ->
                    SavedGroupManagementUiModel(
                        group = group,
                        itemCount = seriesCounts[group.id] ?: 0,
                        isPromotedOnHome = false
                    )
                }
            }
        }

        val groupManagementFlow = combine(
            liveGroupManagementFlow,
            movieGroupManagementFlow,
            seriesGroupManagementFlow
        ) { liveGroups, movieGroups, seriesGroups ->
            (liveGroups + movieGroups + seriesGroups)
                .sortedWith(
                    compareBy<SavedGroupManagementUiModel>(
                        { it.group.contentType.ordinal },
                        { it.group.position },
                        { it.group.name.lowercase() }
                    )
                )
        }

        val activeProviderFlow = providerRepository.getActiveProvider()
        val selectedProviderScopeFlow = uiState
            .map { state -> state.selectedProviderScope }
            .distinctUntilChanged()
        val historyProviderIdsFlow: Flow<Set<Long>> = combine(
            providerIdsFlow,
            activeProviderFlow,
            selectedProviderScopeFlow
        ) { providerIds: List<Long>, activeProvider: Provider?, selectedProviderScope: SavedLibraryProviderScope ->
            when (selectedProviderScope) {
                SavedLibraryProviderScope.CURRENT_PROVIDER -> activeProvider?.id?.let(::setOf).orEmpty()
                SavedLibraryProviderScope.ALL_PROVIDERS -> providerIds.toSet()
            }
        }

        val groupedSectionsFlow = allGroupsFlow.flatMapLatest { groups ->
            if (groups.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    groups.map { group ->
                        observeFavoriteItems(favoriteRepository.getFavoritesByGroup(group.id))
                            .map { items ->
                                FavoriteSectionUiModel(
                                    key = sectionKeyForGroup(group.id),
                                    title = group.name,
                                    subtitle = "${group.contentType.displayName} group",
                                    items = items,
                                    reorderable = items.size > 1
                                )
                            }
                    }
                ) { sections ->
                    sections.toList().filter { it.items.isNotEmpty() }
                }
            }
        }

        val savedHistoryFlow: Flow<SavedHistorySnapshot> = historyProviderIdsFlow.flatMapLatest { providerIds: Set<Long> ->
            observeSavedHistory(providerIds)
        }

        viewModelScope.launch {
            combine(
                combine(globalSectionFlow, groupedSectionsFlow) { globalSection, groupedSections ->
                    buildList {
                        if (globalSection.items.isNotEmpty()) {
                            add(globalSection)
                        }
                        addAll(groupedSections)
                    }
                },
                savedHistoryFlow,
                groupManagementFlow,
                activeProviderFlow
            ) { sections, historySnapshot, managedGroups, activeProvider ->
                SavedLibrarySnapshot(
                    sections = sections,
                    history = historySnapshot,
                    managedGroups = managedGroups,
                    activeProviderId = activeProvider?.id,
                    activeProviderName = activeProvider?.name
                )
            }.collect { snapshot ->
                val sections = snapshot.sections
                val historySnapshot = snapshot.history
                val managedGroups = snapshot.managedGroups
                val globalItems = sections
                    .firstOrNull { it.key == GLOBAL_SECTION_KEY }
                    ?.items
                    .orEmpty()
                _uiState.update { state ->
                    state.copy(
                        sections = state.applyReorderPreview(sections),
                        continueWatching = historySnapshot.continueWatching,
                        recentLive = historySnapshot.recentLive,
                        managedGroups = managedGroups,
                        summary = SavedLibrarySummary(
                            totalItems = globalItems.size,
                            liveCount = globalItems.count { it.favorite.contentType == ContentType.LIVE },
                            movieCount = globalItems.count { it.favorite.contentType == ContentType.MOVIE },
                            seriesCount = globalItems.count { it.favorite.contentType == ContentType.SERIES },
                            customGroupCount = sections.count { it.key != GLOBAL_SECTION_KEY },
                            continueWatchingCount = historySnapshot.continueWatching.size,
                            recentLiveCount = historySnapshot.recentLive.size
                        ),
                        presetSummary = SavedLibraryPresetSummary(
                            allSavedCount = globalItems.size,
                            watchNextCount = historySnapshot.continueWatching.size,
                            liveRecallCount = historySnapshot.recentLive.size,
                            movieCount = globalItems.count { it.favorite.contentType == ContentType.MOVIE },
                            seriesCount = globalItems.count { it.favorite.contentType == ContentType.SERIES },
                            customGroupCount = sections.count { it.key != GLOBAL_SECTION_KEY }
                        ),
                        activeProviderId = snapshot.activeProviderId,
                        activeProviderName = snapshot.activeProviderName,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun enterReorderMode(sectionKey: String, item: FavoriteUiModel) {
        _uiState.update {
            it.copy(
                isReorderMode = true,
                reorderSectionKey = sectionKey,
                reorderItem = item.favorite
            )
        }
    }

    fun exitReorderMode() {
        _uiState.update { it.copy(isReorderMode = false, reorderSectionKey = null, reorderItem = null) }
    }

    fun selectFilter(filter: SavedLibraryFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    fun selectSort(sort: SavedLibrarySort) {
        _uiState.update { it.copy(selectedSort = sort) }
    }

    fun selectPreset(preset: SavedLibraryPreset) {
        _uiState.update { state ->
            state.copy(
                selectedPreset = preset,
                selectedFilter = when (preset) {
                    SavedLibraryPreset.MOVIES -> SavedLibraryFilter.MOVIE
                    SavedLibraryPreset.SERIES -> SavedLibraryFilter.SERIES
                    SavedLibraryPreset.LIVE_RECALL -> SavedLibraryFilter.LIVE
                    else -> state.selectedFilter
                }
            )
        }
    }

    fun selectProviderScope(scope: SavedLibraryProviderScope) {
        _uiState.update { it.copy(selectedProviderScope = scope) }
    }

    fun dismissSavedDialog() {
        _uiState.update {
            it.copy(
                itemDialogItem = null,
                itemMoveTargets = emptyList(),
                managedGroupDialog = null,
                mergeSourceGroup = null,
                mergeTargetCandidates = emptyList()
            )
        }
    }

    fun showItemOptions(item: FavoriteUiModel) {
        viewModelScope.launch {
            val targetGroups = _uiState.value.managedGroups
                .filter { it.group.contentType == item.favorite.contentType }
                .filter { it.group.providerId == item.favorite.providerId }
                .filter { it.group.id != item.favorite.groupId }
            _uiState.update {
                it.copy(
                    itemDialogItem = item,
                    itemMoveTargets = targetGroups
                )
            }
        }
    }

    fun removeFromSavedContext(item: FavoriteUiModel) {
        viewModelScope.launch {
            favoriteRepository.removeFavorite(
                providerId = item.favorite.providerId,
                contentId = item.favorite.contentId,
                contentType = item.favorite.contentType,
                groupId = item.favorite.groupId
            )
            _uiState.update {
                it.copy(
                    itemDialogItem = null,
                    itemMoveTargets = emptyList(),
                    userMessage = "Removed ${item.title} from this saved view."
                )
            }
        }
    }

    fun moveItemToGroup(item: FavoriteUiModel, targetGroupId: Long) {
        viewModelScope.launch {
            when (val result = favoriteRepository.moveFavoriteToGroup(
                providerId = item.favorite.providerId,
                contentId = item.favorite.contentId,
                contentType = item.favorite.contentType,
                fromGroupId = item.favorite.groupId,
                targetGroupId = targetGroupId
            )) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        itemDialogItem = null,
                        itemMoveTargets = emptyList(),
                        userMessage = "Moved ${item.title} to the selected group."
                    )
                }

                is Result.Error -> _uiState.update {
                    it.copy(userMessage = "Unable to move ${item.title}: ${result.errorMessageOrNull() ?: "Unknown error"}")
                }

                Result.Loading -> Unit
            }
        }
    }

    fun showGroupOptions(group: SavedGroupManagementUiModel) {
        _uiState.update {
            it.copy(
                managedGroupDialog = group,
                mergeSourceGroup = null,
                mergeTargetCandidates = emptyList()
            )
        }
    }

    fun deleteManagedGroup(group: SavedGroupManagementUiModel) {
        viewModelScope.launch {
            favoriteRepository.deleteGroup(group.group.id)
            if (group.group.contentType == ContentType.LIVE) {
                val currentPromoted = preferencesRepository.promotedLiveGroupIds.first()
                preferencesRepository.setPromotedLiveGroupIds(currentPromoted - group.group.id)
            }
            _uiState.update {
                it.copy(
                    managedGroupDialog = null,
                    userMessage = "Deleted group ${group.group.name}."
                )
            }
        }
    }

    fun toggleHomePromotion(group: SavedGroupManagementUiModel) {
        if (group.group.contentType != ContentType.LIVE) return
        viewModelScope.launch {
            val currentPromoted = preferencesRepository.promotedLiveGroupIds.first()
            val updated = if (group.group.id in currentPromoted) {
                currentPromoted - group.group.id
            } else {
                currentPromoted + group.group.id
            }
            preferencesRepository.setPromotedLiveGroupIds(updated)
            _uiState.update {
                it.copy(
                    managedGroupDialog = null,
                    userMessage = if (group.group.id in currentPromoted) {
                        appContext.getString(R.string.favorites_removed_home_priority, group.group.name)
                    } else {
                        appContext.getString(R.string.favorites_pinned_home, group.group.name)
                    }
                )
            }
        }
    }

    fun startMergeGroup(group: SavedGroupManagementUiModel) {
        val candidates = _uiState.value.managedGroups
            .filter { it.group.contentType == group.group.contentType && it.group.id != group.group.id }
            .filter { it.group.providerId == group.group.providerId }
        _uiState.update {
            it.copy(
                mergeSourceGroup = group,
                mergeTargetCandidates = candidates
            )
        }
    }

    fun mergeGroupInto(targetGroupId: Long) {
        val source = _uiState.value.mergeSourceGroup ?: return
        viewModelScope.launch {
            when (val result = favoriteRepository.mergeGroupInto(source.group.id, targetGroupId)) {
                is Result.Success -> {
                    if (source.group.contentType == ContentType.LIVE) {
                        val currentPromoted = preferencesRepository.promotedLiveGroupIds.first()
                        preferencesRepository.setPromotedLiveGroupIds(currentPromoted - source.group.id)
                    }
                    _uiState.update {
                        it.copy(
                            managedGroupDialog = null,
                            mergeSourceGroup = null,
                            mergeTargetCandidates = emptyList(),
                            userMessage = appContext.getString(R.string.favorites_merged_group, source.group.name)
                        )
                    }
                }

                is Result.Error -> _uiState.update {
                    it.copy(userMessage = "Unable to merge ${source.group.name}: ${result.errorMessageOrNull() ?: "Unknown error"}")
                }

                Result.Loading -> Unit
            }
        }
    }

    fun userMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun moveItem(direction: Int) {
        val state = _uiState.value
        val reorderSectionKey = state.reorderSectionKey ?: return
        val reorderItem = state.reorderItem ?: return
        val updatedSections = state.sections.map { section ->
            if (section.key != reorderSectionKey) {
                section
            } else {
                val currentItems = section.items.toMutableList()
                val index = currentItems.indexOfFirst { it.favorite.id == reorderItem.id }
                val newIndex = index + direction
                if (index == -1 || newIndex !in currentItems.indices) {
                    section
                } else {
                    java.util.Collections.swap(currentItems, index, newIndex)
                    section.copy(items = currentItems)
                }
            }
        }

        _uiState.update { it.copy(sections = updatedSections) }
    }

    fun saveReorder() {
        val state = _uiState.value
        val sectionKey = state.reorderSectionKey ?: return
        val section = state.sections.firstOrNull { it.key == sectionKey } ?: return

        viewModelScope.launch {
            when (val result = favoriteRepository.reorderFavorites(section.items.map { it.favorite })) {
                is Result.Success -> exitReorderMode()
                is Result.Error -> _uiState.update {
                    it.copy(userMessage = "Unable to save reorder: ${result.errorMessageOrNull() ?: "Unknown error"}")
                }
                Result.Loading -> Unit
            }
        }
    }

    private fun observeFavoriteItems(favoritesFlow: Flow<List<Favorite>>): Flow<List<FavoriteUiModel>> {
        return favoritesFlow.flatMapLatest { favorites ->
            val orderedFavorites = favorites.sortedBy { it.position }
            val channelIds = orderedFavorites.filter { it.contentType == ContentType.LIVE }.map { it.contentId }
            val movieIds = orderedFavorites.filter { it.contentType == ContentType.MOVIE }.map { it.contentId }
            val seriesIds = orderedFavorites.filter { it.contentType == ContentType.SERIES }.map { it.contentId }

            combine(
                playbackHistoryRepository.getRecentlyWatched(limit = 500),
                if (channelIds.isEmpty()) flowOf(emptyList()) else channelRepository.getChannelsByIds(channelIds),
                if (movieIds.isEmpty()) flowOf(emptyList()) else movieRepository.getMoviesByIds(movieIds),
                if (seriesIds.isEmpty()) flowOf(emptyList()) else seriesRepository.getSeriesByIds(seriesIds)
            ) { history, channels, movies, series ->
                val historyMap = history.associateBy { Triple(it.contentType, it.contentId, it.providerId) }
                val channelsById = channels.associateByAnyRawId()
                val moviesById = movies.associateBy { it.id }
                val seriesById = series.associateBy { it.id }

                orderedFavorites.mapIndexedNotNull { index, favorite ->
                    when (favorite.contentType) {
                        ContentType.LIVE -> channelsById[favorite.contentId]?.let { channel ->
                            FavoriteUiModel(
                                favorite = favorite,
                                title = channel.name,
                                subtitle = "Channel ${index + 1}",
                                lastWatchedAt = historyMap[Triple(favorite.contentType, favorite.contentId, channel.providerId)]?.lastWatchedAt ?: 0L,
                                streamUrl = channel.streamUrl,
                                providerId = channel.providerId,
                                categoryId = channel.categoryId,
                                epgChannelId = channel.epgChannelId,
                                launchCategoryId = favorite.groupId?.let { -it } ?: VirtualCategoryIds.FAVORITES,
                                launchIsVirtual = true
                            )
                        }

                        ContentType.MOVIE -> moviesById[favorite.contentId]?.let { movie ->
                            FavoriteUiModel(
                                favorite = favorite,
                                title = movie.name,
                                subtitle = "Movie",
                                lastWatchedAt = historyMap[Triple(favorite.contentType, favorite.contentId, movie.providerId)]?.lastWatchedAt ?: 0L,
                                streamUrl = movie.streamUrl,
                                providerId = movie.providerId,
                                categoryId = movie.categoryId
                            )
                        }

                        ContentType.SERIES -> seriesById[favorite.contentId]?.let { seriesItem ->
                            FavoriteUiModel(
                                favorite = favorite,
                                title = seriesItem.name,
                                subtitle = "Series",
                                lastWatchedAt = historyMap[Triple(favorite.contentType, favorite.contentId, seriesItem.providerId)]?.lastWatchedAt ?: 0L,
                                streamUrl = "",
                                providerId = seriesItem.providerId
                            )
                        }

                        ContentType.VOD,
                        ContentType.SERIES_EPISODE -> null
                    }
                }
            }
        }
    }

    private fun observeSavedHistory(providerIds: Set<Long>): Flow<SavedHistorySnapshot> {
        if (providerIds.isEmpty()) {
            return flowOf(SavedHistorySnapshot())
        }

        val recentHistoryLimit = 18
        val historyFlow: Flow<List<PlaybackHistory>> = if (providerIds.size == 1) {
            playbackHistoryRepository.getRecentlyWatchedByProvider(providerIds.first(), limit = recentHistoryLimit)
        } else {
            playbackHistoryRepository.getRecentlyWatched(limit = recentHistoryLimit)
                .map { history -> history.filter { entry -> entry.providerId in providerIds } }
        }

        return combine(
            getContinueWatching(
                providerIds,
                limit = 8,
                scope = ContinueWatchingScope.ALL_VOD,
                requireResumePosition = true
            ),
            historyFlow
        ) { continueWatchingResult, history ->
            val continueWatching = when (continueWatchingResult) {
                is ContinueWatchingResult.Items -> continueWatchingResult.items
                ContinueWatchingResult.Degraded -> emptyList()
            }
            continueWatching to history
        }.flatMapLatest { (continueWatching, history) ->
            val recentLiveHistory = history
                .asSequence()
                .filter { it.contentType == ContentType.LIVE }
                .distinctBy { it.providerId to it.contentId }
                .take(10)
                .toList()

            val recentLiveIds = recentLiveHistory.map { it.contentId }.distinct()
            val recentLiveFlow: Flow<List<Channel>> = if (recentLiveIds.isEmpty()) {
                flowOf(emptyList<Channel>())
            } else {
                channelRepository.getChannelsByIds(recentLiveIds)
            }

            recentLiveFlow.map { liveChannels ->
                val liveByProviderAndId = liveChannels.associateByProviderAndAnyRawId()
                SavedHistorySnapshot(
                    continueWatching = continueWatching.map { entry ->
                        SavedHistoryUiModel(
                            history = entry,
                            title = entry.title,
                            subtitle = when (entry.contentType) {
                                ContentType.MOVIE,
                                ContentType.VOD -> appContext.getString(R.string.nav_vod)
                                ContentType.SERIES -> appContext.getString(R.string.favorites_content_type_series)
                                ContentType.SERIES_EPISODE -> buildString {
                                    val s = entry.seasonNumber
                                    val e = entry.episodeNumber
                                    if (s != null && e != null) {
                                        append(appContext.getString(R.string.favorites_content_type_episode_format, s, e))
                                    } else {
                                        append(appContext.getString(R.string.favorites_content_type_episode))
                                        entry.seasonNumber?.let { append(" S$it") }
                                        entry.episodeNumber?.let { append("E$it") }
                                    }
                                }
                                ContentType.LIVE -> appContext.getString(R.string.favorites_content_type_live)
                            },
                            providerId = entry.providerId
                        )
                    },
                    recentLive = recentLiveHistory.mapIndexedNotNull { index, entry ->
                        val channel = liveByProviderAndId[entry.providerId to entry.contentId] ?: return@mapIndexedNotNull null
                        SavedHistoryUiModel(
                            history = entry,
                            title = channel.name,
                            subtitle = "Channel ${index + 1}",
                            providerId = channel.providerId,
                            categoryId = channel.categoryId,
                            epgChannelId = channel.epgChannelId,
                            launchIsVirtual = false
                        )
                    }
                )
            }
        }
    }

    private fun FavoritesUiState.applyReorderPreview(
        incomingSections: List<FavoriteSectionUiModel>
    ): List<FavoriteSectionUiModel> {
        if (!isReorderMode || reorderSectionKey == null) {
            return incomingSections
        }

        val previewSection = sections.firstOrNull { it.key == reorderSectionKey } ?: return incomingSections
        return incomingSections.map { section ->
            if (section.key == reorderSectionKey) previewSection else section
        }
    }

    private val ContentType.displayName: String
        get() = when (this) {
            ContentType.LIVE -> "Live"
            ContentType.MOVIE -> "Movie"
            ContentType.VOD -> "VOD"
            ContentType.SERIES -> "Series"
            ContentType.SERIES_EPISODE -> "Episode"
        }

    companion object {
        private const val GLOBAL_SECTION_KEY = "global"

        private fun sectionKeyForGroup(groupId: Long): String = "group:$groupId"

        private fun List<Channel>.associateByProviderAndAnyRawId(): Map<Pair<Long, Long>, Channel> = buildMap {
            this@associateByProviderAndAnyRawId.forEach { channel ->
                channel.allVariantRawIds().forEach { rawId ->
                    putIfAbsent(channel.providerId to rawId, channel)
                }
            }
        }

    }
}

data class FavoritesUiState(
    val sections: List<FavoriteSectionUiModel> = emptyList(),
    val continueWatching: List<SavedHistoryUiModel> = emptyList(),
    val recentLive: List<SavedHistoryUiModel> = emptyList(),
    val managedGroups: List<SavedGroupManagementUiModel> = emptyList(),
    val summary: SavedLibrarySummary = SavedLibrarySummary(),
    val presetSummary: SavedLibraryPresetSummary = SavedLibraryPresetSummary(),
    val activeProviderId: Long? = null,
    val activeProviderName: String? = null,
    val selectedProviderScope: SavedLibraryProviderScope = SavedLibraryProviderScope.CURRENT_PROVIDER,
    val selectedPreset: SavedLibraryPreset = SavedLibraryPreset.ALL_SAVED,
    val selectedFilter: SavedLibraryFilter = SavedLibraryFilter.ALL,
    val selectedSort: SavedLibrarySort = SavedLibrarySort.DEFAULT,
    val itemDialogItem: FavoriteUiModel? = null,
    val itemMoveTargets: List<SavedGroupManagementUiModel> = emptyList(),
    val managedGroupDialog: SavedGroupManagementUiModel? = null,
    val mergeSourceGroup: SavedGroupManagementUiModel? = null,
    val mergeTargetCandidates: List<SavedGroupManagementUiModel> = emptyList(),
    val userMessage: String? = null,
    val isLoading: Boolean = true,
    val isReorderMode: Boolean = false,
    val reorderSectionKey: String? = null,
    val reorderItem: Favorite? = null
)

private data class SavedHistorySnapshot(
    val continueWatching: List<SavedHistoryUiModel> = emptyList(),
    val recentLive: List<SavedHistoryUiModel> = emptyList()
)

private data class SavedLibrarySnapshot(
    val sections: List<FavoriteSectionUiModel>,
    val history: SavedHistorySnapshot,
    val managedGroups: List<SavedGroupManagementUiModel>,
    val activeProviderId: Long?,
    val activeProviderName: String?
)
