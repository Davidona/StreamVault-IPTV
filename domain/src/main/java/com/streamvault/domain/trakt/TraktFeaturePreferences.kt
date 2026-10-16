package com.streamvault.domain.trakt

import kotlinx.coroutines.flow.Flow

/** User choices that control optional Trakt reads and playback writes. */
interface TraktFeaturePreferences {
    val scrobblingEnabled: Flow<Boolean>
    suspend fun setScrobblingEnabled(enabled: Boolean)
    val historySyncEnabled: Flow<Boolean>
    suspend fun setHistorySyncEnabled(enabled: Boolean)
}
