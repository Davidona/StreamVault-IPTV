package com.streamvault.data.parser

import com.streamvault.domain.model.DrmScheme
import com.streamvault.domain.model.StaticClearKey
import com.streamvault.domain.model.StaticClearKeyLicense
import com.streamvault.domain.model.StreamType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

private const val METADATA_VERSION = 1
private const val MAX_HEADER_COUNT = 32
private const val MAX_HEADER_NAME_LENGTH = 128
private const val MAX_HEADER_VALUE_LENGTH = 4_096
private const val MAX_METADATA_LENGTH = 32_768
private const val MAX_STATIC_KEYS = 16

data class M3uPlaybackMetadata(
    val version: Int = METADATA_VERSION,
    val manifestType: StreamType? = null,
    val drmScheme: DrmScheme? = null,
    val licenseUrl: String? = null,
    val staticClearKeyLicense: StaticClearKeyLicense? = null,
    val manifestHeaders: Map<String, String> = emptyMap(),
    val streamHeaders: Map<String, String> = emptyMap(),
    val commonHeaders: Map<String, String> = emptyMap(),
    val licenseHeaders: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val referer: String? = null
)

class M3uPlaybackMetadataBuilder {
    private var manifestType: StreamType? = null
    private var drmScheme: DrmScheme? = null
    private var licenseKey: String? = null
    private val manifestHeaders = linkedMapOf<String, String>()
    private val streamHeaders = linkedMapOf<String, String>()
    private val commonHeaders = linkedMapOf<String, String>()
    private val licenseHeaders = linkedMapOf<String, String>()
    private var userAgent: String? = null
    private var referer: String? = null

    fun applyDirective(line: String) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("#KODIPROP:", ignoreCase = true) -> {
                val property = trimmed.substringAfter(':', missingDelimiterValue = "")
                val separator = property.indexOf('=')
                if (separator <= 0) return
                applyProperty(
                    key = property.substring(0, separator).trim(),
                    value = property.substring(separator + 1).trim()
                )
            }

            trimmed.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                val property = trimmed.substringAfter(':', missingDelimiterValue = "")
                val separator = property.indexOf('=')
                if (separator <= 0) return
                val key = property.substring(0, separator).trim().lowercase(Locale.ROOT)
                val value = property.substring(separator + 1).trim()
                when (key) {
                    "http-user-agent" -> userAgent = value.takeIf(String::isNotBlank)
                    "http-referrer", "http-referer" -> referer = value.takeIf(String::isNotBlank)
                }
            }
        }
    }

    fun putHeaderMap(target: String, headers: Map<String, String>) {
        headers.forEach { (name, value) ->
            putHeader(target, name, value)
        }
    }

    fun build(): M3uPlaybackMetadata? {
        val remoteLicenseUrl = licenseKey
            ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            ?.takeIf { it.length <= MAX_METADATA_LENGTH }
        val staticLicense = if (drmScheme == DrmScheme.CLEARKEY && remoteLicenseUrl == null) {
            licenseKey?.let(::parseStaticClearKeyLicense)
        } else {
            null
        }
        val hasContent = manifestType != null || drmScheme != null || remoteLicenseUrl != null ||
            staticLicense != null || manifestHeaders.isNotEmpty() || streamHeaders.isNotEmpty() ||
            commonHeaders.isNotEmpty() || licenseHeaders.isNotEmpty() ||
            !userAgent.isNullOrBlank() || !referer.isNullOrBlank()
        if (!hasContent) return null
        return M3uPlaybackMetadata(
            manifestType = manifestType,
            drmScheme = drmScheme,
            licenseUrl = remoteLicenseUrl,
            staticClearKeyLicense = staticLicense,
            manifestHeaders = manifestHeaders.toMap(),
            streamHeaders = streamHeaders.toMap(),
            commonHeaders = commonHeaders.toMap(),
            licenseHeaders = licenseHeaders.toMap(),
            userAgent = userAgent,
            referer = referer
        )
    }

    private fun applyProperty(key: String, value: String) {
        val normalizedKey = key.lowercase(Locale.ROOT)
        when (normalizedKey) {
            "inputstream.adaptive.manifest_type" -> manifestType = parseManifestType(value)
            "inputstream.adaptive.license_type" -> drmScheme = parseDrmScheme(value)
            "inputstream.adaptive.license_key" -> licenseKey = value.takeIf(String::isNotBlank)
            "inputstream.adaptive.manifest_headers" -> parseHeaders(value)?.let { putHeaderMap("manifest", it) }
            "inputstream.adaptive.stream_headers" -> parseHeaders(value)?.let { putHeaderMap("stream", it) }
            "inputstream.adaptive.common_headers" -> parseHeaders(value)?.let { putHeaderMap("common", it) }
            "inputstream.adaptive.license_headers" -> parseHeaders(value)?.let { putHeaderMap("license", it) }
        }
    }

    private fun putHeader(target: String, rawName: String, rawValue: String) {
        val name = rawName.trim()
        val value = rawValue.trim()
        if (name.isBlank() || name.length > MAX_HEADER_NAME_LENGTH || value.length > MAX_HEADER_VALUE_LENGTH) return
        if (name.any(Char::isISOControl) || value.any { it == '\r' || it == '\n' }) return
        if (name.equals("Host", ignoreCase = true) ||
            name.equals("Content-Length", ignoreCase = true) ||
            name.equals("Transfer-Encoding", ignoreCase = true) ||
            name.equals("Connection", ignoreCase = true)
        ) return
        val map = when (target) {
            "manifest" -> manifestHeaders
            "stream" -> streamHeaders
            "license" -> licenseHeaders
            else -> commonHeaders
        }
        map.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(map::remove)
        if (manifestHeaders.size + streamHeaders.size + commonHeaders.size + licenseHeaders.size >= MAX_HEADER_COUNT) return
        map[name] = value
    }

    private fun parseHeaders(raw: String): Map<String, String>? {
        if (raw.length > MAX_METADATA_LENGTH) return null
        val result = linkedMapOf<String, String>()
        raw.split('&').forEach { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@forEach
            val name = decode(part.substring(0, separator))
            val value = decode(part.substring(separator + 1))
            if (name.isNotBlank() && value.isNotBlank()) result[name] = value
        }
        return result
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)
}

