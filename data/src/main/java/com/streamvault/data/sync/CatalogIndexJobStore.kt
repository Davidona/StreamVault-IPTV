package com.streamvault.data.sync

import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType

/**
 * Owns durable catalog-index job persistence across the current Xtream and Stalker schemas.
 *
 * The legacy method name is retained at the [SyncManager] facade for source compatibility, but
 * provider-specific row routing and patch/merge semantics belong here rather than in orchestration.
 */
internal class CatalogIndexJobStore(
    private val providerDao: ProviderDao,
    private val xtreamIndexJobDao: XtreamIndexJobDao,
    private val stalkerIndexJobStore: StalkerIndexJobStore
) {
    suspend fun upsert(update: CatalogIndexJobUpdate) {
        val providerType = providerDao.getById(update.providerId)?.type
        val stalkerSection = runCatching { ContentType.valueOf(update.section) }.getOrNull()
        if (
            providerType == ProviderType.STALKER_PORTAL &&
            stalkerSection in setOf(ContentType.MOVIE, ContentType.SERIES)
        ) {
            stalkerIndexJobStore.upsertLegacy(
                providerId = update.providerId,
                section = requireNotNull(stalkerSection),
                state = update.state,
                now = update.now,
                totalCategories = update.totalCategories,
                completedCategories = update.completedCategories,
                nextCategoryIndex = update.nextCategoryIndex,
                failedCategories = update.failedCategories,
                indexedRows = update.indexedRows,
                skippedMalformedRows = update.skippedMalformedRows,
                deletedPrunedRows = update.deletedPrunedRows,
                lastAttemptAt = update.lastAttemptAt,
                lastSuccessAt = update.lastSuccessAt,
                lastError = update.lastError
            )
            return
        }

        val existing = xtreamIndexJobDao.get(update.providerId, update.section)
        xtreamIndexJobDao.upsert(
            (existing ?: XtreamIndexJobEntity(
                providerId = update.providerId,
                section = update.section
            )).copy(
                state = update.state,
                totalCategories = update.totalCategories ?: existing?.totalCategories ?: 0,
                completedCategories = update.completedCategories ?: existing?.completedCategories ?: 0,
                nextCategoryIndex = update.nextCategoryIndex ?: existing?.nextCategoryIndex ?: 0,
                failedCategories = update.failedCategories ?: existing?.failedCategories ?: 0,
                indexedRows = update.indexedRows ?: existing?.indexedRows ?: 0,
                skippedMalformedRows = update.skippedMalformedRows ?: existing?.skippedMalformedRows ?: 0,
                deletedPrunedRows = update.deletedPrunedRows ?: existing?.deletedPrunedRows ?: 0,
                priorityCategoryId = if (update.clearPriority) null
                else update.priorityCategoryId ?: existing?.priorityCategoryId,
                priorityRequestedAt = if (update.clearPriority) 0L
                else update.priorityRequestedAt ?: existing?.priorityRequestedAt ?: 0L,
                lastError = update.lastError,
                lastAttemptAt = update.lastAttemptAt ?: existing?.lastAttemptAt ?: 0L,
                lastSuccessAt = update.lastSuccessAt ?: existing?.lastSuccessAt ?: 0L,
                updatedAt = update.now
            )
        )
    }

    internal fun shouldRunSummary(job: XtreamIndexJobEntity?): Boolean {
        if (job == null) return true
        // Rejected credentials require a configuration change or explicit forced retry,
        // rather than another launch/periodic attempt because lastSuccessAt is zero.
        if (job.state == "FAILED_PERMANENT") return false
        if (job.state in setOf("QUEUED", "RUNNING", "PARTIAL", "STALE", "FAILED_RETRYABLE")) return true
        return ContentCachePolicy.shouldRefresh(job.lastSuccessAt, ContentCachePolicy.CATALOG_TTL_MILLIS)
    }
}

internal data class CatalogIndexJobUpdate(
    val providerId: Long,
    val section: String,
    val state: String,
    val now: Long,
    val totalCategories: Int? = null,
    val completedCategories: Int? = null,
    val nextCategoryIndex: Int? = null,
    val failedCategories: Int? = null,
    val indexedRows: Int? = null,
    val skippedMalformedRows: Int? = null,
    val deletedPrunedRows: Int? = null,
    val priorityCategoryId: Long? = null,
    val priorityRequestedAt: Long? = null,
    val clearPriority: Boolean = false,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null
)
