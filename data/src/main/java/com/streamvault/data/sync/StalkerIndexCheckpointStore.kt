package com.streamvault.data.sync

import com.streamvault.data.local.dao.MovieCategoryHydrationDao
import com.streamvault.data.local.dao.SeriesCategoryHydrationDao
import com.streamvault.data.local.entity.MovieCategoryHydrationEntity
import com.streamvault.data.local.entity.SeriesCategoryHydrationEntity
import com.streamvault.domain.model.ContentType

/** Owns durable per-category Stalker index checkpoints and their recovery projections. */
internal class StalkerIndexCheckpointStore(
    private val movieHydrationDao: MovieCategoryHydrationDao,
    private val seriesHydrationDao: SeriesCategoryHydrationDao
) {
    suspend fun getHydrationSnapshot(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long
    ): StalkerHydrationSnapshot? = when (contentType) {
        ContentType.MOVIE -> movieHydrationDao.get(providerId, categoryId)?.let { hydration ->
            StalkerHydrationSnapshot(
                lastHydratedAt = hydration.lastHydratedAt,
                itemCount = hydration.itemCount,
                lastStatus = hydration.lastStatus,
                lastError = hydration.lastError,
                lastLoadedPage = hydration.lastLoadedPage,
                lastAttemptedPage = hydration.lastAttemptedPage,
                lastSuccessfulPage = hydration.lastSuccessfulPage,
                totalPages = hydration.totalPages,
                advertisedTotalItems = hydration.advertisedTotalItems,
                advertisedTotalPages = hydration.advertisedTotalPages,
                isComplete = hydration.isComplete,
                pageSize = hydration.pageSize,
                retryAfterMs = hydration.retryAfterMs,
                failureCount = hydration.failureCount,
                retryBudgetRemaining = hydration.retryBudgetRemaining,
                lastPageFingerprint = hydration.lastPageFingerprint
            )
        }
        ContentType.SERIES -> seriesHydrationDao.get(providerId, categoryId)?.let { hydration ->
            StalkerHydrationSnapshot(
                lastHydratedAt = hydration.lastHydratedAt,
                itemCount = hydration.itemCount,
                lastStatus = hydration.lastStatus,
                lastError = hydration.lastError,
                lastLoadedPage = hydration.lastLoadedPage,
                lastAttemptedPage = hydration.lastAttemptedPage,
                lastSuccessfulPage = hydration.lastSuccessfulPage,
                totalPages = hydration.totalPages,
                advertisedTotalItems = hydration.advertisedTotalItems,
                advertisedTotalPages = hydration.advertisedTotalPages,
                isComplete = hydration.isComplete,
                pageSize = hydration.pageSize,
                retryAfterMs = hydration.retryAfterMs,
                failureCount = hydration.failureCount,
                retryBudgetRemaining = hydration.retryBudgetRemaining,
                lastPageFingerprint = hydration.lastPageFingerprint
            )
        }
        else -> null
    }

    suspend fun markAttemptStarted(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long,
        hydration: StalkerHydrationSnapshot?,
        attemptedPage: Int,
        now: Long
    ) {
        when (contentType) {
            ContentType.MOVIE -> movieHydrationDao.upsert(
                MovieCategoryHydrationEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    lastHydratedAt = hydration?.lastHydratedAt ?: 0L,
                    itemCount = hydration?.itemCount ?: 0,
                    lastStatus = "RUNNING",
                    lastError = null,
                    lastLoadedPage = hydration?.lastLoadedPage ?: 0,
                    lastAttemptedPage = attemptedPage,
                    lastSuccessfulPage = hydration?.lastSuccessfulPage ?: 0,
                    totalPages = hydration?.totalPages ?: 0,
                    advertisedTotalItems = hydration?.advertisedTotalItems,
                    advertisedTotalPages = hydration?.advertisedTotalPages,
                    isComplete = hydration?.isComplete ?: false,
                    pageSize = hydration?.pageSize ?: 0,
                    retryAfterMs = 0L,
                    failureCount = hydration?.failureCount ?: 0,
                    retryBudgetRemaining = hydration?.retryBudgetRemaining ?: STALKER_CATEGORY_RETRY_BUDGET,
                    lastPageFingerprint = hydration?.lastPageFingerprint
                )
            )
            ContentType.SERIES -> seriesHydrationDao.upsert(
                SeriesCategoryHydrationEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    lastHydratedAt = hydration?.lastHydratedAt ?: 0L,
                    itemCount = hydration?.itemCount ?: 0,
                    lastStatus = "RUNNING",
                    lastError = null,
                    lastLoadedPage = hydration?.lastLoadedPage ?: 0,
                    lastAttemptedPage = attemptedPage,
                    lastSuccessfulPage = hydration?.lastSuccessfulPage ?: 0,
                    totalPages = hydration?.totalPages ?: 0,
                    advertisedTotalItems = hydration?.advertisedTotalItems,
                    advertisedTotalPages = hydration?.advertisedTotalPages,
                    isComplete = hydration?.isComplete ?: false,
                    pageSize = hydration?.pageSize ?: 0,
                    retryAfterMs = 0L,
                    failureCount = hydration?.failureCount ?: 0,
                    retryBudgetRemaining = hydration?.retryBudgetRemaining ?: STALKER_CATEGORY_RETRY_BUDGET,
                    lastPageFingerprint = hydration?.lastPageFingerprint
                )
            )
            else -> Unit
        }
    }

    suspend fun markAttemptSucceeded(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long,
        hydration: StalkerHydrationSnapshot?,
        attemptedPage: Int,
        now: Long,
        itemCount: Int,
        totalPages: Int,
        pageSize: Int,
        advertisedTotalItems: Int?,
        advertisedTotalPages: Int?,
        pageComplete: Boolean,
        truncated: Boolean,
        terminationReason: String?,
        pageFingerprint: String?
    ) {
        val status = if (truncated) "TRUNCATED" else "SUCCESS"
        when (contentType) {
            ContentType.MOVIE -> movieHydrationDao.upsert(
                MovieCategoryHydrationEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    lastHydratedAt = now,
                    itemCount = itemCount,
                    lastStatus = status,
                    lastError = terminationReason,
                    lastLoadedPage = attemptedPage,
                    lastAttemptedPage = attemptedPage,
                    lastSuccessfulPage = attemptedPage,
                    totalPages = totalPages,
                    advertisedTotalItems = advertisedTotalItems,
                    advertisedTotalPages = advertisedTotalPages,
                    isComplete = pageComplete && !truncated,
                    pageSize = pageSize,
                    retryAfterMs = 0L,
                    failureCount = 0,
                    retryBudgetRemaining = STALKER_CATEGORY_RETRY_BUDGET,
                    lastPageFingerprint = pageFingerprint
                )
            )
            ContentType.SERIES -> seriesHydrationDao.upsert(
                SeriesCategoryHydrationEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    lastHydratedAt = now,
                    itemCount = itemCount,
                    lastStatus = status,
                    lastError = terminationReason,
                    lastLoadedPage = attemptedPage,
                    lastAttemptedPage = attemptedPage,
                    lastSuccessfulPage = attemptedPage,
                    totalPages = totalPages,
                    advertisedTotalItems = advertisedTotalItems,
                    advertisedTotalPages = advertisedTotalPages,
                    isComplete = pageComplete && !truncated,
                    pageSize = pageSize,
                    retryAfterMs = 0L,
                    failureCount = 0,
                    retryBudgetRemaining = STALKER_CATEGORY_RETRY_BUDGET,
                    lastPageFingerprint = pageFingerprint
                )
            )
            else -> Unit
        }
    }

    suspend fun markAttemptFailed(
        providerId: Long,
        contentType: ContentType,
        categoryId: Long,
        hydration: StalkerHydrationSnapshot?,
        attemptedPage: Int,
        now: Long,
        message: String,
        retryable: Boolean,
        pageFingerprint: String?
    ) {
        val priorFailures = hydration?.failureCount ?: 0
        val remainingBudget = if (retryable) {
            ((hydration?.retryBudgetRemaining ?: STALKER_CATEGORY_RETRY_BUDGET) - 1).coerceAtLeast(0)
        } else {
            0
        }
        val nextStatus = when {
            !retryable -> "FAILED_PERMANENT"
            remainingBudget <= 0 -> "FAILED_BUDGET_EXHAUSTED"
            else -> "FAILED_RETRYABLE"
        }
        val retryAfterMs = if (nextStatus == "FAILED_RETRYABLE" && priorFailures > 0) {
            now + STALKER_CATEGORY_RETRY_COOLDOWN_MILLIS * (priorFailures + 1).coerceAtLeast(1)
        } else {
            0L
        }
        when (contentType) {
            ContentType.MOVIE -> movieHydrationDao.upsert(
                MovieCategoryHydrationEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    lastHydratedAt = hydration?.lastHydratedAt ?: 0L,
                    itemCount = hydration?.itemCount ?: 0,
                    lastStatus = nextStatus,
                    lastError = message,
                    lastLoadedPage = hydration?.lastLoadedPage ?: 0,
                    lastAttemptedPage = attemptedPage,
                    lastSuccessfulPage = hydration?.lastSuccessfulPage ?: 0,
                    totalPages = hydration?.totalPages ?: 0,
                    advertisedTotalItems = hydration?.advertisedTotalItems,
                    advertisedTotalPages = hydration?.advertisedTotalPages,
                    isComplete = hydration?.isComplete ?: false,
                    pageSize = hydration?.pageSize ?: 0,
                    retryAfterMs = retryAfterMs,
                    failureCount = priorFailures + 1,
                    retryBudgetRemaining = remainingBudget,
                    lastPageFingerprint = pageFingerprint
                )
            )
            ContentType.SERIES -> seriesHydrationDao.upsert(
                SeriesCategoryHydrationEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    lastHydratedAt = hydration?.lastHydratedAt ?: 0L,
                    itemCount = hydration?.itemCount ?: 0,
                    lastStatus = nextStatus,
                    lastError = message,
                    lastLoadedPage = hydration?.lastLoadedPage ?: 0,
                    lastAttemptedPage = attemptedPage,
                    lastSuccessfulPage = hydration?.lastSuccessfulPage ?: 0,
                    totalPages = hydration?.totalPages ?: 0,
                    advertisedTotalItems = hydration?.advertisedTotalItems,
                    advertisedTotalPages = hydration?.advertisedTotalPages,
                    isComplete = hydration?.isComplete ?: false,
                    pageSize = hydration?.pageSize ?: 0,
                    retryAfterMs = retryAfterMs,
                    failureCount = priorFailures + 1,
                    retryBudgetRemaining = remainingBudget,
                    lastPageFingerprint = pageFingerprint
                )
            )
            else -> Unit
        }
    }

    companion object {
        const val STALKER_CATEGORY_RETRY_BUDGET = 3
        const val STALKER_CATEGORY_RETRY_COOLDOWN_MILLIS = 5 * 60 * 1000L
    }
}
