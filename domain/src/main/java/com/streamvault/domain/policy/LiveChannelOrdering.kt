package com.streamvault.domain.policy

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.LiveChannelOrder
import com.streamvault.domain.model.LiveWatchUsage
import com.streamvault.domain.model.LiveChannelOrderMode
import com.streamvault.domain.model.LiveChannelOrderScope
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.CombinedCategoryIdentity
import java.util.Locale
import java.security.MessageDigest

/** Source fingerprint contains no plaintext account details and survives local row-ID remapping. */
fun liveChannelOrderScope(provider: LegacyProvider, category: Category): LiveChannelOrderScope {
    return LiveChannelOrderScope(liveProviderOrderIdentity(provider), "live:${category.id}")
}

/** Stable, non-secret identity for a provider as a member of a combined source. */
fun liveProviderOrderIdentity(provider: LegacyProvider): String {
    val parts = listOf(provider.type.name, provider.createdAt.toString(), provider.serverUrl.trimEnd('/'),
        provider.username, provider.m3uUrl, provider.stalkerMacAddress.uppercase())
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(
        parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it) }
    return "provider:$fingerprint"
}

/** Stable profile and category scope for combined-source settings and backups. */
fun combinedLiveChannelOrderScope(profile: CombinedM3uProfile, category: Category): LiveChannelOrderScope =
    LiveChannelOrderScope("combined:${profile.identityKey}", when {
        category.id == com.streamvault.domain.repository.ChannelRepository.ALL_CHANNELS_ID -> "live:all"
        else -> "live:category:${CombinedCategoryIdentity.keyForName(category.name)}"
    })

/** Favorites inherit the selected provider/profile source while retaining their own override. */
fun liveFavoritesOrderScope(sourceIdentity: String): LiveChannelOrderScope =
    LiveChannelOrderScope(sourceIdentity, "live:favorites")

/** Custom groups belong to their owning provider and use an opaque portable group identity. */
fun liveCustomGroupOrderScope(provider: LegacyProvider, group: com.streamvault.domain.model.VirtualGroup): LiveChannelOrderScope {
    require(group.identityKey.isNotBlank()) { "Custom group is missing its stable identity" }
    return LiveChannelOrderScope(liveProviderOrderIdentity(provider), "live:group:${group.identityKey}")
}

/** Store every remote variant so selecting another quality never loses the row's position. */
fun Channel.liveOrderKeys(): List<String> {
    val remoteIds = (listOf(streamId) + variants.map { it.streamId }).filter { it != 0L }.distinct()
    return remoteIds.map { "stream:$it" }
}

/** Combined channels may share provider stream ids, so include the stable member-provider key. */
fun Channel.liveOrderKeys(providerIdentity: String): List<String> =
    liveOrderKeys().map { key -> "$providerIdentity|$key" }

fun applyLiveChannelOrder(channels: List<Channel>, savedKeys: List<String>): List<Channel> {
    val ranks = savedKeys.withIndex().associate { it.value to it.index }
    return channels.withIndex().sortedWith(compareBy<IndexedValue<Channel>> {
        it.value.liveOrderKeys().mapNotNull(ranks::get).minOrNull() ?: Int.MAX_VALUE
    }.thenBy { it.index }).map { it.value }
}

/** Replace visible positions while preserving saved slots for hidden/protected members. */
fun mergeLiveOrderVisibleKeys(baseKeys: List<String>, visibleOrderedKeys: List<String>): List<String> {
    require(visibleOrderedKeys.distinct().size == visibleOrderedKeys.size)
    val visible = visibleOrderedKeys.toHashSet()
    val pending = visibleOrderedKeys.iterator()
    return buildList {
        baseKeys.distinct().forEach { key -> add(if (key in visible) pending.next() else key) }
        while (pending.hasNext()) add(pending.next())
    }
}

