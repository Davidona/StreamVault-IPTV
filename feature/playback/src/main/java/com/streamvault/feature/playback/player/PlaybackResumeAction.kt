package com.streamvault.feature.playback.player

import com.streamvault.core.navigation.PlaybackStartMode
import com.streamvault.domain.playback.isPlaybackComplete

internal sealed interface PlaybackResumeAction {
    data object None : PlaybackResumeAction
    data class Prompt(val positionMs: Long) : PlaybackResumeAction
    data class Seek(val positionMs: Long) : PlaybackResumeAction
}

internal fun playbackResumeAction(mode: PlaybackStartMode, positionMs: Long?, durationMs: Long?): PlaybackResumeAction {
    if (mode == PlaybackStartMode.START_OVER || positionMs == null || durationMs == null ||
        positionMs <= 0 || isPlaybackComplete(positionMs, durationMs)) return PlaybackResumeAction.None
    return when (mode) {
        PlaybackStartMode.RESUME -> PlaybackResumeAction.Seek(positionMs)
        PlaybackStartMode.PROMPT -> if (positionMs > 5_000) PlaybackResumeAction.Prompt(positionMs) else PlaybackResumeAction.None
        PlaybackStartMode.START_OVER -> PlaybackResumeAction.None
    }
}
