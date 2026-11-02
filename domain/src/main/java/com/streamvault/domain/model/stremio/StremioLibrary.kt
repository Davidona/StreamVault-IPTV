package com.streamvault.domain.model.stremio

import java.util.Base64

private fun itemToken(type: StremioMediaType, id: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString((type.typeName + "\u0000" + id).toByteArray(Charsets.UTF_8))
fun stremioLibraryKey(type: StremioMediaType, id: String) = "__library:" + itemToken(type, id)
fun stremioWatchedKey(type: StremioMediaType, id: String) = "__watched:" + itemToken(type, id)
fun decodeStremioLibraryKey(key: String): Pair<StremioMediaType, String>? = runCatching {
    if (!key.startsWith("__library:") || key.length > 16_384) return@runCatching null
    val parts = String(Base64.getUrlDecoder().decode(key.substringAfter(':')), Charsets.UTF_8).split('\u0000', limit = 2)
    if (parts.size != 2 || parts[1].isBlank()) return@runCatching null
    val type = StremioMediaType.fromTypeName(parts[0])
    if (type == StremioMediaType.UNKNOWN) null else type to parts[1]
}.getOrNull()

fun stremioNotificationsKey(type: StremioMediaType, id: String) = "__notify:" + itemToken(type, id)
fun stremioFollowedKey(type: StremioMediaType, id: String, at: Long) = "__followed:" + itemToken(type, id) + ":" + at
fun stremioFollowedAt(settings: Map<String, Boolean>, type: StremioMediaType, id: String): Long? {
    val prefix = "__followed:" + itemToken(type, id) + ":"
    return settings.filter { it.value && it.key.startsWith(prefix) }.keys.mapNotNull { it.removePrefix(prefix).toLongOrNull() }.maxOrNull()
}
