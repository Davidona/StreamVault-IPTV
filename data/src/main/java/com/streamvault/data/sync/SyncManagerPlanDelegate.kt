package com.streamvault.data.sync

import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.repository.SyncMetadataRepository

/**
 * Bridges the provider-neutral plan contract to the existing provider executors.
 *
 * This is intentionally outside [SyncManager]. The compatibility projection is an adapter concern
 * while the executor ports are migrated to typed snapshots; the manager only assembles this
 * delegate and coordinates lifecycle/state policy.
 */
internal class SyncManagerPlanDelegate(
    private val snapshotAdapter: SyncProviderSnapshotAdapter,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val xtreamCatalogExecutor: XtreamCatalogSyncExecutor,
    private val xtreamCatalogSectionExecutor: XtreamCatalogSectionExecutor,
    private val providerEpgExecutor: ProviderEpgSyncExecutor,
    private val m3uCatalogExecutor: M3uCatalogSyncExecutor,
    private val stalkerSyncExecutor: StalkerCatalogSyncExecutor,
    private val stalkerCatalogSectionExecutor: StalkerCatalogSectionExecutor,
    private val jellyfinCatalogExecutor: JellyfinCatalogSyncExecutor
) : CatalogSyncPlanDelegate {
    private fun ProviderSnapshot.toLegacyProvider() = snapshotAdapter.toLegacyProvider(this)

    override suspend fun syncXtreamFull(request: FullProviderSyncRequest): SyncOutcome =
        xtreamCatalogExecutor.syncFull(
            provider = request.snapshot.toLegacyProvider(),
            force = request.force,
            onProgress = request.onProgress,
            trackInitialLiveOnboarding = request.trackInitialLiveOnboarding,
            syncReason = if (request.trackInitialLiveOnboarding) {
                XtreamLiveSyncReason.INITIAL_ONBOARDING
            } else {
                XtreamLiveSyncReason.FOREGROUND
            },
            afterCatalogApply = request.afterCatalogApply
        )

    override suspend fun syncXtreamLive(request: SectionProviderSyncRequest): SyncOutcome =
        xtreamCatalogExecutor.syncLive(
            request.snapshot.toLegacyProvider(),
            request.syncReason,
            request.onProgress
        )

    override suspend fun syncXtreamEpg(request: SectionProviderSyncRequest) {
        providerEpgExecutor.syncXtreamEpgOnly(request.snapshot.toLegacyProvider(), request.onProgress)
    }

    override suspend fun syncXtreamMovies(request: SectionProviderSyncRequest): SyncOutcome =
        xtreamCatalogSectionExecutor.syncMovies(
            request.snapshot.toLegacyProvider(),
            request.onProgress
        )

    override suspend fun syncXtreamSeries(request: SectionProviderSyncRequest): SyncOutcome =
        xtreamCatalogSectionExecutor.syncSeries(
            request.snapshot.toLegacyProvider(),
            request.onProgress
        )

    override suspend fun syncXtreamGuide(request: ProviderGuideSyncRequest): ProviderGuideSyncResult {
        val provider = request.snapshot.toLegacyProvider()
        return providerEpgExecutor.syncXtreamProviderEpg(
            provider,
            syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id),
            request.now,
            request.force,
            request.onProgress
        )
    }

    override suspend fun syncM3uFull(request: FullProviderSyncRequest): SyncOutcome =
        m3uCatalogExecutor.syncFull(
            request.snapshot.toLegacyProvider(),
            request.force,
            request.onProgress,
            request.afterCatalogApply
        )

    override suspend fun syncM3uLive(request: SectionProviderSyncRequest): SyncOutcome =
        m3uCatalogExecutor.syncLive(request.snapshot.toLegacyProvider(), request.onProgress)

    override suspend fun syncM3uEpg(request: SectionProviderSyncRequest) {
        providerEpgExecutor.syncM3uEpgOnly(request.snapshot.toLegacyProvider(), request.onProgress)
    }

    override suspend fun syncM3uMovies(request: SectionProviderSyncRequest): SyncOutcome =
        m3uCatalogExecutor.syncMovies(request.snapshot.toLegacyProvider(), request.onProgress)

    override suspend fun syncM3uGuide(request: ProviderGuideSyncRequest): ProviderGuideSyncResult {
        val provider = request.snapshot.toLegacyProvider()
        return providerEpgExecutor.syncM3uProviderEpg(
            provider,
            syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id),
            request.now,
            request.force,
            request.onProgress
        )
    }

    override suspend fun syncStalkerFull(request: FullProviderSyncRequest): SyncOutcome =
        stalkerSyncExecutor.syncFull(
            provider = request.snapshot.toLegacyProvider(),
            force = request.force,
            onProgress = request.onProgress,
            afterCatalogApply = request.afterCatalogApply,
            deferProviderStateUntilCatalogCommit = request.deferProviderStateUntilCatalogCommit,
            bootstrap = request.bootstrap
        )

    override suspend fun syncStalkerLive(request: SectionProviderSyncRequest): SyncOutcome =
        stalkerSyncExecutor.syncLive(request.snapshot.toLegacyProvider(), request.onProgress)

    override suspend fun syncStalkerEpg(request: SectionProviderSyncRequest) {
        providerEpgExecutor.syncStalkerEpgOnly(request.snapshot.toLegacyProvider(), request.onProgress)
    }

    override suspend fun syncStalkerMovies(request: SectionProviderSyncRequest): SyncOutcome =
        stalkerCatalogSectionExecutor.syncMovies(
            request.snapshot.toLegacyProvider(),
            request.onProgress
        )

    override suspend fun syncStalkerSeries(request: SectionProviderSyncRequest): SyncOutcome =
        stalkerCatalogSectionExecutor.syncSeries(
            request.snapshot.toLegacyProvider(),
            request.onProgress
        )

    override suspend fun syncStalkerGuide(request: ProviderGuideSyncRequest): ProviderGuideSyncResult {
        val provider = request.snapshot.toLegacyProvider()
        return providerEpgExecutor.syncStalkerProviderEpg(
            provider,
            syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id),
            request.now,
            request.force,
            request.onProgress
        )
    }

    override suspend fun syncJellyfinFull(request: FullProviderSyncRequest): SyncOutcome =
        jellyfinCatalogExecutor.syncFull(
            request.snapshot.toLegacyProvider(),
            request.force,
            request.onProgress,
            request.afterCatalogApply
        )

    override suspend fun syncJellyfinMovies(request: SectionProviderSyncRequest): SyncOutcome =
        jellyfinCatalogExecutor.syncMovies(
            request.snapshot.toLegacyProvider(),
            request.onProgress
        )

    override suspend fun syncJellyfinSeries(request: SectionProviderSyncRequest): SyncOutcome =
        jellyfinCatalogExecutor.syncSeries(
            request.snapshot.toLegacyProvider(),
            request.onProgress
        )

    override suspend fun syncJellyfinGuide(request: ProviderGuideSyncRequest): ProviderGuideSyncResult {
        val provider = request.snapshot.toLegacyProvider()
        return providerEpgExecutor.syncJellyfinProviderEpg(
            provider,
            syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id),
            request.now,
            request.force,
            request.onProgress
        )
    }
}
