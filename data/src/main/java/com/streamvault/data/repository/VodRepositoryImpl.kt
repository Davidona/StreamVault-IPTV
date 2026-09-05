package com.streamvault.data.repository

import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.VodCatalogEntryDao
import com.streamvault.data.local.dao.VodCategoryHydrationDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.provider.ProviderCapabilityResolver
import com.streamvault.data.provider.TypedProviderClientFactory
import com.streamvault.data.provider.toLegacyProvider
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.sync.CatalogHydrationCommands
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.VodCatalogItem
import com.streamvault.domain.model.VodCategoryHydration
import com.streamvault.domain.model.VodCategoryHydrationRequest
import com.streamvault.domain.model.VodCategoryLoadMode
import com.streamvault.domain.model.VodSearchResult
import com.streamvault.domain.provider.CapabilityResolution
import com.streamvault.domain.repository.VodRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class VodRepositoryImpl @Inject constructor(
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val vodCategoryHydrationDao: VodCategoryHydrationDao,
    private val vodCatalogEntryDao: VodCatalogEntryDao,
    private val categoryDao: CategoryDao,
    private val preferencesRepository: PreferencesRepository,
    private val syncManager: CatalogHydrationCommands,
    private val providerCapabilityResolver: ProviderCapabilityResolver,
    private val typedProviderClientFactory: TypedProviderClientFactory
) : VodRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getCategories(providerId: Long): Flow<List<Category>> = combine(
        categoryDao.getByProviderAndType(providerId, ContentType.VOD.name),
        preferencesRepository.parentalControlLevel,
        preferencesRepository.getHiddenCategoryIds(providerId, ContentType.VOD)
    ) { entities, parentalLevel, hiddenIds ->
        entities to (parentalLevel to hiddenIds)
    }.flatMapLatest { pair ->
        val entities = pair.first
        val filters = pair.second
        val parentalLevel = filters.first
        val vodHiddenIds = filters.second
        val unified = filterUnifiedCategories(entities, parentalLevel, vodHiddenIds)
        if (unified.isNotEmpty()) {
            flowOf(unified)
        } else {
            // A UNIFIED_VOD portal that was synced before its layout flip stores the unified
            // catalog as SPLIT-era MOVIE-type categories while the account runtime reports
            // UNIFIED_VOD (the sync's own reconcileStoredCategoryTypes relabels these rows on
            // the next catalog sync). Category ids are layout-independent, so read the MOVIE
            // rows instead of rendering an empty tab until that sync runs.
            flow {
                val movieHiddenIds = preferencesRepository
                    .getHiddenCategoryIds(providerId, ContentType.MOVIE)
                    .first()
                emit(
                    filterUnifiedCategories(
                        categoryDao.getByProviderAndTypeSync(providerId, ContentType.MOVIE.name),
                        parentalLevel,
                        vodHiddenIds + movieHiddenIds
                    )
                )
            }
        }
    }

    private fun filterUnifiedCategories(
        entities: List<CategoryEntity>,
        parentalLevel: Int,
        hiddenIds: Set<Long>
    ): List<Category> = entities.asSequence()
        .filterNot { it.categoryId in hiddenIds }
        .filter { parentalLevel < 3 || (!it.isAdult && !it.isUserProtected) }
        .map { it.toDomain() }
        .toList()

    override fun getCategoryPreview(
        providerId: Long,
        categoryId: Long,
        limit: Int
    ): Flow<List<VodCatalogItem>> = flow {
        ensurePreview(providerId, categoryId)
        emitAll(observeOrderedItems(providerId, categoryId, limit))
    }

    override fun getCategoryItems(
        providerId: Long,
        categoryId: Long
    ): Flow<List<VodCatalogItem>> = observeOrderedItems(providerId, categoryId, Int.MAX_VALUE)

    override fun observeHydration(
        providerId: Long,
        categoryId: Long
    ): Flow<VodCategoryHydration?> = vodCategoryHydrationDao.observe(providerId, categoryId).map { entity ->
        entity?.let {
            VodCategoryHydration(
                lastSuccessfulPage = it.lastSuccessfulPage,
                totalPages = it.totalPages,
                advertisedTotalItems = it.advertisedTotalItems,
                advertisedTotalPages = it.advertisedTotalPages,
                pageSize = it.pageSize,
                itemCount = it.itemCount,
                isComplete = it.isComplete,
                isTruncated = it.lastStatus == "TRUNCATED",
                hasMovies = it.hasMovies,
                hasSeries = it.hasSeries,
                isLoading = it.lastStatus == "RUNNING",
                error = it.lastError
            )
        }
    }

    override suspend fun ensurePreview(
        providerId: Long,
        categoryId: Long
    ): Result<Unit> = syncManager.hydrateUnifiedVodCategory(
        providerId = providerId,
        categoryId = categoryId,
        request = VodCategoryHydrationRequest.OPEN
    )

    override suspend fun requestCategoryHydration(
        providerId: Long,
        categoryId: Long,
        request: VodCategoryHydrationRequest
    ): Result<Unit> {
        val loadMode = preferencesRepository.vodCategoryLoadMode.first()
        val effectiveRequest = if (
            request == VodCategoryHydrationRequest.OPEN && loadMode == VodCategoryLoadMode.COMPLETE_ON_OPEN
        ) VodCategoryHydrationRequest.COMPLETE else request
        val result = syncManager.hydrateUnifiedVodCategory(providerId, categoryId, effectiveRequest)
        if (result is Result.Success && request == VodCategoryHydrationRequest.OPEN &&
            loadMode == VodCategoryLoadMode.PAGED
        ) {
            repositoryScope.launch {
                syncManager.hydrateUnifiedVodCategory(
                    providerId,
                    categoryId,
                    VodCategoryHydrationRequest.NEXT_PAGE
                )
            }
        }
        return result
    }

    override suspend fun hydrateCompletely(providerId: Long, categoryId: Long): Result<Unit> =
        syncManager.hydrateUnifiedVodCategory(
            providerId = providerId,
            categoryId = categoryId,
            request = VodCategoryHydrationRequest.COMPLETE
        )

    override suspend fun searchVod(
        providerId: Long,
        query: String,
        page: Int
    ): Result<VodSearchResult> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return Result.success(VodSearchResult(emptyList(), 0, page, 0))
        }
        val provider = loadCompatibilityProvider(providerId)
        if (provider?.type != ProviderType.STALKER_PORTAL) {
            return Result.error("Portal-backed VOD search is only available for Stalker portal providers.")
        }
        val stalkerProvider = createStalkerProvider(providerId)
        return when (val result = stalkerProvider.searchVodPage(trimmedQuery, page)) {
            is Result.Success -> {
                val page = result.data
                // Portal search movies arrive transient (id == 0), exactly like every other
                // StalkerProvider VOD payload, and only get their real row id once persisted.
                // Resolve them against the local movies table before handing them to the UI:
                // otherwise every result shares stableId "movie:0" and the VOD grid crashes
                // with duplicate LazyGrid keys (and movie-detail lookups by id would fail).
                val entities = page.items.map { movie -> movie.toEntity() }.filter { it.streamId > 0L }
                if (entities.isNotEmpty()) {
                    movieDao.upsertCategoryPage(providerId, entities)
                }
                val persistedByStreamId = if (entities.isEmpty()) {
                    emptyMap()
                } else {
                    movieDao.getByStreamIds(providerId, entities.map { it.streamId })
                        .associateBy { it.streamId }
                }
                val items = page.items.mapNotNull { movie ->
                    persistedByStreamId[movie.streamId]?.toDomain()?.let(VodCatalogItem::MovieItem)
                }
                Result.success(
                    VodSearchResult(
                        items = items,
                        totalCount = page.advertisedTotalItems ?: page.items.size,
                        page = page.page,
                        pageSize = page.pageSize
                    )
                )
            }
            is Result.Error -> Result.error(result.message, result.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private suspend fun loadCompatibilityProvider(providerId: Long): Provider? =
        providerCapabilityResolver.snapshot(providerId)?.toLegacyProvider()

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

    private fun observeOrderedItems(
        providerId: Long,
        categoryId: Long,
        limit: Int
    ): Flow<List<VodCatalogItem>> = vodCatalogEntryDao
        .observeByCategory(providerId, categoryId)
        .flatMapLatest { entries ->
            if (entries.isEmpty()) return@flatMapLatest flowOf(emptyList())
            val movieIds = entries.filter { it.itemType == ContentType.MOVIE }.map { it.targetId }.distinct()
            val seriesIds = entries.filter { it.itemType == ContentType.SERIES }.map { it.targetId }.distinct()
            combine(
                if (movieIds.isEmpty()) flowOf(emptyList()) else movieDao.observeByStreamIds(providerId, movieIds),
                if (seriesIds.isEmpty()) flowOf(emptyList()) else seriesDao.observeBySeriesIds(providerId, seriesIds)
            ) { movies, series ->
                val moviesById = movies.associateBy { it.streamId }
                val seriesById = series.associateBy { it.seriesId }
                entries.asSequence().mapNotNull { entry ->
                    when (entry.itemType) {
                        ContentType.MOVIE -> moviesById[entry.targetId]?.toDomain()?.let(VodCatalogItem::MovieItem)
                        ContentType.SERIES -> seriesById[entry.targetId]?.toDomain()?.let(VodCatalogItem::SeriesItem)
                        else -> null
                    }
                }.take(limit).toList()
            }
        }
}
