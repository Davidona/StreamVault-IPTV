package com.streamvault.feature.playback.player

internal fun PlayerViewModel.fetchEpg(
    providerId: Long,
    internalChannelId: Long,
    epgChannelId: String?,
    streamId: Long = 0L
) {
    if (providerId <= 0L || (internalChannelId <= 0L && epgChannelId == null && streamId <= 0L)) {
        epgCoordinator.clear(::clearEpgState)
        return
    }

    val requestVersion = prepareRequestVersion
    epgCoordinator.request(
        scope = playbackSessionScope(requestVersion),
        sessionId = requestVersion,
        requestKey = EpgRequestKey(
            providerId = providerId,
            internalChannelId = internalChannelId,
            epgChannelId = epgChannelId,
            streamId = streamId
        ),
        onPrograms = ::applyProgramTimeline,
        onClear = ::clearEpgState
    )
}
