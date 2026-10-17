package com.streamvault.domain.sports.provider

import kotlinx.coroutines.flow.Flow

enum class SportsScoreSourceMode { AUTOMATIC, OFF, PROVIDER }

data class SportsScoreSourcePreference(
    val mode: SportsScoreSourceMode = SportsScoreSourceMode.AUTOMATIC,
    val providerInstanceId: String? = null,
) {
    init {
        require((mode == SportsScoreSourceMode.PROVIDER) == !providerInstanceId.isNullOrBlank())
    }

    companion object {
        val AUTOMATIC = SportsScoreSourcePreference()
        val OFF = SportsScoreSourcePreference(SportsScoreSourceMode.OFF)
        fun provider(instanceId: String) = SportsScoreSourcePreference(
            mode = SportsScoreSourceMode.PROVIDER,
            providerInstanceId = instanceId,
        )
    }
}

interface SportsScoreSourceSettings {
    val scoreSourcePreference: Flow<SportsScoreSourcePreference>

    suspend fun setScoreSourcePreference(preference: SportsScoreSourcePreference)
}
