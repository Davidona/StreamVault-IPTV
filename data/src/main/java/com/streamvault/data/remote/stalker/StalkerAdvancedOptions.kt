package com.streamvault.data.remote.stalker

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.streamvault.domain.model.StalkerCatalogLoadOrder

@Serializable
data class StalkerAdvancedOptions(
    val hwVersion: String = "",
    val apiUserAgent: String = "",
    val playerUserAgent: String = "",
    val playerHeaders: String = "",
    val xUserAgentLink: String = LINK_ETHERNET,
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int? = null,
    val requestRules: List<StalkerRequestRule> = emptyList(),
    val catalogLoadOrder: StalkerCatalogLoadOrder = StalkerCatalogLoadOrder.PARALLEL
) {
    val normalizedLink: String
        get() = when {
            xUserAgentLink.equals(LINK_WIFI, ignoreCase = true) -> LINK_WIFI
            else -> LINK_ETHERNET
        }

    val proxy: StalkerHttpProxy?
        get() = if (proxyEnabled && proxyHost.isNotBlank() && proxyPort != null) {
            StalkerHttpProxy(proxyHost.trim(), proxyPort)
        } else {
            null
        }

    companion object {
        const val LINK_ETHERNET = "Ethernet"
        const val LINK_WIFI = "WiFi"
    }
}

@Serializable
data class StalkerRequestRule(
    val action: String = "",
    val blockRequest: Boolean = false,
    val paramOverrides: List<StalkerParamOverride> = emptyList()
)

@Serializable
data class StalkerParamOverride(
    val name: String = "",
    val value: String = ""
)

data class StalkerHttpProxy(
    val host: String,
    val port: Int
)

data class LegacyStalkerEditFields(
    val serialNumber: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val hwVersion: String = "",
    val apiUserAgent: String = "",
    val playerUserAgent: String = "",
    val playerHeaders: String = "",
    val xUserAgentLink: String = StalkerAdvancedOptions.LINK_ETHERNET,
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int? = null
)

object StalkerAdvancedOptionsCodec {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun decode(raw: String): StalkerAdvancedOptions {
        if (raw.isBlank()) return StalkerAdvancedOptions()
        return runCatching { json.decodeFromString<StalkerAdvancedOptions>(raw) }
            .getOrElse { StalkerAdvancedOptions() }
    }

    fun decodeStrict(raw: String): StalkerAdvancedOptions {
        if (raw.isBlank()) return StalkerAdvancedOptions()
        return json.decodeFromString(raw)
    }

    fun encode(options: StalkerAdvancedOptions): String {
        if (options == StalkerAdvancedOptions()) return ""
        return json.encodeToString(options)
    }

    fun decodeLegacyEditFields(raw: String): LegacyStalkerEditFields {
        if (raw.isBlank()) return LegacyStalkerEditFields()
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            return LegacyStalkerEditFields()
        }
        return LegacyStalkerEditFields(
            serialNumber = root.string("serialNumber", "serial_number", "sn"),
            deviceId = root.string("deviceId", "device_id"),
            deviceId2 = root.string("deviceId2", "device_id2"),
            signature = root.string("signature"),
            hwVersion = root.string("hwVersion", "hw_version"),
            apiUserAgent = root.string("apiUserAgent", "api_user_agent"),
            playerUserAgent = root.string("playerUserAgent", "player_user_agent"),
            playerHeaders = root.string("playerHeaders", "player_headers"),
            xUserAgentLink = root.string("xUserAgentLink", "x_user_agent_link").ifBlank {
                StalkerAdvancedOptions.LINK_ETHERNET
            },
            proxyEnabled = root.boolean("proxyEnabled", "proxy_enabled") ?: false,
            proxyHost = root.string("proxyHost", "proxy_host"),
            proxyPort = root.int("proxyPort", "proxy_port")
        )
    }
}

internal fun parseStalkerHeaderOverrides(rawHeaders: String): Map<String, String?> {
    if (rawHeaders.isBlank()) return emptyMap()
    return buildMap {
        rawHeaders
            .split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { entry ->
                val separatorIndex = entry.indexOf(':')
                if (separatorIndex <= 0) return@forEach
                val name = entry.substring(0, separatorIndex).trim()
                if (name.isEmpty()) return@forEach
                val value = entry.substring(separatorIndex + 1).trim().ifEmpty { null }
                put(name, value)
            }
    }
}

private fun JsonObject.string(vararg keys: String): String =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.content?.trim()
    }.orEmpty()

private fun JsonObject.boolean(vararg keys: String): Boolean? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.booleanOrNull
    }

private fun JsonObject.int(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.intOrNull
    }
