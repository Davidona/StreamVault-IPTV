package com.streamvault.data.repository

import com.streamvault.data.parser.M3uPlaybackMetadataCodec
import com.streamvault.domain.model.DrmInfo
import com.streamvault.domain.model.StreamInfo

/** Applies persisted, provider-neutral M3U playback metadata to a resolved stream. */
internal fun StreamInfo.withM3uPlaybackMetadata(rawMetadata: String?): StreamInfo {
    val metadata = M3uPlaybackMetadataCodec.decode(rawMetadata) ?: return this
    val mergedHeaders = linkedMapOf<String, String>().apply {
        putAll(headers)
        putAll(metadata.commonHeaders)
        putAll(metadata.manifestHeaders)
        putAll(metadata.streamHeaders)
        metadata.referer?.let { referer ->
            keys.firstOrNull { it.equals("Referer", ignoreCase = true) }?.let { remove(it) }
            put("Referer", referer)
        }
    }
    val drm = when {
        metadata.staticClearKeyLicense != null -> DrmInfo(
            scheme = metadata.drmScheme ?: com.streamvault.domain.model.DrmScheme.CLEARKEY,
            headers = metadata.licenseHeaders,
            staticClearKeyLicense = metadata.staticClearKeyLicense
        )
        metadata.drmScheme != null && !metadata.licenseUrl.isNullOrBlank() -> DrmInfo(
            scheme = metadata.drmScheme,
            licenseUrl = metadata.licenseUrl,
            headers = metadata.licenseHeaders
        )
        else -> drmInfo
    }
    return copy(
        headers = mergedHeaders,
        userAgent = metadata.userAgent ?: userAgent,
        streamType = metadata.manifestType ?: streamType,
        drmInfo = drm
    )
}
