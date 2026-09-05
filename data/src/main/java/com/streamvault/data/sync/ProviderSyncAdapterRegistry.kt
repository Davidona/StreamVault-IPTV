package com.streamvault.data.sync

import com.streamvault.data.provider.toLegacyProvider
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.provider.CapabilityResolution

internal data class FullProviderSyncRequest(
    val snapshot: ProviderSnapshot,
    val force: Boolean,
    val onProgress: ((String) -> Unit)?,
    val trackInitialLiveOnboarding: Boolean,
    val deferProviderStateUntilCatalogCommit: Boolean,
    val afterCatalogApply: suspend () -> Unit,
    /**
     * Limits the initial-onboarding sync to a small usable subset (capped live channels,
     * category shells, EPG deferred). The remainder is handed off to a background full sync.
     */
    val bootstrap: Boolean = false
)

internal data class SectionProviderSyncRequest(
    val snapshot: ProviderSnapshot,
    val section: SyncRepairSection,
    val syncReason: XtreamLiveSyncReason,
    val onProgress: ((String) -> Unit)?
)

internal data class ProviderGuideSyncRequest(
    val snapshot: ProviderSnapshot,
    val force: Boolean,
    val now: Long,
    val onProgress: ((String) -> Unit)?
)

internal data class ProviderGuideSyncResult(
    val warnings: List<String>,
    val hasRetryableFailure: Boolean
)

internal fun ProviderSnapshot.toSyncCompatibilityProvider() =
    toLegacyProvider()

internal interface CatalogSyncPlan {
    val providerType: ProviderType
    suspend fun syncFull(request: FullProviderSyncRequest): SyncOutcome
    suspend fun syncSection(request: SectionProviderSyncRequest): CapabilityResolution<SyncOutcome>
    suspend fun syncGuide(request: ProviderGuideSyncRequest): CapabilityResolution<ProviderGuideSyncResult>
}

internal class CatalogSyncPlanRegistry(plans: Collection<CatalogSyncPlan>) {
    private val plansByType: Map<ProviderType, CatalogSyncPlan>

    init {
        val duplicates = plans.groupingBy { it.providerType }.eachCount().filterValues { it != 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate catalog sync plans: $duplicates" }
        val missing = ProviderType.entries.toSet() - plans.mapTo(mutableSetOf()) { it.providerType }
        require(missing.isEmpty()) { "Missing catalog sync plans: $missing" }
        plansByType = plans.associateBy { it.providerType }
    }

    fun resolve(snapshot: ProviderSnapshot): CapabilityResolution<CatalogSyncPlan> {
        if (snapshot.provider.type != snapshot.configuration.type) {
            return CapabilityResolution.ConfigurationError("Provider/configuration type mismatch")
        }
        return CapabilityResolution.Available(plansByType.getValue(snapshot.provider.type))
    }
}

internal class LambdaCatalogSyncPlan(
    override val providerType: ProviderType,
    private val full: suspend (FullProviderSyncRequest) -> SyncOutcome,
    private val section: suspend (SectionProviderSyncRequest) -> CapabilityResolution<SyncOutcome>,
    private val guide: suspend (ProviderGuideSyncRequest) -> CapabilityResolution<ProviderGuideSyncResult>
) : CatalogSyncPlan {
    override suspend fun syncFull(request: FullProviderSyncRequest): SyncOutcome = full(request)
    override suspend fun syncSection(request: SectionProviderSyncRequest): CapabilityResolution<SyncOutcome> = section(request)
    override suspend fun syncGuide(request: ProviderGuideSyncRequest): CapabilityResolution<ProviderGuideSyncResult> =
        guide(request)
}
