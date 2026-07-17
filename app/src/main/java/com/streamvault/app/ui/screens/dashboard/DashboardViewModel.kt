package com.streamvault.app.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.ui.model.orderedByRequestedRawIds
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.DatabaseMaintenanceManager
import com.streamvault.data.sync.SyncManager
import com.streamvault.app.update.AppUpdateActionState
import com.streamvault.app.update.AppUpdateInstaller
import com.streamvault.app.update.isRemoteVersionNewer
import com.streamvault.app.update.latestAppUpdateAction
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.usecase.ContinueWatchingResult
import com.streamvault.domain.usecase.ContinueWatchingScope
import com.streamvault.domain.usecase.GetContinueWatching
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.RecordingStatus
import android.content.Context
import com.streamvault.app.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.Year
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import com.streamvault.domain.util.AdultContentVisibilityPolicy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val favoriteRepository: FavoriteRepository,
    private val channelRepository: ChannelRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository,
    private val preferencesRepository: PreferencesRepository,
    private val getContinueWatching: GetContinueWatching,
    private val getCustomCategories: GetCustomCategories,
    private val syncManager: SyncManager,
    private val appUpdateInstaller: AppUpdateInstaller,
    private val recordingManager: RecordingManager,
    private val databaseMaintenanceManager: DatabaseMaintenanceManager
) : ViewModel() {
    private companion object {
        const val FAVORITE_CHANNEL_LIMIT = 12
        const val RECENT_CHANNEL_LIMIT = 12
        const val CONTINUE_WATCHING_LIMIT = 12
        const val MOVIE_SHELF_LIMIT = 12
        const val SERIES_SHELF_LIMIT = 12
        const val HOME_SHORTCUT_LIMIT = 4
    }

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _recordingChannelIds = MutableStateFlow<Set<Long>>(emptySet())
    val recordingChannelIds: StateFlow<Set<Long>> = _recordingChannelIds.asStateFlow()

    private val _scheduledChannelIds = MutableStateFlow<Set<Long>>(emptySet())
    val scheduledChannelIds: StateFlow<Set<Long>> = _scheduledChannelIds.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { databaseMaintenanceManager.runDailyMaintenance() }
            } catch (_: Exception) { }
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
        viewModelScope.launch {
            combine(
                combinedM3uRepository.getActiveLiveSource(),
                providerRepository.getActiveProvider()
            ) { activeSource, activeProvider ->
                Pair(activeSource ?: activeProvider?.id?.let { ActiveLiveSource.ProviderSource(it) }, activeProvider)
            }
                .distinctUntilChanged { old, new ->
                    old.first == new.first && old.second?.id == new.second?.id
                }
                .flatMapLatest { (activeSource, activeProvider) ->
                    flow {
                        if (activeSource == null && activeProvider == null) {
                            emit(DashboardUiState(isLoading = false))
                            return@flow
                        }

                        when (activeSource) {
                            is ActiveLiveSource.ProviderSource -> {
                                val provider = activeProvider?.takeIf { it.id == activeSource.providerId }
                                    ?: providerRepository.getProvider(activeSource.providerId)
                                    ?: run {
                                        emit(DashboardUiState(isLoading = false))
                                        return@flow
                                    }
                                emitAll(observeDashboard(provider, listOf(provider.id), combinedProfileId = null))
                            }

                            is ActiveLiveSource.CombinedM3uSource -> {
                                val liveProviderIds = combinedM3uRepository.getProfile(activeSource.profileId)
                                    ?.members
                                    .orEmpty()
                                    .filter { it.enabled }
                                    .map { it.providerId }
                                    .distinct()
                                val provider = activeProvider?.takeIf { it.id in liveProviderIds }
                                    ?: liveProviderIds.firstOrNull()?.let { providerRepository.getProvider(it) }
                                    ?: run {
                                        emit(DashboardUiState(isLoading = false, currentCombinedProfileId = activeSource.profileId))
                                        return@flow
                                    }
                                emitAll(observeDashboard(provider, liveProviderIds, combinedProfileId = activeSource.profileId))
                            }

                            null -> emit(DashboardUiState(isLoading = false))
                        }
                    }
                }
                .collect { state ->
                    _uiState.value = state
                }
        }
    }

    private fun observeDashboard(
        provider: Provider,
        liveProviderIds: List<Long>,
        combinedProfileId: Long?
    ): Flow<DashboardUiState> {
        val movieShelf = combine(
            movieRepository.getFreshPreview(provider.id, MOVIE_SHELF_LIMIT),
            preferencesRepository.parentalControlLevel
        ) { movies, level ->
            movies
                .filter { !shouldHideVodFromHome(it, level) }
                .take(MOVIE_SHELF_LIMIT)
        }
        val topRatedMovieShelf = combine(
            movieRepository.getTopRatedPreview(provider.id, MOVIE_SHELF_LIMIT),
            preferencesRepository.parentalControlLevel
        ) { movies, level ->
            movies
                .filter { !shouldHideVodFromHome(it, level) }
                .take(MOVIE_SHELF_LIMIT)
        }
        val recommendedMovieShelf = combine(
            movieRepository.getRecommendations(provider.id, MOVIE_SHELF_LIMIT),
            preferencesRepository.parentalControlLevel
        ) { movies, level ->
            movies
                .filter { !shouldHideVodFromHome(it, level) }
                .take(MOVIE_SHELF_LIMIT)
        }
        val seriesShelf = combine(
            seriesRepository.getFreshPreview(provider.id, SERIES_SHELF_LIMIT),
            preferencesRepository.parentalControlLevel
        ) { series, level ->
            series
                .filter { !shouldHideVodFromHome(it, level) }
                .take(SERIES_SHELF_LIMIT)
        }
        val baseContentShelves = combine(
            observeFavoriteChannels(liveProviderIds).onStart { emit(emptyList()) },
            observeRecentChannels(liveProviderIds).onStart { emit(emptyList()) },
            observeContinueWatching(liveProviderIds.toSet()).onStart { emit(ContinueWatchingShelf()) },
            movieShelf.onStart { emit(emptyList()) },
            seriesShelf.onStart { emit(emptyList()) }
        ) { favoriteChannels, recentChannels, continueWatchingShelf, recentMovies, recentSeries ->
            DashboardContentShelves(
                favoriteChannels = favoriteChannels,
                recentChannels = recentChannels,
                continueWatching = continueWatchingShelf.items,
                continueWatchingSeries = continueWatchingShelf.series,
                continueWatchingDegraded = continueWatchingShelf.isDegraded,
                recentMovies = recentMovies,
                recentSeries = recentSeries
            )
        }
        val contentShelvesWithFavorites = combine(
            baseContentShelves,
            observeFavoriteMovies(liveProviderIds).onStart { emit(emptyList()) },
            observeFavoriteSeries(liveProviderIds).onStart { emit(emptyList()) },
            observeContinueWatchingByScope(liveProviderIds.toSet(), ContinueWatchingScope.MOVIES).onStart { emit(emptyList()) },
            observeContinueWatchingByScope(liveProviderIds.toSet(), ContinueWatchingScope.SERIES).onStart { emit(emptyList()) }
        ) { shelves, favoriteMovies, favoriteSeries, continueWatchingMovies, continueWatchingSeriesItems ->
            shelves.copy(
                favoriteMovies = favoriteMovies,
                favoriteSeries = favoriteSeries,
                continueWatchingMovies = continueWatchingMovies,
                continueWatchingSeriesItems = continueWatchingSeriesItems
            )
        }
        val contentShelves = contentShelvesWithFavorites
            .combine(topRatedMovieShelf.onStart { emit(emptyList()) }) { shelves, topRatedMovies ->
                shelves.copy(topRatedMovies = topRatedMovies)
            }
            .combine(recommendedMovieShelf.onStart { emit(emptyList()) }) { shelves, recommendedMovies ->
            shelves.copy(
                recommendedMovies = recommendedMovies
            )
        }

        val baseSnapshot = combine(
            contentShelves,
            buildLiveContext(
                providerIds = liveProviderIds,
                lastVisitedProviderId = provider.id.takeIf { combinedProfileId == null }
            ).onStart {
                emit(DashboardLiveContext(lastVisitedCategory = null, shortcuts = emptyList()))
            },
            observeLiveChannelCount(liveProviderIds).onStart { emit(0) },
            movieRepository.getLibraryCount(provider.id).onStart { emit(0) },
            seriesRepository.getLibraryCount(provider.id).onStart { emit(0) }
        ) { shelves, liveContext, liveChannelCount, movieCount, seriesCount ->
            DashboardSnapshot(
                shelves = shelves,
                liveContext = liveContext,
                liveChannelCount = liveChannelCount,
                movieCount = movieCount,
                seriesCount = seriesCount,
                homeDashboardShelves = AppHomeDashboardShelf.defaultOrder,
                updateNotice = null
            )
        }

        return baseSnapshot.combine(
            preferencesRepository.appHomeDashboardShelves.onStart { emit(AppHomeDashboardShelf.defaultOrder) }
        ) { snapshot, homeDashboardShelves ->
            snapshot.copy(homeDashboardShelves = homeDashboardShelves)
        }.combine(observeUpdateNotice().onStart { emit(null) }) { snapshot, updateNotice ->
            snapshot.copy(updateNotice = updateNotice)
        }.combine(syncManager.syncStateForProvider(provider.id).onStart { emit(SyncState.Idle) }) { snapshot, syncState ->
            DashboardUiState(
                provider = provider,
                homeDashboardShelves = snapshot.homeDashboardShelves,
                favoriteChannels = snapshot.shelves.favoriteChannels,
                recentChannels = snapshot.shelves.recentChannels,
                continueWatching = snapshot.shelves.continueWatching,
                continueWatchingSeries = snapshot.shelves.continueWatchingSeries,
                continueWatchingMovies = snapshot.shelves.continueWatchingMovies,
                continueWatchingSeriesItems = snapshot.shelves.continueWatchingSeriesItems,
                continueWatchingDegraded = snapshot.shelves.continueWatchingDegraded,
                favoriteMovies = snapshot.shelves.favoriteMovies,
                favoriteSeries = snapshot.shelves.favoriteSeries,
                recentMovies = snapshot.shelves.recentMovies,
                recentSeries = snapshot.shelves.recentSeries,
                topRatedMovies = snapshot.shelves.topRatedMovies,
                recommendedMovies = snapshot.shelves.recommendedMovies,
                lastLiveCategory = snapshot.liveContext.lastVisitedCategory,
                liveShortcuts = snapshot.liveContext.shortcuts,
                currentCombinedProfileId = combinedProfileId,
                stats = DashboardStats(
                    liveChannelCount = snapshot.liveChannelCount,
                    favoriteChannelCount = snapshot.shelves.favoriteChannels.size,
                    recentChannelCount = snapshot.shelves.recentChannels.size,
                    continueWatchingCount = snapshot.shelves.continueWatching.size,
                    movieLibraryCount = snapshot.movieCount,
                    seriesLibraryCount = snapshot.seriesCount
                ),
                feature = buildFeature(
                    providerName = provider.name,
                    recentChannels = snapshot.shelves.recentChannels,
                    continueWatching = snapshot.shelves.continueWatching,
                    continueWatchingDegraded = snapshot.shelves.continueWatchingDegraded,
                    recentMovies = snapshot.shelves.recentMovies,
                    recentSeries = snapshot.shelves.recentSeries
                ),
                providerHealth = DashboardProviderHealth(
                    status = provider.status,
                    type = provider.type,
                    lastSyncedAt = provider.lastSyncedAt,
                    expirationDate = provider.expirationDate,
                    maxConnections = provider.maxConnections
                ),
                providerWarnings = when (syncState) {
                    is SyncState.Partial -> syncState.warnings
                    is SyncState.Error -> listOf(syncState.message)
                    else -> emptyList()
                },
                updateNotice = snapshot.updateNotice,
                isLoading = false
            )
        }
    }

    private fun observeFavoriteChannels(providerIds: List<Long>): Flow<List<Channel>> =
        observeLiveFavorites(providerIds)
            .map { favorites ->
                favorites
                    .filter { it.groupId == null }
                    .sortedBy { it.position }
                    .map { it.contentId }
                    .take(FAVORITE_CHANNEL_LIMIT)
            }
            .flatMapLatest(::loadChannelsByOrderedIds)

    private fun observeFavoriteMovies(providerIds: List<Long>): Flow<List<Movie>> =
        observeFavorites(providerIds, ContentType.MOVIE)
            .map { favorites ->
                favorites
                    .filter { it.groupId == null }
                    .sortedBy { it.position }
                    .map { it.contentId }
                    .take(MOVIE_SHELF_LIMIT)
            }
            .flatMapLatest(::loadMoviesByOrderedIds)
            .combine(preferencesRepository.parentalControlLevel) { movies, level ->
                movies
                    .filter { !shouldHideVodFromHome(it, level) }
                    .take(MOVIE_SHELF_LIMIT)
            }

    private fun observeFavoriteSeries(providerIds: List<Long>): Flow<List<Series>> =
        observeFavorites(providerIds, ContentType.SERIES)
            .map { favorites ->
                favorites
                    .filter { it.groupId == null }
                    .sortedBy { it.position }
                    .map { it.contentId }
                    .take(SERIES_SHELF_LIMIT)
            }
            .flatMapLatest(::loadSeriesByOrderedIds)
            .combine(preferencesRepository.parentalControlLevel) { series, level ->
                series
                    .filter { !shouldHideVodFromHome(it, level) }
                    .take(SERIES_SHELF_LIMIT)
            }

    private fun observeRecentChannels(providerIds: List<Long>): Flow<List<Channel>> =
        combine(
            preferencesRepository.showRecentChannelsCategory.flatMapLatest { show ->
                if (!show) flowOf(emptyList())
                else observeRecentLiveIds(providerIds, RECENT_CHANNEL_LIMIT)
                    .flatMapLatest(::loadChannelsByOrderedIds)
            },
            preferencesRepository.parentalControlLevel
        ) { channels, level ->
            AdultContentVisibilityPolicy.filterForAggregatedSurface(
                channels, level
            ) { isAdult || isUserProtected }
        }

    private fun observeContinueWatching(providerIds: Set<Long>): Flow<ContinueWatchingShelf> =
        getContinueWatching(
            providerIds = providerIds,
            limit = CONTINUE_WATCHING_LIMIT,
            scope = ContinueWatchingScope.ALL_VOD
        ).flatMapLatest { result ->
            when (result) {
                is ContinueWatchingResult.Items -> {
                    val seriesIds = result.items
                        .asSequence()
                        .filter { history ->
                            history.contentType == ContentType.SERIES || history.contentType == ContentType.SERIES_EPISODE
                        }
                        .map { history -> history.seriesId ?: history.contentId }
                        .distinct()
                        .toList()
                    if (seriesIds.isEmpty()) {
                        flowOf(ContinueWatchingShelf(items = result.items))
                    } else {
                        seriesRepository.getSeriesByIds(seriesIds).map { series ->
                            ContinueWatchingShelf(
                                items = result.items,
                                series = series.orderedByRequestedSeriesIds(seriesIds)
                            )
                        }
                    }
                }
                ContinueWatchingResult.Degraded -> flowOf(ContinueWatchingShelf(isDegraded = true))
            }
        }

    private fun observeContinueWatchingByScope(
        providerIds: Set<Long>,
        scope: ContinueWatchingScope
    ): Flow<List<PlaybackHistory>> =
        getContinueWatching(
            providerIds = providerIds,
            limit = CONTINUE_WATCHING_LIMIT,
            scope = scope
        ).map { result ->
            when (result) {
                is ContinueWatchingResult.Items -> result.items
                ContinueWatchingResult.Degraded -> emptyList()
            }
        }

    private fun buildLiveContext(
        providerIds: List<Long>,
        lastVisitedProviderId: Long?
    ): Flow<DashboardLiveContext> {
        if (providerIds.isEmpty()) {
            return flowOf(DashboardLiveContext(lastVisitedCategory = null, shortcuts = emptyList()))
        }

        return combine(
            getCustomCategories(providerIds, ContentType.LIVE),
            lastVisitedProviderId?.let(preferencesRepository::getLastLiveCategoryId) ?: flowOf(null),
            preferencesRepository.promotedLiveGroupIds
        ) { customCategories, lastVisitedCategoryId, promotedGroupIds ->
            val lastVisitedCategory = customCategories.firstOrNull { it.id == lastVisitedCategoryId }
            val shortcuts = buildList {
                lastVisitedCategory?.let {
                    add(
                        DashboardLiveShortcut(
                            label = appContext.getString(R.string.home_last_group),
                            detail = it.name,
                            categoryId = it.id,
                            type = DashboardShortcutType.LAST_GROUP
                        )
                    )
                }

                customCategories
                    .asSequence()
                    .filter { it.id != VirtualCategoryIds.FAVORITES }
                    .filter { it.count > 0 }
                    .sortedWith(
                        compareByDescending<Category> { category ->
                            val groupId = if (category.id < 0) -category.id else category.id
                            groupId in promotedGroupIds
                        }.thenByDescending { it.count }
                    )
                    .take(HOME_SHORTCUT_LIMIT)
                    .forEach { category ->
                        val groupId = if (category.id < 0) -category.id else category.id
                        add(
                            DashboardLiveShortcut(
                                label = category.name,
                                detail = if (groupId in promotedGroupIds) {
                                    appContext.getString(R.string.dashboard_pinned_channels_format, category.count)
                                } else {
                                    appContext.getString(R.string.dashboard_channels_format, category.count)
                                },
                                categoryId = category.id,
                                type = DashboardShortcutType.CUSTOM_GROUP
                            )
                        )
                    }
            }
                .distinctBy { it.categoryId ?: it.label }
                .take(HOME_SHORTCUT_LIMIT)

            DashboardLiveContext(
                lastVisitedCategory = lastVisitedCategory,
                shortcuts = shortcuts
            )
        }
    }

    private fun observeLiveChannelCount(providerIds: List<Long>): Flow<Int> = when (providerIds.size) {
        0 -> flowOf(0)
        1 -> channelRepository.getChannelCount(providerIds.first())
        else -> combine(providerIds.map { providerId ->
            channelRepository.getChannelCount(providerId)
        }) { counts ->
            counts.sum()
        }
    }

    private fun observeLiveFavorites(providerIds: List<Long>): Flow<List<Favorite>> = when (providerIds.size) {
        0 -> flowOf(emptyList())
        1 -> favoriteRepository.getFavorites(providerIds.first(), ContentType.LIVE)
        else -> favoriteRepository.getFavorites(providerIds, ContentType.LIVE)
    }

    private fun observeFavorites(providerIds: List<Long>, contentType: ContentType): Flow<List<Favorite>> = when (providerIds.size) {
        0 -> flowOf(emptyList())
        1 -> favoriteRepository.getFavorites(providerIds.first(), contentType)
        else -> favoriteRepository.getFavorites(providerIds, contentType)
    }

    private fun observeRecentLiveIds(providerIds: List<Long>, limit: Int): Flow<List<Long>> = when (providerIds.size) {
        0 -> flowOf(emptyList())
        1 -> playbackHistoryRepository.getRecentlyWatchedByProvider(providerIds.first(), limit)
            .map { history ->
                history
                    .filter { it.contentType == ContentType.LIVE }
                    .sortedByDescending { it.lastWatchedAt }
                    .distinctBy { it.contentId }
                    .map { it.contentId }
                    .take(limit)
            }
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

    private fun loadChannelsByOrderedIds(ids: List<Long>): Flow<List<Channel>> {
        if (ids.isEmpty()) return flowOf(emptyList())

        return channelRepository.getChannelsByIds(ids).map { channels ->
            channels.orderedByRequestedRawIds(ids)
        }
    }

    private fun loadMoviesByOrderedIds(ids: List<Long>): Flow<List<Movie>> {
        if (ids.isEmpty()) return flowOf(emptyList())

        return movieRepository.getMoviesByIds(ids).map { movies ->
            movies.orderedByRequestedMovieIds(ids)
        }
    }

    private fun loadSeriesByOrderedIds(ids: List<Long>): Flow<List<Series>> {
        if (ids.isEmpty()) return flowOf(emptyList())

        return seriesRepository.getSeriesByIds(ids).map { series ->
            series.orderedByRequestedSeriesIds(ids)
        }
    }

    private fun List<Movie>.orderedByRequestedMovieIds(requestedIds: List<Long>): List<Movie> {
        if (requestedIds.isEmpty()) return emptyList()
        val movieByRequestedId = buildMap<Long, Movie> {
            this@orderedByRequestedMovieIds.forEach { movie ->
                movie.rawMovieIdsForDashboard().forEach { rawMovieId ->
                    putIfAbsent(rawMovieId, movie)
                }
            }
        }
        return requestedIds.mapNotNull(movieByRequestedId::get).distinctBy { it.id }
    }

    private fun List<Series>.orderedByRequestedSeriesIds(requestedIds: List<Long>): List<Series> {
        if (requestedIds.isEmpty()) return emptyList()
        val seriesByRequestedId = buildMap<Long, Series> {
            this@orderedByRequestedSeriesIds.forEach { series ->
                series.rawSeriesIdsForDashboard().forEach { rawSeriesId ->
                    putIfAbsent(rawSeriesId, series)
                }
            }
        }
        return requestedIds.mapNotNull(seriesByRequestedId::get).distinctBy { it.id }
    }

    private fun Series.rawSeriesIdsForDashboard(): List<Long> =
        variants.map { it.rawSeriesId }.ifEmpty { listOf(selectedVariantId ?: id) }

    private fun Movie.rawMovieIdsForDashboard(): List<Long> =
        variants.map { it.rawMovieId }.ifEmpty { listOf(id) }

    private fun observeUpdateNotice(): Flow<DashboardUpdateNotice?> {
        val cachedRelease = combine(
            preferencesRepository.cachedAppUpdateVersionName,
            preferencesRepository.cachedAppUpdateVersionCode,
            preferencesRepository.cachedAppUpdatePublishedAt,
            preferencesRepository.cachedAppUpdateDownloadUrl,
            preferencesRepository.cachedAppUpdateDownloadSha256
        ) { latestVersionName, latestVersionCode, publishedAt, downloadUrl, downloadSha256 ->
            DashboardCachedUpdateRelease(
                latestVersionName = latestVersionName,
                latestVersionCode = latestVersionCode,
                publishedAt = publishedAt,
                downloadUrl = downloadUrl,
                downloadSha256 = downloadSha256
            )
        }

        return cachedRelease.combine(appUpdateInstaller.downloadState) { release, downloadState ->
            val latestVersionName = release.latestVersionName
            if (latestVersionName.isNullOrBlank()) {
                return@combine null
            }

            val updateAvailable = isRemoteVersionNewer(
                release.latestVersionCode,
                latestVersionName,
                release.publishedAt
            )
            if (!updateAvailable) {
                return@combine null
            }

            DashboardUpdateNotice(
                latestVersionName = latestVersionName,
                downloadSha256 = release.downloadSha256,
                actionState = latestAppUpdateAction(
                    latestVersionName = latestVersionName,
                    downloadUrl = release.downloadUrl,
                    isUpdateAvailable = updateAvailable,
                    downloadState = downloadState
                )
            )
        }
    }

    private fun buildFeature(
        providerName: String,
        recentChannels: List<Channel>,
        continueWatching: List<PlaybackHistory>,
        continueWatchingDegraded: Boolean,
        recentMovies: List<Movie>,
        recentSeries: List<Series>
    ): DashboardFeature {
        // When continue-watching is empty due to a transient IO failure, do not silently
        // fall through to an unrelated hero — surface an explicit degraded state instead.
        if (continueWatching.isEmpty() && continueWatchingDegraded) {
            return DashboardFeature(
                title = appContext.getString(R.string.dashboard_resume_unavailable),
                summary = appContext.getString(R.string.dashboard_resume_unavailable_detail),
                artworkUrl = null,
                actionLabel = appContext.getString(R.string.dashboard_continue_watching),
                actionType = DashboardFeatureAction.CONTINUE_WATCHING
            )
        }
        val resumeItem = continueWatching.firstOrNull()
        if (resumeItem != null) {
            val detail = when (resumeItem.contentType) {
                ContentType.MOVIE -> appContext.getString(R.string.dashboard_resume_movie)
                ContentType.SERIES -> appContext.getString(R.string.dashboard_resume_series)
                ContentType.SERIES_EPISODE -> {
                    if (resumeItem.seasonNumber != null && resumeItem.episodeNumber != null) {
                        appContext.getString(R.string.dashboard_resume_episode_format, resumeItem.seasonNumber, resumeItem.episodeNumber)
                    } else {
                        appContext.getString(R.string.dashboard_resume_episode)
                    }
                }
                ContentType.LIVE -> appContext.getString(R.string.dashboard_resume_live)
            }
            return DashboardFeature(
                title = resumeItem.title,
                summary = detail,
                artworkUrl = resumeItem.posterUrl,
                actionLabel = appContext.getString(R.string.dashboard_continue_watching),
                actionType = DashboardFeatureAction.CONTINUE_WATCHING
            )
        }

        recentChannels.firstOrNull()?.let { channel ->
            return DashboardFeature(
                title = channel.name,
                summary = appContext.getString(R.string.dashboard_jump_back_live),
                artworkUrl = channel.logoUrl,
                actionLabel = appContext.getString(R.string.dashboard_watch_live),
                actionType = DashboardFeatureAction.LIVE
            )
        }

        recentMovies.firstOrNull()?.let { movie ->
            return DashboardFeature(
                title = movie.name,
                summary = movie.year ?: appContext.getString(R.string.dashboard_fresh_movie_pick),
                artworkUrl = movie.backdropUrl ?: movie.posterUrl,
                actionLabel = appContext.getString(R.string.dashboard_open_movies),
                actionType = DashboardFeatureAction.MOVIES
            )
        }

        recentSeries.firstOrNull()?.let { series ->
            return DashboardFeature(
                title = series.name,
                summary = appContext.getString(R.string.dashboard_updated_series),
                artworkUrl = series.backdropUrl ?: series.posterUrl,
                actionLabel = appContext.getString(R.string.dashboard_open_series),
                actionType = DashboardFeatureAction.SERIES
            )
        }

        return DashboardFeature(
            title = providerName,
            summary = appContext.getString(R.string.dashboard_library_ready),
            artworkUrl = null,
            actionLabel = appContext.getString(R.string.dashboard_open_live_tv),
            actionType = DashboardFeatureAction.LIVE
        )
    }

    private fun movieFreshnessScore(movie: Movie): Long {
        return parseDateScore(movie.releaseDate)
            ?: movie.year?.toIntOrNull()?.toLong()
            ?: movie.id
    }

    private fun seriesFreshnessScore(series: Series): Long {
        return series.lastModified
            .takeIf { it > 0L }
            ?: parseDateScore(series.releaseDate)
            ?: series.id
    }

    private fun shouldHideVodFromHome(movie: Movie, level: Int): Boolean {
        if (AdultContentVisibilityPolicy.showInAggregatedSurfaces(level)) return false
        if (movie.isAdult || movie.isUserProtected) return true
        return titleLooksExplicit(movie.name)
    }

    private fun shouldHideVodFromHome(series: Series, level: Int): Boolean {
        if (AdultContentVisibilityPolicy.showInAggregatedSurfaces(level)) return false
        if (series.isAdult || series.isUserProtected) return true
        return titleLooksExplicit(series.name)
    }

    private fun titleLooksExplicit(title: String): Boolean {
        val normalized = title.lowercase()
        val explicitTerms = listOf("porn", "porno", "xxx", "adult", "18+", "sex", "erotic", "hentai")
        return explicitTerms.any { term -> normalized.contains(term) }
    }

    private fun parseDateScore(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null

        return runCatching {
            LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE).toEpochDay()
        }.getOrNull()
            ?: runCatching {
                Year.parse(raw, DateTimeFormatter.ofPattern("yyyy")).atDay(1).toEpochDay()
            }.getOrNull()
    }

    fun setHomeDashboardShelves(shelves: List<AppHomeDashboardShelf>) {
        viewModelScope.launch {
            preferencesRepository.setAppHomeDashboardShelves(
                AppHomeDashboardShelf.normalizeForStorage(shelves)
            )
        }
    }

    fun resetHomeDashboardShelves() {
        viewModelScope.launch {
            preferencesRepository.setAppHomeDashboardShelves(AppHomeDashboardShelf.defaultOrder)
        }
    }

    fun installDownloadedUpdate() {
        viewModelScope.launch {
            val expectedSha256 = _uiState.value.updateNotice?.downloadSha256
            when (val result = appUpdateInstaller.installDownloadedUpdate(expectedSha256)) {
                is com.streamvault.domain.model.Result.Error -> {
                    _uiState.value = _uiState.value.copy(userMessage = result.message)
                }
                is com.streamvault.domain.model.Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        userMessage = appContext.getString(R.string.settings_update_install_started)
                    )
                }
                else -> Unit
            }
        }
    }

    fun userMessageShown() {
        _uiState.value = _uiState.value.copy(userMessage = null)
    }
}

