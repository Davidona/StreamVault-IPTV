package com.streamvault.domain.playback.events

import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class PlaybackWebhookFormat { NATIVE, JELLYFIN, YAMTRACK }
enum class PlaybackWebhookDelivery { REALTIME, HISTORY }
enum class PlaybackWebhookStatus { IDLE, SENT, RETRYING, REJECTED, EXPIRED, OVERFLOW, SKIPPED, STORAGE_ERROR }

data class PlaybackWebhookDestination(
    val id: String = UUID.randomUUID().toString(),
    val revision: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val headerName: String = "",
    val headerValue: String = "",
    val enabled: Boolean = true,
    val format: PlaybackWebhookFormat = PlaybackWebhookFormat.NATIVE,
    val delivery: PlaybackWebhookDelivery = PlaybackWebhookDelivery.REALTIME,
    val policy: PlaybackEventPolicy = PlaybackEventPolicy(),
    val events: Set<PlaybackEventType> = PlaybackEventType.entries.toSet(),
    val mediaTypes: Set<PlaybackMediaType> = PlaybackMediaType.entries.toSet(),
) {
    override fun toString() = "PlaybackWebhookDestination(id=$id, redacted)"
}

data class PlaybackWebhookConfiguration(
    val enabled: Boolean = false,
    val destinations: List<PlaybackWebhookDestination> = emptyList(),
)

data class PlaybackWebhookDiagnostic(
    val status: PlaybackWebhookStatus = PlaybackWebhookStatus.IDLE,
    val httpStatus: Int? = null,
    val pending: Int = 0,
    val attemptedAtMs: Long? = null,
)

interface PlaybackWebhooks {
    val configuration: StateFlow<PlaybackWebhookConfiguration>
    val diagnostics: StateFlow<Map<String, PlaybackWebhookDiagnostic>>
    suspend fun save(configuration: PlaybackWebhookConfiguration)
    /** Must return immediately; optional delivery never blocks a player callback. */
    fun report(owner: String, observation: PlaybackObservation)
    fun finish(owner: String)
    suspend fun test(destinationId: String)
    fun preview(destination: PlaybackWebhookDestination): String?
}
