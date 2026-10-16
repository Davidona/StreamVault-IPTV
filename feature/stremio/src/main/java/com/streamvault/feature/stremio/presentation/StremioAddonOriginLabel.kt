package com.streamvault.feature.stremio.presentation

import java.net.URI

/** Returns only the server origin so configured manifest path/query tokens are never shown. */
internal fun stremioAddonOriginLabel(installUrl: String): String? = runCatching {
    val uri = URI(installUrl)
    val scheme = uri.scheme?.lowercase() ?: return@runCatching null
    val host = uri.host ?: return@runCatching null
    val bareHost = host.removePrefix("[").removeSuffix("]")
    val safeHost = if (bareHost.contains(':')) "[$bareHost]" else bareHost
    val defaultPort = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
    val port = uri.port.takeIf { it >= 0 && it != defaultPort }
    "$scheme://$safeHost${port?.let { ":$it" }.orEmpty()}"
}.getOrNull()
