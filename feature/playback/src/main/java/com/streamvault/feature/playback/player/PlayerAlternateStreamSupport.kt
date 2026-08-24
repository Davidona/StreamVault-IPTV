package com.streamvault.feature.playback.player

import com.streamvault.data.remote.xtream.XtreamStreamKind
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LiveChannelVariant
import java.net.URI
import java.util.Locale

private val LIVE_AVC_CODEC_TOKENS = listOf("avc", "h264", "x264")
private val LIVE_HEVC_CODEC_TOKENS = listOf("hevc", "h265", "x265", "hev1", "hvc1", "av1")

internal enum class LiveRecoveryCandidateKind {
    VARIANT,
    XTREAM_TS_FALLBACK,
    ALTERNATE
}

internal data class LiveRecoveryCandidate(
    val url: String,
    val kind: LiveRecoveryCandidateKind,
    val variant: LiveChannelVariant? = null
)

internal fun selectNextAlternateUrl(
    candidateUrls: List<String>,
    currentStreamUrl: String,
    triedAlternativeStreams: Set<String>,
    failedStreamsThisSession: Map<String, Int>
): String? {
    return candidateUrls.firstOrNull { altUrl ->
        altUrl != currentStreamUrl &&
            altUrl !in triedAlternativeStreams &&
            (failedStreamsThisSession[altUrl] ?: 0) == 0
    } ?: candidateUrls.firstOrNull { altUrl ->
        altUrl != currentStreamUrl && altUrl !in triedAlternativeStreams
    }
}

internal fun selectNextLiveVariant(
    variants: List<LiveChannelVariant>,
    currentVariantId: Long,
    currentStreamUrl: String,
    triedAlternativeStreams: Set<String>,
    failedStreamsThisSession: Map<String, Int>
): LiveChannelVariant? {
    val eligibleVariants = variants.filter { variant ->
        variant.rawChannelId != currentVariantId &&
            variant.streamUrl.isNotBlank() &&
            variant.streamUrl != currentStreamUrl &&
            variant.streamUrl !in triedAlternativeStreams &&
            (failedStreamsThisSession[variant.streamUrl] ?: 0) == 0
    }
    if (eligibleVariants.isEmpty()) {
        return variants.firstOrNull { variant ->
            variant.rawChannelId != currentVariantId &&
                variant.streamUrl.isNotBlank() &&
                variant.streamUrl != currentStreamUrl &&
                variant.streamUrl !in triedAlternativeStreams
        }
    }

    return eligibleVariants.firstOrNull { liveVariantCodecPriority(it) == 2 }
        ?: eligibleVariants.firstOrNull { liveVariantCodecPriority(it) == 1 }
        ?: eligibleVariants.firstOrNull()
}

internal fun selectNextLiveRecoveryCandidate(
    channel: Channel,
    currentVariantId: Long,
    currentStreamUrl: String,
    currentResolvedPlaybackUrl: String,
    triedAlternativeStreams: Set<String>,
    failedStreamsThisSession: Map<String, Int>,
    preferXtreamTsFallback: Boolean,
    allowXtreamTsFallback: Boolean = true
): LiveRecoveryCandidate? {
    val nextVariant = selectNextLiveVariant(
        variants = channel.variants,
        currentVariantId = currentVariantId,
        currentStreamUrl = currentStreamUrl,
        triedAlternativeStreams = triedAlternativeStreams,
        failedStreamsThisSession = failedStreamsThisSession
    )?.let { variant ->
        LiveRecoveryCandidate(
            url = variant.streamUrl,
            kind = LiveRecoveryCandidateKind.VARIANT,
            variant = variant
        )
    }
    val xtreamTsFallback = if (allowXtreamTsFallback) {
        selectXtreamLiveTsFallbackUrl(
            channel = channel,
            currentStreamUrl = currentStreamUrl,
            currentResolvedPlaybackUrl = currentResolvedPlaybackUrl,
            triedAlternativeStreams = triedAlternativeStreams,
            failedStreamsThisSession = failedStreamsThisSession
        )?.let { fallbackUrl ->
            LiveRecoveryCandidate(
                url = fallbackUrl,
                kind = LiveRecoveryCandidateKind.XTREAM_TS_FALLBACK
            )
        }
    } else {
        null
    }
    val alternate = selectNextAlternateUrl(
        candidateUrls = channel.alternativeStreams.filter { it != xtreamTsFallback?.url },
        currentStreamUrl = currentStreamUrl,
        triedAlternativeStreams = triedAlternativeStreams,
        failedStreamsThisSession = failedStreamsThisSession
    )?.let { alternateUrl ->
        LiveRecoveryCandidate(
            url = alternateUrl,
            kind = LiveRecoveryCandidateKind.ALTERNATE
        )
    }

    return if (preferXtreamTsFallback) {
        xtreamTsFallback ?: nextVariant ?: alternate
    } else {
        nextVariant ?: xtreamTsFallback ?: alternate
    }
}

