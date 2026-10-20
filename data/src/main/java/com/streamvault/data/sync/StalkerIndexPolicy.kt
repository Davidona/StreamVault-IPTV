package com.streamvault.data.sync

import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.remote.stalker.StalkerVodCatalogItem
import com.streamvault.data.remote.stalker.StalkerPagedResult
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.VodCatalogItem
import java.security.MessageDigest

/** Durable state projected into the decisions made by the Stalker index worker. */
internal data class StalkerHydrationSnapshot(
    val lastHydratedAt: Long,
    val itemCount: Int,
    val lastStatus: String,
    val lastError: String?,
    val lastLoadedPage: Int,
    val lastAttemptedPage: Int,
    val lastSuccessfulPage: Int,
    val totalPages: Int,
    val advertisedTotalItems: Int?,
    val advertisedTotalPages: Int?,
    val isComplete: Boolean,
    val pageSize: Int,
    val retryAfterMs: Long,
    val failureCount: Int,
    val retryBudgetRemaining: Int,
    val lastPageFingerprint: String?
)

internal val StalkerHydrationSnapshot.isTerminalFailure: Boolean
    get() = lastStatus in setOf("FAILED_PERMANENT", "FAILED_BUDGET_EXHAUSTED")

internal val StalkerHydrationSnapshot.isTruncated: Boolean
    get() = lastStatus == "TRUNCATED"

internal val StalkerHydrationSnapshot.hasPruneSuppressionRisk: Boolean
    get() = lastStatus in setOf("ANOMALY", "FAILED_RETRYABLE", "COOLDOWN", "TRUNCATED")

internal data class StalkerCatalogDecision(
    val contentType: ContentType?,
    val retryDelaySeconds: Long = 0L,
    val reason: String
)

internal data class StalkerCatalogSectionState(
    val contentType: ContentType,
    val runnable: Boolean,
    val retryDelaySeconds: Long,
    val pending: Boolean,
    val jobState: String?,
    val updatedAt: Long
)

/**
 * Pure Stalker index policy. It owns page continuation, retry gating, anomaly detection, and
 * selection order; database/network orchestration remains in the worker coordinator.
 */
internal object StalkerIndexPolicy {
    fun chooseNextSection(
        layout: CatalogLayout,
        requestedSection: ContentType?,
        movie: StalkerCatalogSectionState,
        series: StalkerCatalogSectionState
    ): StalkerCatalogDecision {
        if (requestedSection == ContentType.MOVIE && movie.runnable) {
            return StalkerCatalogDecision(
                ContentType.MOVIE,
                reason = "explicit movie section is runnable (${movie.jobState ?: "no job"})"
            )
        }
        if (requestedSection == ContentType.SERIES && series.runnable) {
            return StalkerCatalogDecision(
                ContentType.SERIES,
                reason = "explicit series section is runnable (${series.jobState ?: "no job"})"
            )
        }
        if (movie.runnable && series.runnable) {
            // In split mode, finish the native Series projection before VOD-derived
            // supplementation so disappeared native rows can fall back safely.
            val selected = if (layout == CatalogLayout.SPLIT) {
                series
            } else {
                listOf(movie, series).minWith(
                    compareBy<StalkerCatalogSectionState>({ it.updatedAt }, { it.contentType.ordinal })
                )
            }
            return StalkerCatalogDecision(
                selected.contentType,
                reason = "oldest pending section is ${selected.contentType.name.lowercase()} (${selected.jobState ?: "no job"})"
            )
        }
        if (movie.runnable) {
            return StalkerCatalogDecision(ContentType.MOVIE, reason = "movies are runnable (${movie.jobState ?: "no job"})")
        }
        if (series.runnable) {
            val reason = if (movie.pending && movie.retryDelaySeconds > 0L) {
                "movies are waiting ${movie.retryDelaySeconds}s for retry; series is runnable"
            } else {
                "movies are complete or terminal; series is runnable (${series.jobState ?: "no job"})"
            }
            return StalkerCatalogDecision(ContentType.SERIES, reason = reason)
        }

        val retryDelaySeconds = listOf(movie.retryDelaySeconds, series.retryDelaySeconds)
            .filter { it > 0L }
            .minOrNull()
            ?: 0L
        return StalkerCatalogDecision(
            contentType = null,
            retryDelaySeconds = retryDelaySeconds,
            reason = when {
                retryDelaySeconds > 0L -> "catalog is waiting ${retryDelaySeconds}s for retry"
                movie.pending || series.pending -> "catalog has no attemptable work"
                requestedSection == ContentType.MOVIE -> "requested movie section is not pending"
                requestedSection == ContentType.SERIES -> "requested series section is not pending"
                else -> "catalog is idle"
            }
        )
    }

    fun canAttempt(hydration: StalkerHydrationSnapshot?, now: Long): Boolean {
        if (hydration == null) return true
        if (hydration.isComplete || hydration.isTruncated || hydration.isTerminalFailure) return false
        if (hydration.retryBudgetRemaining <= 0) return false
        return hydration.retryAfterMs <= now
    }