/** A visibility-filtered lookup cannot prove missing favorites were removed. */
fun LiveChannelOrder.reconcileVisibleMembership(
    requestedIds: Collection<Long>, rows: List<Channel>, keys: List<String>
): LiveChannelOrder {
    val represented = rows.flatMap { it.allVariantRawIds() }.toSet()
    return if (requestedIds.all { it in represented }) reconcile(keys)
    else copy(manualKeys = (manualKeys + keys).distinct())
}

/** Predefined choices backed by the shared policy. */
val liveChannelPresetModes = listOf(
    LiveChannelOrderMode.PROVIDER, LiveChannelOrderMode.TITLE_ASC, LiveChannelOrderMode.TITLE_DESC,
    LiveChannelOrderMode.RECENTLY_WATCHED, LiveChannelOrderMode.MOST_WATCHED
)

fun LiveChannelOrder.effectiveMode(defaultMode: LiveChannelOrderMode): LiveChannelOrderMode =
    if (mode == LiveChannelOrderMode.USE_DEFAULT) {
        defaultMode.takeIf { it in liveChannelPresetModes } ?: LiveChannelOrderMode.PROVIDER
    } else mode

/** Sort the complete membership before filtering or paging; equal titles retain provider order. */
fun orderLiveChannels(channels: List<Channel>, order: LiveChannelOrder, defaultMode: LiveChannelOrderMode,
    usage: Map<String, LiveWatchUsage> = emptyMap(),
    keySelector: (Channel) -> List<String> = { it.liveOrderKeys() }): List<Channel> =
    when (order.effectiveMode(defaultMode)) {
        LiveChannelOrderMode.MANUAL -> {
            val ranks = order.manualKeys.withIndex().associate { it.value to it.index }
            channels.withIndex().sortedWith(compareBy<IndexedValue<Channel>> {
                keySelector(it.value).mapNotNull(ranks::get).minOrNull() ?: Int.MAX_VALUE
            }.thenBy { it.index }).map { it.value }
        }
        LiveChannelOrderMode.TITLE_ASC -> channels.sortedBy { it.name.lowercase(Locale.ROOT) }
        LiveChannelOrderMode.TITLE_DESC -> channels.sortedByDescending { it.name.lowercase(Locale.ROOT) }
        LiveChannelOrderMode.RECENTLY_WATCHED -> rankLiveUsage(channels, usage, keySelector)
            .sortedWith(compareByDescending<ChannelWatchRank> { it.lastAt }.thenBy { it.index }).map { it.channel }
        LiveChannelOrderMode.MOST_WATCHED -> rankLiveUsage(channels, usage, keySelector)
            .sortedWith(compareByDescending<ChannelWatchRank> { it.ms }
                .thenByDescending { if (it.ms > 0) it.lastAt else 0 }.thenBy { it.index }).map { it.channel }
        else -> channels
    }

private data class ChannelWatchRank(val channel: Channel, val index: Int, val ms: Long, val lastAt: Long)
private fun rankLiveUsage(channels: List<Channel>, usage: Map<String, LiveWatchUsage>, keySelector: (Channel) -> List<String>) = channels.mapIndexed { index, channel ->
    val parts = keySelector(channel).mapNotNull(usage::get)
    ChannelWatchRank(channel, index, parts.sumOf { it.watchedMs.coerceAtLeast(0) }, parts.maxOfOrNull { it.lastWatchedAtMs } ?: 0)
}

data class LiveChannelOrderSnapshot(
    val order: LiveChannelOrder,
    val defaultMode: LiveChannelOrderMode,
    val usage: Map<String, LiveWatchUsage> = emptyMap()
) {
    val effectiveMode get() = order.effectiveMode(defaultMode)
    fun apply(channels: List<Channel>) = orderLiveChannels(channels, order, defaultMode, usage)
    fun apply(channels: List<Channel>, keySelector: (Channel) -> List<String>) =
        orderLiveChannels(channels, order, defaultMode, usage, keySelector)
}
