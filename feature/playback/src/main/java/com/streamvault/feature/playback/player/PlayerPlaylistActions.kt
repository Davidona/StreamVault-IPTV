@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.streamvault.feature.playback.player

import com.streamvault.domain.playback.orderedByRequestedRawIds
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal fun PlayerViewModel.observeCombinedLivePlaylist(
    profileId: Long,
    categoryId: Long
): Flow<List<Channel>> = when {
    categoryId == ChannelRepository.ALL_CHANNELS_ID -> {
        playerPlaylistCoordinator.getCombinedCategories(profileId).flatMapLatest { combinedCategories ->
            combinedCategoriesById = combinedCategories.associateBy { it.category.id }
            val flows = combinedCategories.map { playerPlaylistCoordinator.getCombinedChannels(profileId, it) }
            if (flows.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(flows) { arrays ->
                    arrays.toList().flatMap { it }
                }.map(::applyCombinedSourceProviderFilter)
            }
        }
    }

    categoryId == VirtualCategoryIds.RECENT -> {
        combinedProviderIdsFlow(profileId)
            .flatMapLatest { providerIds -> observeRecentLiveIds(effectiveCombinedProviderIds(providerIds), 24) }
            .flatMapLatest { ids -> loadLiveChannelsByOrderedIds(ids, currentCombinedSourceFilterProviderId) }
    }

    categoryId == VirtualCategoryIds.FAVORITES -> {
        combinedProviderIdsFlow(profileId)
            .flatMapLatest { providerIds -> observeLiveFavorites(effectiveCombinedProviderIds(providerIds)) }
            .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
            .flatMapLatest { ids -> loadLiveChannelsByOrderedIds(ids, currentCombinedSourceFilterProviderId) }
    }

    categoryId < 0L -> {
        playerPlaylistCoordinator.getFavoritesByGroup(-categoryId)
            .map { favorites ->
                favorites
                    .sortedBy { it.position }
                    .let { groupFavorites ->
                        currentCombinedSourceFilterProviderId?.let { selectedProviderId ->
                            groupFavorites.filter { it.providerId == selectedProviderId }
                        } ?: groupFavorites
                    }
                    .map { it.contentId }
            }
            .flatMapLatest { ids -> loadLiveChannelsByOrderedIds(ids, currentCombinedSourceFilterProviderId) }
    }

    else -> {
        playerPlaylistCoordinator.getCombinedCategories(profileId).flatMapLatest { combinedCategories ->
            combinedCategoriesById = combinedCategories.associateBy { it.category.id }
            val combinedCategory = combinedCategoriesById[categoryId]
            if (combinedCategory == null) {
                flowOf(emptyList())
            } else {
                playerPlaylistCoordinator.getCombinedChannels(profileId, combinedCategory)
                    .map(::applyCombinedSourceProviderFilter)
            }
        }
    }
}

internal fun PlayerViewModel.observeRecentChannels() {
    recentChannelsJob?.cancel()
    if (currentContentType != ContentType.LIVE || (currentProviderId <= 0 && currentCombinedProfileId == null)) {
        recentChannelsFlow.value = emptyList()
        return
    }

    recentChannelsJob = playbackSessionScope()?.launch {
        val recentFlow = currentCombinedProfileId?.let { profileId ->
            combinedProviderIdsFlow(profileId)
                .flatMapLatest { providerIds -> observeRecentLiveIds(effectiveCombinedProviderIds(providerIds), 12) }
                .flatMapLatest { ids -> loadLiveChannelsByOrderedIds(ids, currentCombinedSourceFilterProviderId) }
        } ?: playbackHistoryCoordinator.recentlyWatchedByProvider(currentProviderId, limit = 12)
            .map { history ->
                history.asSequence()
                    .filter { it.contentType == ContentType.LIVE }
                    .sortedByDescending { it.lastWatchedAt }
                    .distinctBy { it.contentId }
                    .map { it.contentId }
                    .toList()
            }
            .flatMapLatest { ids -> loadLiveChannelsByOrderedIds(ids) }

        combine(recentFlow, playerPreferencesCoordinator.liveChannelNumberingMode) { channels, numberingMode ->
            numberingMode to channels
        }.collect { (numberingMode, channels) ->
            channelNumberingMode = numberingMode
            val currentListNumbers = channelList.withIndex().associate { (index, channel) ->
                channel.id to resolveChannelNumber(channel, index)
            }
            recentChannelsFlow.value = channels
                .filterNot { it.id == currentContentId }
                .map { channel ->
                    currentListNumbers[channel.id]?.let { number ->
                        channel.copy(number = number)
                    } ?: channel
                }
        }
    }
}

