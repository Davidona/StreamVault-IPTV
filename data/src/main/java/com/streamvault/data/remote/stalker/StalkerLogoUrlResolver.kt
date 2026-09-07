package com.streamvault.data.remote.stalker

import java.net.URI

/** Resolves Stalker portal channel-logo paths into URLs usable by image loaders. */
object StalkerLogoUrlResolver {
    const val STALKER_CHANNEL_LOGO_SIZE_BUCKET = 120

    fun resolveChannelLogoUrl(portalUrl: String, url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url
        if (url.startsWith("//")) return url
        if (url.startsWith("/")) return resolvePortalUrl(portalUrl, url)
        if (url.split('/').any { it == ".." }) return url

        val origin = parseHttpOrigin(portalUrl) ?: return url
        val relativePath = if (url.contains('/')) {
            url.trimStart('/')
        } else {
            "misc/logos/$STALKER_CHANNEL_LOGO_SIZE_BUCKET/$url"
        }
        val installPath = portalInstallPath(origin.path)
        return "${origin.scheme}://${origin.authority}$installPath/$relativePath"
    }

    /** Resolves a root-relative portal path against the configured portal origin. */
    fun resolvePortalUrl(portalUrl: String, url: String): String? {
        val origin = parseHttpOrigin(portalUrl) ?: return url
        return "${origin.scheme}://${origin.authority}$url"
    }

    private fun parseHttpOrigin(portalUrl: String): HttpOrigin? {
        val uri = runCatching { URI(portalUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.takeIf(String::isNotBlank) ?: return null
        val port = uri.port.takeIf { it > 0 }
        val defaultPort = if (scheme == "https") 443 else 80
        val authority = if (port != null && port != defaultPort) "$host:$port" else host
        return HttpOrigin(scheme = scheme, authority = authority, path = uri.path)
    }

    private fun portalInstallPath(portalPath: String?): String {
        val path = portalPath?.trimEnd('/').orEmpty()
        return when {
            path.endsWith("/server/load.php", ignoreCase = true) ->
                path.dropLast("/server/load.php".length)
            path.endsWith("/portal.php", ignoreCase = true) ->
                path.dropLast("/portal.php".length)
            path.endsWith("/c/index.html", ignoreCase = true) ->
                path.dropLast("/c/index.html".length)
            path.endsWith("/c", ignoreCase = true) ->
                path.dropLast("/c".length)
            else -> path
        }
    }

    private data class HttpOrigin(
        val scheme: String,
        val authority: String,
        val path: String?
    )
}
