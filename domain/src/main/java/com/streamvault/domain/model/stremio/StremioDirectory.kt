package com.streamvault.domain.model.stremio

data class StremioDirectoryEntry(val transportUrl: String, val manifest: StremioManifest)

fun stremioConfigureUrl(manifestUrl: String): String? = runCatching {
    val uri = java.net.URI(manifestUrl)
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return@runCatching null
    val path = uri.rawPath.orEmpty().trimEnd('/').removeSuffix("/manifest.json") + "/configure"
    "${uri.scheme}://${uri.rawAuthority}$path" + (uri.rawQuery?.let { "?$it" } ?: "")
}.getOrNull()
