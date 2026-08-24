package com.streamvault.app.ui.screens.player

import android.os.Build
import com.streamvault.app.player.LivePreviewHandoffManager
import com.streamvault.app.player.PreviewHandoffSource
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.player.PlayerEngine
import android.util.Log
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Owns preview/fullscreen handoff lifecycle for the player feature. */
class PlayerPreviewCoordinator @Inject constructor(
    private val handoffManager: LivePreviewHandoffManager,
    private val engineCoordinator: PlayerEngineCoordinator,
    private val preparationCoordinator: PlayerPreparationCoordinator,
    private val preferencesCoordinator: PlayerPreferencesCoordinator,
) {
    internal fun consumeFullscreenHandoff(
        channelId: Long,
        providerId: Long?
    ): LivePreviewHandoffManager.LivePreviewHandoffSession? =
        handoffManager.consumeFullscreenHandoff(channelId, providerId)

    internal fun clear(engine: PlayerEngine?) {
        handoffManager.clear(engine)
    }

    internal fun beginReverseHandoff(
        channel: Channel,
        streamInfo: StreamInfo,
        engine: PlayerEngine,
        source: PreviewHandoffSource
    ) {
        handoffManager.beginReverseHandoff(channel, streamInfo, engine, source)
    }

    internal suspend fun tryAdoptFullscreenHandoff(
        channelId: Long,
        providerId: Long,
        contentType: ContentType,
        audioVideoOffsetMs: Int,
        isCurrent: () -> Boolean,
        onAdopted: (StreamInfo) -> Unit,
        onStarted: (StreamInfo) -> Unit,
    ): Boolean {
        if (contentType != ContentType.LIVE) return false

        val session = consumeFullscreenHandoff(
            channelId = channelId,
            providerId = providerId.takeIf { it > 0L }
        ) ?: return false

        if (shouldBypassForFireTvLiveHls(session.streamInfo)) {
            logInfo("Skipping preview handoff for Fire TV live HLS; fullscreen will prepare a fresh session.")
            clear(session.engine)
            session.engine.release()
            return false
        }

        val adoptedEngine = session.engine
        return runCatching {
            adoptedEngine.clearRenderBinding()
            engineCoordinator.mainEngine.setMediaSessionEnabled(false)
            engineCoordinator.switchTo(adoptedEngine)
            adoptedEngine.setAudioFocusBypassed(false)
            adoptedEngine.setMediaSessionEnabled(preferencesCoordinator.playerMediaSessionEnabled.first())
            adoptedEngine.setResolutionConstrainedForMultiView(false)
            preparationCoordinator.applyPlaybackPreferences(
                engine = adoptedEngine,
                contentType = contentType,
                audioVideoOffsetMs = audioVideoOffsetMs
            )
            if (!isCurrent()) {
                engineCoordinator.switchTo(engineCoordinator.mainEngine)
                adoptedEngine.release()
                false
            } else {
                onAdopted(session.streamInfo)
                adoptedEngine.resetLiveHandoffGrace()
                engineCoordinator.currentEngine.play()
                onStarted(session.streamInfo)
                true
            }
        }.getOrElse {
            clear(adoptedEngine)
            if (engineCoordinator.currentEngine === adoptedEngine) {
                engineCoordinator.switchTo(engineCoordinator.mainEngine)
            }
            adoptedEngine.release()
            false
        }
    }

    private fun shouldBypassForFireTvLiveHls(streamInfo: StreamInfo): Boolean {
        val isAmazonMediaTek = Build.MANUFACTURER.equals("Amazon", ignoreCase = true) &&
            Build.HARDWARE.orEmpty().startsWith("mt", ignoreCase = true)
        val isHls = streamInfo.streamType == StreamType.HLS ||
            streamInfo.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        return isAmazonMediaTek && isHls
    }

    private fun logInfo(message: String) {
        runCatching { Log.i("PlayerVM", message) }
    }
}
