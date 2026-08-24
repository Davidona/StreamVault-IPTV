package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.ChannelRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Owns channel catalog lookup and channel-scoped playback/error mutations for the player. */
class PlayerChannelCoordinator @Inject constructor(
    private val repository: ChannelRepository
) {
    internal fun getCategories(providerId: Long): Flow<List<Category>> =
        repository.getCategories(providerId)

    internal fun getChannelsByIds(ids: List<Long>): Flow<List<Channel>> =
        repository.getChannelsByIds(ids)

    internal fun getChannelsByNumber(providerId: Long, categoryId: Long): Flow<List<Channel>> =
        repository.getChannelsByNumber(providerId, categoryId)

    internal suspend fun getChannel(channelId: Long): Channel? =
        repository.getChannel(channelId)

    internal suspend fun getStreamInfo(
        channel: Channel,
        preferStableUrl: Boolean = false
    ): Result<StreamInfo> = repository.getStreamInfo(channel, preferStableUrl)

    internal suspend fun incrementChannelErrorCount(channelId: Long): Result<Unit> =
        repository.incrementChannelErrorCount(channelId)

    internal suspend fun resetChannelErrorCount(channelId: Long): Result<Unit> =
        repository.resetChannelErrorCount(channelId)
}
