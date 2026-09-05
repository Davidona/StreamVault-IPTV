package com.streamvault.data.remote.stalker

import java.net.URI

/**
 * Resolves Stalker portal channel-logo URLs. Some Ministra portals return the channel
 * `logo` field as a bare filename (`536.png`) that lives under the portal's
 * `misc/logos/{size}/` directory — the convention STBEmu uses when it requests
 * `/stalker_portal/misc/logos/120/536.png`. Absolute URLs, root-relative paths and
 * paths that already contain a directory are returned unchanged.
 *
 * Used both when importing channels (write time) and when presenting stored channels
 * (read time), so rows imported before this fix also render logos without a re-sync.
 */
object StalkerLogoUrlResolver {
    // Channel logos served from the portal's misc/logos/{size}/ directory. STBEmu and
    // other STB clients use the 120 bucket (36×36) as the smallest guaranteed size.
    const val STALKER_CHANNEL_LOGO_SIZE_BUCKET = 120

    fun resolveChannelLogoUrl(portalUrl: String, url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url
        if (url.startsWith("/")) return resolvePortalUrl(portalUrl, url)
        if (url.contains('/')) return url
        val origin = runCatching { URI(portalUrl) }.getOrNull() ?: return url
        val scheme = origin.scheme?.takeIf { it == "http" || it == "https" } ?: "https"
        val host = origin.host?.takeIf(String::isNotBlank) ?: return url
        val port = origin.port.takeIf { it > 0 }
        val authority = if (port != null && port != (if (scheme == "https") 443 else 80)) "$host:$port" else host
        // Derive the portal install root from the configured portal URL (e.g. a portalUrl of
        // `http://host/stalker_portal/server/load.php` maps logos to
        // `http://host/stalker_portal/misc/logos/120/536.png`).
        val installPath = portalInstallPath(origin.path)
        return "$scheme://$authority$installPath/misc/logos/$STALKER_CHANNEL_LOGO_SIZE_BUCKET/$url"
    }

    /** Resolves a root-relative portal path (`/stalker_portal/...`) against the portal origin. */
    fun resolvePortalUrl(portalUrl: String, url: String): String? {
        val origin = runCatching { URI(portalUrl) }.getOrNull() ?: return url
        val scheme = origin.scheme?.takeIf { it == "http" || it == "https" } ?: "https"
        val host = origin.host?.takeIf(String::isNotBlank) ?: return url
        val port = origin.port.takeIf { it > 0 }
        val authority = if (port != null && port != (if (scheme == "https") 443 else 80)) "$host:$port" else host
        return "$scheme://$authority$url"
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
}