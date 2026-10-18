package com.streamvault.feature.live.sports

import com.streamvault.data.preferences.SportsEventChannelPick
import com.streamvault.domain.model.Channel

internal const val SPORTS_EVENT_CHANNEL_PICK_TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L

internal fun SportsBroadcast.channelIdentityKey(): String =
    "${channel.providerId}:${channel.id}"

internal fun SportsEvent.broadcastsForPresentation(): List<SportsBroadcast> {
    val preferredKey = rememberedChannelKey ?: return broadcasts
    return broadcasts.sortedBy { broadcast -> broadcast.channelIdentityKey() != preferredKey }
}

internal fun SportsEvent.rememberedBroadcast(): SportsBroadcast? {
    val preferredKey = rememberedChannelKey ?: return null
    return broadcasts.firstOrNull { it.channelIdentityKey() == preferredKey }
}

internal object SportsEventChannelPreferencePolicy {
    fun apply(
        event: SportsEvent,
        pick: SportsEventChannelPick?,
        availableChannels: List<SportsChannelIndexEntry>,
        nowMillis: Long,
    ): SportsEvent {
        if (pick == null || pick.expiresAtMillis <= nowMillis) return event
        val entry = availableChannels.firstOrNull { candidate ->
            candidate.channel.providerId == pick.providerId && candidate.channel.id == pick.channelId
        } ?: return event
        val channel = entry.channel
        if (!channel.isPlayableForSports()) return event

        val rememberedBroadcast = event.broadcasts.firstOrNull { broadcast ->
            broadcast.channel.providerId == pick.providerId && broadcast.channel.id == pick.channelId
        } ?: SportsBroadcast(channel = channel, combinedProfileId = entry.combinedProfileId)
        val wasAlreadyAvailable = event.broadcasts.any { broadcast ->
            broadcast.channel.providerId == pick.providerId && broadcast.channel.id == pick.channelId
        }
        val broadcasts = if (wasAlreadyAvailable) {
            event.broadcasts
        } else {
            event.broadcasts + rememberedBroadcast
        }
        return event.copy(
            broadcasts = broadcasts,
            rememberedChannelKey = "${pick.providerId}:${pick.channelId}",
            rememberedChannelWasAdded = !wasAlreadyAvailable,
        )
    }

    fun forget(event: SportsEvent): SportsEvent {
        val rememberedKey = event.rememberedChannelKey ?: return event
        return event.copy(
            broadcasts = if (event.rememberedChannelWasAdded) {
                event.broadcasts.filterNot { it.channelIdentityKey() == rememberedKey }
            } else {
                event.broadcasts
            },
            rememberedChannelKey = null,
            rememberedChannelWasAdded = false,
        )
    }

    private fun Channel.isPlayableForSports(): Boolean =
        streamUrl.isNotBlank() && !isAdult && !isUserProtected
}
