package com.streamvault.data.sync

import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.SyncMetadata

enum class XtreamLiveSyncReason {
    INITIAL_ONBOARDING,
    FOREGROUND,
    MANUAL_SETTINGS,
    BACKGROUND_STALE
}

internal enum class EffectiveXtreamLiveSyncMethod {
    STREAM_ALL,
    CATEGORY_BY_CATEGORY
}

internal object XtreamLiveSyncPolicy {
    fun resolve(
        userMode: ProviderXtreamLiveSyncMode,
        runtimeProfile: CatalogSyncRuntimeProfile,
        syncReason: XtreamLiveSyncReason,
        metadata: SyncMetadata,
        now: Long,
        hiddenLiveCategoryIds: Set<Long>,
        hiddenLiveChannelFraction: Float? = null
    ): EffectiveXtreamLiveSyncMethod = when (userMode) {
        ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY -> EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        ProviderXtreamLiveSyncMode.STREAM_ALL -> EffectiveXtreamLiveSyncMethod.STREAM_ALL
        ProviderXtreamLiveSyncMode.AUTO -> resolveAuto(
            runtimeProfile = runtimeProfile,
            syncReason = syncReason,
            metadata = metadata,
            now = now,
            hiddenLiveCategoryIds = hiddenLiveCategoryIds,
            hiddenLiveChannelFraction = hiddenLiveChannelFraction,
            bulkFirstAutoEnabled = runtimeProfile.bulkFirstAutoEnabled
        )
    }

    private fun resolveAuto(
        runtimeProfile: CatalogSyncRuntimeProfile,
        syncReason: XtreamLiveSyncReason,
        metadata: SyncMetadata,
        now: Long,
        hiddenLiveCategoryIds: Set<Long>,
        hiddenLiveChannelFraction: Float?,
        bulkFirstAutoEnabled: Boolean
    ): EffectiveXtreamLiveSyncMethod {
        if (bulkFirstAutoEnabled) {
            // Prefer the bounded bulk route across tiers. Actual low memory, an active cooldown,
            // or a large hidden share still selects category retrieval. Category stress alone
            // does not mean the provider cannot serve a bulk catalog.
            if (runtimeProfile.snapshot.isCurrentlyLowOnMemory) return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
            if (metadata.liveAvoidFullUntil > now) return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
            if (hiddenLiveCategoryIds.isNotEmpty() &&
                hiddenLiveChannelFraction != null &&
                hiddenLiveChannelFraction >= HIDDEN_LIVE_CATEGORY_FRACTION_FOR_CATEGORY_MODE
            ) {
                return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
            }
            return EffectiveXtreamLiveSyncMethod.STREAM_ALL
        }
        if (hiddenLiveCategoryIds.isNotEmpty()) return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        if (runtimeProfile.tier == DeviceSyncTier.LOW) return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        if (runtimeProfile.snapshot.isCurrentlyLowOnMemory) return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        if (metadata.liveAvoidFullUntil > now) return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        if (metadata.liveSequentialFailuresRemembered) return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        if (syncReason == XtreamLiveSyncReason.INITIAL_ONBOARDING && runtimeProfile.preferSegmentedLiveOnboarding) {
            return EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
        }
        return when (syncReason) {
            XtreamLiveSyncReason.BACKGROUND_STALE,
            XtreamLiveSyncReason.MANUAL_SETTINGS -> EffectiveXtreamLiveSyncMethod.CATEGORY_BY_CATEGORY
            XtreamLiveSyncReason.INITIAL_ONBOARDING,
            XtreamLiveSyncReason.FOREGROUND -> EffectiveXtreamLiveSyncMethod.STREAM_ALL
        }
    }

    private const val HIDDEN_LIVE_CATEGORY_FRACTION_FOR_CATEGORY_MODE = 0.60f
}
