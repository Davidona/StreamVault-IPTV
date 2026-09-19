package com.streamvault.feature.playback.player.overlay

import com.streamvault.feature.playback.player.NumericChannelInputState
import com.streamvault.feature.playback.player.PlayerNoticeAction
import com.streamvault.feature.playback.player.PlayerNoticeState
import com.streamvault.feature.playback.player.PlayerRecoveryType
import com.streamvault.player.PlayerTrack
import com.streamvault.player.TrackType

internal object PlayerOverlayGoldenFixtures {
    val invalidNumericInputState = NumericChannelInputState(
        input = "999",
        matchedChannelName = null,
        invalid = true
    )

    val notice = PlayerNoticeState(
        message = "Playback recovered on the alternate stream.",
        recoveryType = PlayerRecoveryType.NETWORK,
        actions = listOf(PlayerNoticeAction.RETRY, PlayerNoticeAction.OPEN_GUIDE)
    )

    val audioTracks = listOf(
        PlayerTrack("audio-en", "English 5.1", "en", TrackType.AUDIO, true),
        PlayerTrack("audio-es", "Spanish Stereo", "es", TrackType.AUDIO, false)
    )

    val subtitleTracks = listOf(
        PlayerTrack("sub-en", "English CC", "en", TrackType.TEXT, true),
        PlayerTrack("sub-es", "Spanish", "es", TrackType.TEXT, false)
    )

    const val fixedClock = "21:47"
    const val vodTitle = "The Long Night"
    const val aspectRatioLabel = "Original"
    const val displayChannelNumber = 105
    const val currentPositionMs = 4_200_000L
    const val durationMs = 7_200_000L
}