object M3uPlaybackMetadataCodec {
    fun encode(metadata: M3uPlaybackMetadata): String {
        val root = buildJsonObject {
            put("version", metadata.version)
            metadata.manifestType?.let { put("manifestType", it.name) }
            metadata.drmScheme?.let { put("drmScheme", it.name) }
            metadata.licenseUrl?.let { put("licenseUrl", it) }
            put("manifestHeaders", metadata.manifestHeaders.toJsonObject())
            put("streamHeaders", metadata.streamHeaders.toJsonObject())
            put("commonHeaders", metadata.commonHeaders.toJsonObject())
            put("licenseHeaders", metadata.licenseHeaders.toJsonObject())
            metadata.userAgent?.let { put("userAgent", it) }
            metadata.referer?.let { put("referer", it) }
            put("staticClearKeyKeys", buildJsonArray {
                metadata.staticClearKeyLicense?.keys
                    ?.sortedBy { it.keyIdBase64Url }
                    ?.forEach { key ->
                        add(buildJsonObject {
                            put("kid", key.keyIdBase64Url)
                            put("key", key.keyBase64Url)
                        })
                    }
            })
        }
        return root.toString()
    }

    fun decode(raw: String?): M3uPlaybackMetadata? {
        if (raw.isNullOrBlank() || raw.length > MAX_METADATA_LENGTH) return null
        return runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
            if (root["version"]?.jsonPrimitive?.intOrNull != METADATA_VERSION) return null
            val builder = M3uPlaybackMetadataBuilder()
            root.stringValue("manifestType")
                .takeIf(String::isNotBlank)
                ?.let { builder.applyDirective("#KODIPROP:inputstream.adaptive.manifest_type=$it") }
            root.stringValue("drmScheme")
                .takeIf(String::isNotBlank)
                ?.let { builder.applyDirective("#KODIPROP:inputstream.adaptive.license_type=$it") }
            root.stringValue("licenseUrl")
                .takeIf(String::isNotBlank)
                ?.let { builder.applyDirective("#KODIPROP:inputstream.adaptive.license_key=$it") }
            applyJsonHeaders(root["manifestHeaders"]?.jsonObject, "manifest", builder)
            applyJsonHeaders(root["streamHeaders"]?.jsonObject, "stream", builder)
            applyJsonHeaders(root["commonHeaders"]?.jsonObject, "common", builder)
            applyJsonHeaders(root["licenseHeaders"]?.jsonObject, "license", builder)
            root.stringValue("userAgent")
                .takeIf(String::isNotBlank)
                ?.let { builder.applyDirective("#EXTVLCOPT:http-user-agent=$it") }
            root.stringValue("referer")
                .takeIf(String::isNotBlank)
                ?.let { builder.applyDirective("#EXTVLCOPT:http-referer=$it") }
            val keys = root["staticClearKeyKeys"]?.jsonArray
            if (keys != null && keys.isNotEmpty()) {
                val rawKeys = buildString {
                    for (index in keys.indices) {
                        if (index > 0) append(',')
                        val key = keys[index].jsonObject
                        append(key.stringValue("kid"))
                        append(':')
                        append(key.stringValue("key"))
                    }
                }
                builder.applyDirective("#KODIPROP:inputstream.adaptive.license_key=$rawKeys")
            }
            builder.build()
        }.getOrNull()
    }

    private fun applyJsonHeaders(json: JsonObject?, target: String, builder: M3uPlaybackMetadataBuilder) {
        if (json == null) return
        builder.putHeaderMap(target, json.mapValues { it.value.jsonPrimitive.content })
    }
}

