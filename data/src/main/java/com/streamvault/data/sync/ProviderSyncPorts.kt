package com.streamvault.data.sync

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.VodCategoryHydrationRequest
import kotlinx.coroutines.flow.Flow

/** Commands needed by provider repositories and background workers. */
interface ProviderSyncCommands {
    fun currentSyncState(providerId: Long): SyncState

    suspend fun sync(
        providerId: Long,
        force: Boolean = false,
        movieFastSyncOverride: Boolean? = null,
        epgSyncModeOverride: ProviderEpgSyncMode? = null,
        onProgress: ((String) -> Unit)? = null,
        trackInitialLiveOnboarding: Boolean = false,
        bootstrap: Boolean = false
    ): Result<Unit>

    suspend fun syncWithProviderOverride(
        providerId: Long,
        force: Boolean = false,
        movieFastSyncOverride: Boolean? = null,
        epgSyncModeOverride: ProviderEpgSyncMode? = null,
        onProgress: ((String) -> Unit)? = null,
        trackInitialLiveOnboarding: Boolean = false,
        providerOverride: Provider? = null,
        afterCatalogApply: (suspend () -> Unit)? = null,
        bootstrap: Boolean = false
    ): Result<Unit>

    suspend fun syncEpg(
        providerId: Long,
        force: Boolean = true,
        onProgress: ((String) -> Unit)? = null
    ): Result<Unit>

    suspend fun retrySection(
        providerId: Long,
        section: SyncRepairSection,
        movieFastSyncOverride: Boolean? = null,
        syncReason: XtreamLiveSyncReason = XtreamLiveSyncReason.MANUAL_SETTINGS,
        onProgress: ((String) -> Unit)? = null
    ): Result<Unit>

    suspend fun processQueuedXtreamIndexJobs(
        providerId: Long,
        section: ContentType? = null,
        force: Boolean = false,
        maxCategoriesPerSection: Int? = null,
        onProgress: ((String) -> Unit)? = null
    ): Result<Unit>

    suspend fun processQueuedStalkerIndexJobs(
        providerId: Long,
        section: ContentType? = null,
        force: Boolean = false,
        maxCategoriesPerSection: Int? = null,
        onProgress: ((String) -> Unit)? = null
    ): Result<Unit>

    suspend fun rebuildXtreamIndex(
        providerId: Long,
        onProgress: ((String) -> Unit)? = null
    ): Result<Unit>

    fun scheduleProviderSyncResume(providerId: Long, configurationGeneration: Long? = null)

    fun scheduleBackgroundEpgSync(providerId: Long)

    fun scheduleXtreamIndexSync(
        providerId: Long,
        section: ContentType? = null,
        force: Boolean = false
    )

    fun scheduleStalkerIndexSync(
        providerId: Long,
        section: ContentType? = null,
        force: Boolean = false,
        initialDelaySeconds: Long = 0L,
        appendSuccessor: Boolean = false
    )

    fun cancelStalkerIndexSync(providerId: Long)
}

/** Catalog hydration and prioritization commands needed by browse repositories. */
interface CatalogHydrationCommands {
    suspend fun hydrateUnifiedVodCategory(
        providerId: Long,
        categoryId: Long,
        request: VodCategoryHydrationRequest
    ): Result<Unit>

    suspend fun hydrateSplitVodCategory(
        providerId: Long,
        movieCategoryId: Long,
        request: VodCategoryHydrationRequest,
        requestedProjection: ContentType = ContentType.MOVIE
    ): Result<Unit>

    suspend fun hydrateSplitVodSeriesCategory(
        providerId: Long,
        seriesCategoryId: Long,
        request: VodCategoryHydrationRequest
    ): Result<Unit>

    suspend fun prioritizeXtreamIndexCategory(
        providerId: Long,
        section: ContentType,
        categoryId: Long
    )

    suspend fun prioritizeStalkerIndexCategory(
        providerId: Long,
        section: ContentType,
        categoryId: Long
    )
}

/** Narrow state source used by the derived domain sync-state reader. */
interface ProviderSyncStateSource {
    fun currentSyncState(providerId: Long): SyncState

    fun syncStateForProvider(providerId: Long): Flow<SyncState>
}

/** Cleanup hook used by deletion workers without exposing sync policy. */
interface ProviderSyncLifecycle {
    suspend fun onProviderDeleted(providerId: Long)

    suspend fun reconcileStalkerIndexWorkAtStartup()
}
