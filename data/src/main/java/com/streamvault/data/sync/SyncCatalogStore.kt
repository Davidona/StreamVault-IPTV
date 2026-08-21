package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.TmdbIdentityDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.CategoryImportStageEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ChannelImportStageEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.MovieImportStageEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.SeriesImportStageEntity
import com.streamvault.data.local.entity.TmdbIdentityEntity
import com.streamvault.data.remote.xtream.XtreamStreamKind
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.ContentType
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.random.Random

internal class SyncCatalogStore(
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val categoryDao: CategoryDao,
    private val catalogSyncDao: CatalogSyncDao,
    private val tmdbIdentityDao: TmdbIdentityDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val workflowCommitFence: ProviderWorkflowCommitFence = ProviderWorkflowCommitFence(),
    private val sizeLimits: CatalogSizeLimits = CatalogSizeLimits()
) {
    companion object {
        private const val TAG = "SyncCatalogStore"
        private const val STAGE_BATCH_SIZE = 500
    }

    data class StagedLiveImportState(
        val sessionId: Long,
        val channelCount: Int,
        val movieCount: Int,
        val seriesCount: Int,
        val categories: List<CategoryEntity>
    )

    /**
     * Result returned from staged-import helpers that track both the accepted count and
     * whether the size limit was hit, so callers can choose between full-replace and
     * upsert-only commit semantics.
     */
    private data class StagingResult(val acceptedCount: Int, val overflowed: Boolean)

    private val sessionIds = AtomicLong(Random.nextLong(1L, Long.MAX_VALUE))
    private val digest = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    fun newSessionId(): Long {
        while (true) {
            val current = sessionIds.get()
            val next = if (current >= Long.MAX_VALUE - 1) {
                Random.nextLong(1L, Long.MAX_VALUE)
            } else {
                current + 1
            }
            if (sessionIds.compareAndSet(current, next)) {
                return next
            }
        }
    }

    suspend fun replaceLiveCatalog(
        providerId: Long,
        categories: List<CategoryEntity>?,
        channels: List<ChannelEntity>,
        afterCatalogApply: suspend () -> Unit = {}
    ): Int {
        val sessionId = newSessionId()
        val stagedChannels = buildChannelStages(providerId, sessionId, channels)
        val limitedChannels = limitChannels(providerId, stagedChannels)
        // If the provider exceeded the size limit we applied a subset. Running full stale
        // deletion would then delete every row outside the retained subset, converting a
        // safety cap into destructive data loss. Switch to upsert-only for overflow syncs.
        val overflowed = limitedChannels.size < stagedChannels.size
        try {
            clearProviderStaging(providerId)
            categories?.let { stageCategories(providerId, sessionId, it) }
            insertStageRows(limitedChannels, catalogSyncDao::insertChannelStages)
            providerTransaction(providerId) {
                categories?.let { applyCategories(providerId, sessionId, "LIVE", pruneStale = !overflowed) }
                if (overflowed) {
                    upsertChannels(providerId, sessionId)
                } else {
                    applyChannels(providerId, sessionId)
                }
                catalogSyncDao.rebuildChannelFts()
                afterCatalogApply()
            }
            return limitedChannels.size
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun replaceMovieCatalog(providerId: Long, categories: List<CategoryEntity>?, movies: Sequence<MovieEntity>): Int {
        val sessionId = newSessionId()
        try {
            clearProviderStaging(providerId)
            categories?.let { stageCategories(providerId, sessionId, it) }
            val stagingResult = stageMovieSequence(providerId, sessionId, movies)
            providerTransaction(providerId) {
                categories?.let { applyCategories(providerId, sessionId, "MOVIE", pruneStale = !stagingResult.overflowed) }
                if (stagingResult.overflowed) {
                    upsertMovies(providerId, sessionId)
                } else {
                    applyMovies(providerId, sessionId)
                }
                catalogSyncDao.rebuildMovieFts()
            }
            movieDao.restoreWatchProgress(providerId)
            return stagingResult.acceptedCount
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun replaceSeriesCatalog(providerId: Long, categories: List<CategoryEntity>?, series: Sequence<SeriesEntity>): Int {
        val sessionId = newSessionId()
        try {
            clearProviderStaging(providerId)
            categories?.let { stageCategories(providerId, sessionId, it) }
            val stagingResult = stageSeriesSequence(providerId, sessionId, series)
            providerTransaction(providerId) {
                categories?.let { applyCategories(providerId, sessionId, "SERIES", pruneStale = !stagingResult.overflowed) }
                if (stagingResult.overflowed) {
                    upsertSeries(providerId, sessionId)
                } else {
                    applySeries(providerId, sessionId)
                }
                catalogSyncDao.rebuildSeriesFts()
            }
            return stagingResult.acceptedCount
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun applyStagedLiveCatalog(
        providerId: Long,
        sessionId: Long,
        categories: List<CategoryEntity>?,
        afterCatalogApply: suspend () -> Unit = {}
    ) {
        try {
            providerTransaction(providerId) {
                categories?.let { timed(providerId, "category-staging") { stageCategories(providerId, sessionId, it) } }
                categories?.let { timed(providerId, "category-commit") { applyCategories(providerId, sessionId, "LIVE") } }
                timed(providerId, "channel-insert-update-delete") { applyChannels(providerId, sessionId) }
                timed(providerId, "channel-fts-rebuild") { catalogSyncDao.rebuildChannelFts() }
                afterCatalogApply()
            }
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun applyStagedMovieCatalog(
        providerId: Long,
        sessionId: Long,
        categories: List<CategoryEntity>?,
        afterCatalogApply: suspend () -> Unit = {}
    ) {
        try {
            providerTransaction(providerId) {
                categories?.let { stageCategories(providerId, sessionId, it) }
                categories?.let { applyCategories(providerId, sessionId, "MOVIE") }
                applyMovies(providerId, sessionId)
                catalogSyncDao.rebuildMovieFts()
                afterCatalogApply()
            }
            movieDao.restoreWatchProgress(providerId)
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun applyStagedSeriesCatalog(
        providerId: Long,
        sessionId: Long,
        categories: List<CategoryEntity>?,
        afterCatalogApply: suspend () -> Unit = {}
    ) {
        try {
            providerTransaction(providerId) {
                categories?.let { stageCategories(providerId, sessionId, it) }
                categories?.let { applyCategories(providerId, sessionId, "SERIES") }
                applySeries(providerId, sessionId)
                catalogSyncDao.rebuildSeriesFts()
                afterCatalogApply()
            }
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    // ── Upsert-only staged apply methods (Issue 1: Partial commit scope) ──────────────

    /**
     * Staged live-catalog commit that updates and inserts without deleting stale rows.
     * Use when the staged session represents a partial (subset) of categories/channels —
     * absent rows must NOT be treated as deletions.
     */
    suspend fun applyStagedLiveCatalogUpsertOnly(
        providerId: Long,
        sessionId: Long,
        categories: List<CategoryEntity>?,
        afterCatalogApply: suspend () -> Unit = {}
    ) {
        try {
            providerTransaction(providerId) {
                categories?.let { stageCategories(providerId, sessionId, it) }
                categories?.let { applyCategories(providerId, sessionId, "LIVE", pruneStale = false) }
                upsertChannels(providerId, sessionId)
                catalogSyncDao.rebuildChannelFts()
                afterCatalogApply()
            }
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    /**
     * Staged movie-catalog commit that updates and inserts without deleting stale rows.
     * Use when the staged session represents a partial (subset) of categories/pages.
     */
    suspend fun applyStagedMovieCatalogUpsertOnly(providerId: Long, sessionId: Long, categories: List<CategoryEntity>?) {
        try {
            providerTransaction(providerId) {
                categories?.let { stageCategories(providerId, sessionId, it) }
                categories?.let { applyCategories(providerId, sessionId, "MOVIE", pruneStale = false) }
                upsertMovies(providerId, sessionId)
                catalogSyncDao.rebuildMovieFts()
            }
            movieDao.restoreWatchProgress(providerId)
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    /**
     * Staged series-catalog commit that updates and inserts without deleting stale rows.
     * Use when the staged session represents a partial (subset) of categories/pages.
     */
    suspend fun applyStagedSeriesCatalogUpsertOnly(providerId: Long, sessionId: Long, categories: List<CategoryEntity>?) {
        try {
            providerTransaction(providerId) {
                categories?.let { stageCategories(providerId, sessionId, it) }
                categories?.let { applyCategories(providerId, sessionId, "SERIES", pruneStale = false) }
                upsertSeries(providerId, sessionId)
                catalogSyncDao.rebuildSeriesFts()
            }
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    /**
     * Non-staged live-catalog upsert: stages the given channels, then updates and inserts
     * without deleting stale rows. Used for partial Xtream category results.
     */
    suspend fun upsertLiveCatalog(
        providerId: Long,
        categories: List<CategoryEntity>?,
        channels: List<ChannelEntity>,
        afterCatalogApply: suspend () -> Unit = {}
    ): Int {
        val sessionId = newSessionId()
        val stagedChannels = buildChannelStages(providerId, sessionId, channels)
        try {
            clearProviderStaging(providerId)
            categories?.let { stageCategories(providerId, sessionId, it) }
            insertStageRows(stagedChannels, catalogSyncDao::insertChannelStages)
            providerTransaction(providerId) {
                categories?.let { applyCategories(providerId, sessionId, "LIVE", pruneStale = false) }
                upsertChannels(providerId, sessionId)
                catalogSyncDao.rebuildChannelFts()
                afterCatalogApply()
            }
            return stagedChannels.size
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    /**
     * Non-staged movie-catalog upsert: stages the given movies, then updates and inserts
     * without deleting stale rows. Used for partial Xtream category/page results.
     */
    suspend fun upsertMovieCatalog(providerId: Long, categories: List<CategoryEntity>?, movies: Sequence<MovieEntity>): Int {
        val sessionId = newSessionId()
        try {
            clearProviderStaging(providerId)
            categories?.let { stageCategories(providerId, sessionId, it) }
            val stagingResult = stageMovieSequence(providerId, sessionId, movies)
            providerTransaction(providerId) {
                categories?.let { applyCategories(providerId, sessionId, "MOVIE", pruneStale = false) }
                upsertMovies(providerId, sessionId)
                catalogSyncDao.rebuildMovieFts()
            }
            movieDao.restoreWatchProgress(providerId)
            return stagingResult.acceptedCount
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    /**
     * Non-staged series-catalog upsert: stages the given series, then updates and inserts
     * without deleting stale rows. Used for partial Xtream category/page results.
     */
    suspend fun upsertSeriesCatalog(providerId: Long, categories: List<CategoryEntity>?, series: Sequence<SeriesEntity>): Int {
        val sessionId = newSessionId()
        try {
            clearProviderStaging(providerId)
            categories?.let { stageCategories(providerId, sessionId, it) }
            val stagingResult = stageSeriesSequence(providerId, sessionId, series)
            providerTransaction(providerId) {
                categories?.let { applyCategories(providerId, sessionId, "SERIES", pruneStale = false) }
                upsertSeries(providerId, sessionId)
                catalogSyncDao.rebuildSeriesFts()
            }
            return stagingResult.acceptedCount
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun upsertCategories(providerId: Long, type: String, categories: List<CategoryEntity>) {
        val sessionId = newSessionId()
        try {
            clearProviderStaging(providerId)
            stageCategories(providerId, sessionId, categories)
            providerTransaction(providerId) {
                applyCategories(providerId, sessionId, type, pruneStale = false)
            }
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun replaceCategories(providerId: Long, type: String, categories: List<CategoryEntity>) {
        val sessionId = newSessionId()
        try {
            clearProviderStaging(providerId)
            stageCategories(providerId, sessionId, categories)
            providerTransaction(providerId) {
                applyCategories(providerId, sessionId, type)
            }
        } finally {
            clearSession(providerId, sessionId)
        }
    }

    suspend fun stageChannelBatch(providerId: Long, sessionId: Long, channels: List<ChannelEntity>) {
        timed(providerId, "channel-staging-batch(${channels.size})") {
            providerTransaction(providerId) {
                requireStageCapacity(
                    currentCount = catalogSyncDao.countChannelStages(providerId, sessionId),
                    incomingCount = channels.distinctBy { it.streamId }.size,
                    maximum = sizeLimits.maxChannelsPerProvider,
                    label = "channel"
                )
                insertStageRows(buildChannelStages(providerId, sessionId, channels), catalogSyncDao::insertChannelStages)
            }
        }
    }

    private suspend fun <T> timed(providerId: Long, stage: String, block: suspend () -> T): T {
        val startedAt = System.currentTimeMillis()
        return try {
            block()
        } finally {
            Log.i(TAG, "catalog timing provider=$providerId stage=$stage took=${System.currentTimeMillis() - startedAt}ms")
        }
    }

    suspend fun stageMovieBatch(providerId: Long, sessionId: Long, movies: List<MovieEntity>) {
        providerTransaction(providerId) {
            requireStageCapacity(
                currentCount = catalogSyncDao.countMovieStages(providerId, sessionId),
                incomingCount = movies.distinctBy { it.streamId }.size,
                maximum = sizeLimits.maxMoviesPerProvider,
                label = "movie"
            )
            insertStageRows(buildMovieStages(providerId, sessionId, movies), catalogSyncDao::insertMovieStages)
        }
    }

    suspend fun stageSeriesBatch(providerId: Long, sessionId: Long, series: List<SeriesEntity>) {
        insertStageRows(buildSeriesStages(providerId, sessionId, series), catalogSyncDao::insertSeriesStages)
    }

    suspend fun stageCategories(providerId: Long, sessionId: Long, categories: List<CategoryEntity>) {
        val rows = categories
            .distinctBy { it.categoryId to it.type }
            .map { category ->
                CategoryImportStageEntity(
                    sessionId = sessionId,
                    providerId = providerId,
                    categoryId = category.categoryId,
                    name = category.name,
                    parentId = category.parentId,
                    type = category.type,
                    providerOrder = category.providerOrder,
                    isAdult = category.isAdult,
                    syncFingerprint = categoryFingerprint(category)
                )
            }
        providerTransaction(providerId) {
            rows.groupBy { it.type }.forEach { (type, typeRows) ->
                requireStageCapacity(
                    currentCount = catalogSyncDao.countCategoryStages(providerId, sessionId, type.name),
                    incomingCount = typeRows.size,
                    maximum = sizeLimits.maxM3uCategoriesPerType,
                    label = "${type.name.lowercase()} category"
                )
            }
            insertStageRows(rows, catalogSyncDao::insertCategoryStages)
        }
    }

    suspend fun finalizeStagedImport(
        providerId: Long,
        sessionId: Long,
        liveCategories: List<CategoryEntity>?,
        movieCategories: List<CategoryEntity>?,
        includeLive: Boolean,
        includeMovies: Boolean,
        afterCatalogApply: suspend () -> Unit = {}
    ) {
        providerTransaction(providerId) {
            if (includeLive) {
                stageCategories(providerId, sessionId, liveCategories.orEmpty())
                applyCategories(providerId, sessionId, "LIVE")
                applyChannels(providerId, sessionId)
                catalogSyncDao.rebuildChannelFts()
            }
            if (includeMovies) {
                stageCategories(providerId, sessionId, movieCategories.orEmpty())
                applyCategories(providerId, sessionId, "MOVIE")
                applyMovies(providerId, sessionId)
                catalogSyncDao.rebuildMovieFts()
            }
            if (includeLive || includeMovies) {
                afterCatalogApply()
            }
        }
        if (includeMovies) {
            movieDao.restoreWatchProgress(providerId)
        }
    }

    /** Promotes one complete Jellyfin Movies+Series snapshot in a single Room transaction. */
    suspend fun applyStagedJellyfinCatalog(
        providerId: Long,
        movieSessionId: Long,
        seriesSessionId: Long,
        movieCategories: List<CategoryEntity>,
        seriesCategories: List<CategoryEntity>,
        afterCatalogApply: suspend () -> Unit = {}
    ) {
        try {
            providerTransaction(providerId) {
                stageCategories(providerId, movieSessionId, movieCategories)
                applyCategories(providerId, movieSessionId, "MOVIE")
                applyMovies(providerId, movieSessionId)
                catalogSyncDao.rebuildMovieFts()

                stageCategories(providerId, seriesSessionId, seriesCategories)
                applyCategories(providerId, seriesSessionId, "SERIES")
                applySeries(providerId, seriesSessionId)
                catalogSyncDao.rebuildSeriesFts()
                afterCatalogApply()
            }
            movieDao.restoreWatchProgress(providerId)
        } finally {
            withContext(NonCancellable) {
                clearSession(providerId, movieSessionId)
                if (seriesSessionId != movieSessionId) clearSession(providerId, seriesSessionId)
            }
        }
    }

    suspend fun discardStagedImport(providerId: Long, sessionId: Long) {
        clearSession(providerId, sessionId)
    }

    private suspend fun <T> providerTransaction(
        providerId: Long,
        block: suspend () -> T
    ): T = transactionRunner.inTransaction {
        workflowCommitFence.assertCanCommit(providerId)
        block()
    }

    private fun requireStageCapacity(currentCount: Int, incomingCount: Int, maximum: Int, label: String) {
        if (incomingCount > maximum - currentCount) {
            throw CatalogAdmissionExceeded("Staged $label limit exceeded")
        }
    }

    suspend fun stagedLiveImportState(providerId: Long, sessionId: Long): StagedLiveImportState {
        val stagedCategories = catalogSyncDao.getCategoryStages(providerId, sessionId, "LIVE")
        val categorySummaries = catalogSyncDao.getChannelStageCategorySummaries(providerId, sessionId)
        return StagedLiveImportState(
            sessionId = sessionId,
            channelCount = catalogSyncDao.countChannelStages(providerId, sessionId),
            movieCount = catalogSyncDao.countMovieStages(providerId, sessionId),
            seriesCount = catalogSyncDao.countSeriesStages(providerId, sessionId),
            categories = if (stagedCategories.isNotEmpty()) {
                stagedCategories.map { stage ->
                    CategoryEntity(
                        categoryId = stage.categoryId,
                        name = stage.name,
                        parentId = stage.parentId,
                        type = ContentType.LIVE,
                        providerId = providerId,
                        providerOrder = stage.providerOrder,
                        isAdult = stage.isAdult
                    )
                }
            } else {
                categorySummaries.map { summary ->
                    CategoryEntity(
                        categoryId = summary.categoryId,
                        name = summary.name,
                        parentId = 0,
                        type = ContentType.LIVE,
                        providerId = providerId,
                        isAdult = summary.isAdult
                    )
                }
            }
        )
    }

    suspend fun clearProviderStaging(providerId: Long) {
        transactionRunner.inTransaction {
            catalogSyncDao.clearProviderChannelStages(providerId)
            catalogSyncDao.clearProviderMovieStages(providerId)
            catalogSyncDao.clearProviderSeriesStages(providerId)
            catalogSyncDao.clearProviderCategoryStages(providerId)
        }
    }

    private suspend fun applyCategories(
        providerId: Long,
        sessionId: Long,
        type: String,
        pruneStale: Boolean = true
    ) {
        val stagedByCategoryId = catalogSyncDao.getCategoryStages(providerId, sessionId, type)
            .associateBy { it.categoryId }
        val changed = categoryDao.getByProviderAndTypeSync(providerId, type)
            .mapNotNull { current ->
                val stage = stagedByCategoryId[current.categoryId] ?: return@mapNotNull null
                if (current.syncFingerprint == stage.syncFingerprint) return@mapNotNull null
                // User-set protection is preserved across provider metadata changes (rename,
                // parent, adult-flag). Protection is removed only when the category identity
                // truly disappears (deleteStaleCategoriesForStage cascade) or via explicit
                // user action — never by a harmless provider-side metadata drift.
                current.copy(
                    name = stage.name,
                    parentId = stage.parentId,
                    isAdult = stage.isAdult,
                    providerOrder = stage.providerOrder,
                    isUserProtected = current.isUserProtected,
                    syncFingerprint = stage.syncFingerprint
                )
            }
        if (changed.isNotEmpty()) {
            categoryDao.updateAll(changed)
        }
        catalogSyncDao.insertMissingCategoriesFromStage(providerId, sessionId, type)
        if (pruneStale) {
            catalogSyncDao.deleteStaleCategoriesForStage(providerId, sessionId, type)
        }
    }

    private suspend fun applyChannels(providerId: Long, sessionId: Long) {
        catalogSyncDao.updateChangedChannelsFromStage(providerId, sessionId)
        catalogSyncDao.insertMissingChannelsFromStage(providerId, sessionId)
        catalogSyncDao.deleteStaleChannelsForStage(providerId, sessionId)
    }

    /**
     * Upsert-only channel apply: updates and inserts staged rows without deleting any
     * existing rows. Used when a partial result or size-limit overflow makes it unsafe
     * to treat absent staged rows as deletions.
     */
    private suspend fun upsertChannels(providerId: Long, sessionId: Long) {
        catalogSyncDao.updateChangedChannelsFromStage(providerId, sessionId)
        catalogSyncDao.insertMissingChannelsFromStage(providerId, sessionId)
        // Intentionally no stale deletion — partial or overflow commits must not remove
        // channels that were not part of the successfully fetched subset.
    }

    private suspend fun applyMovies(providerId: Long, sessionId: Long) {
        catalogSyncDao.updateChangedMoviesFromStage(providerId, sessionId)
        catalogSyncDao.insertMissingMoviesFromStage(providerId, sessionId)
        catalogSyncDao.deleteStaleMoviesForStage(providerId, sessionId)
        refreshMovieTmdbIdentities(providerId)
    }

    /**
     * Upsert-only movie apply: updates and inserts staged rows without deleting any
     * existing rows. Used for partial category/page results and size-limit overflow.
     */
    private suspend fun upsertMovies(providerId: Long, sessionId: Long) {
        catalogSyncDao.updateChangedMoviesFromStage(providerId, sessionId)
        catalogSyncDao.insertMissingMoviesFromStage(providerId, sessionId)
        // Intentionally no stale deletion.
        refreshMovieTmdbIdentities(providerId)
    }

    private suspend fun applySeries(providerId: Long, sessionId: Long) {
        catalogSyncDao.updateChangedSeriesFromStage(providerId, sessionId)
        catalogSyncDao.insertMissingSeriesFromStage(providerId, sessionId)
        catalogSyncDao.deleteStaleSeriesForStage(providerId, sessionId)
        refreshSeriesTmdbIdentities(providerId)
    }

    /**
     * Upsert-only series apply: updates and inserts staged rows without deleting any
     * existing rows. Used for partial category/page results and size-limit overflow.
     */
    private suspend fun upsertSeries(providerId: Long, sessionId: Long) {
        catalogSyncDao.updateChangedSeriesFromStage(providerId, sessionId)
        catalogSyncDao.insertMissingSeriesFromStage(providerId, sessionId)
        // Intentionally no stale deletion.
        refreshSeriesTmdbIdentities(providerId)
    }

    private suspend fun refreshMovieTmdbIdentities(providerId: Long) {
        val identities = movieDao.getTmdbIdsByProvider(providerId)
            .distinctBy { it.tmdbId }
            .map { mapping ->
                TmdbIdentityEntity(
                    tmdbId = mapping.tmdbId,
                    contentType = ContentType.MOVIE,
                    canonicalProviderId = providerId,
                    firstSeenAt = System.currentTimeMillis()
                )
            }
        if (identities.isNotEmpty()) {
            tmdbIdentityDao.upsertAll(identities)
        }
        tmdbIdentityDao.pruneOrphanedMovieIdentities()
    }

    private suspend fun refreshSeriesTmdbIdentities(providerId: Long) {
        val identities = seriesDao.getTmdbIdsByProvider(providerId)
            .distinctBy { it.tmdbId }
            .map { mapping ->
                TmdbIdentityEntity(
                    tmdbId = mapping.tmdbId,
                    contentType = ContentType.SERIES,
                    canonicalProviderId = providerId,
                    firstSeenAt = System.currentTimeMillis()
                )
            }
        if (identities.isNotEmpty()) {
            tmdbIdentityDao.upsertAll(identities)
        }
        tmdbIdentityDao.pruneOrphanedSeriesIdentities()
    }

    private suspend fun clearSession(providerId: Long, sessionId: Long) {
        withContext(NonCancellable) {
            transactionRunner.inTransaction {
                catalogSyncDao.clearChannelStages(providerId, sessionId)
                catalogSyncDao.clearMovieStages(providerId, sessionId)
                catalogSyncDao.clearSeriesStages(providerId, sessionId)
                catalogSyncDao.clearCategoryStages(providerId, sessionId)
            }
        }
    }

    private fun buildChannelStages(
        providerId: Long,
        sessionId: Long,
        channels: List<ChannelEntity>
    ): List<ChannelImportStageEntity> {
        return channels
            .distinctBy { it.streamId }
            .map { channel ->
                ChannelImportStageEntity(
                    sessionId = sessionId,
                    providerId = providerId,
                    streamId = channel.streamId,
                    name = channel.name,
                    logoUrl = channel.logoUrl,
                    groupTitle = channel.groupTitle,
                    categoryId = channel.categoryId,
                    categoryName = channel.categoryName,
                    streamUrl = channel.streamUrl,
                    epgChannelId = channel.epgChannelId,
                    number = channel.number,
                    catchUpSupported = channel.catchUpSupported,
                    catchUpDays = channel.catchUpDays,
                    catchUpSource = channel.catchUpSource,
                    isAdult = channel.isAdult,
                    logicalGroupId = channel.logicalGroupId,
                    errorCount = channel.errorCount,
                    syncFingerprint = channelFingerprint(channel)
                )
            }
    }

    private fun buildMovieStages(
        providerId: Long,
        sessionId: Long,
        movies: List<MovieEntity>
    ): List<MovieImportStageEntity> {
        return movies
            .distinctBy { it.streamId }
            .map { movie ->
                MovieImportStageEntity(
                    sessionId = sessionId,
                    providerId = providerId,
                    streamId = movie.streamId,
                    name = movie.name,
                    posterUrl = movie.posterUrl,
                    backdropUrl = movie.backdropUrl,
                    categoryId = movie.categoryId,
                    categoryName = movie.categoryName,
                    streamUrl = movie.streamUrl,
                    containerExtension = movie.containerExtension,
                    plot = movie.plot,
                    cast = movie.cast,
                    director = movie.director,
                    genre = movie.genre,
                    releaseDate = movie.releaseDate,
                    duration = movie.duration,
                    durationSeconds = movie.durationSeconds,
                    rating = movie.rating,
                    year = movie.year,
                    tmdbId = movie.tmdbId,
                    youtubeTrailer = movie.youtubeTrailer,
                    isAdult = movie.isAdult,
                    syncFingerprint = movieFingerprint(movie),
                    addedAt = movie.addedAt
                )
            }
    }

    private fun buildSeriesStages(
        providerId: Long,
        sessionId: Long,
        series: List<SeriesEntity>
    ): List<SeriesImportStageEntity> {
        return series
            .distinctBy(::seriesRemoteKey)
            .map { item ->
                SeriesImportStageEntity(
                    sessionId = sessionId,
                    providerId = providerId,
                    seriesId = item.seriesId,
                    providerSeriesId = item.providerSeriesId,
                    providerSeriesKey = seriesRemoteKey(item),
                    name = item.name,
                    posterUrl = item.posterUrl,
                    backdropUrl = item.backdropUrl,
                    categoryId = item.categoryId,
                    categoryName = item.categoryName,
                    plot = item.plot,
                    cast = item.cast,
                    director = item.director,
                    genre = item.genre,
                    releaseDate = item.releaseDate,
                    rating = item.rating,
                    tmdbId = item.tmdbId,
                    youtubeTrailer = item.youtubeTrailer,
                    episodeRunTime = item.episodeRunTime,
                    lastModified = item.lastModified,
                    isAdult = item.isAdult,
                    syncFingerprint = seriesFingerprint(item)
                )
            }
    }

    private suspend fun stageMovieSequence(
        providerId: Long,
        sessionId: Long,
        movies: Sequence<MovieEntity>
    ): StagingResult {
        return stageDistinctRows(
            items = movies,
            keySelector = { movie -> movie.streamId },
            limit = sizeLimits.maxMoviesPerProvider,
            bestFirstComparator = compareByDescending<MovieEntity> { it.rating }
                .thenBy { it.name.lowercase() }
                .thenBy { it.streamId },
            logLabel = "movies",
            providerId = providerId,
            stageBuilder = { movie -> movieStageEntity(providerId, sessionId, movie) },
            insert = catalogSyncDao::insertMovieStages
        )
    }

    private suspend fun stageSeriesSequence(
        providerId: Long,
        sessionId: Long,
        series: Sequence<SeriesEntity>
    ): StagingResult {
        return stageDistinctRows(
            items = series,
            keySelector = { item -> item.seriesId },
            limit = sizeLimits.maxSeriesPerProvider,
            bestFirstComparator = compareByDescending<SeriesEntity> { it.rating }
                .thenBy { it.name.lowercase() }
                .thenBy { it.seriesId },
            logLabel = "series",
            providerId = providerId,
            stageBuilder = { item -> seriesStageEntity(providerId, sessionId, item) },
            insert = catalogSyncDao::insertSeriesStages
        )
    }

    private suspend fun <T, K, S> stageDistinctRows(
        items: Sequence<T>,
        keySelector: (T) -> K,
        limit: Int,
        bestFirstComparator: Comparator<T>,
        logLabel: String,
        providerId: Long,
        stageBuilder: (T) -> S,
        insert: suspend (List<S>) -> Unit
    ): StagingResult {
        val seenKeys = HashSet<K>()
        val bestItems = java.util.PriorityQueue<T>(limit.coerceAtLeast(1), bestFirstComparator.reversed())
        val batch = ArrayList<S>(STAGE_BATCH_SIZE)
        var distinctCount = 0

        items.forEach { item ->
            if (!seenKeys.add(keySelector(item))) {
                return@forEach
            }
            distinctCount++
            if (bestItems.size < limit) {
                bestItems += item
            } else if (limit > 0 && bestFirstComparator.compare(item, bestItems.peek()) < 0) {
                bestItems.poll()
                bestItems += item
            }
        }

        val overflowed = distinctCount > limit
        if (overflowed) {
            Log.w(
                TAG,
                "Provider $providerId $logLabel staging exceeded limit $limit; kept ${bestItems.size} highest-priority distinct rows out of $distinctCount."
            )
        }

        bestItems
            .toList()
            .sortedWith(bestFirstComparator)
            .forEach { item ->
                batch.add(stageBuilder(item))
                if (batch.size >= STAGE_BATCH_SIZE) {
                    insert(batch)
                    batch.clear()
                }
            }

        if (batch.isNotEmpty()) {
            insert(batch)
        }

        return StagingResult(acceptedCount = bestItems.size, overflowed = overflowed)
    }

    private suspend fun <T> insertStageRows(
        rows: List<T>,
        insert: suspend (List<T>) -> Unit
    ) {
        rows.chunked(STAGE_BATCH_SIZE).forEach { chunk ->
            if (chunk.isNotEmpty()) {
                insert(chunk)
            }
        }
    }

    private fun movieStageEntity(
        providerId: Long,
        sessionId: Long,
        movie: MovieEntity
    ): MovieImportStageEntity {
        return MovieImportStageEntity(
            sessionId = sessionId,
            providerId = providerId,
            streamId = movie.streamId,
            name = movie.name,
            posterUrl = movie.posterUrl,
            backdropUrl = movie.backdropUrl,
            categoryId = movie.categoryId,
            categoryName = movie.categoryName,
            streamUrl = movie.streamUrl,
            containerExtension = movie.containerExtension,
            plot = movie.plot,
            cast = movie.cast,
            director = movie.director,
            genre = movie.genre,
            releaseDate = movie.releaseDate,
            duration = movie.duration,
            durationSeconds = movie.durationSeconds,
            rating = movie.rating,
            year = movie.year,
            tmdbId = movie.tmdbId,
            youtubeTrailer = movie.youtubeTrailer,
            isAdult = movie.isAdult,
            syncFingerprint = movieFingerprint(movie),
            addedAt = movie.addedAt
        )
    }

    private fun seriesStageEntity(
        providerId: Long,
        sessionId: Long,
        item: SeriesEntity
    ): SeriesImportStageEntity {
        return SeriesImportStageEntity(
            sessionId = sessionId,
            providerId = providerId,
            seriesId = item.seriesId,
            providerSeriesId = item.providerSeriesId,
            providerSeriesKey = seriesRemoteKey(item),
            name = item.name,
            posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl,
            categoryId = item.categoryId,
            categoryName = item.categoryName,
            plot = item.plot,
            cast = item.cast,
            director = item.director,
            genre = item.genre,
            releaseDate = item.releaseDate,
            rating = item.rating,
            tmdbId = item.tmdbId,
            youtubeTrailer = item.youtubeTrailer,
            episodeRunTime = item.episodeRunTime,
            lastModified = item.lastModified,
            isAdult = item.isAdult,
            syncFingerprint = seriesFingerprint(item)
        )
    }

    private fun channelFingerprint(channel: ChannelEntity): String {
        val xtreamLiveToken = XtreamUrlFactory.parseInternalStreamUrl(channel.streamUrl)
            ?.takeIf { token -> token.kind == XtreamStreamKind.LIVE }
        if (xtreamLiveToken != null) {
            return fingerprint(
                channel.streamId.toString(),
                normalizeText(channel.name),
                normalizeUrl(channel.logoUrl),
                normalizeText(channel.groupTitle),
                channel.categoryId?.toString().orEmpty(),
                normalizeText(channel.epgChannelId),
                channel.number.toString(),
                channel.catchUpSupported.toString(),
                channel.catchUpDays.toString(),
                channel.isAdult.toString(),
                normalizeText(xtreamLiveToken.containerExtension)
            )
        }
        return fingerprint(
            normalizeText(channel.name),
            normalizeUrl(channel.logoUrl),
            normalizeText(channel.groupTitle),
            channel.categoryId?.toString().orEmpty(),
            normalizeText(channel.categoryName),
            normalizeUrl(channel.streamUrl),
            normalizeText(channel.epgChannelId),
            channel.number.toString(),
            channel.catchUpSupported.toString(),
            channel.catchUpDays.toString(),
            normalizeUrl(channel.catchUpSource),
            channel.isAdult.toString()
        )
    }

    private fun movieFingerprint(movie: MovieEntity): String {
        return fingerprint(
            normalizeText(movie.name),
            normalizeUrl(movie.posterUrl),
            normalizeUrl(movie.backdropUrl),
            movie.categoryId?.toString().orEmpty(),
            normalizeText(movie.categoryName),
            normalizeUrl(movie.streamUrl),
            normalizeText(movie.containerExtension),
            normalizeText(movie.plot),
            normalizeText(movie.cast),
            normalizeText(movie.director),
            normalizeText(movie.genre),
            normalizeText(movie.releaseDate),
            normalizeText(movie.duration),
            movie.durationSeconds.toString(),
            movie.rating.toString(),
            normalizeText(movie.year),
            movie.tmdbId?.toString().orEmpty(),
            normalizeUrl(movie.youtubeTrailer),
            movie.isAdult.toString(),
            movie.addedAt.toString()
        )
    }

    private fun seriesFingerprint(series: SeriesEntity): String {
        return fingerprint(
            seriesRemoteKey(series),
            normalizeText(series.name),
            normalizeUrl(series.posterUrl),
            normalizeUrl(series.backdropUrl),
            series.categoryId?.toString().orEmpty(),
            normalizeText(series.categoryName),
            normalizeText(series.plot),
            normalizeText(series.cast),
            normalizeText(series.director),
            normalizeText(series.genre),
            normalizeText(series.releaseDate),
            series.rating.toString(),
            series.tmdbId?.toString().orEmpty(),
            normalizeUrl(series.youtubeTrailer),
            normalizeText(series.episodeRunTime),
            series.lastModified.toString(),
            series.isAdult.toString()
        )
    }

    private fun seriesRemoteKey(series: SeriesEntity): String {
        return series.providerSeriesId?.takeIf { it.isNotBlank() } ?: series.seriesId.toString()
    }

    private fun categoryFingerprint(category: CategoryEntity): String {
        return fingerprint(
            category.categoryId.toString(),
            normalizeText(category.name),
            category.parentId?.toString().orEmpty(),
            category.type.name,
            category.providerOrder.toString(),
            category.isAdult.toString()
        )
    }

    private fun fingerprint(vararg values: String): String {
        val messageDigest = requireNotNull(digest.get())
        messageDigest.reset()
        values.forEach { value ->
            messageDigest.update(value.toByteArray(Charsets.UTF_8))
            messageDigest.update(0)
        }
        return messageDigest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun normalizeText(value: String?): String {
        return value
            .orEmpty()
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    private fun normalizeUrl(value: String?): String {
        val url = value?.trim().orEmpty()
        if (url.isEmpty()) {
            return ""
        }
        val parsed = runCatching { URI(url) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase().orEmpty()
        val host = parsed?.host?.lowercase().orEmpty()
        val path = parsed?.path.orEmpty().trimEnd('/')
        val query = parsed?.query
            ?.split('&')
            ?.mapNotNull { pair ->
                val key = pair.substringBefore('=').lowercase()
                val normalizedValue = pair.substringAfter('=', "")
                when (key) {
                    "token", "auth", "password", "username" -> null
                    else -> "$key=$normalizedValue"
                }
            }
            ?.sorted()
            ?.joinToString("&")
            .orEmpty()
        return listOf(scheme, host, path, query)
            .joinToString("|")
            .ifBlank { url.lowercase() }
    }

    private fun limitChannels(
        providerId: Long,
        channels: List<ChannelImportStageEntity>
    ): List<ChannelImportStageEntity> {
        if (channels.size <= sizeLimits.maxChannelsPerProvider) {
            return channels
        }
        val limited = channels
            .sortedWith(
                compareBy<ChannelImportStageEntity>(
                    { if (it.number > 0) it.number else Int.MAX_VALUE },
                    { it.name.lowercase() },
                    { it.streamId }
                )
            )
            .take(sizeLimits.maxChannelsPerProvider)
        Log.w(
            TAG,
            "Provider $providerId channel staging exceeded limit ${sizeLimits.maxChannelsPerProvider}; kept ${limited.size} lowest-numbered distinct rows out of ${channels.size}."
        )
        return limited
    }
}
