package com.streamvault.feature.provider.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class ProviderStalkerAdvancedOptions(
    val hwVersion: String = "",
    val apiUserAgent: String = "",
    val playerUserAgent: String = "",
    val playerHeaders: String = "",
    val xUserAgentLink: String = LINK_ETHERNET,
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int? = null,
    val requestRules: List<ProviderStalkerRequestRule> = emptyList(),
) {
    val normalizedLink: String
        get() = if (xUserAgentLink.equals(LINK_WIFI, ignoreCase = true)) LINK_WIFI else LINK_ETHERNET

    val proxy: ProviderStalkerHttpProxy?
        get() = if (proxyEnabled && proxyHost.isNotBlank() && proxyPort != null) {
            ProviderStalkerHttpProxy(proxyHost.trim(), proxyPort)
        } else {
            null
        }

    companion object {
        const val LINK_ETHERNET = "Ethernet"
        const val LINK_WIFI = "WiFi"
    }
}

internal data class ProviderStalkerRequestRule(
    val action: String = "",
    val blockRequest: Boolean = false,
    val paramOverrides: List<ProviderStalkerParamOverride> = emptyList(),
)

internal data class ProviderStalkerParamOverride(
    val name: String = "",
    val value: String = "",
)

internal data class ProviderStalkerHttpProxy(val host: String, val port: Int)

internal data class ProviderStalkerLegacyEditFields(
    val serialNumber: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val hwVersion: String = "",
    val apiUserAgent: String = "",
    val playerUserAgent: String = "",
    val playerHeaders: String = "",
    val xUserAgentLink: String = ProviderStalkerAdvancedOptions.LINK_ETHERNET,
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int? = null,
)

internal object ProviderStalkerAdvancedOptionsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String): ProviderStalkerAdvancedOptions {
        if (raw.isBlank()) return ProviderStalkerAdvancedOptions()
        return runCatching { decodeObject(json.parseToJsonElement(raw).jsonObject) }
            .getOrElse { ProviderStalkerAdvancedOptions() }
    }

    fun encode(options: ProviderStalkerAdvancedOptions): String {
        if (options == ProviderStalkerAdvancedOptions()) return ""
        return buildJsonObject {
            put("hwVersion", options.hwVersion)
            put("apiUserAgent", options.apiUserAgent)
            put("playerUserAgent", options.playerUserAgent)
            put("playerHeaders", options.playerHeaders)
            put("xUserAgentLink", options.xUserAgentLink)
            put("proxyEnabled", options.proxyEnabled)
            put("proxyHost", options.proxyHost)
            options.proxyPort?.let { put("proxyPort", it) }
            put("requestRules", buildJsonArray {
                options.requestRules.forEach { rule ->
                    add(buildJsonObject {
                        put("action", rule.action)
                        put("blockRequest", rule.blockRequest)
                        put("paramOverrides", buildJsonArray {
                            rule.paramOverrides.forEach { override ->
                                add(buildJsonObject {
                                    put("name", override.name)
                                    put("value", override.value)
                                })
                            }
                        })
                    })
                }
            })
        }.toString()
    }

    fun decodeLegacyEditFields(raw: String): ProviderStalkerLegacyEditFields {
        if (raw.isBlank()) return ProviderStalkerLegacyEditFields()
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            return ProviderStalkerLegacyEditFields()
        }
        return ProviderStalkerLegacyEditFields(
            serialNumber = root.string("serialNumber", "serial_number", "sn"),
            deviceId = root.string("deviceId", "device_id"),
            deviceId2 = root.string("deviceId2", "device_id2"),
            signature = root.string("signature"),
            hwVersion = root.string("hwVersion", "hw_version"),
            apiUserAgent = root.string("apiUserAgent", "api_user_agent"),
            playerUserAgent = root.string("playerUserAgent", "player_user_agent"),
            playerHeaders = root.string("playerHeaders", "player_headers"),
            xUserAgentLink = root.string("xUserAgentLink", "x_user_agent_link").ifBlank {
                ProviderStalkerAdvancedOptions.LINK_ETHERNET
            },
            proxyEnabled = root.boolean("proxyEnabled", "proxy_enabled") ?: false,
            proxyHost = root.string("proxyHost", "proxy_host"),
            proxyPort = root.int("proxyPort", "proxy_port"),
        )
    }

    private fun decodeObject(root: JsonObject): ProviderStalkerAdvancedOptions =
        ProviderStalkerAdvancedOptions(
            hwVersion = root.string("hwVersion"),
            apiUserAgent = root.string("apiUserAgent"),
            playerUserAgent = root.string("playerUserAgent"),
            playerHeaders = root.string("playerHeaders"),
            xUserAgentLink = root.string("xUserAgentLink").ifBlank {
                ProviderStalkerAdvancedOptions.LINK_ETHERNET
            },
            proxyEnabled = root.boolean("proxyEnabled") ?: false,
            proxyHost = root.string("proxyHost"),
            proxyPort = root.int("proxyPort"),
            requestRules = root.array("requestRules").map { element ->
                val rule = element.jsonObject
                ProviderStalkerRequestRule(
                    action = rule.string("action"),
                    blockRequest = rule.boolean("blockRequest") ?: false,
                    paramOverrides = rule.array("paramOverrides").map { overrideElement ->
                        val override = overrideElement.jsonObject
                        ProviderStalkerParamOverride(
                            name = override.string("name"),
                            value = override.string("value"),
                        )
                    },
                )
            },
        )
}

private fun JsonObject.string(vararg keys: String): String =
    keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.content?.trim() }.orEmpty()

private fun JsonObject.boolean(vararg keys: String): Boolean? =
    keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.booleanOrNull }

private fun JsonObject.int(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.intOrNull }

private fun JsonObject.array(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())