private data class DashboardLiveContext(
    val lastVisitedCategory: Category?,
    val shortcuts: List<DashboardLiveShortcut>
)

private data class ContinueWatchingShelf(
    val items: List<PlaybackHistory> = emptyList(),
    val series: List<Series> = emptyList(),
    val isDegraded: Boolean = false
)

private data class DashboardContentShelves(
    val favoriteChannels: List<Channel>,
    val recentChannels: List<Channel>,
    val continueWatching: List<PlaybackHistory>,
    val continueWatchingSeries: List<Series> = emptyList(),
    val continueWatchingMovies: List<PlaybackHistory> = emptyList(),
    val continueWatchingSeriesItems: List<PlaybackHistory> = emptyList(),
    val continueWatchingDegraded: Boolean = false,
    val favoriteMovies: List<Movie> = emptyList(),
    val favoriteSeries: List<Series> = emptyList(),
    val recentMovies: List<Movie>,
    val recentSeries: List<Series>,
    val topRatedMovies: List<Movie> = emptyList(),
    val recommendedMovies: List<Movie> = emptyList()
)

private data class DashboardSnapshot(
    val shelves: DashboardContentShelves,
    val liveContext: DashboardLiveContext,
    val liveChannelCount: Int,
    val movieCount: Int,
    val seriesCount: Int,
    val homeDashboardShelves: List<AppHomeDashboardShelf>,
    val updateNotice: DashboardUpdateNotice?
)

