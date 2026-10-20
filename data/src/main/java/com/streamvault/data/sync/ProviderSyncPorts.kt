package com.streamvault.data.sync

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.sync.ProviderSectionSyncResult
import com.streamvault.domain.model.VodCategoryHydrationRequest
import kotlinx.coroutines.flow.Flow

/** Commands needed by provider repositories and background workers. */
interface ProviderSyncCommands {
    fun scheduleAutomaticSyncCheck() {}

    fun currentSyncState(providerId: Long): SyncState

    /** Resume queued primary work; implementations may coalesce an already completed catalog. */
    suspend fun resumePrimaryCatalog(providerId: Long, trackInitialLiveOnboarding: Boolean): Result<Unit> =
        sync(providerId, force = false, trackInitialLiveOnboarding = trackInitialLiveOnboarding)

    /** Complete Stalker bootstrap in one worker without rebuilding its committed VOD category shells. */
    suspend fun resumeStalkerBootstrap(providerId: Long): Result<Unit> =
        resumePrimaryCatalog(providerId, trackInitialLiveOnboarding = false)

    suspend fun sync(
        providerId: Long,
        force: Boolean = false,
        movieFastSyncOverride: Boolean? = null,
        epgSyncModeOverride: ProviderEpgSyncMode? = null,
        onProgress: ((String) -> Unit)? = null,
        trackInitialLiveOnboarding: Boolean = false
    ): Result<Unit> = sync(
        providerId = providerId,
        force = force,
        movieFastSyncOverride = movieFastSyncOverride,
        epgSyncModeOverride = epgSyncModeOverride,
        onProgress = onProgress,
        trackInitialLiveOnboarding = trackInitialLiveOnboarding,
        bootstrap = false
    )

    suspend fun sync(
        providerId: Long,
        force: Boolean = false,
        movieFastSyncOverride: Boolean? = null,
        epgSyncModeOverride: ProviderEpgSyncMode? = null,
        onProgress: ((String) -> Unit)? = null,
        trackInitialLiveOnboarding: Boolean = false,
        bootstrap: Boolean
    ): Result<Unit>

    suspend fun syncWithProviderOverride(
        providerId: Long,
        force: Boolean = false,
        movieFastSyncOverride: Boolean? = null,
        epgSyncModeOverride: ProviderEpgSyncMode? = null,
        onProgress: ((String) -> Unit)? = null,
        trackInitialLiveOnboarding: Boolean = false,
        providerOverride: Provider? = null,
        afterCatalogApply: (suspend () -> Unit)? = null
    ): Result<Unit> = syncWithProviderOverride(
        providerId = providerId,
        force = force,
        movieFastSyncOverride = movieFastSyncOverride,
        epgSyncModeOverride = epgSyncModeOverride,
        onProgress = onProgress,
        trackInitialLiveOnboarding = trackInitialLiveOnboarding,
        providerOverride = providerOverride,
        afterCatalogApply = afterCatalogApply,
        bootstrap = false
    )

    suspend fun syncWithProviderOverride(
        providerId: Long,
        force: Boolean = false,
        movieFastSyncOverride: Boolean? = null,
        epgSyncModeOverride: ProviderEpgSyncMode? = null,
        onProgress: ((String) -> Unit)? = null,
        trackInitialLiveOnboarding: Boolean = false,
        providerOverride: Provider? = null,
        afterCatalogApply: (suspend () -> Unit)? = null,
        bootstrap: Boolean
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

    suspend fun refreshScheduledPlaylist(providerId: Long, live: Boolean, movies: Boolean): Result<ProviderSectionSyncResult> =
        syncSection(providerId, if (live) SyncRepairSection.LIVE else SyncRepairSection.MOVIES)

    suspend fun refreshPlaylistCatalog(providerId: Long, onProgress: ((String) -> Unit)? = null): Result<ProviderSectionSyncResult> =
        refreshScheduledPlaylist(providerId, live = true, movies = true)

    fun scheduleManualEpgSync(providerId: Long) { scheduleBackgroundEpgSync(providerId) }

    suspend fun refreshScheduledSection(providerId: Long, section: SyncRepairSection): Result<ProviderSectionSyncResult> =
        syncSection(providerId, section)

    /** Explicit refresh, distinct from the failed-category repair used by workers. */
    suspend fun syncSection(
        providerId: Long,
        section: SyncRepairSection,
        onProgress: ((String) -> Unit)? = null
    ): Result<ProviderSectionSyncResult>

    suspend fun processQueuedXtreamIndexJobs(
        providerId: Long,
        section: ContentType? = null,
        force: Boolean = false,
        maxCategoriesPerSection: Int? = null,
        maxRowsPerSection: Int? = null,
        maxDurationMillis: Long? = null,
        onProgress: ((String) -> Unit)? = null
    ): Result<Unit>

    suspend fun processQueuedStalkerIndexJobs(
        providerId: Long,
        section: ContentType? = null,
        force: Boolean = false,
        maxCategoriesPerSection: Int? = null,
        onProgress: ((String) -> Unit)? = null,
        maxPagesPerSection: Int? = null
    ): Result<Unit>

    suspend fun rebuildXtreamIndex(
        providerId: Long,
        onProgress: ((String) -> Unit)? = null
    ): Result<Unit>

    suspend fun rebuildIndex(
        providerId: Long,
        onProgress: ((String) -> Unit)? = null
    ): Result<ProviderSectionSyncResult>

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