internal fun buildXtreamLiveTsFallbackUrl(
    channel: Channel,
    currentStreamUrl: String,
    currentResolvedPlaybackUrl: String
): String? {
    val token = XtreamUrlFactory.parseInternalStreamUrl(currentStreamUrl)
    if (token != null && token.kind != XtreamStreamKind.LIVE) return null

    val currentLooksLikeHls = token?.containerExtension == "m3u8" ||
        currentStreamUrl.contains("ext=m3u8", ignoreCase = true) ||
        currentResolvedPlaybackUrl.contains("ext=m3u8", ignoreCase = true) ||
        currentResolvedPlaybackUrl.lowercase(Locale.ROOT).substringBefore('?').endsWith(".m3u8")
    if (!currentLooksLikeHls) return null

    val providerId = token?.providerId ?: channel.providerId
    val streamId = token?.streamId
        ?: channel.streamId.takeIf { it > 0L }
        ?: extractXtreamLiveStreamId(currentResolvedPlaybackUrl)
        ?: return null
    if (providerId <= 0L || streamId <= 0L) return null

    return XtreamUrlFactory.buildInternalStreamUrl(
        providerId = providerId,
        kind = XtreamStreamKind.LIVE,
        streamId = streamId,
        containerExtension = "ts"
    )
}

internal fun selectXtreamLiveTsFallbackUrl(
    channel: Channel,
    currentStreamUrl: String,
    currentResolvedPlaybackUrl: String,
    triedAlternativeStreams: Set<String>,
    failedStreamsThisSession: Map<String, Int>
): String? {
    val fallbackUrl = buildXtreamLiveTsFallbackUrl(
        channel = channel,
        currentStreamUrl = currentStreamUrl,
        currentResolvedPlaybackUrl = currentResolvedPlaybackUrl
    ) ?: return null
    return fallbackUrl.takeIf {
        it != currentStreamUrl &&
            it !in triedAlternativeStreams &&
            (failedStreamsThisSession[it] ?: 0) == 0
    }
}

private fun extractXtreamLiveStreamId(url: String): Long? {
    val pathSegments = runCatching { URI(url).path }
        .getOrNull()
        ?.trim('/')
        ?.split('/')
        ?.filter { it.isNotBlank() }
        .orEmpty()
    val liveIndex = pathSegments.indexOfFirst { it.equals("live", ignoreCase = true) }
    if (liveIndex < 0) return null
    val fileSegment = pathSegments.getOrNull(liveIndex + 3) ?: return null
    return fileSegment.substringBefore('?')
        .substringBeforeLast('.', missingDelimiterValue = fileSegment)
        .toLongOrNull()
}

private fun liveVariantCodecPriority(variant: LiveChannelVariant): Int {
    val codecLabel = variant.attributes.codecLabel?.trim()?.uppercase(Locale.ROOT).orEmpty()
    if (codecLabel in setOf("AVC", "H.264")) return 2
    if (codecLabel in setOf("HEVC", "AV1")) return 0

    val name = buildString {
        append(variant.originalName)
        append(' ')
        append(variant.canonicalName)
    }.lowercase(Locale.ROOT)
    return when {
        LIVE_AVC_CODEC_TOKENS.any { token -> name.contains(token) } -> 2
        LIVE_HEVC_CODEC_TOKENS.any { token -> name.contains(token) } -> 0
        else -> 1
    }
}
