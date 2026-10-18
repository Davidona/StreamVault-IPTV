package com.streamvault.domain.repository

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface PlaybackHistoryRepository {
    suspend fun isIncognitoModeEnabled(): Boolean = false
    fun getRecentlyWatched(limit: Int = 100): Flow<List<PlaybackHistory>>
    suspend fun getRecentlyWatchedPage(offset: Int, limit: Int): List<PlaybackHistory> {
        require(offset >= 0)
        require(limit > 0)
        val endExclusive = offset.toLong() + limit
        require(endExclusive <= Int.MAX_VALUE)
        return getRecentlyWatched(endExclusive.toInt()).first().drop(offset).take(limit)
    }
    fun getRecentlyWatchedByProvider(providerId: Long, limit: Int = 100): Flow<List<PlaybackHistory>>
    fun getRecentlyWatchedByProviders(providerIds: Set<Long>, limit: Int = 100): Flow<List<PlaybackHistory>>
    fun getUnwatchedCount(providerId: Long, seriesId: Long): Flow<Int>
    suspend fun getPlaybackHistory(
        contentId: Long,
        contentType: ContentType,
        providerId: Long,
        seriesId: Long? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): PlaybackHistory?

    suspend fun markAsWatched(history: PlaybackHistory): Result<Unit>
    suspend fun importTraktHistory(history: PlaybackHistory,
        identity: com.streamvault.domain.trakt.TraktMediaIdentity,
        match: com.streamvault.domain.trakt.TraktHistoryMatch): Result<Boolean> =
        Result.error("History import is unavailable")
    suspend fun recordPlayback(history: PlaybackHistory): Result<Unit>
    suspend fun updateResumePosition(history: PlaybackHistory): Result<Unit>
    suspend fun flushPendingProgress(): Result<Unit>
    suspend fun removeFromHistory(contentId: Long, contentType: ContentType, providerId: Long): Result<Unit>
    suspend fun clearAllHistory(): Result<Unit>
    suspend fun clearHistoryForProvider(providerId: Long): Result<Unit>
    suspend fun clearLiveHistoryForProvider(providerId: Long): Result<Unit>
}
