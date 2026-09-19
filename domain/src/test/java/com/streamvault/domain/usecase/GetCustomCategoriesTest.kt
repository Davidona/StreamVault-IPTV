package com.streamvault.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.LiveChannelVariant
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.model.VirtualGroup
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.FavoriteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GetCustomCategoriesTest {

    @Test
    fun mergesCustomCategoriesAcrossProviders() = runTest {
        val useCase = GetCustomCategories(
            favoriteRepository = FakeFavoriteRepository(
                favorites = listOf(
                    Favorite(providerId = 1L, contentId = 101L, contentType = ContentType.LIVE, groupId = null),
                    Favorite(providerId = 1L, contentId = 102L, contentType = ContentType.LIVE, groupId = 10L),
                    Favorite(providerId = 2L, contentId = 201L, contentType = ContentType.LIVE, groupId = 20L),
                    Favorite(providerId = 2L, contentId = 202L, contentType = ContentType.LIVE, groupId = 20L)
                ),
                groups = listOf(
                    VirtualGroup(id = 10L, providerId = 1L, name = "Sports", contentType = ContentType.LIVE),
                    VirtualGroup(id = 20L, providerId = 2L, name = "Kids", contentType = ContentType.LIVE)
                )
            ),
            channelRepository = FakeChannelRepository(
                channels = listOf(
                    rawChannel(id = 101L, name = "One", logicalGroupId = "p1_one", providerId = 1L),
                    rawChannel(id = 102L, name = "Two", logicalGroupId = "p1_two", providerId = 1L),
                    rawChannel(id = 201L, name = "Three", logicalGroupId = "p2_three", providerId = 2L),
                    rawChannel(id = 202L, name = "Four", logicalGroupId = "p2_four", providerId = 2L)
                )
            )
        )

        val result = useCase(listOf(1L, 2L), ContentType.LIVE).first()

        assertThat(result).hasSize(3)
        assertThat(result[0].id).isEqualTo(VirtualCategoryIds.FAVORITES)
        assertThat(result[0].count).isEqualTo(1)
        assertThat(result.drop(1).map { it.name to it.count })
            .containsExactly("Sports" to 1, "Kids" to 2)
    }

    @Test
    fun liveCustomGroupCountsFollowGroupedChannelPresentation() = runTest {
        val useCase = GetCustomCategories(
            favoriteRepository = FakeFavoriteRepository(
                favorites = listOf(
                    Favorite(providerId = 1L, contentId = 101L, contentType = ContentType.LIVE, groupId = 10L),
                    Favorite(providerId = 1L, contentId = 102L, contentType = ContentType.LIVE, groupId = 10L)
                ),
                groups = listOf(
                    VirtualGroup(id = 10L, providerId = 1L, name = "Sports", contentType = ContentType.LIVE)
                )
            ),
            channelRepository = FakeChannelRepository(
                channels = listOf(
                    groupedChannel(
                        id = 101L,
                        providerId = 1L,
                        logicalGroupId = "p1_sports",
                        rawIds = listOf(101L, 102L)
                    )
                )
            )
        )

        val result = useCase(listOf(1L), ContentType.LIVE).first()

        assertThat(result).hasSize(2)
        assertThat(result[1].name).isEqualTo("Sports")
        assertThat(result[1].count).isEqualTo(1)
    }

    @Test
    fun returnsEmptyListForNoProviders() = runTest {
        val useCase = GetCustomCategories(FakeFavoriteRepository(), FakeChannelRepository())

        val result = useCase(emptyList(), ContentType.LIVE).first()

        assertThat(result).isEmpty()
    }

    private class FakeFavoriteRepository(
        private val favorites: List<Favorite> = emptyList(),
        private val groups: List<VirtualGroup> = emptyList()
    ) : FavoriteRepository {
        override fun getFavorites(providerId: Long, contentType: ContentType?): Flow<List<Favorite>> =
            getFavorites(listOf(providerId), contentType)

        override fun getFavorites(providerIds: List<Long>, contentType: ContentType?): Flow<List<Favorite>> =
            flowOf(
                favorites.filter { favorite ->
                    favorite.providerId in providerIds &&
                        favorite.groupId == null &&
                        (contentType == null || favorite.contentType == contentType)
                }
            )

        override fun getAllFavorites(providerIds: List<Long>, contentType: ContentType): Flow<List<Favorite>> =
            flowOf(
                favorites.filter { favorite ->
                    favorite.providerId in providerIds && favorite.contentType == contentType
                }
            )

        @Deprecated("Use getFavorites(providerId, contentType) instead")
        override fun getAllFavorites(providerId: Long, contentType: ContentType): Flow<List<Favorite>> =
            getAllFavorites(listOf(providerId), contentType)

        override fun getFavoritesByGroup(groupId: Long): Flow<List<Favorite>> =
            flowOf(favorites.filter { it.groupId == groupId })

        override fun getGroups(providerId: Long, contentType: ContentType): Flow<List<VirtualGroup>> =
            getGroups(listOf(providerId), contentType)

        override fun getGroups(providerIds: List<Long>, contentType: ContentType): Flow<List<VirtualGroup>> =
            flowOf(groups.filter { group -> group.providerId in providerIds && group.contentType == contentType })

        override fun getGlobalFavoriteCount(providerId: Long, contentType: ContentType): Flow<Int> =
            error("Not used in test")

        override fun getGroupFavoriteCounts(providerId: Long, contentType: ContentType): Flow<Map<Long, Int>> =
            error("Not used in test")

        override fun getGroupFavoriteCounts(providerIds: List<Long>, contentType: ContentType): Flow<Map<Long, Int>> =
            error("Not used in test")

        override suspend fun addFavorite(providerId: Long, contentId: Long, contentType: ContentType, groupId: Long?) =
            error("Not used in test")

        override suspend fun removeFavorite(providerId: Long, contentId: Long, contentType: ContentType, groupId: Long?) =
            error("Not used in test")

        override suspend fun moveFavoriteToGroup(
            providerId: Long,
            contentId: Long,
            contentType: ContentType,
            fromGroupId: Long?,
            targetGroupId: Long?
        ) = error("Not used in test")

        override suspend fun mergeGroupInto(sourceGroupId: Long, targetGroupId: Long) =
            error("Not used in test")

        override suspend fun reorderFavorites(favorites: List<Favorite>) = error("Not used in test")

        override suspend fun isFavorite(providerId: Long, contentId: Long, contentType: ContentType): Boolean =
            error("Not used in test")

        override suspend fun getGroupMemberships(providerId: Long, contentId: Long, contentType: ContentType): List<Long> =
            error("Not used in test")

        override suspend fun createGroup(providerId: Long, name: String, iconEmoji: String?, contentType: ContentType) =
            error("Not used in test")

        override suspend fun deleteGroup(groupId: Long) = error("Not used in test")

        override suspend fun renameGroup(groupId: Long, newName: String) = error("Not used in test")
    }

    private class FakeChannelRepository(
        private val channels: List<Channel> = emptyList()
    ) : ChannelRepository {
        override fun getChannels(providerId: Long): Flow<List<Channel>> = error("Not used in test")
        override fun getChannelCount(providerId: Long): Flow<Int> = error("Not used in test")
        override fun getChannelsByCategory(providerId: Long, categoryId: Long): Flow<List<Channel>> = error("Not used in test")
        override fun getChannelsByCategoryPage(providerId: Long, categoryId: Long, limit: Int): Flow<List<Channel>> = error("Not used in test")
        override fun getChannelsByNumber(providerId: Long, categoryId: Long): Flow<List<Channel>> = error("Not used in test")
        override fun getChannelsWithoutErrors(providerId: Long, categoryId: Long): Flow<List<Channel>> = error("Not used in test")
        override fun getChannelsWithoutErrorsPage(providerId: Long, categoryId: Long, limit: Int): Flow<List<Channel>> = error("Not used in test")
        override suspend fun getChannelsByCategoryPageOffset(providerId: Long, categoryId: Long, limit: Int, offset: Int): List<Channel> = error("Not used in test")
        override suspend fun getChannelsWithoutErrorsPageOffset(providerId: Long, categoryId: Long, limit: Int, offset: Int): List<Channel> = error("Not used in test")
        override fun searchChannelsByCategory(providerId: Long, categoryId: Long, query: String): Flow<List<Channel>> = error("Not used in test")
        override fun searchChannelsByCategoryPaged(providerId: Long, categoryId: Long, query: String, limit: Int): Flow<List<Channel>> = error("Not used in test")
        override fun getCategories(providerId: Long): Flow<List<com.streamvault.domain.model.Category>> = error("Not used in test")
        override suspend fun getCategoriesSnapshot(providerId: Long): List<com.streamvault.domain.model.Category> =
            error("Not used in test")
        override fun searchChannels(providerId: Long, query: String): Flow<List<Channel>> = error("Not used in test")
        override suspend fun getChannel(channelId: Long): Channel? = error("Not used in test")
        override suspend fun getStreamInfo(channel: Channel, preferStableUrl: Boolean) = error("Not used in test")
        override suspend fun refreshChannels(providerId: Long) = error("Not used in test")
        override fun getChannelsByIds(ids: List<Long>): Flow<List<Channel>> =
            flowOf(channels.filter { channel -> channel.allVariantRawIds().any(ids::contains) })
        override suspend fun incrementChannelErrorCount(channelId: Long) = error("Not used in test")
        override suspend fun resetChannelErrorCount(channelId: Long) = error("Not used in test")
    }

    private fun rawChannel(id: Long, name: String, logicalGroupId: String, providerId: Long): Channel = Channel(
        id = id,
        name = name,
        providerId = providerId,
        logicalGroupId = logicalGroupId,
        selectedVariantId = id,
        variants = listOf(
            LiveChannelVariant(
                rawChannelId = id,
                logicalGroupId = logicalGroupId,
                providerId = providerId,
                originalName = name,
                canonicalName = name,
                streamUrl = "https://example.com/$id"
            )
        )
    )

    private fun groupedChannel(id: Long, providerId: Long, logicalGroupId: String, rawIds: List<Long>): Channel = Channel(
        id = id,
        name = "Grouped",
        providerId = providerId,
        logicalGroupId = logicalGroupId,
        selectedVariantId = id,
        variants = rawIds.map { rawId ->
            LiveChannelVariant(
                rawChannelId = rawId,
                logicalGroupId = logicalGroupId,
                providerId = providerId,
                originalName = "Grouped $rawId",
                canonicalName = "Grouped",
                streamUrl = "https://example.com/$rawId"
            )
        }
    )
}
