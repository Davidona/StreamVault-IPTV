package com.streamvault.domain.repository

import com.streamvault.domain.model.LiveWatchDelta
import com.streamvault.domain.model.LiveWatchUsage
import kotlinx.coroutines.flow.Flow

/** Private local history. Never exported as inferred historical playback minutes. */
interface LiveWatchUsageRepository {
    /** Changes only on explicit history clearing; periodic watch ticks must not reshuffle browsing. */
    val resetVersion: Flow<Long> get() = kotlinx.coroutines.flow.flowOf(0L)
    /** Remote stream keys; time totals include the current UTC day and preceding 29 days. */
    fun observe(providerId: Long): Flow<Map<String, LiveWatchUsage>>
    suspend fun record(delta: LiveWatchDelta)
    suspend fun clearAll()
    suspend fun clearProvider(providerId: Long)
    suspend fun clearContent(providerId: Long, contentId: Long)
}
