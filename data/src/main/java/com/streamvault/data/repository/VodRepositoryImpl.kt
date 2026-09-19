package com.streamvault.data.repository

import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.VodCatalogEntryDao
import com.streamvault.data.local.dao.VodCategoryHydrationDao
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.provider.ProviderCapabilityResolver
import com.streamvault.data.provider.TypedProviderClientFactory
import com.streamvault.data.sync.CatalogHydrationCommands
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
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
    private val capabilityResolver: ProviderCapabilityResolver,
    private val typedProviderClientFactory: TypedProviderClientFactory
) : VodRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getCategories(providerId: Long): Flow<List<Category>> = combine(
        categoryDao.getByProviderAndType(providerId, ContentType.VOD.name),
        preferencesRepository.parentalControlLevel,
        preferencesRepository.getHiddenCategoryIds(providerId, ContentType.VOD)
    ) { entities, parentalLevel, vodHiddenIds ->
        entities to (parentalLevel to vodHiddenIds)
    }.flatMapLatest { (entities, filters) ->
        val parentalLevel = filters.first
        val vodHiddenIds = filters.second
        val unified = filterUnifiedCategories(entities, parentalLevel, vodHiddenIds)
        if (unified.isNotEmpty()) {
            flowOf(unified)
        } else {
            // A UNIFIED_VOD portal may still have SPLIT-era MOVIE rows until the next sync
            // reconciles their type. Category ids are layout-independent, so keep the VOD
            // surface usable while that durable repair catches up.
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
        entities: List<com.streamvault.data.local.entity.CategoryEntity>,
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
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return Result.success(
            VodSearchResult(emptyList(), totalCount = 0, page = page, pageSize = 0, hasMore = false)
        )
        if (page < 1) return Result.error("VOD search page must be positive")

        val snapshot = capabilityResolver.snapshot(providerId)
            ?: return Result.error("Provider $providerId has no typed configuration")
        if (snapshot.provider.type != com.streamvault.domain.model.ProviderType.STALKER_PORTAL) {
            return Result.error("Portal search is only available for Stalker providers")
        }
        val resolution = try {
            typedProviderClientFactory.stalker(snapshot)
        } catch (error: Throwable) {
            return Result.error(error.message ?: "Provider configuration failed", error)
        }
        val provider = when (resolution) {
            is CapabilityResolution.Available -> resolution.capability
            is CapabilityResolution.ConfigurationError -> return Result.error(resolution.reason)
            is CapabilityResolution.Unsupported -> return Result.error(resolution.reason)
            is CapabilityResolution.Restricted -> return Result.error(resolution.reason)
        }

        return when (val remote = runCatching { provider.searchVodPage(normalizedQuery, page) }.getOrElse {
            Result.error(it.message ?: "VOD search failed", it)
        }) {
            is Result.Success -> {
                val visibleItems = filterVisibleSearchItems(
                    providerId,
                    remote.data.items.map { it.item }
                )
                val persistedItems = persistSearchItems(providerId, visibleItems)
                Result.success(
                    VodSearchResult(
                        items = persistedItems,
                        totalCount = remote.data.advertisedTotalItems ?: remote.data.items.size,
                        page = remote.data.page,
                        pageSize = remote.data.pageSize,
                        hasMore = !remote.data.isComplete
                    )
                )
            }
            is Result.Error -> Result.error(remote.message, remote.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private suspend fun filterVisibleSearchItems(
        providerId: Long,
        items: List<VodCatalogItem>
    ): List<VodCatalogItem> {
        val parentalLevel = preferencesRepository.parentalControlLevel.first()
        val hiddenCategoryIds = preferencesRepository
            .getHiddenCategoryIds(providerId, ContentType.VOD)
            .first()
        return items.filter { item ->
            when (item) {
                is VodCatalogItem.MovieItem -> item.movie.categoryId !in hiddenCategoryIds &&
                    (parentalLevel < 3 || (!item.movie.isAdult && !item.movie.isUserProtected))
                is VodCatalogItem.SeriesItem -> item.series.categoryId !in hiddenCategoryIds &&
                    (parentalLevel < 3 || (!item.series.isAdult && !item.series.isUserProtected))
            }
        }
    }

    private suspend fun persistSearchItems(
        providerId: Long,
        items: List<VodCatalogItem>
    ): List<VodCatalogItem> {
        val movies = items.filterIsInstance<VodCatalogItem.MovieItem>().map { it.movie }
        val persistedMovieIds = mutableMapOf<Long, Long>()
        if (movies.isNotEmpty()) {
            val existing = movieDao.getByStreamIds(providerId, movies.map { it.streamId })
                .associateBy { it.streamId }
            movieDao.upsertCategoryPage(
                providerId,
                movies.map { movie ->
                    val current = existing[movie.streamId]
                    movie.toEntity().copy(
                        id = current?.id ?: movie.id.takeIf { it > 0 } ?: 0L,
                        watchProgress = current?.watchProgress ?: movie.watchProgress,
                        lastWatchedAt = current?.lastWatchedAt ?: movie.lastWatchedAt,
                        isUserProtected = current?.isUserProtected ?: movie.isUserProtected
                    )
                }
            )
            movieDao.getByStreamIds(providerId, movies.map { it.streamId })
                .forEach { persistedMovieIds[it.streamId] = it.id }
        }
        val series = items.filterIsInstance<VodCatalogItem.SeriesItem>().map { it.series }
        val persistedSeriesIds = mutableMapOf<Long, Long>()
        if (series.isNotEmpty()) seriesDao.upsertCategoryPage(
            providerId,
            series.map { item -> item.toEntity() }
        )
        if (series.isNotEmpty()) {
            seriesDao.getBySeriesIds(providerId, series.map { it.seriesId })
                .forEach { persistedSeriesIds[it.seriesId] = it.id }
        }
        return items.map { item ->
            when (item) {
                is VodCatalogItem.MovieItem -> item.copy(
                    movie = item.movie.copy(id = persistedMovieIds[item.movie.streamId] ?: item.movie.id)
                )
                is VodCatalogItem.SeriesItem -> item.copy(
                    series = item.series.copy(id = persistedSeriesIds[item.series.seriesId] ?: item.series.id)
                )
            }
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
