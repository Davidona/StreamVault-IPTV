package com.streamvault.data.repository

import android.database.sqlite.SQLiteException
import android.util.Log
import com.streamvault.data.local.dao.*
import com.streamvault.data.local.entity.*
import com.streamvault.data.mapper.*
import com.streamvault.data.remote.stalker.StalkerContentCapabilitiesCodec
import com.streamvault.data.remote.stalker.StalkerEpisodeSelectorDialect
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerRequestCoordinator
import com.streamvault.data.remote.stalker.StalkerRequestDescriptor
import com.streamvault.data.remote.stalker.StalkerResponseMetrics
import com.streamvault.data.remote.stalker.StalkerTrafficCoordinator
import com.streamvault.data.remote.stalker.StalkerSeriesDetailDialect
import com.streamvault.data.remote.stalker.StalkerUrlFactory
import com.streamvault.data.remote.stalker.StalkerVodPlaybackDialect
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.sync.ContentCachePolicy
import com.streamvault.data.sync.CatalogHydrationCommands
import com.streamvault.data.util.SeriesPresentationSettings
import com.streamvault.data.util.buildPresentedSeries
import com.streamvault.domain.model.*
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result.Success
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.SeriesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.streamvault.data.util.toFtsPrefixQuery
import com.streamvault.data.util.rankSearchResults
import javax.inject.Inject
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.provider.ProviderCapabilityResolver
import com.streamvault.data.provider.TypedProviderClientFactory
import com.streamvault.data.provider.toLegacyProvider
import com.streamvault.data.provider.ProviderCapabilityTimeoutException
import com.streamvault.domain.provider.CapabilityResolution
import com.streamvault.domain.provider.ProviderContentReference
import com.streamvault.domain.util.KeyedMutexRegistry
import com.streamvault.domain.util.BoundedKeySet
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesRepositoryImpl @Inject constructor(
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val categoryDao: CategoryDao,
    private val favoriteDao: FavoriteDao,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val providerDao: ProviderDao,
    private val preferencesRepository: PreferencesRepository,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver,
    private val xtreamContentIndexDao: XtreamContentIndexDao,
    private val xtreamIndexJobDao: XtreamIndexJobDao,
    private val syncManager: CatalogHydrationCommands,
    private val seriesCategoryHydrationDao: SeriesCategoryHydrationDao,
    private val stalkerRequestCoordinator: StalkerRequestCoordinator,
    private val providerCapabilityResolver: ProviderCapabilityResolver,
    private val typedProviderClientFactory: TypedProviderClientFactory
) : SeriesRepository {
    private companion object {
        const val MAX_BACKGROUND_CATEGORY_REFRESHES = 256
        const val TAG = "SeriesRepository"
        const val SEARCH_RESULT_LIMIT = 200
        const val SEARCH_OVERSAMPLE_LIMIT = 500
        const val MIN_SEARCH_QUERY_LENGTH = 2
        const val BROWSE_WINDOW_BUFFER = 80
        const val XTREAM_CATEGORY_HYDRATION_CONCURRENCY = 1
        const val XTREAM_EMPTY_CATEGORY_RETRY_COOLDOWN_MILLIS = 30_000L
        const val CURSOR_BATCH_SIZE = 40
        const val STALKER_PREVIEW_REQUIRED_COUNT_THRESHOLD = 24
        const val STALKER_PREVIEW_MAX_REMOTE_PAGES = 2
        const val STALKER_INITIAL_CATEGORY_FILL_COUNT = 40
        const val STALKER_INITIAL_CATEGORY_MAX_REMOTE_PAGES = 4
        const val STALKER_COMPLETE_PAGE_BATCH_SIZE = 200
        const val DETAIL_REFRESH_TTL_MILLIS = 14L * 24L * 60L * 60L * 1000L
        const val XTREAM_DETAIL_HYDRATION_TIMEOUT_MILLIS = 8_000L
        const val CACHE_STATE_SUMMARY_ONLY = "SUMMARY_ONLY"
        const val CACHE_STATE_DETAIL_HYDRATED = "DETAIL_HYDRATED"
        val DETAIL_YEAR_REGEX = Regex("""(19|20)\d{2}""")
    }

    private data class NameCursor(
        val name: String,
        val id: Long
    )

    private data class RatingCursor(
        val rating: Float,
        val name: String,
        val id: Long
    )

    private data class FreshCursor(
        val lastModified: Long,
        val name: String,
        val id: Long
    )

    private val xtreamCategoryLoadLocks = KeyedMutexRegistry<String>()
    private val backgroundRefreshes = BoundedKeySet<String>(MAX_BACKGROUND_CATEGORY_REFRESHES)
    private val repositoryScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(XTREAM_CATEGORY_HYDRATION_CONCURRENCY)
    )
    private val seriesPresentationSettingsFlow: Flow<SeriesPresentationSettings> = combine(
        preferencesRepository.vodDuplicateHandlingMode,
        preferencesRepository.vodVariantPreferenceMode,
        preferencesRepository.vodVariantSelections,
        preferencesRepository.vodVariantObservations
    ) { duplicateHandlingMode, preferenceMode, preferredVariants, observations ->
        SeriesPresentationSettings(
            duplicateHandlingMode = duplicateHandlingMode,
            preferenceMode = preferenceMode,
            preferredVariants = preferredVariants,
            observations = observations
        )
    }

    override fun getSeries(providerId: Long): Flow<List<Series>> =
        combine(
            seriesDao.getByProvider(providerId),
            preferencesRepository.parentalControlLevel
        ) { entities, level: Int ->
            if (level >= 3) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }
            .combine(seriesPresentationSettingsFlow) { list, settings ->
                buildPresentedSeries(list, settings)
            }

    override fun getSeriesByCategory(providerId: Long, categoryId: Long): Flow<List<Series>> =
        flow {
            ensureXtreamCategoryLoaded(providerId, categoryId)
            emitAll(
                combine(
                    seriesDao.getByCategory(providerId, categoryId),
                    preferencesRepository.parentalControlLevel
                ) { entities, level: Int ->
                    if (level >= 3) {
                        entities.filter { !it.isUserProtected }
                    } else {
                        entities
                    }
                }.map { list -> list.map { it.toDomain() } }
                    .combine(seriesPresentationSettingsFlow) { list, settings ->
                        buildPresentedSeries(list, settings)
                    }
            )
        }

    override fun getSeriesByCategoryPage(
        providerId: Long,
        categoryId: Long,
        limit: Int,
        offset: Int
    ): Flow<List<Series>> = flow {
        ensureXtreamCategoryLoaded(providerId, categoryId, requiredCount = offset + limit)
        emitAll(
            combine(
                seriesDao.getByCategoryPage(providerId, categoryId, limit, offset),
                preferencesRepository.parentalControlLevel
            ) { entities, level: Int ->
                if (level >= 3) {
                    entities.filter { !it.isUserProtected }
                } else {
                    entities
                }
            }.map { list -> list.map { it.toDomain() } }
                .combine(seriesPresentationSettingsFlow) { list, settings ->
                    buildPresentedSeries(list, settings)
                }
        )
    }

    override fun getSeriesByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<Series>> =
        flow {
            ensureXtreamCategoryLoaded(providerId, categoryId, requiredCount = limit)
            emitAll(
                combine(
                    seriesDao.getByCategoryPreview(providerId, categoryId, limit),
                    preferencesRepository.parentalControlLevel
                ) { entities, level: Int ->
                    if (level >= 3) {
                        entities.filter { !it.isUserProtected }
                    } else {
                        entities
                    }
                }.map { list -> list.map { it.toDomain() } }
                    .combine(seriesPresentationSettingsFlow) { list, settings ->
                        buildPresentedSeries(list, settings).take(limit)
                    }
            )
        }

    override fun getCategoryPreviewRows(providerId: Long, categoryIds: List<Long>, limitPerCategory: Int): Flow<Map<Long?, List<Series>>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.SERIES.name),
            preferencesRepository.parentalControlLevel
        ) { categories, level ->
            val requestedIds = categoryIds.toSet()
            val filtered = if (level >= 3) categories.filter { !it.isAdult && !it.isUserProtected } else categories
            filtered.filter { it.categoryId in requestedIds } to level
        }.flatMapLatest { (filteredCategories, level) ->
            if (filteredCategories.isEmpty()) {
                flowOf(emptyMap())
            } else channelFlow {
                val provider = loadCompatibilityProvider(providerId)
                val previewCategories = if (provider?.type == ProviderType.STALKER_PORTAL) {
                    val stalkerProvider = createStalkerProvider(providerId)
                    filteredCategories.filterNot { category ->
                        stalkerProvider.isWildcardCategory(ContentType.SERIES, category.categoryId)
                    }
                } else {
                    filteredCategories
                }
                if (previewCategories.isEmpty()) {
                    send(emptyMap())
                    return@channelFlow
                }
                if (provider != null &&
                    (provider.type == ProviderType.XTREAM_CODES || provider.type == ProviderType.STALKER_PORTAL)
                ) {
                    previewCategories.forEach { category ->
                        triggerSeriesCategoryHydration(
                            providerId = providerId,
                            categoryId = category.categoryId,
                            provider = provider,
                            requiredCount = limitPerCategory,
                            allowStalkerWildcard = false
                        )
                    }
                }
                val categoryGroupFlows: List<Flow<Pair<Long?, List<Series>>>> = previewCategories.map { cat ->
                    seriesDao.getByCategoryPreview(providerId, cat.categoryId, limitPerCategory)
                        .map { entities ->
                            val items = if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            (cat.categoryId as Long?) to items.map { it.toDomain() }
                        }
                }
                combine(
                    combine(categoryGroupFlows) { pairs ->
                        pairs.associate { it.first to it.second }
                    },
                    seriesPresentationSettingsFlow
                ) { previews, settings ->
                    previews.mapValues { (_, items) -> buildPresentedSeries(items, settings).take(limitPerCategory) }
                }.collect { previews ->
                    send(previews)
                }
            }
        }

    override fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<Series>> =
        combine(
            seriesDao.getTopRatedPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities, level: Int ->
            if (level >= 3) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }
            .combine(seriesPresentationSettingsFlow) { list, settings ->
                buildPresentedSeries(list, settings).take(limit)
            }

    override fun getFreshPreview(providerId: Long, limit: Int): Flow<List<Series>> =
        combine(
            seriesDao.getFreshPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities, level: Int ->
            if (level >= 3) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }
            .combine(seriesPresentationSettingsFlow) { list, settings ->
                buildPresentedSeries(list, settings).take(limit)
            }

    override fun getSeriesByIds(ids: List<Long>): Flow<List<Series>> =
        seriesDao.getByIds(ids).map { entities -> entities.map { it.toDomain() } }

    override fun getCategories(providerId: Long): Flow<List<Category>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.SERIES.name),
            preferencesRepository.parentalControlLevel
        ) { entities: List<CategoryEntity>, level: Int ->
            val mapped = entities.map { it.toDomain() }
            if (level >= 3) {
                mapped.filter { !it.isAdult && !it.isUserProtected }
            } else {
                mapped
            }
        }

    override fun getCategoryItemCounts(providerId: Long): Flow<Map<Long, Int>> =
        seriesDao.getCategoryCounts(providerId).map { counts ->
            counts.associate { it.categoryId to it.item_count }
        }

    override fun getLibraryCount(providerId: Long): Flow<Int> =
        seriesDao.getCount(providerId)

    override fun browseSeries(query: LibraryBrowseQuery): Flow<PagedResult<Series>> {
        return flow {
            val normalizedSearch = query.searchQuery.trim()
            val requiresCompleteCategory = normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH ||
                query.filterBy.type != LibraryFilterType.ALL ||
                query.sortBy != LibrarySortBy.LIBRARY ||
                preferencesRepository.vodDuplicateHandlingMode.first() != VodDuplicateHandlingMode.SHOW_ALL
            if (requiresCompleteCategory) {
                query.categoryId?.let {
                    requestCategoryHydration(query.providerId, it, VodCategoryHydrationRequest.COMPLETE)
                }
            }
            query.categoryId
                ?.takeIf { normalizedSearch.length < MIN_SEARCH_QUERY_LENGTH }
                ?.let {
                    val provider = loadCompatibilityProvider(query.providerId)
                    if (provider?.type == ProviderType.XTREAM_CODES) {
                        ensureXtreamCategoryLoaded(
                            providerId = query.providerId,
                            categoryId = it,
                            requiredCount = browseFetchLimit(query)
                        )
                    }
                }
            emit(fetchSeriesBrowseResult(query))
        }.flowOn(Dispatchers.IO)
    }

    override fun observeCategoryHydration(
        providerId: Long,
        categoryId: Long
    ): Flow<VodCategoryHydration?> = seriesCategoryHydrationDao.observe(providerId, categoryId).map { entity ->
        entity?.let {
            VodCategoryHydration(
                lastSuccessfulPage = it.lastSuccessfulPage,
                totalPages = it.totalPages,
                advertisedTotalItems = it.advertisedTotalItems,
                advertisedTotalPages = it.advertisedTotalPages,
                itemCount = it.itemCount,
                isComplete = it.isComplete,
                isTruncated = it.lastStatus == "TRUNCATED",
                hasSeries = it.itemCount > 0,
                isLoading = it.lastStatus == "RUNNING",
                error = it.lastError
            )
        }
    }

    override suspend fun requestCategoryHydration(
        providerId: Long,
        categoryId: Long,
        request: VodCategoryHydrationRequest
    ): Result<Unit> {
        val provider = loadCompatibilityProvider(providerId) ?: return Result.error("Provider not found")
        if (provider.type != ProviderType.STALKER_PORTAL) return Result.success(Unit)
        val mode = preferencesRepository.vodCategoryLoadMode.first()
        val effectiveRequest = if (
            request == VodCategoryHydrationRequest.OPEN && mode == VodCategoryLoadMode.COMPLETE_ON_OPEN
        ) VodCategoryHydrationRequest.COMPLETE else request
        val current = seriesCategoryHydrationDao.get(providerId, categoryId)
        val requestedPage = if (effectiveRequest == VodCategoryHydrationRequest.NEXT_PAGE) {
            ((current?.lastSuccessfulPage ?: 0) + 1).coerceAtLeast(1)
        } else null
        val hasDerived = seriesDao.countByCategoryAndOrigin(
            providerId,
            categoryId,
            SeriesCatalogOrigin.VOD_DERIVED
        ) > 0
        if (!hasDerived && !(effectiveRequest == VodCategoryHydrationRequest.OPEN && (current?.lastSuccessfulPage ?: 0) > 0)) {
            val count = seriesDao.getCountByCategory(providerId, categoryId).first()
            hydrateStalkerSeriesCategoryToCount(
                providerId = providerId,
                categoryId = categoryId,
                provider = provider,
                requiredCount = if (effectiveRequest == VodCategoryHydrationRequest.COMPLETE) Int.MAX_VALUE else count + 1,
                localCount = count,
                hydration = current,
                loadCompletely = effectiveRequest == VodCategoryHydrationRequest.COMPLETE,
                requestedPage = requestedPage
            )
        }
        val derivedResult = if (
            hasDerived && provider.catalogLayout == CatalogLayout.SPLIT && provider.catalogLayoutDetectionVersion > 0
        ) {
            syncManager.hydrateSplitVodSeriesCategory(providerId, categoryId, effectiveRequest)
        } else {
            Result.success(Unit)
        }
        if (derivedResult is Result.Success && request == VodCategoryHydrationRequest.OPEN &&
            mode == VodCategoryLoadMode.PAGED
        ) {
            repositoryScope.launch {
                requestCategoryHydration(providerId, categoryId, VodCategoryHydrationRequest.NEXT_PAGE)
            }
        }
        return derivedResult
    }

    override fun searchSeries(providerId: Long, query: String): Flow<List<Series>> =
        query.trim().takeIf { it.length >= MIN_SEARCH_QUERY_LENGTH }?.toFtsPrefixQuery().let { ftsQuery ->
            if (ftsQuery.isNullOrBlank()) {
            flowOf(emptyList())
            } else combine(
                safeSeriesSearchFlow(
                    source = seriesDao.search(providerId, ftsQuery, SEARCH_RESULT_LIMIT),
                    fallback = {
                        seriesDao.searchFallback(providerId, query.trim().toSqlLikePattern(), SEARCH_RESULT_LIMIT)
                    },
                    rawQuery = query.trim()
                ),
                preferencesRepository.parentalControlLevel
            ) { entities, level: Int ->
                if (level >= 3) {
                    entities.filter { !it.isUserProtected }
                } else {
                    entities
                }
            }.map { list ->
                list.map { it.toDomain() }
                    .rankSearchResults(query) { it.name }
            }.combine(favoriteDao.getAllByType(providerId, ContentType.SERIES.name)) { series, favorites ->
                val favoriteIds = favorites.map { it.contentId }.toSet()
                series.map { if (it.id in favoriteIds) it.copy(isFavorite = true) else it }
            }.combine(seriesPresentationSettingsFlow) { series, settings ->
                buildPresentedSeries(series, settings)
            }
        }

    override suspend fun getSeriesById(seriesId: Long): Series? =
        seriesDao.getById(seriesId)?.toDomain()

    override suspend fun getEpisodeById(episodeId: Long): Episode? =
        episodeDao.getById(episodeId)?.toDomain()

    override suspend fun getSeriesDetails(
        providerId: Long,
        seriesId: Long,
        knownPresentation: SeriesDetailPresentationHint?
    ): Result<Series> {
        val seriesEntity = seriesDao.getById(seriesId)
            ?: seriesDao.getBySeriesId(providerId, seriesId)
            ?: return Result.error("Series not found")

        val provider = loadCompatibilityProvider(providerId)
            ?: return Result.error("Provider not found")

        if (seriesEntity.seriesId <= 0L) {
            return Result.success(attachSeriesPresentation(buildSeriesWithPersistedEpisodes(seriesEntity), knownPresentation))
        }

        if (provider.type == ProviderType.XTREAM_CODES && seriesEntity.hasFreshXtreamDetails()) {
            return Result.success(attachSeriesPresentation(buildSeriesWithPersistedEpisodes(seriesEntity), knownPresentation))
        }
        if (provider.type == ProviderType.STALKER_PORTAL && seriesEntity.hasFreshStalkerDetails()) {
            val localSeries = buildSeriesWithPersistedEpisodes(seriesEntity)
            // Older catalog-page REPLACE writes could cascade-delete the episodes
            // while leaving the parent marked as freshly hydrated. A VOD-derived
            // series with that impossible state must self-repair on the next open.
            if (
                seriesEntity.catalogOrigin != SeriesCatalogOrigin.VOD_DERIVED ||
                localSeries.seasons.any { season -> season.episodes.isNotEmpty() }
            ) {
                return Result.success(attachSeriesPresentation(localSeries, knownPresentation))
            }
        }

        val remoteResult = try {
            val capabilitySet = when (val resolution = providerCapabilityResolver.resolve(providerId)) {
                is CapabilityResolution.Available -> resolution.capability
                is CapabilityResolution.ConfigurationError,
                is CapabilityResolution.Restricted,
                is CapabilityResolution.Unsupported -> return Result.success(
                    attachSeriesPresentation(buildSeriesWithPersistedEpisodes(seriesEntity), knownPresentation)
                )
            }
            val seriesSource = when (val resolution = capabilitySet.seriesCatalog()) {
                is CapabilityResolution.Available -> resolution.capability
                is CapabilityResolution.ConfigurationError,
                is CapabilityResolution.Restricted,
                is CapabilityResolution.Unsupported -> return Result.success(
                    attachSeriesPresentation(buildSeriesWithPersistedEpisodes(seriesEntity), knownPresentation)
                )
            }
            seriesSource.hydrateSeries(
                reference = ProviderContentReference(
                    providerId = providerId,
                    localId = seriesEntity.id,
                    streamId = seriesEntity.seriesId,
                    remoteId = seriesEntity.providerSeriesId,
                    seriesCatalogOrigin = seriesEntity.catalogOrigin,
                    episodePlaybackTemplateUrl = seriesEntity.episodePlaybackTemplateUrl
                ),
                current = buildSeriesWithPersistedEpisodes(seriesEntity)
            )
        } catch (e: Exception) {
            if (provider.type == ProviderType.XTREAM_CODES) {
                xtreamContentIndexDao.markDetailHydrationError(
                    providerId = providerId,
                    contentType = ContentType.SERIES.name,
                    remoteId = seriesEntity.providerSeriesId?.takeIf { it.isNotBlank() } ?: seriesEntity.seriesId.toString(),
                    errorState = "DETAIL_FAILED_RETRYABLE"
                )
                return Result.success(attachSeriesPresentation(buildSeriesWithPersistedEpisodes(seriesEntity), knownPresentation))
            }
            return Result.error(e.message ?: "Failed to access provider credentials", e)
        }

        return when (remoteResult) {
            is Result.Success -> {
                val remoteSeries = remoteResult.data
                val hasRemoteEpisodes = remoteSeries.seasons.any { season -> season.episodes.isNotEmpty() }
                val hasPersistedEpisodes = episodeDao.getBySeriesSync(seriesEntity.id).isNotEmpty()

                val updatedSeries = seriesEntity.copy(
                    name = remoteSeries.name.ifBlank { seriesEntity.name },
                    posterUrl = remoteSeries.posterUrl ?: seriesEntity.posterUrl,
                    backdropUrl = remoteSeries.backdropUrl ?: seriesEntity.backdropUrl,
                    categoryId = remoteSeries.categoryId ?: seriesEntity.categoryId,
                    categoryName = remoteSeries.categoryName ?: seriesEntity.categoryName,
                    plot = remoteSeries.plot ?: seriesEntity.plot,
                    cast = remoteSeries.cast ?: seriesEntity.cast,
                    director = remoteSeries.director ?: seriesEntity.director,
                    genre = remoteSeries.genre ?: seriesEntity.genre,
                    releaseDate = remoteSeries.releaseDate ?: seriesEntity.releaseDate,
                    rating = if (remoteSeries.rating > 0f) remoteSeries.rating else seriesEntity.rating,
                    tmdbId = remoteSeries.tmdbId ?: seriesEntity.tmdbId,
                    youtubeTrailer = remoteSeries.youtubeTrailer ?: seriesEntity.youtubeTrailer,
                    episodeRunTime = remoteSeries.episodeRunTime ?: seriesEntity.episodeRunTime,
                    lastModified = if (remoteSeries.lastModified > 0) remoteSeries.lastModified else seriesEntity.lastModified,
                    providerSeriesId = remoteSeries.providerSeriesId?.takeIf { it.isNotBlank() } ?: seriesEntity.providerSeriesId,
                    catalogOrigin = if (seriesEntity.catalogOrigin == SeriesCatalogOrigin.NATIVE) {
                        SeriesCatalogOrigin.NATIVE
                    } else {
                        remoteSeries.catalogOrigin
                    },
                    episodePlaybackTemplateUrl = remoteSeries.episodePlaybackTemplateUrl
                        ?: seriesEntity.episodePlaybackTemplateUrl,
                    cacheState = if (hasRemoteEpisodes || hasPersistedEpisodes) {
                        CACHE_STATE_DETAIL_HYDRATED
                    } else {
                        "SUMMARY_ONLY"
                    },
                    detailHydratedAt = if (hasRemoteEpisodes || hasPersistedEpisodes) {
                        System.currentTimeMillis()
                    } else {
                        0L
                    }
                )
                seriesDao.update(updatedSeries)
                if (provider.type == ProviderType.XTREAM_CODES) {
                    xtreamContentIndexDao.markDetailHydrated(
                        providerId = providerId,
                        contentType = ContentType.SERIES.name,
                        remoteId = seriesEntity.providerSeriesId?.takeIf { it.isNotBlank() } ?: seriesEntity.seriesId.toString(),
                        localContentId = seriesEntity.id,
                        imageUrl = updatedSeries.posterUrl,
                        detailHydratedAt = updatedSeries.detailHydratedAt
                    )
                }

                val episodesToPersist = remoteSeries.seasons
                    .flatMap { season ->
                        season.episodes.map { episode ->
                            val remoteEpisodeId = episode.episodeId.takeIf { it > 0 } ?: episode.id
                            episode.copy(
                                id = 0,
                                episodeId = remoteEpisodeId,
                                seasonNumber = if (episode.seasonNumber > 0) episode.seasonNumber else season.seasonNumber,
                                seriesId = seriesEntity.id,
                                providerId = providerId
                            ).toEntity().copy(
                                id = 0,
                                episodeId = remoteEpisodeId,
                                seriesId = seriesEntity.id,
                                providerId = providerId
                            )
                        }
                    }

                if (episodesToPersist.isNotEmpty()) {
                    episodeDao.replaceAll(seriesEntity.id, providerId, episodesToPersist)
                }

                val persistedSeries = seriesDao.getById(seriesEntity.id) ?: updatedSeries
                val persistedEpisodes = episodeDao.getBySeriesSync(seriesEntity.id).map { it.toDomain() }
                val persistedByRemoteEpisodeId = persistedEpisodes.associateBy {
                    it.episodeId.takeIf { remoteId -> remoteId > 0 } ?: it.id
                }

                val remoteSeasonMetadataByNumber = remoteSeries.seasons.associateBy { it.seasonNumber }
                val mergedSeasons = if (remoteSeries.seasons.any { it.episodes.isNotEmpty() }) {
                    remoteSeries.seasons
                        .sortedBy { it.seasonNumber }
                        .map { remoteSeason ->
                            val seenIds = mutableSetOf<String>()
                            val mergedEpisodes = remoteSeason.episodes
                                .mapNotNull { remoteEpisode ->
                                    val remoteEpisodeId = remoteEpisode.episodeId.takeIf { it > 0 } ?: remoteEpisode.id
                                    val dedupeKey = if (remoteEpisodeId > 0) remoteEpisodeId.toString()
                                        else "${remoteEpisode.seasonNumber}-${remoteEpisode.episodeNumber}"
                                    if (!seenIds.add(dedupeKey)) {
                                        Log.w(TAG, "Duplicate episode skipped: seriesId=${seriesEntity.id} key=$dedupeKey")
                                        return@mapNotNull null
                                    }
                                    persistedByRemoteEpisodeId[remoteEpisodeId] ?: remoteEpisode.copy(
                                        episodeId = remoteEpisodeId,
                                        seriesId = seriesEntity.id,
                                        providerId = providerId
                                    )
                                }
                                .sortedBy { it.episodeNumber }
                            remoteSeason.copy(
                                episodes = mergedEpisodes,
                                episodeCount = mergedEpisodes.size
                            )
                        }
                } else if (persistedEpisodes.isNotEmpty()) {
                    persistedEpisodes.groupBy { it.seasonNumber }
                        .entries
                        .sortedBy { it.key }
                        .map { (seasonNumber, episodes) ->
                            val seasonMetadata = remoteSeasonMetadataByNumber[seasonNumber]
                            Season(
                                seasonNumber = seasonNumber,
                                name = seasonMetadata?.name?.takeIf { it.isNotBlank() } ?: "Season $seasonNumber",
                                coverUrl = seasonMetadata?.coverUrl,
                                airDate = seasonMetadata?.airDate,
                                episodes = episodes,
                                episodeCount = seasonMetadata?.episodeCount?.takeIf { it > 0 } ?: episodes.size
                            )
                        }
                } else if (remoteSeries.seasons.isNotEmpty()) {
                    remoteSeries.seasons.sortedBy { it.seasonNumber }
                } else {
                    persistedEpisodes.groupBy { it.seasonNumber }
                        .entries
                        .sortedBy { it.key }
                        .map { (seasonNumber, episodes) ->
                            Season(
                                seasonNumber = seasonNumber,
                                name = "Season $seasonNumber",
                                episodes = episodes,
                                episodeCount = episodes.size
                            )
                        }
                }

                Result.success(
                    attachSeriesPresentation(
                        persistedSeries.toDomain().copy(seasons = mergedSeasons),
                        knownPresentation
                    )
                )
            }
            is Result.Error -> {
                if (provider.type == ProviderType.XTREAM_CODES) {
                    xtreamContentIndexDao.markDetailHydrationError(
                        providerId = providerId,
                        contentType = ContentType.SERIES.name,
                        remoteId = seriesEntity.providerSeriesId?.takeIf { it.isNotBlank() } ?: seriesEntity.seriesId.toString(),
                        errorState = if (remoteResult.exception is ProviderCapabilityTimeoutException) {
                            "DETAIL_FAILED_TIMEOUT"
                        } else {
                            "DETAIL_FAILED_RETRYABLE"
                        }
                    )
                }
                val localSeries = attachSeriesPresentation(buildSeriesWithPersistedEpisodes(seriesEntity), knownPresentation)
                Result.success(localSeries)
            }
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getEpisodeStreamInfo(episode: Episode): Result<StreamInfo> = try {
        xtreamStreamUrlResolver.resolveAndCommitMetadata(
            url = episode.streamUrl,
            fallbackProviderId = episode.providerId,
            fallbackStreamId = episode.episodeId.takeIf { it > 0 } ?: episode.id,
            fallbackContentType = ContentType.SERIES_EPISODE,
            fallbackContainerExtension = episode.containerExtension
        )?.let { resolvedStream ->
            val ext = resolvedStream.containerExtension ?: episode.containerExtension
            Result.success(
                StreamInfo(
                    url = resolvedStream.url,
                    title = episode.title,
                    headers = resolvedStream.headers,
                    userAgent = resolvedStream.userAgent,
                    playbackTransportPolicy = resolvedStream.playbackTransportPolicy,
                    allowInvalidSsl = resolvedStream.allowInvalidSsl,
                    proxyHost = resolvedStream.proxyHost,
                    proxyPort = resolvedStream.proxyPort,
                    streamType = StreamType.fromContainerExtension(ext),
                    containerExtension = ext,
                    expirationTime = resolvedStream.expirationTime
                )
            )
        } ?: Result.error("No stream URL available for episode: ${episode.title}")
    } catch (e: Exception) {
        Result.error(e.message ?: "Failed to resolve stream URL for episode: ${episode.title}", e)
    }

    override suspend fun refreshSeries(providerId: Long): Result<Unit> =
        Result.success(Unit) // Handled by ProviderRepository

    private suspend fun buildSeriesWithPersistedEpisodes(seriesEntity: SeriesEntity): Series {
        val episodes = episodeDao.getBySeriesSync(seriesEntity.id).map { it.toDomain() }
        val seasons = episodes.groupBy { it.seasonNumber }
            .entries
            .sortedBy { it.key }
            .map { (seasonNumber, seasonEpisodes) ->
                Season(
                    seasonNumber = seasonNumber,
                    name = "Season $seasonNumber",
                    episodes = seasonEpisodes,
                    episodeCount = seasonEpisodes.size
                )
            }
        return seriesEntity.toDomain().copy(seasons = seasons)
    }

    private suspend fun attachSeriesPresentation(
        series: Series,
        knownPresentation: SeriesDetailPresentationHint? = null
    ): Series {
        val settings = seriesPresentationSettingsFlow.first()
        if (settings.duplicateHandlingMode == VodDuplicateHandlingMode.SHOW_ALL) {
            return series
        }
        knownPresentation
            ?.takeIf { hint ->
                hint.providerId == series.providerId && hint.variants.any { variant -> variant.rawSeriesId == series.id }
            }
            ?.let { hint ->
                return attachPresentedSeries(
                    series = series,
                    logicalGroupId = hint.logicalGroupId,
                    variants = hint.variants,
                    duplicateConfidence = hint.duplicateConfidence
                )
            }
        val groupedSeries = buildPresentedSeries(
            series = loadSeriesPresentationCandidates(series).map { it.toDomain() },
            settings = settings
        ).firstOrNull { presented ->
            presented.id == series.id || presented.variants.any { variant -> variant.rawSeriesId == series.id }
        } ?: return series
        return attachPresentedSeries(
            series = series,
            logicalGroupId = groupedSeries.logicalGroupId,
            variants = groupedSeries.variants,
            duplicateConfidence = groupedSeries.duplicateConfidence,
            fallbackVariantLabel = groupedSeries.variantLabel
        )
    }

    private suspend fun loadSeriesPresentationCandidates(series: Series): List<SeriesEntity> {
        series.tmdbId?.takeIf { it > 0L }?.let { tmdbId ->
            val tmdbMatches = seriesDao.getByProviderAndTmdbIdSync(series.providerId, tmdbId)
            if (tmdbMatches.isNotEmpty()) {
                return tmdbMatches
            }
        }

        val displayYear = seriesDisplayYearForDetail(series)
        if (displayYear != null) {
            val narrowed = (
                seriesDao.getByProviderAndReleaseYearPrefixSync(series.providerId, "$displayYear%") +
                    listOfNotNull(seriesDao.getById(series.id))
                ).distinctBy { it.id }
            if (narrowed.isNotEmpty()) {
                return narrowed
            }
        }

        return listOfNotNull(seriesDao.getById(series.id))
    }

    private fun attachPresentedSeries(
        series: Series,
        logicalGroupId: String?,
        variants: List<VodSeriesVariant>,
        duplicateConfidence: VodDuplicateConfidence,
        fallbackVariantLabel: String? = null
    ): Series {
        val selectedVariant = variants.firstOrNull { it.rawSeriesId == series.id }
        return series.copy(
            logicalGroupId = logicalGroupId ?: "",
            selectedVariantId = series.id,
            variants = variants,
            duplicateConfidence = duplicateConfidence,
            variantLabel = selectedVariant?.label ?: fallbackVariantLabel
        )
    }

    private fun seriesDisplayYearForDetail(series: Series): Int? =
        series.releaseDate?.filter(Char::isDigit)?.take(4)?.toIntOrNull()
            ?: DETAIL_YEAR_REGEX.find(series.name)?.value?.toIntOrNull()

    private fun seriesBrowseSource(query: LibraryBrowseQuery): Flow<List<Series>> {
        val normalizedSearch = query.searchQuery.trim()
        val fetchLimit = browseFetchLimit(query)
        val fastFlow = when {
            normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH -> {
                val ftsQuery = normalizedSearch.toFtsPrefixQuery() ?: return flowOf(emptyList())
                query.categoryId?.let { categoryId ->
                    combine(
                        safeSeriesSearchFlow(
                            source = seriesDao.searchByCategory(query.providerId, categoryId, ftsQuery, SEARCH_OVERSAMPLE_LIMIT),
                            fallback = {
                                seriesDao.searchByCategoryFallback(
                                    query.providerId,
                                    categoryId,
                                    normalizedSearch.toSqlLikePattern(),
                                    SEARCH_OVERSAMPLE_LIMIT
                                )
                            },
                            rawQuery = normalizedSearch
                        ),
                        preferencesRepository.parentalControlLevel
                    ) { entities, level ->
                        if (level >= 3) entities.filter { !it.isUserProtected } else entities
                    }.map { entities ->
                        entities.map { it.toDomain() }
                            .rankSearchResults(normalizedSearch) { it.name }
                    }
                } ?: searchSeries(query.providerId, normalizedSearch)
            }
            query.filterBy.type in setOf(LibraryFilterType.ALL, LibraryFilterType.TOP_RATED, LibraryFilterType.RECENTLY_UPDATED) &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE, LibrarySortBy.RELEASE, LibrarySortBy.UPDATED, LibrarySortBy.RATING) -> {
                when {
                    query.sortBy == LibrarySortBy.RATING || query.filterBy.type == LibraryFilterType.TOP_RATED -> {
                        query.categoryId?.let { categoryId ->
                            flow {
                                ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                                emitAll(
                                    combine(
                                        seriesDao.getTopRatedByCategoryPreview(query.providerId, categoryId, fetchLimit),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level >= 3) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: getTopRatedPreview(query.providerId, fetchLimit)
                    }
                    query.sortBy == LibrarySortBy.RELEASE -> {
                        query.categoryId?.let { categoryId ->
                            flow {
                                ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                                emitAll(
                                    combine(
                                        seriesDao.getReleasedByCategoryPreview(query.providerId, categoryId, fetchLimit),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level >= 3) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: combine(
                            seriesDao.getReleasedPreview(query.providerId, fetchLimit),
                            preferencesRepository.parentalControlLevel
                        ) { entities, level ->
                            if (level >= 3) entities.filter { !it.isUserProtected } else entities
                        }.map { entities -> entities.map { it.toDomain() } }
                    }
                    query.sortBy == LibrarySortBy.UPDATED || query.filterBy.type == LibraryFilterType.RECENTLY_UPDATED -> {
                        query.categoryId?.let { categoryId ->
                            flow {
                                ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                                emitAll(
                                    combine(
                                        seriesDao.getFreshByCategoryPreview(query.providerId, categoryId, fetchLimit),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level >= 3) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: getFreshPreview(query.providerId, fetchLimit)
                    }
                    else -> {
                        query.categoryId?.let { categoryId ->
                            flow {
                                ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                                emitAll(
                                    combine(
                                        seriesDao.getByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level >= 3) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: combine(
                            seriesDao.getByProviderPage(query.providerId, fetchLimit, 0),
                            preferencesRepository.parentalControlLevel
                        ) { entities, level ->
                            if (level >= 3) entities.filter { !it.isUserProtected } else entities
                        }.map { entities -> entities.map { it.toDomain() } }
                    }
                }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.FAVORITES &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                        emitAll(
                            combine(
                                seriesDao.getFavoritesByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    seriesDao.getFavoritesByProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.IN_PROGRESS &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                        emitAll(
                            combine(
                                seriesDao.getInProgressByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    seriesDao.getInProgressByProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.UNWATCHED &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                        emitAll(
                            combine(
                                seriesDao.getUnwatchedByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    seriesDao.getUnwatchedByProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy == LibrarySortBy.WATCH_COUNT -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchLimit)
                        emitAll(
                            combine(
                                seriesDao.getByWatchCountCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    seriesDao.getByWatchCountProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            else -> null
        }

        val categoryId = query.categoryId
        return fastFlow ?: flow {
            categoryId?.let { ensureXtreamCategoryLoaded(query.providerId, it, fetchLimit) }
            val source = if (categoryId == null) {
                seriesDao.getByProviderPage(query.providerId, fetchLimit, 0)
            } else {
                seriesDao.getByCategoryPage(query.providerId, categoryId, fetchLimit, 0)
            }
            emitAll(
                combine(
                    source,
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            )
        }
    }

    private fun seriesBrowseTotalCount(query: LibraryBrowseQuery): Flow<Int> {
        val normalizedSearch = query.searchQuery.trim()
        return when {
            normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH -> seriesBrowseSource(query).map { it.size }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.FAVORITES &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getFavoriteCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getFavoriteCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.IN_PROGRESS &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getInProgressCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getInProgressCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.UNWATCHED &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getUnwatchedCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getUnwatchedCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy == LibrarySortBy.WATCH_COUNT -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getCount(query.providerId)
            }
            normalizedSearch.isBlank() && query.filterBy.type == LibraryFilterType.TOP_RATED -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getTopRatedCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getTopRatedCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() && query.filterBy.type == LibraryFilterType.RECENTLY_UPDATED -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getFreshCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getFreshCountByProvider(query.providerId)
            }
            query.filterBy.type in setOf(LibraryFilterType.ALL, LibraryFilterType.TOP_RATED, LibraryFilterType.RECENTLY_UPDATED) &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE, LibrarySortBy.RELEASE, LibrarySortBy.UPDATED, LibrarySortBy.RATING) -> {
                query.categoryId?.let { categoryId ->
                    seriesDao.getCountByCategory(query.providerId, categoryId)
                } ?: seriesDao.getCount(query.providerId)
            }
            else -> seriesBrowseSource(query).map { it.size }
        }
    }

    private fun browseFetchLimit(query: LibraryBrowseQuery): Int =
        (query.offset + query.limit + BROWSE_WINDOW_BUFFER).coerceAtMost(SEARCH_RESULT_LIMIT)

    private suspend fun fetchSeriesBrowseResult(query: LibraryBrowseQuery): PagedResult<Series> {
        val normalizedSearch = query.searchQuery.trim()
        val presentationSettings = seriesPresentationSettingsFlow.first()
        if (normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH && supportsFastSeriesSearchBrowse(query)) {
            return fetchFastSeriesSearchBrowseResult(query, normalizedSearch, presentationSettings)
        }

        if (normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH) {
            val searchResults = seriesBrowseSource(query).first()
            val favoriteIds = favoriteDao.getAllByType(query.providerId, ContentType.SERIES.name)
                .first()
                .asSequence()
                .filter { it.groupId == null }
                .map { it.contentId }
                .toSet()
            val history = playbackHistoryDao.getByProvider(query.providerId).first()
            val inProgressIds = history
                .asSequence()
                .filter { it.contentType == ContentType.SERIES || it.contentType == ContentType.SERIES_EPISODE }
                .filter {
                    it.resumePositionMs > 0L && (
                        it.totalDurationMs <= 0L ||
                            it.resumePositionMs < (it.totalDurationMs * 0.95f).toLong()
                        )
                }
                .mapNotNull { it.seriesId ?: it.contentId }
                .toSet()
            val completedSeriesIds = history
                .asSequence()
                .filter { it.contentType == ContentType.SERIES_EPISODE }
                .filter { it.totalDurationMs > 0L && it.resumePositionMs >= (it.totalDurationMs * 0.95f).toLong() }
                .mapNotNull { it.seriesId }
                .toSet()
            val watchCounts = history
                .asSequence()
                .filter { it.contentType == ContentType.SERIES || it.contentType == ContentType.SERIES_EPISODE }
                .groupBy { it.seriesId ?: it.contentId }
                .mapValues { (_, entries) -> entries.maxOf { it.watchCount } }
            val filteredResults = applySeriesBrowseQuery(
                series = searchResults,
                query = query,
                favoriteIds = favoriteIds,
                inProgressIds = inProgressIds,
                completedSeriesIds = completedSeriesIds,
                watchCounts = watchCounts
            ).let { results -> buildPresentedSeries(results, presentationSettings) }
            val items = filteredResults.drop(query.offset).take(query.limit)

            return PagedResult(
                items = items,
                totalCount = filteredResults.size,
                offset = query.offset,
                limit = query.limit,
                hasMoreRemote = false
            )
        }

        val rawTotalCount = seriesBrowseTotalCount(query).first()
        val favoriteIds = favoriteDao.getAllByType(query.providerId, ContentType.SERIES.name)
            .first()
            .asSequence()
            .filter { it.groupId == null }
            .map { it.contentId }
            .toSet()

        val canUseCursorWindow = supportsCursorBrowse(query)
        val items = if (canUseCursorWindow) {
            fetchSeriesCursorWindow(query, favoriteIds)
        } else {
            val series = seriesBrowseSource(query).first()
            val history = playbackHistoryDao.getByProvider(query.providerId).first()
            val inProgressIds = history
                .asSequence()
                .filter { it.contentType == ContentType.SERIES || it.contentType == ContentType.SERIES_EPISODE }
                .filter {
                    it.resumePositionMs > 0L && (
                        it.totalDurationMs <= 0L ||
                            it.resumePositionMs < (it.totalDurationMs * 0.95f).toLong()
                        )
                }
                .mapNotNull { it.seriesId ?: it.contentId }
                .toSet()
            val completedSeriesIds = history
                .asSequence()
                .filter { it.contentType == ContentType.SERIES_EPISODE }
                .filter { it.totalDurationMs > 0L && it.resumePositionMs >= (it.totalDurationMs * 0.95f).toLong() }
                .mapNotNull { it.seriesId }
                .toSet()
            val watchCounts = history
                .asSequence()
                .filter { it.contentType == ContentType.SERIES || it.contentType == ContentType.SERIES_EPISODE }
                .groupBy { it.seriesId ?: it.contentId }
                .mapValues { (_, entries) -> entries.maxOf { it.watchCount } }

            applySeriesBrowseQuery(
                series = series,
                query = query,
                favoriteIds = favoriteIds,
                inProgressIds = inProgressIds,
                completedSeriesIds = completedSeriesIds,
                watchCounts = watchCounts
            ).let { results -> buildPresentedSeries(results, presentationSettings) }
                .drop(query.offset)
                .take(query.limit)
        }

        val totalCount = rawTotalCount

        val hasMoreRemote = query.categoryId?.let { categoryId ->
            val provider = loadCompatibilityProvider(query.providerId)
            if (provider?.type == ProviderType.STALKER_PORTAL) {
                seriesCategoryHydrationDao.get(query.providerId, categoryId)?.let { !it.isComplete } ?: false
            } else {
                false
            }
        } ?: false

        return PagedResult(
            items = items,
            totalCount = totalCount,
            offset = query.offset,
            limit = query.limit,
            hasMoreRemote = hasMoreRemote
        )
    }

    private suspend fun fetchFastSeriesSearchBrowseResult(
        query: LibraryBrowseQuery,
        normalizedSearch: String,
        presentationSettings: SeriesPresentationSettings
    ): PagedResult<Series> {
        val ftsQuery = normalizedSearch.toFtsPrefixQuery() ?: return PagedResult(
            items = emptyList(),
            totalCount = 0,
            offset = query.offset,
            limit = query.limit
        )
        val includeProtected = if (preferencesRepository.parentalControlLevel.first() >= 3) 0 else 1
        val pageLimit = query.limit + 1
        val rows = query.categoryId?.let { categoryId ->
            seriesDao.searchByCategoryPage(
                providerId = query.providerId,
                categoryId = categoryId,
                query = ftsQuery,
                rawQuery = normalizedSearch,
                prefixLike = normalizedSearch.toSqlPrefixLikePattern(),
                includeProtected = includeProtected,
                limit = pageLimit,
                offset = query.offset
            )
        } ?: seriesDao.searchPage(
            providerId = query.providerId,
            query = ftsQuery,
            rawQuery = normalizedSearch,
            prefixLike = normalizedSearch.toSqlPrefixLikePattern(),
            includeProtected = includeProtected,
            limit = pageLimit,
            offset = query.offset
        )
        val hasMore = rows.size > query.limit
        val favoriteIds = favoriteDao.getAllByType(query.providerId, ContentType.SERIES.name)
            .first()
            .asSequence()
            .filter { it.groupId == null }
            .map { it.contentId }
            .toSet()
        val items = rows
            .take(query.limit)
            .map { it.toDomain() }
            .map { series -> if (series.id in favoriteIds) series.copy(isFavorite = true) else series }
            .let { rawItems -> buildPresentedSeries(rawItems, presentationSettings) }

        return PagedResult(
            items = items,
            totalCount = query.offset + items.size + if (hasMore) 1 else 0,
            offset = query.offset,
            limit = query.limit,
            hasMoreRemote = hasMore
        )
    }

    private suspend fun fetchSeriesCursorWindow(
        query: LibraryBrowseQuery,
        favoriteIds: Set<Long>
    ): List<Series> {
        val parentalLevel = preferencesRepository.parentalControlLevel.first()
        val targetVisibleCount = (query.offset + query.limit).coerceAtLeast(query.limit)
        val collected = ArrayList<Series>(targetVisibleCount)

        when {
            query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy == LibrarySortBy.LIBRARY -> {
                collectSeriesPages<FreshCursor>(query, parentalLevel, collected, favoriteIds,
                    extractCursor = { FreshCursor(it.lastModified, it.name, it.id) }
                ) { limit, cursor ->
                    loadSeriesFreshPage(query, limit, cursor)
                }
            }
            query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy == LibrarySortBy.TITLE -> {
                collectSeriesPages<NameCursor>(query, parentalLevel, collected, favoriteIds,
                    extractCursor = { NameCursor(it.name, it.id) }
                ) { limit, cursor ->
                    loadSeriesNamePage(query, limit, cursor)
                }
            }
            (query.sortBy == LibrarySortBy.RATING || query.filterBy.type == LibraryFilterType.TOP_RATED) -> {
                collectSeriesPages<RatingCursor>(query, parentalLevel, collected, favoriteIds,
                    extractCursor = { RatingCursor(it.rating, it.name, it.id) }
                ) { limit, cursor ->
                    loadSeriesRatingPage(query, limit, cursor)
                }
            }
            query.sortBy == LibrarySortBy.UPDATED ||
                query.filterBy.type == LibraryFilterType.RECENTLY_UPDATED -> {
                collectSeriesPages<FreshCursor>(query, parentalLevel, collected, favoriteIds,
                    extractCursor = { FreshCursor(it.lastModified, it.name, it.id) }
                ) { limit, cursor ->
                    loadSeriesFreshPage(query, limit, cursor)
                }
            }
        }

        return collected.drop(query.offset).take(query.limit)
    }

    private suspend fun <C> collectSeriesPages(
        query: LibraryBrowseQuery,
        parentalLevel: Int,
        collected: MutableList<Series>,
        favoriteIds: Set<Long>,
        extractCursor: (SeriesBrowseEntity) -> C,
        loadPage: suspend (limit: Int, cursor: C?) -> List<SeriesBrowseEntity>
    ) {
        var cursor: C? = null
        val targetVisibleCount = query.offset + query.limit
        while (collected.size < targetVisibleCount) {
            val batch = loadPage(CURSOR_BATCH_SIZE, cursor)
            if (batch.isEmpty()) {
                return
            }
            val visibleBatch = if (parentalLevel >= 3) {
                batch.filterNot { it.isUserProtected }
            } else {
                batch
            }
            collected += visibleBatch.map { entity ->
                val item = entity.toDomain()
                if (item.id in favoriteIds) item.copy(isFavorite = true) else item
            }
            if (batch.size < CURSOR_BATCH_SIZE) {
                return
            }
            cursor = extractCursor(batch.last())
        }
    }

    private suspend fun loadSeriesNamePage(query: LibraryBrowseQuery, limit: Int, cursor: NameCursor?): List<SeriesBrowseEntity> {
        val categoryId = query.categoryId
        return if (categoryId == null) {
            if (cursor == null) seriesDao.getByProviderCursorPage(query.providerId, limit)
            else seriesDao.getByProviderCursorPageAfter(query.providerId, cursor.name, cursor.id, limit)
        } else {
            if (cursor == null) seriesDao.getByCategoryCursorPage(query.providerId, categoryId, limit)
            else seriesDao.getByCategoryCursorPageAfter(query.providerId, categoryId, cursor.name, cursor.id, limit)
        }
    }

    private suspend fun loadSeriesRatingPage(query: LibraryBrowseQuery, limit: Int, cursor: RatingCursor?): List<SeriesBrowseEntity> {
        val categoryId = query.categoryId
        return if (categoryId == null) {
            if (cursor == null) seriesDao.getTopRatedCursorPage(query.providerId, limit)
            else seriesDao.getTopRatedCursorPageAfter(query.providerId, cursor.rating, cursor.name, cursor.id, limit)
        } else {
            if (cursor == null) seriesDao.getTopRatedByCategoryCursorPage(query.providerId, categoryId, limit)
            else seriesDao.getTopRatedByCategoryCursorPageAfter(query.providerId, categoryId, cursor.rating, cursor.name, cursor.id, limit)
        }
    }

    private suspend fun loadSeriesFreshPage(query: LibraryBrowseQuery, limit: Int, cursor: FreshCursor?): List<SeriesBrowseEntity> {
        val categoryId = query.categoryId
        return if (categoryId == null) {
            if (cursor == null) seriesDao.getFreshCursorPage(query.providerId, limit)
            else seriesDao.getFreshCursorPageAfter(query.providerId, cursor.lastModified, cursor.name, cursor.id, limit)
        } else {
            if (cursor == null) seriesDao.getFreshByCategoryCursorPage(query.providerId, categoryId, limit)
            else seriesDao.getFreshByCategoryCursorPageAfter(query.providerId, categoryId, cursor.lastModified, cursor.name, cursor.id, limit)
        }
    }

    private fun supportsCursorBrowse(query: LibraryBrowseQuery): Boolean {
        if (query.searchQuery.isNotBlank()) return false
        return when {
            query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy in setOf(
                    LibrarySortBy.LIBRARY,
                    LibrarySortBy.TITLE,
                    LibrarySortBy.UPDATED,
                    LibrarySortBy.RATING
                ) -> true
            else -> false
        }
    }

    private fun applySeriesBrowseQuery(
        series: List<Series>,
        query: LibraryBrowseQuery,
        favoriteIds: Set<Long>,
        inProgressIds: Set<Long>,
        completedSeriesIds: Set<Long> = emptySet(),
        watchCounts: Map<Long, Int>
    ): List<Series> {
        val withFavoriteState = series.map { item -> item.copy(isFavorite = item.id in favoriteIds) }
        val filtered = withFavoriteState.filter { item ->
            seriesMatchesFilter(item, query.filterBy.type, inProgressIds, completedSeriesIds) && seriesMatchesSearch(item, query.searchQuery)
        }

        val sorted = when (query.sortBy) {
            LibrarySortBy.LIBRARY -> filtered
            LibrarySortBy.TITLE -> filtered.sortedBy { it.name.lowercase() }
            LibrarySortBy.RELEASE -> filtered.sortedByDescending(::seriesReleaseScore)
            LibrarySortBy.UPDATED -> filtered.sortedByDescending(::seriesUpdatedScore)
            LibrarySortBy.RATING -> filtered.sortedByDescending { it.rating }
            LibrarySortBy.WATCH_COUNT -> filtered.sortedByDescending { watchCounts[it.id] ?: 0 }
        }

        return if (query.searchQuery.isBlank() || query.sortBy != LibrarySortBy.LIBRARY) {
            sorted
        } else {
            sorted.rankSearchResults(query.searchQuery) { it.name }
        }
    }

    private fun supportsFastSeriesSearchBrowse(query: LibraryBrowseQuery): Boolean =
        query.filterBy.type == LibraryFilterType.ALL &&
            query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE)

    private fun seriesMatchesFilter(
        series: Series,
        filterType: LibraryFilterType,
        inProgressIds: Set<Long>,
        completedSeriesIds: Set<Long> = emptySet()
    ): Boolean = when (filterType) {
        LibraryFilterType.ALL -> true
        LibraryFilterType.FAVORITES -> series.isFavorite
        LibraryFilterType.IN_PROGRESS -> series.id in inProgressIds
        LibraryFilterType.UNWATCHED -> series.id !in completedSeriesIds
        LibraryFilterType.TOP_RATED -> series.rating > 0f
        LibraryFilterType.RECENTLY_UPDATED -> seriesUpdatedScore(series) > 0L
    }

    private fun seriesMatchesSearch(series: Series, searchQuery: String): Boolean {
        val normalizedQuery = searchQuery.trim().lowercase()
        if (normalizedQuery.isBlank()) return true
        return sequenceOf(series.name, series.genre, series.categoryName)
            .filterNotNull()
            .any { value -> value.lowercase().contains(normalizedQuery) }
    }

    private fun safeSeriesSearchFlow(
        source: Flow<List<SeriesBrowseEntity>>,
        fallback: () -> Flow<List<SeriesBrowseEntity>>,
        rawQuery: String
    ): Flow<List<SeriesBrowseEntity>> = flow {
        try {
            source.collect { ftsRows ->
                if (ftsRows.isEmpty()) {
                    emit(emptyList())
                } else {
                    emit(ftsRows)
                }
            }
        } catch (error: SQLiteException) {
            Log.w(TAG, "FTS series search failed for queryLength=${rawQuery.length}; using LIKE-only search", error)
            emitAll(fallback())
        }
    }

    private fun String.toSqlLikePattern(): String {
        val escaped = buildString(length) {
            this@toSqlLikePattern.forEach { char ->
                when (char) {
                    '%', '_', '\\' -> append('\\')
                }
                append(char)
            }
        }
        return "%$escaped%"
    }

    private fun String.toSqlPrefixLikePattern(): String {
        val escaped = buildString(length) {
            this@toSqlPrefixLikePattern.forEach { char ->
                when (char) {
                    '%', '_', '\\' -> append('\\')
                }
                append(char)
            }
        }
        return "$escaped%"
    }

    private suspend fun ensureXtreamCategoryLoaded(
        providerId: Long,
        categoryId: Long,
        requiredCount: Int = SEARCH_RESULT_LIMIT,
        refreshStaleInBackground: Boolean = true,
        allowStalkerWildcard: Boolean = true,
        loadStalkerCategoryCompletely: Boolean = false
    ) {
        val key = "$providerId:$categoryId"
        val provider = loadCompatibilityProvider(providerId) ?: return
        if (provider.type != ProviderType.XTREAM_CODES && provider.type != ProviderType.STALKER_PORTAL) return

        val localCount = seriesDao.getCountByCategory(providerId, categoryId).first()
        val hydration = seriesCategoryHydrationDao.get(providerId, categoryId)
        if (provider.type == ProviderType.XTREAM_CODES) {
            if (localCount > 0) {
            } else {
                syncManager.prioritizeXtreamIndexCategory(providerId, ContentType.SERIES, categoryId)
            }
            return
        }
        if (provider.type == ProviderType.STALKER_PORTAL) {
            if (!shouldUseStalkerLazyFallback(
                    hydration = hydration,
                    localCount = localCount,
                    requiredCount = requiredCount,
                    loadCompletely = loadStalkerCategoryCompletely
                )
            ) {
                return
            }
            hydrateStalkerSeriesCategoryToCount(
                providerId = providerId,
                categoryId = categoryId,
                provider = provider,
                requiredCount = requiredCount,
                localCount = localCount,
                hydration = hydration,
                allowWildcard = allowStalkerWildcard,
                loadCompletely = loadStalkerCategoryCompletely
            )
            return
        }
    }

    private fun triggerSeriesCategoryHydration(
        providerId: Long,
        categoryId: Long,
        provider: Provider,
        requiredCount: Int = SEARCH_RESULT_LIMIT,
        allowStalkerWildcard: Boolean = true
    ) {
        val key = "$providerId:$categoryId"
        repositoryScope.launch {
            if (!backgroundRefreshes.awaitAdd(key)) return@launch
            try {
                if (provider.type == ProviderType.XTREAM_CODES) {
                    return@launch
                }
                val localCount = seriesDao.getCountByCategory(providerId, categoryId).first()
                val hydration = seriesCategoryHydrationDao.get(providerId, categoryId)
                if (localCount == 0 && hydration?.isEmptyRetryCoolingDown() == true) return@launch
                if (provider.type == ProviderType.STALKER_PORTAL) {
                    if (!shouldUseStalkerLazyFallback(hydration, localCount, requiredCount)) {
                        return@launch
                    }
                    hydrateStalkerSeriesCategoryToCount(
                        providerId = providerId,
                        categoryId = categoryId,
                        provider = provider,
                        requiredCount = requiredCount,
                        localCount = localCount,
                        hydration = hydration,
                        allowWildcard = allowStalkerWildcard
                    )
                }
            } finally {
                backgroundRefreshes.remove(key)
            }
        }
    }

    private fun shouldUseStalkerLazyFallback(
        hydration: SeriesCategoryHydrationEntity?,
        localCount: Int,
        requiredCount: Int,
        loadCompletely: Boolean = false
    ): Boolean {
        val now = System.currentTimeMillis()
        val hasEmptySuccessfulCheckpoint = localCount == 0 &&
            (hydration?.lastSuccessfulPage ?: hydration?.lastLoadedPage ?: 0) > 0
        if (hasEmptySuccessfulCheckpoint) return true
        if (hydration?.isComplete == true) return false
        if (loadCompletely) return true
        if (localCount >= requiredCount) return false
        if (hydration?.lastStatus in setOf("FAILED_PERMANENT", "FAILED_BUDGET_EXHAUSTED")) return true
        if (hydration?.retryBudgetRemaining == 0 && hydration.retryAfterMs <= now) return true
        return true
    }

    private suspend fun hydrateStalkerSeriesCategoryToCount(
        providerId: Long,
        categoryId: Long,
        provider: Provider,
        requiredCount: Int,
        localCount: Int? = null,
        hydration: SeriesCategoryHydrationEntity? = null,
        allowWildcard: Boolean = true,
        loadCompletely: Boolean = false,
        requestedPage: Int? = null
    ) {
        val key = "$providerId:$categoryId"
        xtreamCategoryLoadLocks.withLock(key) {
            val persistedHydration = seriesCategoryHydrationDao.get(providerId, categoryId)
            if (requestedPage != null && (persistedHydration?.lastSuccessfulPage ?: 0) >= requestedPage) return@withLock
            val stalkerProvider = createStalkerProvider(providerId)
            if (!allowWildcard && stalkerProvider.isWildcardCategory(ContentType.SERIES, categoryId)) return@withLock
            var currentCount = localCount ?: seriesDao.getCountByCategory(providerId, categoryId).first()
            var currentHydration = persistedHydration ?: hydration
            if ((currentHydration?.isComplete == true && currentCount > 0) ||
                (!loadCompletely && currentCount >= requiredCount)
            ) return@withLock
            if (currentCount == 0 && currentHydration?.isEmptyRetryCoolingDown() == true) return@withLock

            val isPreviewLoad = requiredCount <= STALKER_PREVIEW_REQUIRED_COUNT_THRESHOLD
            val isInitialCategoryFill = !isPreviewLoad && currentCount < STALKER_INITIAL_CATEGORY_FILL_COUNT
            val targetCount = when {
                loadCompletely -> Int.MAX_VALUE
                isInitialCategoryFill -> minOf(requiredCount, STALKER_INITIAL_CATEGORY_FILL_COUNT)
                else -> requiredCount
            }
            val maxRemotePages = when {
                loadCompletely -> STALKER_COMPLETE_PAGE_BATCH_SIZE
                isPreviewLoad -> STALKER_PREVIEW_MAX_REMOTE_PAGES
                isInitialCategoryFill -> STALKER_INITIAL_CATEGORY_MAX_REMOTE_PAGES
                else -> 1
            }
            var nextPage = when {
                currentCount == 0 && (currentHydration?.lastSuccessfulPage ?: 0) > 0 -> 1
                currentHydration?.lastStatus in setOf("FAILED_RETRYABLE", "COOLDOWN", "ANOMALY") ->
                    currentHydration?.lastAttemptedPage?.coerceAtLeast(1) ?: 1
                else ->
                    ((currentHydration?.lastSuccessfulPage ?: currentHydration?.lastLoadedPage ?: 0) + 1)
                        .coerceAtLeast(1)
            }
            // The cached totalPages can under-report when the preview hydrate stored
            // it from a partial response. Skip the pre-fetch guard on the first
            // iteration so we always perform at least one real fetch that refreshes
            // totalPages; subsequent iterations use the in-loop updated value.
            var firstIteration = true
            var remotePagesRequested = 0
            val seenPageFingerprints = mutableSetOf<String>()
            while (currentCount < targetCount) {
                val attemptStartedAt = System.currentTimeMillis()
                if (isPreviewLoad && nextPage > STALKER_PREVIEW_MAX_REMOTE_PAGES) break
                if (remotePagesRequested >= maxRemotePages) break
                val totalPages = currentHydration?.advertisedTotalPages ?: 0
                if (!firstIteration && totalPages > 0 && nextPage > totalPages) break
                firstIteration = false
                remotePagesRequested += 1
                seriesCategoryHydrationDao.upsert(
                    (currentHydration ?: SeriesCategoryHydrationEntity(providerId, categoryId)).copy(
                        lastAttemptedPage = nextPage,
                        lastStatus = "RUNNING",
                        lastError = null
                    )
                )
                val requestPriority = if (isPreviewLoad) {
                    StalkerRequestPriority.VISIBLE_PREVIEW
                } else {
                    StalkerRequestPriority.OPEN_CATEGORY
                }
                val coordinatedResult = stalkerRequestCoordinator.execute(
                    providerId = providerId,
                    priority = requestPriority,
                    descriptor = StalkerRequestDescriptor(
                        contentType = "SERIES",
                        action = "CATEGORY_PAGE",
                        categoryKey = categoryId.toString(),
                        page = nextPage
                    ),
                    metricsOf = { result ->
                        val page = (result as? Success)?.data
                        StalkerResponseMetrics(
                            items = page?.items?.size,
                            pages = page?.page,
                            advertisedTotal = page?.advertisedTotalItems,
                            truncated = page?.isTruncated,
                            terminationReason = page?.terminationReason
                        )
                    }
                ) {
                    if (provider.catalogLayout == CatalogLayout.SPLIT && provider.catalogLayoutDetectionVersion > 0) {
                        stalkerProvider.getNativeSeriesListPage(categoryId, nextPage)
                    } else {
                        stalkerProvider.getSeriesListPage(categoryId, nextPage)
                    }
                }
                if (coordinatedResult is Result.Error) {
                    stalkerRequestCoordinator.recordFailure(providerId, coordinatedResult.exception)
                }
                when (val result = coordinatedResult) {
                    is Success -> {
                        if (
                            currentHydration?.advertisedTotalPages != null &&
                            result.data.advertisedTotalPages != null &&
                            currentHydration?.advertisedTotalPages != result.data.advertisedTotalPages
                        ) {
                            seriesCategoryHydrationDao.upsert(
                                (currentHydration ?: SeriesCategoryHydrationEntity(providerId, categoryId)).copy(
                                    lastAttemptedPage = result.data.page,
                                    lastStatus = "ANOMALY",
                                    lastError = "Portal changed its advertised catalog page count while loading.",
                                    retryAfterMs = 0L
                                )
                            )
                            break
                        }
                        val pageFingerprint = result.data.items.joinToString("|") {
                            (it.providerSeriesId ?: it.seriesId).toString()
                        }.takeIf(String::isNotEmpty)
                        if (pageFingerprint != null && !seenPageFingerprints.add(pageFingerprint)) {
                            seriesCategoryHydrationDao.upsert(
                                (currentHydration ?: SeriesCategoryHydrationEntity(providerId, categoryId)).copy(
                                    lastAttemptedPage = result.data.page,
                                    lastStatus = "ANOMALY",
                                    lastError = "Portal repeated a catalog page while loading page ${result.data.page}.",
                                    retryAfterMs = 0L
                                )
                            )
                            break
                        }
                        val entities = result.data.items.map { series -> series.toEntity() }
                        val pageComplete = result.data.isComplete
                        val pageLimitReached = loadCompletely &&
                            remotePagesRequested >= STALKER_COMPLETE_PAGE_BATCH_SIZE &&
                            !pageComplete
                        val truncated = result.data.isTruncated || pageLimitReached
                        val terminationReason = result.data.terminationReason
                            ?: "page_limit".takeIf { pageLimitReached }
                        seriesDao.upsertCategoryPage(providerId, entities)
                        currentCount = seriesDao.getCountByCategory(providerId, categoryId).first()
                        currentHydration = SeriesCategoryHydrationEntity(
                            providerId = providerId,
                            categoryId = categoryId,
                            lastHydratedAt = attemptStartedAt,
                            itemCount = currentCount,
                            lastStatus = if (truncated) "TRUNCATED" else "SUCCESS",
                            lastError = terminationReason,
                            lastLoadedPage = result.data.page,
                            lastAttemptedPage = result.data.page,
                            lastSuccessfulPage = result.data.page,
                            totalPages = result.data.totalPages,
                            advertisedTotalItems = result.data.advertisedTotalItems,
                            advertisedTotalPages = result.data.advertisedTotalPages,
                            isComplete = pageComplete && !truncated,
                            pageSize = result.data.pageSize,
                            retryAfterMs = 0L,
                            failureCount = 0,
                            retryBudgetRemaining = 3,
                            lastPageFingerprint = pageFingerprint
                        )
                        seriesCategoryHydrationDao.upsert(currentHydration!!)
                        if (pageComplete || truncated) break
                        nextPage = result.data.page + 1
                    }
                    is Result.Error -> {
                        val priorFailureCount = currentHydration?.failureCount ?: 0
                        val remainingBudget = ((currentHydration?.retryBudgetRemaining ?: 3) - 1).coerceAtLeast(0)
                        val nextStatus = when {
                            remainingBudget <= 0 -> "FAILED_BUDGET_EXHAUSTED"
                            else -> "FAILED_RETRYABLE"
                        }
                        seriesCategoryHydrationDao.upsert(
                            SeriesCategoryHydrationEntity(
                                providerId = providerId,
                                categoryId = categoryId,
                                lastHydratedAt = currentHydration?.lastHydratedAt ?: 0L,
                                itemCount = currentCount,
                                lastStatus = nextStatus,
                                lastError = result.message,
                                lastLoadedPage = currentHydration?.lastLoadedPage ?: 0,
                                lastAttemptedPage = nextPage,
                                lastSuccessfulPage = currentHydration?.lastSuccessfulPage ?: currentHydration?.lastLoadedPage ?: 0,
                                totalPages = currentHydration?.totalPages ?: 0,
                                advertisedTotalItems = currentHydration?.advertisedTotalItems,
                                advertisedTotalPages = currentHydration?.advertisedTotalPages,
                                isComplete = currentHydration?.isComplete ?: false,
                                pageSize = currentHydration?.pageSize ?: 0,
                                retryAfterMs = if (nextStatus == "FAILED_RETRYABLE") {
                                    attemptStartedAt + XTREAM_EMPTY_CATEGORY_RETRY_COOLDOWN_MILLIS * (priorFailureCount + 1)
                                } else {
                                    0L
                                },
                                failureCount = priorFailureCount + 1,
                                retryBudgetRemaining = remainingBudget,
                                lastPageFingerprint = currentHydration?.lastPageFingerprint
                            )
                        )
                        break
                    }
                    is Result.Loading -> break
                }
            }
        }
    }

    private fun SeriesCategoryHydrationEntity.isFresh(now: Long = System.currentTimeMillis()): Boolean {
        if (lastStatus != "SUCCESS") return false
        return !ContentCachePolicy.shouldRefresh(lastHydratedAt, ContentCachePolicy.SERIES_CATEGORY_TTL_MILLIS, now)
    }

    private fun SeriesCategoryHydrationEntity.isEmptyRetryCoolingDown(now: Long = System.currentTimeMillis()): Boolean {
        if (lastStatus != "EMPTY_RETRY") return false
        return !ContentCachePolicy.shouldRefresh(lastHydratedAt, XTREAM_EMPTY_CATEGORY_RETRY_COOLDOWN_MILLIS, now)
    }

    private fun SeriesEntity.hasFreshXtreamDetails(now: Long = System.currentTimeMillis()): Boolean {
        if (cacheState == CACHE_STATE_SUMMARY_ONLY) return false
        if (detailHydratedAt <= 0L) return false
        if (remoteStaleAt > 0L) return false
        if (ContentCachePolicy.shouldRefresh(detailHydratedAt, DETAIL_REFRESH_TTL_MILLIS, now)) return false
        return true
    }

    private fun seriesReleaseScore(series: Series): Long =
        series.releaseDate
            ?.filter { it.isDigit() }
            ?.take(8)
            ?.toLongOrNull()
            ?: seriesUpdatedScore(series)

    private fun seriesUpdatedScore(series: Series): Long =
        series.lastModified.takeIf { it > 0L } ?: 0L

    private suspend fun createStalkerProvider(providerId: Long): StalkerProvider {
        val snapshot = providerCapabilityResolver.snapshot(providerId)
            ?: throw IllegalStateException("Provider $providerId has no typed configuration")
        return when (val resolution = typedProviderClientFactory.stalker(snapshot)) {
            is CapabilityResolution.Available -> resolution.capability
            is CapabilityResolution.ConfigurationError -> throw IllegalStateException(resolution.reason)
            is CapabilityResolution.Restricted -> throw IllegalStateException(resolution.reason)
            is CapabilityResolution.Unsupported -> throw IllegalStateException(resolution.reason)
        }
    }

    private fun SeriesEntity.hasFreshStalkerDetails(now: Long = System.currentTimeMillis()): Boolean {
        if (cacheState != CACHE_STATE_DETAIL_HYDRATED) return false
        if (detailHydratedAt <= 0L) return false
        if (remoteStaleAt > 0L) return false
        return !ContentCachePolicy.shouldRefresh(detailHydratedAt, DETAIL_REFRESH_TTL_MILLIS, now)
    }

    private fun Provider.contentCapabilityGeneration(): Int = listOf(
        serverUrl,
        stalkerMacAddress,
        stalkerAuthMode.name,
        stalkerRequestedProfileId,
        stalkerLearnedProfileId,
        stalkerAdvancedOptionsJson,
        catalogLayout.name,
        catalogLayoutDetectionVersion.toString()
    ).joinToString("\u0000").hashCode()

    private suspend fun loadCompatibilityProvider(providerId: Long): Provider? =
        providerCapabilityResolver.snapshot(providerId)?.toLegacyProvider()
}
