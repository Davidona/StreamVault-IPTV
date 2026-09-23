package com.streamvault.player.playback

import com.streamvault.domain.model.StreamInfo

internal data class PlaybackPreparationPlan(
    val resolvedStreamType: ResolvedStreamType,
    val timeoutProfile: PlayerTimeoutProfile,
    val retryContext: PlaybackRetryContext,
    val retryPolicy: PlayerRetryPolicy
)

internal fun buildPlaybackPreparationPlan(
    streamInfo: StreamInfo,
    preload: Boolean,
    fastRetryOnTransientFailures: () -> Boolean = { false },
    playbackStarted: () -> Boolean
): PlaybackPreparationPlan {
    val resolvedStreamType = StreamTypeResolver.resolve(streamInfo)
    val timeoutProfile = PlayerTimeoutProfile.resolve(streamInfo, resolvedStreamType, preload = preload)
    val retryContext = PlaybackRetryContext(
        resolvedStreamType = resolvedStreamType,
        timeoutProfile = timeoutProfile,
        playbackContentKind = streamInfo.playbackContentKind
    )
    return PlaybackPreparationPlan(
        resolvedStreamType = resolvedStreamType,
        timeoutProfile = timeoutProfile,
        retryContext = retryContext,
        retryPolicy = PlayerRetryPolicy(
            streamContext = retryContext,
            fastRetryOnTransientFailures = fastRetryOnTransientFailures,
            playbackStarted = playbackStarted
        )
    )
}
