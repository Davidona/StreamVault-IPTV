package com.streamvault.domain.repository

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import kotlinx.coroutines.flow.Flow

interface ChannelRepository {
    fun getChannels(providerId: Long): Flow<List<Channel>>
    fun getChannelCount(providerId: Long): Flow<Int>
    fun getChannelsByCategory(providerId: Long, categoryId: Long): Flow<List<Channel>>
    fun getChannelsByCategoryPage(providerId: Long, categoryId: Long, limit: Int): Flow<List<Channel>>
    fun getChannelsByNumber(providerId: Long, categoryId: Long = ALL_CHANNELS_ID): Flow<List<Channel>>
    fun getChannelsWithoutErrors(providerId: Long, categoryId: Long = ALL_CHANNELS_ID): Flow<List<Channel>>
    fun getChannelsWithoutErrorsPage(providerId: Long, categoryId: Long = ALL_CHANNELS_ID, limit: Int): Flow<List<Channel>>
    suspend fun getChannelsByCategoryPageOffset(providerId: Long, categoryId: Long, limit: Int, offset: Int): List<Channel>
    suspend fun getChannelsWithoutErrorsPageOffset(providerId: Long, categoryId: Long, limit: Int, offset: Int): List<Channel>
    fun searchChannelsByCategory(providerId: Long, categoryId: Long, query: String): Flow<List<Channel>>
    fun searchChannelsByCategoryPaged(providerId: Long, categoryId: Long, query: String, limit: Int): Flow<List<Channel>>
    fun getCategories(providerId: Long): Flow<List<Category>>
    suspend fun getCategoriesSnapshot(providerId: Long): List<Category>
    fun searchChannels(providerId: Long, query: String): Flow<List<Channel>>
    suspend fun getChannel(channelId: Long): Channel?
    suspend fun getStreamInfo(channel: Channel, preferStableUrl: Boolean = false): Result<StreamInfo>
    suspend fun refreshChannels(providerId: Long): Result<Unit>
    fun getChannelsByIds(ids: List<Long>): Flow<List<Channel>>
    suspend fun incrementChannelErrorCount(channelId: Long): Result<Unit>
    suspend fun resetChannelErrorCount(channelId: Long): Result<Unit>

    companion object {
        const val ALL_CHANNELS_ID = -1_000_000L
    }
}
