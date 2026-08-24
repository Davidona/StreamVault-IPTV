package com.streamvault.app.ui.screens.player

import com.streamvault.domain.model.StreamInfo
import com.streamvault.feature.playback.api.CastMediaRequest
import com.streamvault.feature.playback.cast.CastConnectionState
import com.streamvault.feature.playback.cast.CastManager
import com.streamvault.feature.playback.cast.CastMediaRequestBuildResult
import com.streamvault.feature.playback.cast.CastMediaRequestFactory
import com.streamvault.feature.playback.cast.CastPlaybackCoordinator
import com.streamvault.feature.playback.cast.CastPlaybackEvent
import com.streamvault.feature.playback.cast.CastStartResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Bundles cast route lifecycle, request construction, and playback events for the player. */
class PlayerCastCoordinator @Inject constructor(
    private val castManager: CastManager,
    private val requestFactory: CastMediaRequestFactory,
    private val playbackCoordinator: CastPlaybackCoordinator
) {
    val connectionState: StateFlow<CastConnectionState> = castManager.connectionState
    val playbackEvents: Flow<CastPlaybackEvent> = playbackCoordinator.playbackEvents

    internal suspend fun startCasting(request: CastMediaRequest): CastStartResult =
        playbackCoordinator.startCasting(request)

    internal fun stopCasting() {
        castManager.stopCasting()
    }

    internal fun buildFromStreamInfo(
        streamInfo: StreamInfo,
        title: String,
        subtitle: String?,
        artworkUrl: String?,
        isLive: Boolean,
        startPositionMs: Long
    ): CastMediaRequestBuildResult = requestFactory.buildFromStreamInfo(
        streamInfo = streamInfo,
        title = title,
        subtitle = subtitle,
        artworkUrl = artworkUrl,
        isLive = isLive,
        startPositionMs = startPositionMs
    )
}
