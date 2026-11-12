package com.streamvault.data.webhook

import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.domain.playback.events.PlaybackEvent
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** URL paths and custom headers may both contain credentials. Never expose them via toString. */
class PlaybackWebhookEndpoint(url: String, headers: Map<String, String> = emptyMap()) {
    internal val url: HttpUrl = url.toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty())
        require(it.fragment == null)
    }
    internal val headers: Map<String, String> = headers.toMap().also { values ->
        require(values.size <= 8)
        values.forEach { (name, value) ->
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")))
            require(name.lowercase(Locale.ROOT) !in RESERVED_HEADERS)
            require(value.length <= 4096 && value.all { it == '\t' || it.code in 32..126 })
        }
    }

    override fun toString(): String = "PlaybackWebhookEndpoint(redacted)"

    private companion object {
        val RESERVED_HEADERS = setOf("host", "content-type", "content-length", "transfer-encoding",
            "connection", "keep-alive", "te", "trailer", "upgrade", "proxy-authorization",
            "proxy-authenticate", "idempotency-key", "x-streamvault-event-id", "user-agent")
    }
}

enum class WebhookDeliveryCategory { SUCCESS, RETRYABLE, REJECTED }

/** No URL, exception message, header values, or remote response body in diagnostics. */
data class WebhookDeliveryResult(
    val category: WebhookDeliveryCategory,
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null,
)

/** One attempt only. Queue ownership, ordering, expiry and retries belong to the dispatcher. */
class PlaybackWebhookSender(private val now: () -> Instant = { Instant.now() }) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun send(endpoint: PlaybackWebhookEndpoint, event: PlaybackEvent): WebhookDeliveryResult {
        return sendJson(endpoint, event.eventId, NativePlaybackEventJson.encode(event))
    }

    suspend fun sendJson(endpoint: PlaybackWebhookEndpoint, eventId: String, json: String): WebhookDeliveryResult {
        val request = Request.Builder().url(endpoint.url)
            .header("User-Agent", "StreamVault")
            .header("X-StreamVault-Event-Id", eventId)
            .header("Idempotency-Key", eventId)
            .apply { endpoint.headers.forEach { (name, value) -> header(name, value) } }
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            client.newCall(request).useCancellableResponse { response ->
                val category = when (response.code) {
                    in 200..299 -> WebhookDeliveryCategory.SUCCESS
                    408, 429, in 500..599 -> WebhookDeliveryCategory.RETRYABLE
                    else -> WebhookDeliveryCategory.REJECTED
                }
                // Deliberately do not consume the response body: it is irrelevant and untrusted.
                WebhookDeliveryResult(category, response.code,
                    if (category == WebhookDeliveryCategory.RETRYABLE) retryAfter(response.header("Retry-After")) else null)
            }
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            WebhookDeliveryResult(WebhookDeliveryCategory.RETRYABLE)
        }
    }

    private fun retryAfter(value: String?): Long? {
        if (value == null) return null
        val seconds = value.toLongOrNull()?.coerceIn(0, 300)
        if (seconds != null) return seconds * 1000
        return runCatching {
            java.time.Duration.between(now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                .toMillis().coerceIn(0, 300_000)
        }.getOrNull()
    }
}