private data class DashboardCachedUpdateRelease(
    val latestVersionName: String?,
    val latestVersionCode: Int?,
    val publishedAt: String?,
    val downloadUrl: String?,
    val downloadSha256: String?
)

data class DashboardUiState(
    val provider: Provider? = null,
    val homeDashboardShelves: List<AppHomeDashboardShelf> = AppHomeDashboardShelf.defaultOrder,
    val favoriteChannels: List<Channel> = emptyList(),
    val recentChannels: List<Channel> = emptyList(),
    val continueWatching: List<PlaybackHistory> = emptyList(),
    val continueWatchingSeries: List<Series> = emptyList(),
    val continueWatchingMovies: List<PlaybackHistory> = emptyList(),
    val continueWatchingSeriesItems: List<PlaybackHistory> = emptyList(),
    val continueWatchingDegraded: Boolean = false,
    val favoriteMovies: List<Movie> = emptyList(),
    val favoriteSeries: List<Series> = emptyList(),
    val recentMovies: List<Movie> = emptyList(),
    val recentSeries: List<Series> = emptyList(),
    val topRatedMovies: List<Movie> = emptyList(),
    val recommendedMovies: List<Movie> = emptyList(),
    val lastLiveCategory: Category? = null,
    val liveShortcuts: List<DashboardLiveShortcut> = emptyList(),
    val feature: DashboardFeature = DashboardFeature(),
    val providerHealth: DashboardProviderHealth = DashboardProviderHealth(),
    val providerWarnings: List<String> = emptyList(),
    val currentCombinedProfileId: Long? = null,
    val updateNotice: DashboardUpdateNotice? = null,
    val stats: DashboardStats = DashboardStats(),
    val userMessage: String? = null,
    val isLoading: Boolean = true
)

