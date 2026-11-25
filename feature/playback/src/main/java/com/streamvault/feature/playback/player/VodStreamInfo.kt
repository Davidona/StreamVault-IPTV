package com.streamvault.feature.playback.player

import com.streamvault.domain.model.VideoFormat
import java.util.Locale
import kotlin.math.max

/** Quality, video codec, HDR and audio codec chips read from the decoded stream, never from titles. */
internal fun vodStreamBadges(format: VideoFormat): List<String> = buildList {
    qualityBadge(format.width, format.height)?.let(::add)
    videoCodecBadge(format.codecV)?.let(::add)
    if (format.isHdr) add("HDR")
    audioCodecBadge(format.codecA)?.let(::add)
}

// Letterboxed 16:9 content has a short frame, so judge by the larger of the real and the 16:9 height.
private fun qualityBadge(width: Int, height: Int): String? {
    if (width <= 0 && height <= 0) return null
    return when (max(height, width * 9 / 16)) {
        in 2160..Int.MAX_VALUE -> "4K"
        in 1080..2159 -> "FHD"
        in 720..1079 -> "HD"
        else -> "SD"
    }
}

private fun videoCodecBadge(codec: String?): String? {
    val c = codec?.lowercase(Locale.ROOT) ?: return null
    return when {
        "dolby-vision" in c || c.startsWith("dvh") || c.startsWith("dvav") -> "Dolby Vision"
        "hevc" in c || "hvc1" in c || "hev1" in c || "h265" in c -> "HEVC"
        "avc" in c || "h264" in c -> "H.264"
        "av01" in c || "av1" in c -> "AV1"
        "vp9" in c || "vp09" in c -> "VP9"
        "mpeg2" in c || "mp2v" in c -> "MPEG-2"
        else -> null
    }
}

private fun audioCodecBadge(codec: String?): String? {
    val c = codec?.lowercase(Locale.ROOT) ?: return null
    return when {
        "eac3" in c || "ec-3" in c -> "E-AC-3"
        "truehd" in c -> "TrueHD"
        "ac-3" in c || "ac3" in c -> "AC-3"
        "dts" in c -> "DTS"
        "mp4a" in c || "aac" in c -> "AAC"
        "opus" in c -> "Opus"
        "audio/mpeg" in c || "mp3" in c -> "MP3"
        else -> null
    }
}

/** Wall-clock time the title ends at, or null when the duration is unknown. */
internal fun vodEndsAtMs(nowMs: Long, positionMs: Long, durationMs: Long, playbackSpeed: Float): Long? {
    if (durationMs <= 0L) return null
    val speed = if (playbackSpeed > 0f) playbackSpeed else 1f
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
    return nowMs + (remainingMs / speed).toLong()
}
