package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.policy.liveFavoritesOrderScope
import com.streamvault.domain.policy.liveCustomGroupOrderScope
import com.streamvault.domain.policy.liveProviderOrderIdentity
import com.streamvault.domain.policy.liveOrderKeys
import com.streamvault.domain.model.LiveChannelOrderScope
import com.streamvault.domain.usecase.ObserveLiveChannelOrdering
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/** Owns channel catalog lookup and channel-scoped playback/error mutations for the player. */
class PlayerChannelCoordinator @Inject constructor(
    private val repository: ChannelRepository,
    private val observeLiveChannelOrdering: ObserveLiveChannelOrdering,
    private val providers: ProviderRepository,
    private val favorites: FavoriteRepository,
    private val combined: CombinedM3uRepository
) {
    internal fun getCategories(providerId: Long): Flow<List<Category>> =
        repository.getCategories(providerId)

    internal fun getChannelsByIds(ids: List<Long>): Flow<List<Channel>> =
        repository.getChannelsByIds(ids)

    internal fun getChannelsByNumber(providerId: Long, categoryId: Long): Flow<List<Channel>> =
        observeLiveChannelOrdering(providerId, categoryId, repository.getChannelsByNumber(providerId, categoryId))

    internal fun orderVirtual(providerId: Long, profileId: Long?, categoryId: Long, membership: Flow<List<Channel>>): Flow<List<Channel>> = flow {
        val profile = profileId?.let { combined.getProfile(it) }
        if (profileId != null && profile == null) { emitAll(membership); return@flow }
        val providerIds = profile?.members?.filter { it.enabled }?.map { it.providerId }?.distinct() ?: listOf(providerId)
        val isFavorites = categoryId == VirtualCategoryIds.FAVORITES
        val group = if (isFavorites) null else favorites.getGroups(providerIds, ContentType.LIVE).first()
            .singleOrNull { it.id == -categoryId }
        val ownerId = group?.providerId ?: providerIds.singleOrNull()
        val owner = ownerId?.let { providers.getProvider(it) }
        val scope = when {
            isFavorites && profile != null -> LiveChannelOrderScope("combined:${profile.identityKey}", "live:favorites")
            isFavorites && owner != null -> liveFavoritesOrderScope(liveProviderOrderIdentity(owner))
            group != null && owner != null -> liveCustomGroupOrderScope(owner, group)
            else -> null
        }
        if (scope == null) { emitAll(membership); return@flow }
        val combinedFavorites = profile != null && isFavorites
        val usageIds = if (combinedFavorites) providerIds else listOfNotNull(ownerId)
        val identities = if (combinedFavorites) usageIds.mapNotNull { id ->
            providers.getProvider(id)?.let { id to liveProviderOrderIdentity(it) }
        }.toMap() else emptyMap()
        emitAll(combine(membership, observeLiveChannelOrdering.observeSettings(scope, usageIds, identities)) { rows, snapshot ->
            snapshot.apply(rows) { channel ->
                identities[channel.providerId]?.let(channel::liveOrderKeys) ?: channel.liveOrderKeys()
            }
        })
    }

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
