package com.streamvault.feature.playback.player

import com.streamvault.feature.playback.translation.LiveTranslationClient
import com.streamvault.feature.playback.translation.LiveTranslationSession
import com.streamvault.player.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import javax.inject.Inject

/** Owns the cancellable real-time translation session for the active playback. */
class PlayerTranslationCoordinator @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private var session: LiveTranslationSession? = null

    internal val isActive: Boolean
        get() = session != null

    internal fun start(
        scope: CoroutineScope,
        playerEngine: PlayerEngine,
        endpoint: String,
        logicalUrl: String,
        providerId: Long,
        contentId: Long,
        onSourceLanguageDetected: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        LiveTranslationSession(
            scope = scope,
            playerEngine = playerEngine,
            client = LiveTranslationClient(okHttpClient, endpoint),
            logicalUrl = logicalUrl,
            providerId = providerId,
            contentId = contentId,
            onSourceLanguageDetected = onSourceLanguageDetected,
            onError = onError
        ).also { nextSession ->
            session = nextSession
            nextSession.start()
        }
    }

    internal fun stop() {
        session?.stop()
        session = null
    }
}