internal fun PlayerViewModel.observeLastVisitedCategory() {
    lastVisitedCategoryJob?.cancel()
    if (currentContentType != ContentType.LIVE || (currentProviderId <= 0 && currentCombinedProfileId == null)) {
        _lastVisitedCategory.value = null
        return
    }

    lastVisitedCategoryJob = playbackSessionScope()?.launch {
        val categoriesFlow = currentCombinedProfileId?.let { profileId ->
            combinedProviderIdsFlow(profileId).flatMapLatest { providerIds ->
                combine(
                    playerPlaylistCoordinator.getCombinedCategories(profileId),
                    playerPlaylistCoordinator.getCustomCategories(providerIds)
                ) { combinedCategories, customCategories ->
                    combinedCategoriesById = combinedCategories.associateBy { it.category.id }
                    buildCombinedLiveCategories(combinedCategories, customCategories) to null
                }
            }
        } ?: combine(
            playerPlaylistCoordinator.getCategories(currentProviderId),
            playerPlaylistCoordinator.getCustomCategories(listOf(currentProviderId)),
            playerPreferencesCoordinator.getLastLiveCategoryId(currentProviderId),
            playerPreferencesCoordinator.getHiddenCategoryIds(currentProviderId, ContentType.LIVE)
        ) { providerCategories, customCategories, lastVisitedCategoryId, hiddenCategoryIds ->
            val visibleProviderCategories = providerCategories.filter { category ->
                category.id == ChannelRepository.ALL_CHANNELS_ID || category.id !in hiddenCategoryIds
            }
            val adjustedProviderCategories = visibleProviderCategories.map { category ->
                if (category.id == ChannelRepository.ALL_CHANNELS_ID) {
                    category.copy(
                        count = providerCategories
                            .filter { it.id != ChannelRepository.ALL_CHANNELS_ID && it.id !in hiddenCategoryIds }
                            .sumOf(Category::count)
                    )
                } else {
                    category
                }
            }
            val allCategories = customCategories + adjustedProviderCategories
            val lastVisited = if (lastVisitedCategoryId == null || lastVisitedCategoryId == VirtualCategoryIds.RECENT) {
                null
            } else {
                allCategories.firstOrNull { it.id == lastVisitedCategoryId }
            }
            allCategories to lastVisited
        }

        categoriesFlow.collect { (allCategories, lastVisited) ->
            availableCategoriesFlow.value = allCategories
            _lastVisitedCategory.value = lastVisited
        }
    }
}

fun PlayerViewModel.openLastVisitedCategory() {
    val category = _lastVisitedCategory.value ?: return
    if (currentContentType != ContentType.LIVE) return

    currentCategoryId = category.id
    activeCategoryIdFlow.value = category.id
    isVirtualCategory = category.isVirtual
    loadPlaylist(
        categoryId = category.id,
        providerId = currentProviderId,
        isVirtual = category.isVirtual,
        initialChannelId = currentContentId
    )
    openChannelListOverlay()
}

internal fun PlayerViewModel.loadPlaylist(
    categoryId: Long,
    providerId: Long,
    isVirtual: Boolean,
    initialChannelId: Long
) {
    playlistJob?.cancel()
    playlistJob = playbackSessionScope()?.launch {
        val flows = currentCombinedProfileId?.let { profileId ->
            observeCombinedLivePlaylist(profileId, categoryId)
        } ?: if (isVirtual) {
            if (categoryId == VirtualCategoryIds.RECENT) {
                playbackHistoryCoordinator.recentlyWatchedByProvider(providerId, limit = 24)
                    .map { history ->
                        history.asSequence()
                            .filter { it.contentType == ContentType.LIVE }
                            .sortedByDescending { it.lastWatchedAt }
                            .distinctBy { it.contentId }
                            .map { it.contentId }
                            .toList()
                    }
                    .flatMapLatest { ids ->
                        if (ids.isEmpty()) flowOf(emptyList())
                        else playerPlaylistCoordinator.getChannelsByIds(ids).map { unsorted ->
                            unsorted.orderedByRequestedRawIds(ids)
                        }
                    }
            } else if (categoryId == VirtualCategoryIds.FAVORITES) {
                playerPlaylistCoordinator.getFavorites(currentProviderId)
                    .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                    .flatMapLatest { ids ->
                        if (ids.isEmpty()) flowOf(emptyList())
                        else playerPlaylistCoordinator.getChannelsByIds(ids).map { unsorted ->
                            unsorted.orderedByRequestedRawIds(ids)
                        }
                    }
            } else {
                val groupId = if (categoryId < 0) -categoryId else categoryId
                playerPlaylistCoordinator.getFavoritesByGroup(groupId)
                    .map { favorites -> favorites.sortedBy { it.position }.map { it.contentId } }
                    .flatMapLatest { ids ->
                        if (ids.isEmpty()) flowOf(emptyList())
                        else playerPlaylistCoordinator.getChannelsByIds(ids).map { unsorted ->
                            unsorted.orderedByRequestedRawIds(ids)
                        }
                    }
            }
        } else {
            playerPlaylistCoordinator.getChannelsByNumber(providerId, categoryId)
        }

        combine(flows, playerPreferencesCoordinator.liveChannelNumberingMode) { channels, numberingMode ->
            val displayedChannels = when (numberingMode) {
                ChannelNumberingMode.GROUP -> channels.mapIndexed { index, channel ->
                    channel.copy(number = index + 1)
                }
                ChannelNumberingMode.PROVIDER -> channels
                ChannelNumberingMode.HIDDEN -> channels.map { it.copy(number = 0) }
            }
            numberingMode to displayedChannels.sanitizedChannelsForPlayer()
        }.collect { (numberingMode, displayedChannels) ->
            channelNumberingMode = numberingMode
            channelList = displayedChannels
            currentChannelFlowList.value = displayedChannels
            val targetId = if (currentContentId != -1L) currentContentId else initialChannelId
            if (targetId != -1L) {
                currentChannelIndex = channelList.indexOfFirst { it.id == targetId }
            }
            if (currentChannelIndex == -1) {
                currentChannelIndex = channelList.indexOfFirst { it.streamUrl == currentStreamUrl }
            }

            if (currentChannelIndex != -1) {
                currentChannelFlow.value = channelList[currentChannelIndex].sanitizedForPlayer()
                refreshCurrentChannelRecording()
                val channel = channelList[currentChannelIndex]
                displayChannelNumberFlow.value = resolveChannelNumber(channel, currentChannelIndex)
            } else if (displayedChannels.isNotEmpty() && currentContentId != -1L) {
                showPlayerNotice(
                    message = "Channel not found in current playlist",
                    recoveryType = PlayerRecoveryType.SOURCE,
                    actions = listOf(PlayerNoticeAction.OPEN_GUIDE)
                )
            }
        }
    }
}

