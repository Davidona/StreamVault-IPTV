package com.streamvault.data.manager.recording

import com.streamvault.domain.model.PlaybackTransportMode
import com.streamvault.domain.model.PlaybackTransportPolicy
import com.streamvault.domain.model.StalkerTransportOrigin
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** Network settings that playback applies to this provider-resolved stream. */
data class RecordingNetworkProfile(
    val playbackTransportPolicy: PlaybackTransportPolicy? = null,
    val allowInvalidSsl: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int? = null
) {
    fun toJson(): String = Json.encodeToString(
        buildJsonObject {
            put("allowInvalidSsl", allowInvalidSsl)
            put("proxyHost", proxyHost)
            put("proxyPort", proxyPort?.let(::JsonPrimitive) ?: JsonNull)
            put("playbackTransportPolicy", playbackTransportPolicy?.toJsonElement() ?: JsonNull)
        }
    )

    companion object {
        fun fromJson(raw: String?): RecordingNetworkProfile = runCatching {
            val root = Json.parseToJsonElement(raw.orEmpty()).jsonObject
            RecordingNetworkProfile(
                playbackTransportPolicy = root["playbackTransportPolicy"]
                    ?.takeUnless { it == JsonNull }
                    ?.jsonObject
                    ?.toTransportPolicy(),
                allowInvalidSsl = root["allowInvalidSsl"]?.jsonPrimitive?.booleanOrNull ?: false,
                proxyHost = root["proxyHost"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                proxyPort = root["proxyPort"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..65535 }
            )
        }.getOrDefault(RecordingNetworkProfile())
    }
}

fun interface RecordingHttpClientFactory {
    fun create(profile: RecordingNetworkProfile): OkHttpClient
}

private fun PlaybackTransportPolicy.toJsonElement(): JsonObject = buildJsonObject {
    put("mode", mode.name)
    put("origin", buildJsonObject {
        put("scheme", origin.scheme)
        put("host", origin.host)
        put("port", origin.port)
    })
    put("spkiSha256", spkiSha256?.let(::JsonPrimitive) ?: JsonNull)
    put("allowCrossOriginHttpRedirects", allowCrossOriginHttpRedirects)
}

private fun JsonObject.toTransportPolicy(): PlaybackTransportPolicy? = runCatching {
    val originJson = getValue("origin").jsonObject
    PlaybackTransportPolicy(
        mode = PlaybackTransportMode.valueOf(getValue("mode").jsonPrimitive.content),
        origin = StalkerTransportOrigin(
            scheme = originJson.getValue("scheme").jsonPrimitive.content,
            host = originJson.getValue("host").jsonPrimitive.content,
            port = originJson.getValue("port").jsonPrimitive.int
        ),
        spkiSha256 = get("spkiSha256")?.jsonPrimitive?.contentOrNull?.takeUnless { it.isBlank() },
        allowCrossOriginHttpRedirects = get("allowCrossOriginHttpRedirects")?.jsonPrimitive?.booleanOrNull ?: false
    )
}.getOrNull()
