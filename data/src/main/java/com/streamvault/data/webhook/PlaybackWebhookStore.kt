package com.streamvault.data.webhook

import android.util.AtomicFile
import com.google.gson.Gson
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.playback.events.*
import java.io.File
import java.util.UUID

internal data class PendingWebhook(
    val destinationId: String, val revision: String, val eventId: String, val sessionId: String,
    val type: PlaybackEventType, val body: String, val expiresAtMs: Long,
    val durable: Boolean, var attempts: Int = 0, var nextAttemptMs: Long = 0,
)

internal data class StoredWebhooks(
    val installationId: String = UUID.randomUUID().toString(),
    val configuration: PlaybackWebhookConfiguration = PlaybackWebhookConfiguration(),
    val pending: List<PendingWebhook> = emptyList(),
)

/** Entire configuration and history payloads are encrypted and excluded from backups. */
internal class PlaybackWebhookStore(file: File, private val crypto: CredentialCrypto) {
    private val atomic = AtomicFile(file)
    private val gson = Gson()

    fun read(): StoredWebhooks {
        if (!atomic.baseFile.exists()) return StoredWebhooks()
        require(atomic.baseFile.length() <= 16 * 1024 * 1024)
        return gson.fromJson(crypto.decryptIfNeeded(atomic.openRead().bufferedReader().use { it.readText() }),
            StoredWebhooks::class.java).also { validate(it.configuration) }
    }

    fun write(state: StoredWebhooks) {
        val output = atomic.startWrite()
        try {
            output.write(crypto.encryptIfNeeded(gson.toJson(state)).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }

    companion object {
        fun endpoint(destination: PlaybackWebhookDestination) = PlaybackWebhookEndpoint(destination.url,
            if (destination.headerName.isBlank()) emptyMap() else mapOf(destination.headerName to destination.headerValue))

        fun validate(configuration: PlaybackWebhookConfiguration) {
            require(configuration.destinations.size <= 4)
            require(configuration.destinations.map { it.id }.distinct().size == configuration.destinations.size)
            configuration.destinations.forEach {
                require(it.id.matches(Regex("[a-zA-Z0-9-]{1,64}")))
                require(it.revision.matches(Regex("[a-zA-Z0-9-]{1,64}")))
                require(it.name.isNotBlank() && it.name.length <= 100)
                requireNotNull(it.format)
                requireNotNull(it.delivery)
                require((it.events as Set<*>).all { event -> event is PlaybackEventType })
                require((it.mediaTypes as Set<*>).all { type -> type is PlaybackMediaType })
                require(it.url.length <= 4096)
                require(it.headerName.isNotBlank() || it.headerValue.isBlank())
                PlaybackEventPolicy(it.policy.progressIntervalMs, it.policy.watchedPercent)
                endpoint(it)
            }
        }
    }
}
