package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.CombinedCategory
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.usecase.GetCustomCategories
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Owns provider/favorite/category data access used to build live playlists. */
class PlayerPlaylistCoordinator @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val getCustomCategories: GetCustomCategories,
    private val channelCoordinator: PlayerChannelCoordinator
) {
    internal fun getFavorites(providerId: Long): Flow<List<Favorite>> =
        favoriteRepository.getFavorites(providerId, ContentType.LIVE)

    internal fun getFavorites(providerIds: List<Long>): Flow<List<Favorite>> =
        favoriteRepository.getFavorites(providerIds, ContentType.LIVE)

    internal fun getFavoritesByGroup(groupId: Long): Flow<List<Favorite>> =
        favoriteRepository.getFavoritesByGroup(groupId)

    internal fun getCustomCategories(providerIds: List<Long>): Flow<List<Category>> =
        getCustomCategories(providerIds, ContentType.LIVE)

    internal fun getCategories(providerId: Long): Flow<List<Category>> =
        channelCoordinator.getCategories(providerId)

    internal fun getChannelsByIds(ids: List<Long>): Flow<List<Channel>> =
        channelCoordinator.getChannelsByIds(ids)

    internal fun getChannelsByNumber(providerId: Long, categoryId: Long): Flow<List<Channel>> =
        channelCoordinator.getChannelsByNumber(providerId, categoryId)

    internal fun getCombinedCategories(profileId: Long): Flow<List<CombinedCategory>> =
        combinedM3uRepository.getCombinedCategories(profileId)

    internal fun getCombinedChannels(
        profileId: Long,
        category: CombinedCategory
    ): Flow<List<Channel>> = combinedM3uRepository.getCombinedChannels(profileId, category)

    internal suspend fun getProfile(profileId: Long): CombinedM3uProfile? =
        combinedM3uRepository.getProfile(profileId)
}
