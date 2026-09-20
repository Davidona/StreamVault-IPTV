package com.streamvault.feature.playback.player

import android.os.Build
import com.streamvault.feature.playback.preview.LivePreviewHandoffManager
import com.streamvault.feature.playback.preview.PreviewHandoffSource
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
    private var adoptedHandoffSource: PreviewHandoffSource? = null

    internal fun consumeFullscreenHandoff(
        channelId: Long,
        providerId: Long?
    ): LivePreviewHandoffManager.LivePreviewHandoffSession? =
        handoffManager.consumeFullscreenHandoff(channelId, providerId)

    internal fun clear(engine: PlayerEngine?) {
        handoffManager.clear(engine)
    }

    /**
     * Returns the browse surface that handed the active engine to the fullscreen
     * player, or null when the active engine was not adopted from a preview.
     *
     * Consuming resets the value so a later teardown cannot reverse-handoff a
     * stream to a stale origin. This is what routes a Guide preview back to the
     * Guide instead of letting the Home surface silently adopt it.
     */
    internal fun consumeAdoptedHandoffSource(): PreviewHandoffSource? {
        val source = adoptedHandoffSource
        adoptedHandoffSource = null
        return source
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
            adoptedHandoffSource = null
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
                adoptedHandoffSource = null
                false
            } else {
                onAdopted(session.streamInfo)
                adoptedEngine.resetLiveHandoffGrace()
                engineCoordinator.currentEngine.play()
                onStarted(session.streamInfo)
                adoptedHandoffSource = session.source
                true
            }
        }.getOrElse {
            clear(adoptedEngine)
            if (engineCoordinator.currentEngine === adoptedEngine) {
                engineCoordinator.switchTo(engineCoordinator.mainEngine)
            }
            adoptedEngine.release()
            adoptedHandoffSource = null
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
