package com.streamvault.player

data class PlayerTrack(
    val id: String,
    val name: String,
    val language: String?,
    val type: TrackType,
    val isSelected: Boolean,
    val subtitleSource: SubtitleTrackSource = SubtitleTrackSource.PROVIDER,
    val videoHeight: Int? = null,
    val videoBitrateBps: Long? = null,
)

enum class SubtitleTrackSource { PROVIDER, ONLINE, DEVICE }

internal const val ONLINE_SUBTITLE_TRACK_ID = "streamvault-online-subtitle"
internal const val DEVICE_SUBTITLE_TRACK_ID = "streamvault-device-subtitle"

enum class TrackType {
    AUDIO,
    TEXT, // Subtitles
    VIDEO
}
