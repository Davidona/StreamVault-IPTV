package com.streamvault.domain.model

import com.streamvault.domain.util.StreamEntryUrlPolicy

data class StreamInfo(
    val url: String,
    val title: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    /**
     * Provider-scoped transport decision. The legacy Boolean remains only for existing
     * non-Stalker compatibility paths.
     */
    val playbackTransportPolicy: PlaybackTransportPolicy? = null,
    val allowInvalidSsl: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int? = null,
    val streamType: StreamType = StreamType.UNKNOWN,
    val containerExtension: String? = null,
    val catchUpUrl: String? = null,
    val expirationTime: Long? = null,
    val drmInfo: DrmInfo? = null
) {
    init {
        require(url.isNotBlank()) { "StreamInfo url must not be blank" }
        expirationTime?.let { require(it >= 0) { "StreamInfo expirationTime must be non-negative" } }
        proxyPort?.let { require(it in 1..65535) { "StreamInfo proxyPort must be between 1 and 65535" } }
    }
}

enum class PlaybackTransportMode {
    STRICT,
    USER_ACCEPTED_UNVERIFIED_HTTPS,
    USER_ACCEPTED_HTTP
}

data class PlaybackTransportPolicy(
    val mode: PlaybackTransportMode,
    val origin: StalkerTransportOrigin,
    /** Base64 SHA-256 of the approved SubjectPublicKeyInfo. */
    val spkiSha256: String? = null,
    /**
     * IPTV providers often redirect an approved HTTP portal URL to a raw-IP or CDN HTTP
     * stream. The player may follow those redirects only when the user explicitly accepted
     * cleartext HTTP transport.
     */
    val allowCrossOriginHttpRedirects: Boolean = false
) {
    init {
        if (mode == PlaybackTransportMode.USER_ACCEPTED_UNVERIFIED_HTTPS) {
            require(!spkiSha256.isNullOrBlank()) {
                "Accepted unverified HTTPS playback requires an SPKI fingerprint"
            }
        }
        if (allowCrossOriginHttpRedirects) {
            require(mode == PlaybackTransportMode.USER_ACCEPTED_HTTP) {
                "Cross-origin playback redirects require accepted HTTP transport"
            }
            require(origin.scheme.equals("http", ignoreCase = true)) {
                "Cross-origin playback redirects require an HTTP origin"
            }
        }
    }
}

data class StaticClearKey(
    val keyIdBase64Url: String,
    val keyBase64Url: String
) {
    init {
        require(keyIdBase64Url.isNotBlank()) { "Static ClearKey key ID must not be blank" }
        require(keyBase64Url.isNotBlank()) { "Static ClearKey key must not be blank" }
    }

    override fun toString(): String = "StaticClearKey(<redacted>)"
}

data class StaticClearKeyLicense(
    val keys: List<StaticClearKey>,
    val fingerprint: String
) {
    init {
        require(keys.isNotEmpty()) { "Static ClearKey license must contain at least one key" }
        require(fingerprint.isNotBlank()) { "Static ClearKey license fingerprint must not be blank" }
    }

    override fun toString(): String = "StaticClearKeyLicense(keys=${keys.size}, fingerprint=$fingerprint)"
}

data class DrmInfo(
    val scheme: DrmScheme,
    val licenseUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val multiSession: Boolean = false,
    val forceDefaultLicenseUrl: Boolean = false,
    val playClearContentWithoutKey: Boolean = false,
    val staticClearKeyLicense: StaticClearKeyLicense? = null
) {
    private val hasRemoteLicense: Boolean
        get() = licenseUrl.isNotBlank()

    init {
        require(hasRemoteLicense xor (staticClearKeyLicense != null)) {
            "DrmInfo must contain exactly one remote or static license source"
        }
        if (hasRemoteLicense) {
            require(StreamEntryUrlPolicy.isAllowed(licenseUrl)) {
                "DrmInfo licenseUrl must use an allowed stream-entry URL scheme"
            }
        }
        if (staticClearKeyLicense != null) {
            require(scheme == DrmScheme.CLEARKEY) {
                "Static license material is supported only for ClearKey"
            }
        }
    }

    override fun toString(): String = buildString {
        append("DrmInfo(scheme=")
        append(scheme)
        append(", licenseUrl=")
        append(if (hasRemoteLicense) redactedUrl(licenseUrl) else "<local>")
        append(", headers=")
        append(headers.keys)
        append(", multiSession=")
        append(multiSession)
        append(", forceDefaultLicenseUrl=")
        append(forceDefaultLicenseUrl)
        append(", playClearContentWithoutKey=")
        append(playClearContentWithoutKey)
        append(", staticClearKeyLicense=")
        append(staticClearKeyLicense?.let { "<${it.keys.size} keys>" } ?: "null")
        append(')')
    }

    private fun redactedUrl(url: String): String = runCatching {
        val parsed = java.net.URI(url)
        buildString {
            append(parsed.scheme ?: "<url>")
            append("://")
            append(parsed.host ?: "<host>")
            parsed.port.takeIf { it >= 0 }?.let { append(':').append(it) }
        }
    }.getOrDefault("<url>")
}

enum class DrmScheme {
    WIDEVINE,
    PLAYREADY,
    CLEARKEY
}

enum class StreamType {
    HLS,
    DASH,
    SMOOTH_STREAMING,
    MPEG_TS,
    PROGRESSIVE,
    RTSP,    // PE-H03: native RTSP via Media3 RtspMediaSource
    UNKNOWN;

    companion object {
        fun fromContainerExtension(ext: String?): StreamType {
            return when (ext?.trim()?.removePrefix(".")?.lowercase()) {
                "ts" -> MPEG_TS
                "m3u8" -> HLS
                "mpd" -> DASH
                "ism", "isml" -> SMOOTH_STREAMING
                "mp4", "mkv", "avi", "mov", "mp3", "aac", "m4a", "flv", "webm" -> PROGRESSIVE
                else -> UNKNOWN
            }
        }
    }
}

enum class DecoderMode {
    AUTO,
    HARDWARE,
    SOFTWARE,
    COMPATIBILITY
}

enum class PlayerSurfaceMode {
    AUTO,
    SURFACE_VIEW,
    TEXTURE_VIEW
}

enum class VodHttpProtocolMode {
    COMPATIBILITY_HTTP1,
    AUTO
}

enum class LiveStreamFormatMode {
    AUTO,
    HLS,
    MPEG_TS
}
