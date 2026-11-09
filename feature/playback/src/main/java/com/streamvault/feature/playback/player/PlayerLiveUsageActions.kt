package com.streamvault.feature.playback.player

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LiveWatchSample
import com.streamvault.domain.model.liveWatchTargetOrNull
import com.streamvault.player.PlaybackState

/** Captures no URLs and performs no persistence on the player thread. */
internal fun PlayerViewModel.reportLiveUsage(stop: Boolean = false) {
    val target = if (stop || !isLiveUsageScreenActive || !isAppInForeground ||
        currentContentType != ContentType.LIVE || isCatchUpPlayback()) null
    else currentChannelFlow.value?.liveWatchTargetOrNull()
    val playing = target != null && livePlaybackReadyForCurrentSession && playerEngine.isPlaying.value &&
        playerEngine.playbackState.value == PlaybackState.READY
    playbackHistoryCoordinator.sampleLiveUsage(LiveWatchSample(liveUsageOwner, target, playing,
        System.nanoTime() / 1_000_000, System.currentTimeMillis()))
}
