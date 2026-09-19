package com.streamvault.data.preferences

import com.streamvault.domain.settings.VodTrackPreference
import com.streamvault.domain.settings.VodTrackPreferences
import java.util.Base64

internal fun encodeVodTrackPreferences(preferences: VodTrackPreferences?): String = preferences
    ?.let { value ->
        listOf(value.audio, value.subtitle)
            .joinToString(";", transform = ::encodeVodTrackPreference)
    }
    .orEmpty()

internal fun decodeVodTrackPreferences(encoded: String?): VodTrackPreferences? {
    val parts = encoded.orEmpty().split(';')
    if (parts.size != 2 || parts.all(String::isBlank)) return null
    return VodTrackPreferences(
        audio = decodeVodTrackPreference(parts[0]),
        subtitle = decodeVodTrackPreference(parts[1])
    ).takeUnless { it.audio == null && it.subtitle == null }
}

internal fun encodeVodTrackPreferenceEntries(
    values: Map<String, VodTrackPreferences>
): String = values.entries
    .sortedBy { it.key }
    .joinToString("\n") { (key, preferences) ->
        "${encodeComponent(key)}=${encodeVodTrackPreferences(preferences)}"
    }

internal fun decodeVodTrackPreferenceEntries(
    encoded: String?
): Map<String, VodTrackPreferences> = encoded
    .orEmpty()
    .lineSequence()
    .mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val key = decodeComponent(line.substring(0, separator)) ?: return@mapNotNull null
        val preferences = decodeVodTrackPreferences(line.substring(separator + 1)) ?: return@mapNotNull null
        key to preferences
    }
    .toMap()

private fun encodeVodTrackPreference(preference: VodTrackPreference?): String {
    if (preference == null) return "-"
    return listOf(
        if (preference.disabled) "1" else "0",
        encodeComponent(preference.trackId),
        encodeComponent(preference.language),
        encodeComponent(preference.label)
    ).joinToString("~")
}

private fun decodeVodTrackPreference(encoded: String): VodTrackPreference? {
    if (encoded == "-") return null
    val parts = encoded.split('~')
    if (parts.size != 4) return null
    return VodTrackPreference(
        trackId = decodeComponent(parts[1]),
        language = decodeComponent(parts[2]),
        label = decodeComponent(parts[3]),
        disabled = parts[0] == "1"
    )
}

private fun encodeComponent(value: String?): String = value
    ?.takeIf(String::isNotEmpty)
    ?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }
    .orEmpty()

private fun decodeComponent(value: String): String? = value
    .takeIf(String::isNotEmpty)
    ?.let { runCatching { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
