package com.streamvault.domain.model.stremio

data class StremioInstalledAddon(
    val addon: StremioAddon,
    val manifest: StremioManifest
)

data class StremioAddonSource(
    val addonId: Long,
    val addonName: String,
    val manifestId: String,
    val installFingerprint: String
)

data class StremioAttributedResource<T>(
    val source: StremioAddonSource,
    val resource: T
)

data class StremioAddonResourceFailure(
    val source: StremioAddonSource,
    val message: String
)

data class StremioMetaResolution(
    val result: StremioAttributedResource<StremioMetaDetail>? = null,
    val failures: List<StremioAddonResourceFailure> = emptyList(),
    val candidateCount: Int = 0
)

data class StremioResourceResolution<T>(
    val results: List<StremioAttributedResource<T>> = emptyList(),
    val failures: List<StremioAddonResourceFailure> = emptyList(),
    val candidateCount: Int = 0,
    /** Completed providers in configured add-on order, including providers that returned no resources. */
    val completedAddonIds: List<Long> = emptyList()
)
