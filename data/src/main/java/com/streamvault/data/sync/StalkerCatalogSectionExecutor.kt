package com.streamvault.data.sync

import com.streamvault.data.local.dao.MovieCategoryHydrationDao
import com.streamvault.data.local.dao.SeriesCategoryHydrationDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.XtreamContentIndexDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.sync.Section
import kotlinx.coroutines.flow.first

/** Owns Stalker movie/series repair setup and durable index continuation handoff. */
internal class StalkerCatalogSectionExecutor(
    private val movieCategoryHydrationDao: MovieCategoryHydrationDao,
    private val seriesCategoryHydrationDao: SeriesCategoryHydrationDao,
    private val xtreamContentIndexDao: XtreamContentIndexDao,
    private val syncCatalogStore: SyncCatalogStore,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val createProvider: (Provider) -> StalkerProvider,
    private val updateIndexJob: suspend (Long, ContentType, Int, Long) -> Unit,
    private val emitSectionProgress: (Long, Section, Int, Int) -> Unit,
    private val progress: (Long, ((String) -> Unit)?, String) -> Unit,
    private val hasPendingIndex: suspend (Long, ContentType) -> Boolean = { _, _ -> false },
    private val invalidateUnifiedVod: suspend (Long) -> Unit = {}
) {
    suspend fun queueIndexSection(
        providerId: Long,
        contentType: ContentType,
        totalCategories: Int,
        now: Long
    ) {
        require(contentType == ContentType.MOVIE || contentType == ContentType.SERIES) {
            "Unsupported Stalker index section: $contentType"
        }
        when (contentType) {
            ContentType.MOVIE -> movieCategoryHydrationDao.deleteByProvider(providerId)
            ContentType.SERIES -> seriesCategoryHydrationDao.deleteByProvider(providerId)
            else -> Unit
        }
        xtreamContentIndexDao.markRowsStaleForProviderAndType(providerId, contentType.name)
        updateIndexJob(providerId, contentType, totalCategories, now)
    }

    suspend fun syncMovies(
        provider: Provider,
        onProgress: ((String) -> Unit)?,
        preservePendingIndex: Boolean = false,
        indexCatalog: Boolean = true
    ): SyncOutcome {
        if (indexCatalog && preservePendingIndex && hasPendingIndex(provider.id, ContentType.MOVIE)) {
            progress(provider.id, onProgress, "Resuming Movies index...")
            return queuedOutcome(ContentType.MOVIE)
        }
        val now = System.currentTimeMillis()
        progress(provider.id, onProgress, "Queueing Movies index...")
        val api = createProvider(provider)
        val unified = provider.catalogLayout == com.streamvault.domain.model.CatalogLayout.UNIFIED_VOD
        val categoryType = if (unified) ContentType.VOD else ContentType.MOVIE
        val categories = requireResult(if (unified) api.getUnifiedVodCategories() else api.getVodCategories(), "Failed to load movie categories")
        emitSectionProgress(
            provider.id,
            Section.VOD,
            categories.size,
            movieDao.getCount(provider.id).first()
        )
        syncCatalogStore.replaceCategories(
            providerId = provider.id,
            type = categoryType.name,
            categories = categories.map { category ->
                CategoryEntity(
                    providerId = provider.id,
                    categoryId = category.id,
                    name = category.name,
                    parentId = category.parentId,
                    type = categoryType,
                    isAdult = category.isAdult
                )
            }
        )
        if (!indexCatalog) {
            // Refresh category contents lazily, retaining cached rows until replacement.
            movieCategoryHydrationDao.deleteByProvider(provider.id)
            if (unified) invalidateUnifiedVod(provider.id)
            val current = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
            syncMetadataRepository.updateMetadata(current.copy(lastMovieSync = now, lastMovieSuccess = now))
            return SyncOutcome(activation = SyncActivation.ACTIVATED_CATALOG)
        }
        if (unified) invalidateUnifiedVod(provider.id)
        queueIndexSection(provider.id, ContentType.MOVIE, categories.size, now)
        val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
            .copy(
                lastMovieAttempt = now,
                movieCount = movieDao.getCount(provider.id).first(),
                movieSyncMode = VodSyncMode.PAGED,
                movieWarningsCount = 0,
                movieCatalogStale = true
            )
        syncMetadataRepository.updateMetadata(metadata)
        return SyncOutcome(
            continuationWork = listOf(
                SyncContinuation(
                    operation = SyncContinuationOperation.INDEX_CATALOG,
                    section = ContentType.MOVIE,
                    reason = "movie category shell is committed; durable item indexing is queued"
                )
            ),
            activation = SyncActivation.DEFERRED_TO_FOLLOW_UP
        )
    }

    suspend fun syncSeries(
        provider: Provider,
        onProgress: ((String) -> Unit)?,
        preservePendingIndex: Boolean = false,
        indexCatalog: Boolean = true
    ): SyncOutcome {
        if (indexCatalog && preservePendingIndex && hasPendingIndex(provider.id, ContentType.SERIES)) {
            progress(provider.id, onProgress, "Resuming Series index...")
            return queuedOutcome(ContentType.SERIES)
        }
        progress(provider.id, onProgress, "Queueing Series index...")
        val api = createProvider(provider)
        val categories = requireResult(api.getSeriesCategories(), "Failed to load series categories")
        emitSectionProgress(
            provider.id,
            Section.SERIES,
            categories.size,
            seriesDao.getCount(provider.id).first()
        )
        syncCatalogStore.replaceCategories(
            providerId = provider.id,
            type = ContentType.SERIES.name,
            categories = categories.map { category ->
                CategoryEntity(
                    providerId = provider.id,
                    categoryId = category.id,
                    name = category.name,
                    parentId = category.parentId,
                    type = ContentType.SERIES,
                    isAdult = category.isAdult
                )
            }
        )
        val now = System.currentTimeMillis()
        if (!indexCatalog) {
            seriesCategoryHydrationDao.deleteByProvider(provider.id)
            val current = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
            syncMetadataRepository.updateMetadata(current.copy(lastSeriesSync = now, lastSeriesSuccess = now))
            return SyncOutcome(activation = SyncActivation.ACTIVATED_CATALOG)
        }
        queueIndexSection(provider.id, ContentType.SERIES, categories.size, now)
        val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
            .copy(
                seriesCount = seriesDao.getCount(provider.id).first()
            )
        syncMetadataRepository.updateMetadata(metadata)
        return SyncOutcome(
            continuationWork = listOf(
                SyncContinuation(
                    operation = SyncContinuationOperation.INDEX_CATALOG,
                    section = ContentType.SERIES,
                    reason = "series category shell is committed; durable item indexing is queued"
                )
            ),
            activation = SyncActivation.DEFERRED_TO_FOLLOW_UP
        )
    }

    private fun <T> requireResult(result: Result<T>, fallbackMessage: String): T = when (result) {
        is Result.Success -> result.data
        is Result.Error -> throw IllegalStateException(result.message.ifBlank { fallbackMessage }, result.exception)
        is Result.Loading -> throw IllegalStateException("Unexpected loading state")
    }

    private fun queuedOutcome(section: ContentType) = SyncOutcome(
        continuationWork = listOf(SyncContinuation(
            operation = SyncContinuationOperation.INDEX_CATALOG,
            section = section,
            reason = "resume the existing durable index without resetting its hydration progress"
        )),
        activation = SyncActivation.DEFERRED_TO_FOLLOW_UP
    )
}
