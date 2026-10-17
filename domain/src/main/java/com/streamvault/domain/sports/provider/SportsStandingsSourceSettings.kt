package com.streamvault.domain.sports.provider

import kotlinx.coroutines.flow.Flow

enum class SportsStandingsSourceMode { AUTOMATIC, OFF, PROVIDER }

data class SportsStandingsSourcePreference(
    val mode: SportsStandingsSourceMode = SportsStandingsSourceMode.AUTOMATIC,
    val providerInstanceId: String? = null,
) {
    init {
        require((mode == SportsStandingsSourceMode.PROVIDER) == !providerInstanceId.isNullOrBlank())
    }

    companion object {
        val AUTOMATIC = SportsStandingsSourcePreference()
        val OFF = SportsStandingsSourcePreference(SportsStandingsSourceMode.OFF)
        fun provider(instanceId: String) = SportsStandingsSourcePreference(
            mode = SportsStandingsSourceMode.PROVIDER,
            providerInstanceId = instanceId,
        )
    }
}

interface SportsStandingsSourceSettings {
    val standingsSourcePreference: Flow<SportsStandingsSourcePreference>

    suspend fun setStandingsSourcePreference(preference: SportsStandingsSourcePreference)
}
