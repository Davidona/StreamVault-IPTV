package com.streamvault.feature.playback.player

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.timeshift.TimeshiftConfig
import javax.inject.Inject

/** Owns the decision to attach or detach the local live-rewind source. */
class PlayerTimeshiftCoordinator @Inject constructor(
    private val playerEngineCoordinator: PlayerEngineCoordinator
) {
    internal data class Request(
        val contentType: ContentType,
        val enabled: Boolean,
        val streamClassLabel: String,
        val config: TimeshiftConfig,
        val streamInfoOverride: StreamInfo?,
        val currentResolvedStreamInfo: StreamInfo?,
        val currentResolvedPlaybackUrl: String,
        val currentStreamUrl: String?,
        val playbackTitle: String,
        val currentTitle: String,
        val channelKey: String?
    )

    internal fun startOrStop(
        request: Request,
        onUnavailable: () -> Unit,
        onPreparing: () -> Unit
    ) {
        val engine = playerEngineCoordinator.currentEngine
        if (request.contentType != ContentType.LIVE || !request.enabled) {
            engine.stopLiveTimeshift()
            return
        }
        if (!shouldStartLiveTimeshiftForStreamClass(request.streamClassLabel)) {
            engine.stopLiveTimeshift()
            return
        }

        val streamInfo = resolveTimeshiftStreamInfo(
            streamInfoOverride = request.streamInfoOverride,
            currentResolvedStreamInfo = request.currentResolvedStreamInfo,
            currentResolvedPlaybackUrl = request.currentResolvedPlaybackUrl,
            currentStreamUrl = request.currentStreamUrl,
            playbackTitle = request.playbackTitle,
            currentTitle = request.currentTitle
        ) ?: run {
            engine.stopLiveTimeshift()
            onUnavailable()
            return
        }

        onPreparing()
        engine.startLiveTimeshift(
            streamInfo = streamInfo,
            channelKey = request.channelKey ?: streamInfo.url,
            config = request.config
        )
    }
}
