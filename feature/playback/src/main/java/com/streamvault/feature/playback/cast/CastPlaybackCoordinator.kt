package com.streamvault.feature.playback.cast

import com.streamvault.feature.playback.api.CastMediaRequest
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

interface CastPlaybackCoordinator {
    val playbackEvents: SharedFlow<CastPlaybackEvent>
    suspend fun startCasting(request: CastMediaRequest): CastStartResult
}

@Singleton
class DefaultCastPlaybackCoordinator @Inject constructor(
    private val castManager: CastManager
) : CastPlaybackCoordinator {
    override val playbackEvents: SharedFlow<CastPlaybackEvent> = castManager.playbackEvents

    override suspend fun startCasting(request: CastMediaRequest): CastStartResult {
        return castManager.startCasting(request)
    }
}
