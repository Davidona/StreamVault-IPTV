package com.streamvault.app.live

import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.app.navigation.playerNavigationRequest
import com.streamvault.app.navigation.toLivePlayerRequest
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.domain.playback.isArchivePlayable
import com.streamvault.feature.live.api.LiveArchivePlaybackRequest
import com.streamvault.feature.live.api.LiveChannelPlaybackRequest

/** Maps feature-owned Live intents into the app's player payload at the composition boundary. */
internal fun LiveChannelPlaybackRequest.toAppPlayerNavigationRequest(): PlayerNavigationRequest =
    channel.toLivePlayerRequest(
        categoryId = categoryId,
        providerId = providerId,
        isVirtual = isVirtual,
        combinedProfileId = combinedProfileId,
        combinedSourceFilterProviderId = combinedSourceFilterProviderId,
        returnDestination = returnRoute?.let(AppRouteCodec::decode),
    )

/**
 * Maps an archive intent only when the domain playback policy accepts its program. Keeping the
 * rejection here preserves the existing app callback boundary and makes it testable in isolation.
 */
internal fun LiveArchivePlaybackRequest.toAppArchivePlayerNavigationRequest(
    now: Long = System.currentTimeMillis(),
): PlayerNavigationRequest? {
    if (!channel.isArchivePlayable(program, now)) return null
    return playerNavigationRequest(
        streamUrl = channel.streamUrl,
        title = channel.name,
        channelId = channel.epgChannelId,
        internalId = channel.id,
        categoryId = categoryId,
        providerId = channel.providerId,
        isVirtual = isVirtual,
        combinedProfileId = combinedProfileId,
        contentType = "LIVE",
        archiveStartMs = program.startTime,
        archiveEndMs = program.endTime,
        archiveTitle = "${channel.name}: ${program.title}",
        returnDestination = returnRoute?.let(AppRouteCodec::decode),
    )
}