private fun PlayerViewModel.applyCombinedSourceProviderFilter(channels: List<Channel>): List<Channel> {
    val selectedProviderId = currentCombinedSourceFilterProviderId ?: return channels
    return channels.filter { it.providerId == selectedProviderId }
}

private fun PlayerViewModel.loadLiveChannelsByOrderedIds(
    ids: List<Long>,
    providerId: Long? = null
): Flow<List<Channel>> = if (ids.isEmpty()) {
    flowOf(emptyList())
} else {
    playerPlaylistCoordinator.getChannelsByIds(ids).map { unsorted ->
        val filtered = providerId?.let { requiredProviderId ->
            unsorted.filter { it.providerId == requiredProviderId }
        } ?: unsorted
        filtered.orderedByRequestedRawIds(ids)
    }
}

private fun PlayerViewModel.combinedProviderIdsFlow(profileId: Long): Flow<List<Long>> = flow {
    emit(playerPlaylistCoordinator.getProfile(profileId)?.members.orEmpty())
}.map { members ->
    currentCombinedProfileMembers = members
    members.filter { it.enabled }.map { it.providerId }
}

private fun PlayerViewModel.effectiveCombinedProviderIds(providerIds: List<Long>): List<Long> =
    currentCombinedSourceFilterProviderId?.let { selectedProviderId ->
        providerIds.filter { it == selectedProviderId }
    } ?: providerIds

private fun PlayerViewModel.observeLiveFavorites(providerIds: List<Long>): Flow<List<Favorite>> = when (providerIds.size) {
    0 -> flowOf(emptyList())
    1 -> playerPlaylistCoordinator.getFavorites(providerIds.first())
    else -> playerPlaylistCoordinator.getFavorites(providerIds)
}

private fun PlayerViewModel.observeRecentLiveIds(providerIds: List<Long>, limit: Int): Flow<List<Long>> = when (providerIds.size) {
    0 -> flowOf(emptyList())
    1 -> playbackHistoryCoordinator.recentlyWatchedByProvider(providerIds.first(), limit)
        .map { history ->
            history.asSequence()
                .filter { it.contentType == ContentType.LIVE }
                .sortedByDescending { it.lastWatchedAt }
                .distinctBy { it.contentId }
                .map { it.contentId }
                .take(limit)
                .toList()
        }

    else -> combine(providerIds.map { providerId ->
        playbackHistoryCoordinator.recentlyWatchedByProvider(providerId, limit)
    }) { histories ->
        histories.toList()
            .flatMap { it }
            .asSequence()
            .filter { it.contentType == ContentType.LIVE }
            .sortedByDescending { it.lastWatchedAt }
            .distinctBy { it.providerId to it.contentId }
            .map { it.contentId }
            .take(limit)
            .toList()
    }
}

private fun buildCombinedLiveCategories(
    combinedCategories: List<com.streamvault.domain.model.CombinedCategory>,
    customCategories: List<Category>
): List<Category> = buildList {
    val favoritesCategory = customCategories.find { it.id == VirtualCategoryIds.FAVORITES }
    if (favoritesCategory != null) {
        add(favoritesCategory)
    }
    add(
        Category(
            id = VirtualCategoryIds.RECENT,
            name = "Recent",
            type = ContentType.LIVE,
            isVirtual = true,
            count = 0
        )
    )
    addAll(customCategories.filter { it.id != VirtualCategoryIds.FAVORITES })
    add(
        Category(
            id = ChannelRepository.ALL_CHANNELS_ID,
            name = "All Channels",
            type = ContentType.LIVE,
            count = combinedCategories.sumOf { it.category.count }
        )
    )
    addAll(combinedCategories.map { it.category })
}
