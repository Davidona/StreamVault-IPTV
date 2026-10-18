package com.streamvault.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object StremioCatalogSettingsCodec {
    fun decode(raw: String): Map<String, Boolean> = runCatching {
        if (raw.isBlank()) return@runCatching emptyMap()
        Json.parseToJsonElement(raw).jsonObject.mapNotNull { (key, value) ->
            val enabled = value.jsonPrimitive.booleanOrNull ?: return@mapNotNull null
            key to enabled
        }.toMap()
    }.getOrDefault(emptyMap())

    fun encode(settings: Map<String, Boolean>): String =
        if (settings.isEmpty()) {
            ""
        } else {
            buildJsonObject {
                settings.toSortedMap().forEach { (key, enabled) -> put(key, enabled) }
            }.toString()
        }
}
