package com.streamvault.domain.sports.provider

import kotlinx.coroutines.flow.Flow

enum class SportsMetadataSourceMode { AUTOMATIC, OFF, PROVIDER }

data class SportsMetadataSourcePreference(
    val mode: SportsMetadataSourceMode = SportsMetadataSourceMode.AUTOMATIC,
    val providerInstanceId: String? = null,
) {
    init {
        require((mode == SportsMetadataSourceMode.PROVIDER) == !providerInstanceId.isNullOrBlank())
    }

    companion object {
        val AUTOMATIC = SportsMetadataSourcePreference()
        val OFF = SportsMetadataSourcePreference(SportsMetadataSourceMode.OFF)
        fun provider(instanceId: String) = SportsMetadataSourcePreference(
            mode = SportsMetadataSourceMode.PROVIDER,
            providerInstanceId = instanceId,
        )
    }
}

interface SportsMetadataSourceSettings {
    val metadataSourcePreference: Flow<SportsMetadataSourcePreference>

    suspend fun setMetadataSourcePreference(preference: SportsMetadataSourcePreference)
}