    fun nextAttemptPage(hydration: StalkerHydrationSnapshot?): Int {
        if (hydration == null) return 1
        if (hydration.lastStatus in setOf("FAILED_RETRYABLE", "COOLDOWN", "ANOMALY")) {
            return hydration.lastAttemptedPage.coerceAtLeast(1)
        }
        return (hydration.lastSuccessfulPage + 1).coerceAtLeast(1)
    }

    fun nextRetryDelaySeconds(
        hydrations: Iterable<StalkerHydrationSnapshot?>,
        now: Long
    ): Long {
        val retryAt = hydrations
            .filterNotNull()
            .filter { hydration ->
                !hydration.isComplete &&
                    !hydration.isTerminalFailure &&
                    hydration.retryBudgetRemaining > 0 &&
                    hydration.retryAfterMs > now
            }
            .minOfOrNull { it.retryAfterMs }
            ?: return 0L
        return ((retryAt - now + 999L) / 1000L).coerceAtLeast(1L)
    }

    fun detectPageAnomaly(
        hydration: StalkerHydrationSnapshot?,
        requestedPage: Int,
        pagedResult: StalkerPagedResult<out Any>,
        pageFingerprint: String?
    ): String? {
        if (pagedResult.page != requestedPage) {
            return "Portal returned page ${pagedResult.page} while page $requestedPage was requested."
        }
        if (hydration != null && hydration.totalPages > 0 && pagedResult.totalPages > 0) {
            if (pagedResult.totalPages < hydration.lastSuccessfulPage) {
                return "Portal page count regressed below the last successful page."
            }
            if (
                hydration.advertisedTotalPages != null &&
                pagedResult.advertisedTotalPages != null &&
                hydration.advertisedTotalPages != pagedResult.advertisedTotalPages
            ) {
                return "Portal changed its advertised catalog page count while indexing."
            }
            if (
                hydration.lastAttemptedPage == requestedPage &&
                hydration.lastPageFingerprint != null &&
                hydration.lastPageFingerprint == pageFingerprint &&
                hydration.lastStatus in setOf("FAILED_RETRYABLE", "RUNNING", "ANOMALY")
            ) {
                return "Portal repeated the same page payload for page $requestedPage."
            }
        }
        return null
    }

    fun pageFingerprint(items: List<out Any>, contentType: ContentType): String? {
        if (items.isEmpty()) return "empty"
        val classifiedSeeds = items.filterIsInstance<StalkerVodCatalogItem>()
            .map { entry -> "${entry.rawItemId}:${entry.item.stableId}" }
        if (classifiedSeeds.isNotEmpty()) return sha1Hex(classifiedSeeds.joinToString("|"))
        val seeds = when (contentType) {
            ContentType.MOVIE -> items.filterIsInstance<Movie>().map { movie ->
                "${movie.streamId}:${movie.categoryId}:${movie.addedAt}"
            }
            ContentType.SERIES -> items.filterIsInstance<Series>().map { series ->
                "${series.providerSeriesId ?: series.seriesId}:${series.categoryId}:${series.lastModified}"
            }
            else -> emptyList()
        }
        if (seeds.isEmpty()) return null
        return sha1Hex(seeds.joinToString("|"))
    }

    /**
     * Categories the wildcard walk did not cover locally: they have no indexed rows after the
     * sweep (adult-excluded categories, or genuinely empty ones) and need a targeted
     * per-category backfill.
     */
    fun wildcardBackfillCategories(
        normalCategories: List<CategoryEntity>,
        localRowCounts: Map<Long, Int>
    ): List<CategoryEntity> = normalCategories.filter { category ->
        (localRowCounts[category.categoryId] ?: 0) == 0
    }

    fun dedupePageItems(items: List<out Any>, contentType: ContentType): List<out Any> {
        val classified = items.filterIsInstance<StalkerVodCatalogItem>()
        if (classified.isNotEmpty()) return classified.distinctBy(StalkerVodCatalogItem::rawItemId)
        return when (contentType) {
            ContentType.MOVIE -> items.filterIsInstance<Movie>().distinctBy(Movie::streamId)
            ContentType.SERIES -> items.filterIsInstance<Series>().distinctBy {
                it.providerSeriesId ?: it.seriesId.toString()
            }
            else -> items
        }
    }

    fun filterToVisibleCategories(
        items: List<out Any>,
        contentType: ContentType,
        visibleCategoryIds: Set<Long>?
    ): List<out Any> = when (contentType) {
        ContentType.MOVIE -> {
            val classified = items.filterIsInstance<StalkerVodCatalogItem>()
            if (classified.isNotEmpty()) {
                classified.filter { entry ->
                    val categoryId = when (val item = entry.item) {
                        is VodCatalogItem.MovieItem -> item.movie.categoryId
                        is VodCatalogItem.SeriesItem -> item.series.categoryId
                    }
                    visibleCategoryIds == null || categoryId in visibleCategoryIds
                }
            } else {
                items.filterIsInstance<Movie>().filter { movie ->
                    visibleCategoryIds == null || movie.categoryId in visibleCategoryIds
                }
            }
        }
        ContentType.SERIES -> items.filterIsInstance<Series>().filter { series ->
            visibleCategoryIds == null || series.categoryId in visibleCategoryIds
        }
        else -> items
    }

    private fun sha1Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte -> append("%02x".format(byte)) }
        }
    }
}
