package com.streamvault.feature.playback.player

import com.streamvault.core.navigation.StremioPlaybackReference
import com.streamvault.domain.model.stremio.*
import com.streamvault.domain.repository.StremioAddonRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Drains the final stop event after the screen scope has been disposed. */
internal class StremioPlaybackEventDispatcher(repository: StremioAddonRepository) {
    private val tracker = StremioPlaybackEventTracker()
    private val queue = Channel<StremioPlaybackEvent>(64)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        scope.launch {
            try {
                for (event in queue) {
                    try {
                        withTimeout(10_000) {
                            repository.reportActivity("player", event.identity.type, event.identity.id, event.extras())
                        }
                    } catch (_: TimeoutCancellationException) {
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (_: Exception) {}
                }
            } finally { scope.cancel() }
        }
    }
    fun update(reference: StremioPlaybackReference?, position: Long, duration: Long,
        playing: Boolean, ready: Boolean, ended: Boolean, nowMs: Long, speed: Float) {
        val identity = reference?.let { StremioPlaybackIdentity(StremioMediaType.fromTypeName(it.mediaType),
            it.exactVideoId, it.catalogOriginKey + ":" + it.streamOriginKey) }
        tracker.update(identity, position, duration, playing, ready, ended, nowMs, speed).forEach { queue.trySend(it) }
    }
    fun close() {
        tracker.close().forEach { queue.trySend(it) }
        queue.close()
    }
}