private fun Map<String, String>.toJsonObject(): JsonObject = buildJsonObject {
    forEach { (name, value) -> put(name, value) }
}

private fun JsonObject.stringValue(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun parseManifestType(raw: String): StreamType? = when (raw.trim().lowercase(Locale.ROOT)) {
    "mpd", "dash" -> StreamType.DASH
    "m3u8", "hls" -> StreamType.HLS
    "ism", "isml", "smoothstreaming" -> StreamType.SMOOTH_STREAMING
    else -> null
}

private fun parseDrmScheme(raw: String): DrmScheme? = when (raw.trim().lowercase(Locale.ROOT)) {
    "widevine", "com.widevine.alpha" -> DrmScheme.WIDEVINE
    "playready", "com.microsoft.playready" -> DrmScheme.PLAYREADY
    "clearkey", "org.w3.clearkey" -> DrmScheme.CLEARKEY
    else -> null
}

private fun parseStaticClearKeyLicense(raw: String): StaticClearKeyLicense? {
    val pairs = when {
        raw.trim().startsWith("{") -> parseJsonKeyPairs(raw)
        else -> raw.split(',').mapNotNull { token ->
            val separator = token.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            token.substring(0, separator).trim() to token.substring(separator + 1).trim()
        }
    }
    val normalized = linkedMapOf<String, String>()
    pairs.take(MAX_STATIC_KEYS).forEach { (rawKid, rawKey) ->
        val kid = decodeKeyBytes(rawKid) ?: return@forEach
        val key = decodeKeyBytes(rawKey) ?: return@forEach
        if (kid.size != 16 || key.size != 16) return@forEach
        normalized[encodeBase64Url(kid)] = encodeBase64Url(key)
    }
    if (normalized.isEmpty()) return null
    val keys = normalized.toSortedMap().map { (kid, key) -> StaticClearKey(kid, key) }
    val fingerprintSource = keys.joinToString("|") { "${it.keyIdBase64Url}:${it.keyBase64Url}" }
    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(fingerprintSource.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    return StaticClearKeyLicense(keys, fingerprint)
}

private fun parseJsonKeyPairs(raw: String): List<Pair<String, String>> = runCatching {
    val root = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
    val keys = root["keys"]?.jsonArray
    if (keys != null) {
        keys.mapNotNull { element ->
            val item = element.jsonObject
            val kid = item.stringValue("kid")
            val key = item.stringValue("k")
            (kid to key).takeIf { kid.isNotBlank() && key.isNotBlank() }
        }
    } else {
        root.mapNotNull { (name, value) ->
            val key = value.jsonPrimitive.contentOrNull.orEmpty()
            (name to key).takeIf { name.isNotBlank() && key.isNotBlank() }
        }
    }
}.getOrDefault(emptyList())

private fun decodeKeyBytes(raw: String): ByteArray? {
    val value = raw.trim()
    if (value.length == 32 && HEX_KEY_PATTERN.matches(value)) {
        return runCatching {
            ByteArray(16) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }
    return runCatching {
        Base64.getUrlDecoder().decode(value)
    }.recoverCatching {
        Base64.getDecoder().decode(value)
    }.getOrNull()?.takeIf { it.size == 16 }
}

private val HEX_KEY_PATTERN = Regex("[0-9a-fA-F]{32}")

private fun encodeBase64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
