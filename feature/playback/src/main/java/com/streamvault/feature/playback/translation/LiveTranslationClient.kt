package com.streamvault.feature.playback.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val PCM_MEDIA_TYPE = "application/octet-stream".toMediaType()
private const val DEFAULT_WHISPER_MODEL = "whisper-large-v3"

/**
 * A streaming translation update for the current phrase. [chunkId] identifies the
 * phrase: partials (isFinal=false) update the same line in place, a new chunkId
 * starts a new line, and isFinal marks the phrase as complete. [text] may be blank
 * when an upload produced no new transcription (silence / in-progress).
 */
data class LiveTranslationUpdate(
    val chunkId: Long,
    val isFinal: Boolean,
    val text: String,
    val sourceLanguage: String?
)

class LiveTranslationClient(
    private val okHttpClient: OkHttpClient,
    private val endpoint: String
) {
    suspend fun startSession(
        logicalUrl: String,
        providerId: Long,
        contentId: Long
    ): String = withContext(Dispatchers.IO) {
        val requestPayload = JSONObject()
            .put("logicalStreamUrl", logicalUrl)
            .put("providerId", providerId)
            .put("contentId", contentId)
            .put("targetLanguage", "en")
            .put("task", "translate")
            .put("model", DEFAULT_WHISPER_MODEL)
            .put("autoDetectSourceLanguage", true)
        val request = Request.Builder()
            .url("${endpoint.trimEnd('/')}/v1/live-translation/session")
            .post(requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Live translation start failed (${response.code})")
            }
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body.ifBlank { "{}" })
            json.optString("sessionId")
                .ifBlank { json.optString("id") }
                .ifBlank { throw IllegalStateException("Missing translation session id") }
        }
    }

    suspend fun uploadPcmChunk(
        sessionId: String,
        pcm16Mono16k: ByteArray,
        startMs: Long,
        endMs: Long
    ): LiveTranslationUpdate =
        withContext(Dispatchers.IO) {
            val url = "${endpoint.trimEnd('/')}/v1/live-translation/session/$sessionId/audio" +
                "?startMs=$startMs&endMs=$endMs&sampleRate=16000&channelCount=1&encoding=pcm_s16le"
            val request = Request.Builder()
                .url(url)
                .post(pcm16Mono16k.toRequestBody(PCM_MEDIA_TYPE))
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Live translation audio upload failed (${response.code})")
                }
                val body = response.body?.string().orEmpty()
                parseTranslationUpdate(body)
            }
        }

    suspend fun stopSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${endpoint.trimEnd('/')}/v1/live-translation/session/$sessionId")
                .delete()
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { /* best-effort */ }
            }
        }
    }
}

internal fun parseTranslationUpdate(body: String): LiveTranslationUpdate {
    val json = JSONObject(body.ifBlank { "{}" })
    return LiveTranslationUpdate(
        chunkId = json.optLong("chunkId"),
        isFinal = json.optBoolean("isFinal"),
        text = json.optString("text").trim(),
        sourceLanguage = json.optString("sourceLanguage").takeIf { it.isNotBlank() }
    )
}