data class DashboardUpdateNotice(
    val latestVersionName: String,
    val downloadSha256: String?,
    val actionState: AppUpdateActionState
) {
    val installReady: Boolean
        get() = actionState == AppUpdateActionState.InstallLatest

    val installPermissionRequired: Boolean
        get() = actionState == AppUpdateActionState.InstallPermissionRequired
}

data class DashboardProviderHealth(
    val status: ProviderStatus = ProviderStatus.UNKNOWN,
    val type: ProviderType = ProviderType.M3U,
    val lastSyncedAt: Long = 0L,
    val expirationDate: Long? = null,
    val maxConnections: Int = 1
)

data class DashboardStats(
    val liveChannelCount: Int = 0,
    val favoriteChannelCount: Int = 0,
    val recentChannelCount: Int = 0,
    val continueWatchingCount: Int = 0,
    val movieLibraryCount: Int = 0,
    val seriesLibraryCount: Int = 0
)

data class DashboardFeature(
    val title: String = "",
    val summary: String = "",
    val artworkUrl: String? = null,
    val actionLabel: String = "",
    val actionType: DashboardFeatureAction = DashboardFeatureAction.LIVE
)

enum class DashboardFeatureAction {
    LIVE,
    CONTINUE_WATCHING,
    MOVIES,
    SERIES
}

data class DashboardLiveShortcut(
    val label: String,
    val detail: String,
    val categoryId: Long?,
    val type: DashboardShortcutType
)

enum class DashboardShortcutType {
    FAVORITES,
    RECENT,
    LAST_GROUP,
    CUSTOM_GROUP
}
