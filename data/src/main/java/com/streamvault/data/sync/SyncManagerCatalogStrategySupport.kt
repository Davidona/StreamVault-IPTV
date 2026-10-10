package com.streamvault.data.sync

import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.util.AdultContentClassifier
import com.streamvault.data.remote.xtream.XtreamNetworkException
import com.streamvault.data.remote.xtream.XtreamParsingException
import com.streamvault.data.remote.xtream.XtreamResponseTooLargeException
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SyncMetadata

internal class SyncManagerCatalogStrategySupport(
    private val shouldRememberSequentialPreference: (Throwable) -> Boolean,
    private val avoidFullCatalogCooldownMillis: Long
) {
    fun strategyWarnings(result: CatalogStrategyResult<*>): List<String> = when (result) {
        is CatalogStrategyResult.Success -> result.warnings
        is CatalogStrategyResult.Partial -> result.warnings
        is CatalogStrategyResult.Failure -> result.warnings
        is CatalogStrategyResult.EmptyValid -> result.warnings
    }

    fun <T> shouldDowngradeCategorySync(
        totalCategories: Int,
        failures: Int,
        fastFailures: Int,
        outcomes: List<CategoryFetchOutcome<T>>
    ): Boolean {
        if (totalCategories <= 1) return false
        val failureRatio = failures.toFloat() / totalCategories.toFloat()
        val firstWindow = outcomes.take(minOf(4, outcomes.size))
        val firstWindowFailures = firstWindow.count { outcome ->
            outcome is CategoryFetchOutcome.Failure && shouldRememberSequentialPreference(outcome.error)
        }
        return failureRatio >= 0.75f ||
            fastFailures >= minOf(3, totalCategories) ||
            firstWindowFailures >= minOf(3, firstWindow.size)
    }

    fun <T> shouldRetryFailedCategories(
        totalCategories: Int,
        failures: Int,
        downgradeRecommended: Boolean,
        outcomes: List<CategoryFetchOutcome<T>>
    ): Boolean {
        if (totalCategories <= 1 || failures == 0) {
            return false
        }
        val stressFailures = outcomes.count { outcome ->
            outcome is CategoryFetchOutcome.Failure && shouldRememberSequentialPreference(outcome.error)
        }
        return downgradeRecommended || stressFailures > 0 || failures <= minOf(2, totalCategories)
    }

    fun updateAvoidFullUntil(
        previousAvoidFullUntil: Long,
        now: Long,
        feedback: XtreamStrategyFeedback
    ): Long {
        return when {
            feedback.attemptedFullCatalog && feedback.fullCatalogUnsafe -> now + avoidFullCatalogCooldownMillis
            feedback.attemptedFullCatalog -> 0L
            feedback.preferredSegmentedFirst && feedback.segmentedStressDetected -> 0L
            previousAvoidFullUntil <= now -> 0L
            else -> previousAvoidFullUntil
        }
    }

    fun shouldPreferSegmentedLiveSync(metadata: SyncMetadata, now: Long): Boolean =
        metadata.liveAvoidFullUntil > now

    fun shouldPreferSegmentedMovieSync(metadata: SyncMetadata, now: Long): Boolean =
        metadata.movieAvoidFullUntil > now

    fun shouldPreferSegmentedSeriesSync(metadata: SyncMetadata, now: Long): Boolean =
        metadata.seriesAvoidFullUntil > now

    fun shouldAvoidFullCatalogStrategy(error: Throwable): Boolean {
        return when (error) {
            is XtreamResponseTooLargeException,
            is LowMemoryCatalogAbortException,
            is XtreamLiveBufferLimitException,
            is com.streamvault.data.remote.xtream.XtreamLiveRowTooLargeException,
            is XtreamParsingException -> true
            is java.net.SocketTimeoutException,
            is java.io.InterruptedIOException -> true
            is XtreamNetworkException ->
                error.message.orEmpty().contains("timed out", ignoreCase = true)
            is IllegalStateException -> {
                val normalized = error.message.orEmpty().lowercase()
                normalized.contains("oversized") ||
                    normalized.contains("safe in-memory budget") ||
                    normalized.contains("unreadable response") ||
                    normalized.contains("invalid catalog data")
            }
            else -> false
        }
    }

    fun sawSegmentedStress(
        warnings: List<String>,
        result: CatalogStrategyResult<*>,
        sequentialWarnings: Set<String>
    ): Boolean {
        val warningMatched = warnings.any { warning ->
            sequentialWarnings.any { marker -> warning.contains(marker, ignoreCase = true) }
        }
        val resultWarningMatched = strategyWarnings(result).any { warning ->
            sequentialWarnings.any { marker -> warning.contains(marker, ignoreCase = true) }
        }
        val failureMatched = (
            (result as? CatalogStrategyResult.Failure)
                ?.error
                ?.let(shouldRememberSequentialPreference)
            ) == true
        return warningMatched || resultWarningMatched || failureMatched
    }

    fun buildFallbackMovieCategories(providerId: Long, movies: List<Movie>): List<CategoryEntity> {
        return buildFallbackCategories(
            providerId = providerId,
            type = ContentType.MOVIE,
            items = movies,
            categoryId = { movie -> movie.categoryId },
            categoryName = { movie -> movie.categoryName },
            isAdult = { movie -> movie.isAdult }
        )
    }

    fun buildFallbackLiveCategories(providerId: Long, channels: List<Channel>): List<CategoryEntity> {
        return buildFallbackCategories(
            providerId = providerId,
            type = ContentType.LIVE,
            items = channels,
            categoryId = { channel -> channel.categoryId },
            categoryName = { channel -> channel.categoryName ?: channel.groupTitle },
            isAdult = { channel -> channel.isAdult }
        )
    }

    fun buildFallbackSeriesCategories(providerId: Long, series: List<Series>): List<CategoryEntity> {
        return buildFallbackCategories(
            providerId = providerId,
            type = ContentType.SERIES,
            items = series,
            categoryId = { item -> item.categoryId },
            categoryName = { item -> item.categoryName },
            isAdult = { item -> item.isAdult }
        )
    }

    fun mergePreferredAndFallbackCategories(
        preferred: List<CategoryEntity>?,
        fallback: List<CategoryEntity>?
    ): List<CategoryEntity>? {
        if (preferred.isNullOrEmpty()) return fallback?.takeIf { it.isNotEmpty() }
        if (fallback.isNullOrEmpty()) return preferred

        val merged = LinkedHashMap<Pair<Long, ContentType>, CategoryEntity>()
        preferred.forEach { category ->
            merged[category.categoryId to category.type] = category
        }
        fallback.forEach { category ->
            val key = category.categoryId to category.type
            val existing = merged[key]
            merged[key] = if (existing == null) {
                category
            } else {
                existing.copy(
                    isAdult = existing.isAdult || category.isAdult,
                    isUserProtected = existing.isUserProtected || category.isUserProtected,
                    name = existing.name.ifBlank { category.name }
                )
            }
        }
        return merged.values.toList()
    }

    private fun <T> buildFallbackCategories(
        providerId: Long,
        type: ContentType,
        items: List<T>,
        categoryId: (T) -> Long?,
        categoryName: (T) -> String?,
        isAdult: (T) -> Boolean
    ): List<CategoryEntity> {
        val categories = LinkedHashMap<Long, CategoryEntity>()
        items.forEach { item ->
            val resolvedCategoryId = categoryId(item) ?: return@forEach
            val resolvedCategoryName = categoryName(item)?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "Category $resolvedCategoryId"
            val candidate = CategoryEntity(
                categoryId = resolvedCategoryId,
                name = resolvedCategoryName,
                parentId = 0,
                type = type,
                providerId = providerId,
                isAdult = isAdult(item) || AdultContentClassifier.isAdultCategoryName(resolvedCategoryName)
            )
            val existing = categories[resolvedCategoryId]
            categories[resolvedCategoryId] = if (existing == null) {
                candidate
            } else {
                existing.copy(
                    name = preferredFallbackCategoryName(existing.name, candidate.name, resolvedCategoryId),
                    isAdult = existing.isAdult || candidate.isAdult,
                    isUserProtected = existing.isUserProtected || candidate.isUserProtected
                )
            }
        }
        return categories.values.toList()
    }

    private fun preferredFallbackCategoryName(
        currentName: String,
        candidateName: String,
        categoryId: Long
    ): String {
        val placeholderName = "Category $categoryId"
        return when {
            currentName == placeholderName && candidateName != placeholderName -> candidateName
            currentName != placeholderName -> currentName
            else -> candidateName
        }
    }
}
