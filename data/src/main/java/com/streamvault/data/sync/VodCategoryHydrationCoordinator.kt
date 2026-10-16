package com.streamvault.data.sync

import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.MovieCategoryHydrationDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesCategoryHydrationDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.VodCategoryHydrationDao
import com.streamvault.data.local.dao.VodCatalogEntryDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.MovieCategoryHydrationEntity
import com.streamvault.data.local.entity.SeriesCategoryHydrationEntity
import com.streamvault.data.local.entity.VodCategoryHydrationEntity
import com.streamvault.data.local.entity.VodCatalogEntryEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SeriesCatalogOrigin
import com.streamvault.domain.model.VodCatalogItem
import com.streamvault.domain.model.VodCategoryHydrationRequest
import kotlinx.coroutines.flow.first

private const val STALKER_COMPLETE_PAGE_BATCH_SIZE = 200
private const val STALKER_CATEGORY_RETRY_BUDGET = 3

/**
 * Owns interactive Stalker VOD category hydration and its page-level commit rules.
 *
 * The manager still exposes the historical methods used by repositories, but it no longer
 * owns category paging, derived-series protection, or hydration checkpoint writes.
 */
internal class VodCategoryHydrationCoordinator(
    private val providerSyncLocks: ProviderSyncLockRegistry,
    private val categoryDao: CategoryDao,
    private val movieCategoryHydrationDao: MovieCategoryHydrationDao,
    private val seriesCategoryHydrationDao: SeriesCategoryHydrationDao,
    private val vodCategoryHydrationDao: VodCategoryHydrationDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val vodCatalogEntryDao: VodCatalogEntryDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val loadProvider: suspend (Long) -> Provider?,
    private val createProvider: (Provider) -> StalkerProvider
) {
    suspend fun hydrateUnifiedVodCategory(
        providerId: Long,
        categoryId: Long,
        request: VodCategoryHydrationRequest
    ): Result<Unit> {
        val requestedPage = if (request == VodCategoryHydrationRequest.NEXT_PAGE) {
            ((vodCategoryHydrationDao.get(providerId, categoryId)?.lastSuccessfulPage ?: 0) + 1)
                .coerceAtLeast(1)
        } else {
            null
        }
        return providerSyncLocks.withVodCategoryLock(
            providerId = providerId,
            categoryId = categoryId,
            splitCatalog = false
        ) {
            val provider = loadProvider(providerId)
                ?: return@withVodCategoryLock Result.error("Provider not found")
            if (provider.type != ProviderType.STALKER_PORTAL) {
                return@withVodCategoryLock Result.success(Unit)
            }
            val current = vodCategoryHydrationDao.get(providerId, categoryId)
            if (current?.isComplete == true ||
                (requestedPage != null && current != null && current.lastSuccessfulPage >= requestedPage) ||
                (request == VodCategoryHydrationRequest.OPEN && (current?.lastSuccessfulPage ?: 0) > 0)
            ) {
                return@withVodCategoryLock Result.success(Unit)
            }

            val api = createProvider(provider)
            var hydration = current
            var nextPage = ((current?.lastSuccessfulPage ?: 0) + 1).coerceAtLeast(1)
            var remotePagesRequested = 0
            val seenPageFingerprints = mutableSetOf<String>()
            while (true) {
                val attemptAt = System.currentTimeMillis()
                if (request == VodCategoryHydrationRequest.COMPLETE &&
                    remotePagesRequested >= STALKER_COMPLETE_PAGE_BATCH_SIZE
                ) {
                    break
                }
                remotePagesRequested += 1
                vodCategoryHydrationDao.upsert(
                    (hydration ?: VodCategoryHydrationEntity(providerId, categoryId)).copy(
                        lastAttemptedPage = nextPage,
                        lastStatus = "RUNNING",
                        lastError = null
                    )
                )
                when (val pageResult = api.getUnifiedVodPage(categoryId, nextPage)) {
                    is Result.Success -> {
                        val page = pageResult.data
                        if (
                            hydration?.advertisedTotalPages != null &&
                            page.advertisedTotalPages != null &&
                            hydration?.advertisedTotalPages != page.advertisedTotalPages
                        ) {
                            val prior = hydration ?: VodCategoryHydrationEntity(providerId, categoryId)
                            hydration = prior.copy(
                                lastAttemptedPage = page.page,
                                lastStatus = "ANOMALY",
                                lastError = "Portal changed its advertised catalog page count while loading.",
                                retryAfterMs = 0L
                            )
                            vodCategoryHydrationDao.upsert(hydration!!)
                            return@withVodCategoryLock Result.error(
                                hydration!!.lastError ?: "Portal changed its advertised page count"
                            )
                        }
                        val pageFingerprint = page.items.joinToString("|") { it.rawItemId }
                            .takeIf(String::isNotEmpty)
                        if (hasRepeatedPageFingerprint(
                                seenFingerprints = seenPageFingerprints,
                                previousFingerprint = hydration?.lastPageFingerprint,
                                currentFingerprint = pageFingerprint
                            )
                        ) {
                            val prior = hydration ?: VodCategoryHydrationEntity(providerId, categoryId)
                            hydration = prior.copy(
                                lastAttemptedPage = page.page,
                                lastStatus = "ANOMALY",
                                lastError = "Portal repeated a catalog page while loading page ${page.page}.",
                                retryAfterMs = 0L
                            )
                            vodCategoryHydrationDao.upsert(hydration!!)
                            return@withVodCategoryLock Result.error(
                                hydration!!.lastError ?: "Portal repeated a catalog page"
                            )
                        }
                        val movies = page.items.mapNotNull { entry ->
                            (entry.item as? VodCatalogItem.MovieItem)?.movie
                                ?.toEntity()
                        }
                        val series = page.items.mapNotNull { entry ->
                            (entry.item as? VodCatalogItem.SeriesItem)?.series
                                ?.toEntity()
                        }
                        val entries = page.items.mapIndexed { rawIndex, entry ->
                            when (val item = entry.item) {
                                is VodCatalogItem.MovieItem -> VodCatalogEntryEntity(
                                    providerId = providerId,
                                    categoryId = categoryId,
                                    rawItemId = entry.rawItemId,
                                    itemType = ContentType.MOVIE,
                                    targetId = item.movie.streamId,
                                    rawPage = page.page,
                                    rawIndex = rawIndex
                                )
                                is VodCatalogItem.SeriesItem -> VodCatalogEntryEntity(
                                    providerId = providerId,
                                    categoryId = categoryId,
                                    rawItemId = entry.rawItemId,
                                    itemType = ContentType.SERIES,
                                    targetId = item.series.seriesId,
                                    rawPage = page.page,
                                    rawIndex = rawIndex
                                )
                            }
                        }
                        val pageLimitReached = request == VodCategoryHydrationRequest.COMPLETE &&
                            remotePagesRequested >= STALKER_COMPLETE_PAGE_BATCH_SIZE &&
                            !page.isComplete
                        val truncated = page.isTruncated || pageLimitReached
                        val pageComplete = page.isComplete && !truncated
                        val terminationReason = page.terminationReason
                            ?: "page_limit".takeIf { pageLimitReached }
                        transactionRunner.inTransaction {
                            // Protection can be edited while the network request is in flight.
                            // Read just this category at commit time so a long COMPLETE request
                            // cannot apply a stale cached flag and the page/checkpoint stay atomic.
                            val protected = categoryDao.getByProviderTypeAndCategoryId(
                                providerId = providerId,
                                type = ContentType.VOD.name,
                                categoryId = categoryId
                            )?.isUserProtected == true
                            movieDao.upsertCategoryPage(
                                providerId,
                                movies.map { it.copy(isUserProtected = protected) }
                            )
                            seriesDao.upsertCategoryPage(
                                providerId,
                                series.map { it.copy(isUserProtected = protected) }
                            )
                            vodCatalogEntryDao.replacePage(providerId, categoryId, page.page, entries)
                            val persistedCount = vodCatalogEntryDao.countByCategory(providerId, categoryId)
                            hydration = VodCategoryHydrationEntity(
                                providerId = providerId,
                                categoryId = categoryId,
                                lastLoadedPage = page.page,
                                lastAttemptedPage = page.page,
                                lastSuccessfulPage = page.page,
                                totalPages = page.totalPages,
                                advertisedTotalItems = page.advertisedTotalItems,
                                advertisedTotalPages = page.advertisedTotalPages,
                                pageSize = page.pageSize,
                                itemCount = persistedCount,
                                isComplete = pageComplete,
                                hasMovies = (hydration?.hasMovies == true) || movies.isNotEmpty(),
                                hasSeries = (hydration?.hasSeries == true) || series.isNotEmpty(),
                                lastHydratedAt = attemptAt,
                                lastStatus = if (truncated) "TRUNCATED" else "SUCCESS",
                                lastError = terminationReason,
                                retryAfterMs = 0L,
                                failureCount = 0,
                                retryBudgetRemaining = STALKER_CATEGORY_RETRY_BUDGET,
                                lastPageFingerprint = pageFingerprint
                            )
                            vodCategoryHydrationDao.upsert(hydration!!)
                        }
                        if (truncated) {
                            return@withVodCategoryLock Result.error(
                                terminationReason ?: "VOD response was truncated"
                            )
                        }
                        if (pageComplete || request != VodCategoryHydrationRequest.COMPLETE) {
                            return@withVodCategoryLock Result.success(Unit)
                        }
                        nextPage = page.page + 1
                    }
                    is Result.Error -> {
                        val prior = hydration ?: VodCategoryHydrationEntity(providerId, categoryId)
                        hydration = prior.copy(
                            lastAttemptedPage = nextPage,
                            lastStatus = "FAILED_RETRYABLE",
                            lastError = pageResult.message,
                            failureCount = prior.failureCount + 1,
                            retryBudgetRemaining = (prior.retryBudgetRemaining - 1).coerceAtLeast(0)
                        )
                        vodCategoryHydrationDao.upsert(hydration!!)
                        return@withVodCategoryLock Result.error(pageResult.message, pageResult.exception)
                    }
                    Result.Loading -> return@withVodCategoryLock Result.error("VOD hydration did not complete")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Result.success(Unit)
        }
    }

    suspend fun hydrateSplitVodCategory(
        providerId: Long,
        movieCategoryId: Long,
        request: VodCategoryHydrationRequest,
        requestedProjection: ContentType = ContentType.MOVIE
    ): Result<Unit> {
        val requestedPage = if (request == VodCategoryHydrationRequest.NEXT_PAGE) {
            ((movieCategoryHydrationDao.get(providerId, movieCategoryId)?.lastSuccessfulPage ?: 0) + 1)
                .coerceAtLeast(1)
        } else {
            null
        }
        return providerSyncLocks.withVodCategoryLock(
            providerId = providerId,
            categoryId = movieCategoryId,
            splitCatalog = true
        ) {
            val provider = loadProvider(providerId)
                ?: return@withVodCategoryLock Result.error("Provider not found")
            if (provider.type != ProviderType.STALKER_PORTAL || provider.catalogLayout != CatalogLayout.SPLIT) {
                return@withVodCategoryLock Result.success(Unit)
            }
            var hydration = movieCategoryHydrationDao.get(providerId, movieCategoryId)
            var movieCount = movieDao.getCountByCategory(providerId, movieCategoryId).first()
            if (hydration?.isComplete == true ||
                (requestedPage != null && hydration != null && hydration.lastSuccessfulPage >= requestedPage) ||
                (request == VodCategoryHydrationRequest.OPEN && (hydration?.lastSuccessfulPage ?: 0) > 0)
            ) {
                return@withVodCategoryLock Result.success(Unit)
            }
            val api = createProvider(provider)
            val movieCategory = categoryDao.getByProviderAndTypeSync(providerId, ContentType.MOVIE.name)
                .firstOrNull { it.categoryId == movieCategoryId }
                ?: return@withVodCategoryLock Result.error("Movie category not found")
            // Populate the provider's category identity cache before translating it to Series.
            api.getVodCategories()
            val seriesCategoryId = api.projectVodCategoryToSeries(movieCategoryId)
                ?: return@withVodCategoryLock Result.error("Unable to resolve VOD-derived Series category")
            val initialProjectionCount = when (requestedProjection) {
                ContentType.SERIES -> seriesDao.getCountByCategory(providerId, seriesCategoryId).first()
                else -> movieCount
            }
            var nextPage = ((hydration?.lastSuccessfulPage ?: 0) + 1).coerceAtLeast(1)
            var remotePagesRequested = 0
            val seenPageFingerprints = mutableSetOf<String>()
            while (true) {
                val attemptAt = System.currentTimeMillis()
                if (request == VodCategoryHydrationRequest.COMPLETE &&
                    remotePagesRequested >= STALKER_COMPLETE_PAGE_BATCH_SIZE
                ) {
                    break
                }
                remotePagesRequested += 1
                movieCategoryHydrationDao.upsert(
                    (hydration ?: MovieCategoryHydrationEntity(providerId, movieCategoryId)).copy(
                        lastAttemptedPage = nextPage,
                        lastStatus = "RUNNING",
                        lastError = null
                    )
                )
                seriesCategoryHydrationDao.get(providerId, seriesCategoryId)?.let { seriesHydration ->
                    seriesCategoryHydrationDao.upsert(
                        seriesHydration.copy(
                            lastAttemptedPage = nextPage,
                            lastStatus = "RUNNING",
                            lastError = null
                        )
                    )
                }
                when (val pageResult = api.getSplitVodPage(movieCategoryId, seriesCategoryId, nextPage)) {
                    is Result.Success -> {
                        val page = pageResult.data
                        if (
                            hydration?.advertisedTotalPages != null &&
                            page.advertisedTotalPages != null &&
                            hydration?.advertisedTotalPages != page.advertisedTotalPages
                        ) {
                            val prior = hydration ?: MovieCategoryHydrationEntity(providerId, movieCategoryId)
                            hydration = prior.copy(
                                lastAttemptedPage = page.page,
                                lastStatus = "ANOMALY",
                                lastError = "Portal changed its advertised catalog page count while loading.",
                                retryAfterMs = 0L
                            )
                            movieCategoryHydrationDao.upsert(hydration!!)
                            return@withVodCategoryLock Result.error(
                                hydration!!.lastError ?: "Portal changed its advertised page count"
                            )
                        }
                        val pageFingerprint = page.items.joinToString("|") { it.rawItemId }
                            .takeIf(String::isNotEmpty)
                        if (hasRepeatedPageFingerprint(
                                seenFingerprints = seenPageFingerprints,
                                previousFingerprint = hydration?.lastPageFingerprint,
                                currentFingerprint = pageFingerprint
                            )
                        ) {
                            val prior = hydration ?: MovieCategoryHydrationEntity(providerId, movieCategoryId)
                            hydration = prior.copy(
                                lastAttemptedPage = page.page,
                                lastStatus = "ANOMALY",
                                lastError = "Portal repeated a catalog page while loading page ${page.page}.",
                                retryAfterMs = 0L
                            )
                            movieCategoryHydrationDao.upsert(hydration!!)
                            return@withVodCategoryLock Result.error(
                                hydration!!.lastError ?: "Portal repeated a catalog page"
                            )
                        }
                        val movies = page.items.mapNotNull { (it.item as? VodCatalogItem.MovieItem)?.movie?.toEntity() }
                        val incomingSeries = page.items.mapNotNull {
                            (it.item as? VodCatalogItem.SeriesItem)?.series
                                ?.copy(isUserProtected = movieCategory.isUserProtected)
                                ?.toEntity()
                        }
                        val pageLimitReached = request == VodCategoryHydrationRequest.COMPLETE &&
                            remotePagesRequested >= STALKER_COMPLETE_PAGE_BATCH_SIZE &&
                            !page.isComplete
                        val truncated = page.isTruncated || pageLimitReached
                        val pageComplete = page.isComplete && !truncated
                        val terminationReason = page.terminationReason
                            ?: "page_limit".takeIf { pageLimitReached }
                        val existingSeriesHydration = seriesCategoryHydrationDao.get(providerId, seriesCategoryId)
                        transactionRunner.inTransaction {
                            movieDao.upsertCategoryPage(providerId, movies)
                            if (incomingSeries.isNotEmpty()) {
                                categoryDao.insertAll(
                                    listOf(
                                        CategoryEntity(
                                            providerId = providerId,
                                            categoryId = seriesCategoryId,
                                            name = movieCategory.name,
                                            parentId = movieCategory.parentId,
                                            type = ContentType.SERIES,
                                            providerOrder = movieCategory.providerOrder,
                                            isAdult = movieCategory.isAdult,
                                            isUserProtected = movieCategory.isUserProtected
                                        )
                                    )
                                )
                            }
                            // Check inside the same transaction as the write so a concurrent
                            // native-Series refresh can never be replaced by derived VOD data.
                            val existingSeries = seriesDao.getBySeriesIds(
                                providerId,
                                incomingSeries.map { it.seriesId }
                            ).associateBy { it.seriesId }
                            val derivedSeries = incomingSeries.filter { incoming ->
                                existingSeries[incoming.seriesId]?.catalogOrigin != SeriesCatalogOrigin.NATIVE
                            }
                            seriesDao.upsertCategoryPage(providerId, derivedSeries)
                            movieCount = movieDao.getCountByCategory(providerId, movieCategoryId).first()
                            val seriesCount = seriesDao.getCountByCategory(providerId, seriesCategoryId).first()
                            hydration = MovieCategoryHydrationEntity(
                                providerId = providerId,
                                categoryId = movieCategoryId,
                                lastHydratedAt = attemptAt,
                                itemCount = movieCount,
                                lastStatus = if (truncated) "TRUNCATED" else "SUCCESS",
                                lastError = terminationReason,
                                lastLoadedPage = page.page,
                                lastAttemptedPage = page.page,
                                lastSuccessfulPage = page.page,
                                totalPages = page.totalPages,
                                advertisedTotalItems = page.advertisedTotalItems,
                                advertisedTotalPages = page.advertisedTotalPages,
                                isComplete = pageComplete,
                                pageSize = page.pageSize,
                                retryBudgetRemaining = STALKER_CATEGORY_RETRY_BUDGET,
                                lastPageFingerprint = pageFingerprint
                            )
                            movieCategoryHydrationDao.upsert(hydration!!)
                            if (incomingSeries.isNotEmpty() || existingSeriesHydration != null) {
                                seriesCategoryHydrationDao.upsert(
                                    SeriesCategoryHydrationEntity(
                                        providerId = providerId,
                                        categoryId = seriesCategoryId,
                                        lastHydratedAt = attemptAt,
                                        itemCount = seriesCount,
                                        lastStatus = if (truncated) "TRUNCATED" else "SUCCESS",
                                        lastError = terminationReason,
                                        lastLoadedPage = page.page,
                                        lastAttemptedPage = page.page,
                                        lastSuccessfulPage = page.page,
                                        totalPages = page.totalPages,
                                        advertisedTotalItems = page.advertisedTotalItems,
                                        advertisedTotalPages = page.advertisedTotalPages,
                                        isComplete = pageComplete,
                                        pageSize = page.pageSize,
                                        retryBudgetRemaining = STALKER_CATEGORY_RETRY_BUDGET,
                                        lastPageFingerprint = pageFingerprint
                                    )
                                )
                            }
                        }
                        if (truncated) {
                            return@withVodCategoryLock Result.error(
                                terminationReason ?: "VOD response was truncated"
                            )
                        }
                        val projectionCount = when (requestedProjection) {
                            ContentType.SERIES -> seriesDao.getCountByCategory(providerId, seriesCategoryId).first()
                            else -> movieCount
                        }
                        if (pageComplete ||
                            (request != VodCategoryHydrationRequest.COMPLETE &&
                                projectionCount > initialProjectionCount)
                        ) {
                            return@withVodCategoryLock Result.success(Unit)
                        }
                        nextPage = page.page + 1
                    }
                    is Result.Error -> {
                        val prior = hydration ?: MovieCategoryHydrationEntity(providerId, movieCategoryId)
                        movieCategoryHydrationDao.upsert(
                            prior.copy(
                                lastAttemptedPage = nextPage,
                                lastStatus = "FAILED_RETRYABLE",
                                lastError = pageResult.message,
                                failureCount = prior.failureCount + 1,
                                retryBudgetRemaining = (prior.retryBudgetRemaining - 1).coerceAtLeast(0)
                            )
                        )
                        seriesCategoryHydrationDao.get(providerId, seriesCategoryId)?.let { seriesHydration ->
                            seriesCategoryHydrationDao.upsert(
                                seriesHydration.copy(
                                    lastAttemptedPage = nextPage,
                                    lastStatus = "FAILED_RETRYABLE",
                                    lastError = pageResult.message,
                                    failureCount = seriesHydration.failureCount + 1,
                                    retryBudgetRemaining = (seriesHydration.retryBudgetRemaining - 1).coerceAtLeast(0)
                                )
                            )
                        }
                        return@withVodCategoryLock Result.error(pageResult.message, pageResult.exception)
                    }
                    Result.Loading -> return@withVodCategoryLock Result.error("Split VOD hydration did not complete")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Result.success(Unit)
        }
    }

    suspend fun hydrateSplitVodSeriesCategory(
        providerId: Long,
        seriesCategoryId: Long,
        request: VodCategoryHydrationRequest
    ): Result<Unit> {
        val provider = loadProvider(providerId) ?: return Result.error("Provider not found")
        if (provider.type != ProviderType.STALKER_PORTAL || provider.catalogLayout != CatalogLayout.SPLIT) {
            return Result.success(Unit)
        }
        val api = createProvider(provider)
        api.getSeriesCategories()
        val movieCategoryId = api.projectSeriesCategoryToVod(seriesCategoryId)
            ?: return Result.error("Unable to resolve VOD category for derived Series")
        return hydrateSplitVodCategory(providerId, movieCategoryId, request, ContentType.SERIES)
    }
}

private fun hasRepeatedPageFingerprint(
    seenFingerprints: MutableSet<String>,
    previousFingerprint: String?,
    currentFingerprint: String?
): Boolean {
    if (currentFingerprint == null) return false
    return previousFingerprint == currentFingerprint || !seenFingerprints.add(currentFingerprint)
}
