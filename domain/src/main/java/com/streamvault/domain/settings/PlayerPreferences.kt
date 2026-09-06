package com.streamvault.domain.settings

import com.streamvault.domain.model.LiveChannelObservedQuality
import com.streamvault.domain.model.VodVariantObservation
import kotlinx.coroutines.flow.Flow

/** Player and multiview preferences exposed without leaking persistence details. */
interface PlayerPreferences : SettingsPreferences {
    val playerMuted: Flow<Boolean>

    val liveVariantObservations: Flow<Map<Long, LiveChannelObservedQuality>>

    val vodVariantObservations: Flow<Map<Long, VodVariantObservation>>

    val lastActiveProviderId: Flow<Long?>

    val multiViewPerformanceMode: Flow<String?>

    fun getLastLiveCategoryId(providerId: Long): Flow<Long?>

    fun getAspectRatioForChannel(channelId: Long): Flow<String?>

    fun observeAudioVideoOffsetForChannel(channelId: Long): Flow<Int?>

    fun getMultiViewPreset(presetIndex: Int): Flow<List<Long>>

    suspend fun clearAudioVideoOffsetForChannel(channelId: Long)

    suspend fun recordLiveVariantObservation(
        rawChannelId: Long,
        observedQuality: LiveChannelObservedQuality
    )

    suspend fun recordVodVariantObservation(
        rawItemId: Long,
        observation: VodVariantObservation
    )

    suspend fun setPlayerMuted(muted: Boolean)

    suspend fun setAspectRatioForChannel(channelId: Long, ratio: String)

    suspend fun setAudioVideoOffsetForChannel(channelId: Long, offsetMs: Int)

    suspend fun setPreferredLiveVariant(
        providerId: Long,
        logicalGroupId: String,
        rawChannelId: Long
    )

    suspend fun setMultiViewPreset(presetIndex: Int, channelIds: List<Long>)

    suspend fun setMultiViewPerformanceMode(mode: String)
}
