package com.streamvault.domain.sports.provider

import kotlinx.coroutines.flow.Flow

enum class SportsEventSourceMode { AUTOMATIC, EPG, PROVIDER }

data class SportsEventSourcePreference(
    val mode: SportsEventSourceMode = SportsEventSourceMode.AUTOMATIC,
    val providerInstanceId: String? = null,
) {
    init {
        require((mode == SportsEventSourceMode.PROVIDER) == !providerInstanceId.isNullOrBlank())
    }

    companion object {
        val AUTOMATIC = SportsEventSourcePreference()
        val EPG = SportsEventSourcePreference(SportsEventSourceMode.EPG)
        fun provider(instanceId: String) = SportsEventSourcePreference(
            mode = SportsEventSourceMode.PROVIDER,
            providerInstanceId = instanceId,
        )
    }
}

interface SportsEventSourceSettings {
    val eventSourcePreference: Flow<SportsEventSourcePreference>

    suspend fun setEventSourcePreference(preference: SportsEventSourcePreference)
}
