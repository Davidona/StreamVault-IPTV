package com.streamvault.player.playback

import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.StreamInfo
import java.util.Locale

internal data class PlaybackBufferPolicy(
    val label: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackBufferMs: Int,
    val rebufferMs: Int,
    val targetBufferBytes: Int,
    val prioritizeTimeOverSizeThresholds: Boolean,
    val qualityReason: String = "baseline",
    val lowMemoryCapped: Boolean = false
)

internal object PlaybackBufferPolicies {
    private const val DEFAULT_TARGET_BUFFER_BYTES = -1
    private const val MPEG_TS_LIVE_TARGET_BUFFER_BYTES = 16 * 1024 * 1024
    private const val MEDIUM_LIVE_TARGET_BUFFER_BYTES = 32 * 1024 * 1024
    private const val LARGE_LIVE_TARGET_BUFFER_BYTES = 64 * 1024 * 1024
    private const val MPEG_TS_LIVE_MIN_BUFFER_MS = 5_000
    private const val MPEG_TS_LIVE_MAX_BUFFER_MS = 10_000

    private const val LOW_MEMORY_LIVE_MIN_BUFFER_MS = 4_000
    private const val LOW_MEMORY_LIVE_MAX_BUFFER_MS = 12_000
    private const val LOW_MEMORY_COMPAT_LIVE_MIN_BUFFER_MS = 6_000
    private const val LOW_MEMORY_COMPAT_LIVE_MAX_BUFFER_MS = 15_000
    private const val LOW_MEMORY_VOD_MIN_BUFFER_MS = 15_000
    private const val LOW_MEMORY_VOD_MAX_BUFFER_MS = 45_000
    private const val LOW_MEMORY_PLAYBACK_BUFFER_MS = 1_000
    private const val LOW_MEMORY_REBUFFER_MS = 3_000

    private const val LIVE_MIN_BUFFER_MS = 8_000
    private const val LIVE_MAX_BUFFER_MS = 30_000
    private const val COMPAT_LIVE_MIN_BUFFER_MS = 15_000
    private const val COMPAT_LIVE_MAX_BUFFER_MS = 45_000
    private const val VOD_MIN_BUFFER_MS = 90_000
    private const val VOD_MAX_BUFFER_MS = 240_000
    private const val PLAYBACK_BUFFER_MS = 1_500
    private const val REBUFFER_MS = 5_000
    private const val VOD_PLAYBACK_BUFFER_MS = 8_000
    private const val VOD_REBUFFER_MS = 18_000
    private const val MEDIUM_LIVE_MIN_BUFFER_MS = 15_000
    private const val MEDIUM_LIVE_MAX_BUFFER_MS = 45_000
    private const val MEDIUM_LIVE_PLAYBACK_BUFFER_MS = 3_000
    private const val MEDIUM_LIVE_REBUFFER_MS = 10_000
    private const val LARGE_LIVE_MIN_BUFFER_MS = 30_000
    private const val LARGE_LIVE_MAX_BUFFER_MS = 90_000
    private const val LARGE_LIVE_PLAYBACK_BUFFER_MS = 5_000
    private const val LARGE_LIVE_REBUFFER_MS = 15_000

    fun forPlayback(
        isLive: Boolean,
        compatibilityMode: Boolean,
        lowMemoryDevice: Boolean
    ): PlaybackBufferPolicy = forPlayback(
        resolvedStreamType = if (isLive) ResolvedStreamType.HLS else ResolvedStreamType.PROGRESSIVE,
        compatibilityMode = compatibilityMode,
        lowMemoryDevice = lowMemoryDevice
    )

    fun forPlayback(
        isLive: Boolean,
        compatibilityMode: Boolean
    ): PlaybackBufferPolicy = forPlayback(
        isLive = isLive,
        compatibilityMode = compatibilityMode,
        lowMemoryDevice = false
    )

    fun forPlayback(
        resolvedStreamType: ResolvedStreamType,
        compatibilityMode: Boolean
    ): PlaybackBufferPolicy = forPlayback(
        resolvedStreamType = resolvedStreamType,
        compatibilityMode = compatibilityMode,
        lowMemoryDevice = false
    )

    fun forPlayback(
        resolvedStreamType: ResolvedStreamType,
        compatibilityMode: Boolean,
        lowMemoryDevice: Boolean
    ): PlaybackBufferPolicy = forPlayback(
        resolvedStreamType = resolvedStreamType,
        compatibilityMode = compatibilityMode,
        lowMemoryDevice = lowMemoryDevice,
        bufferMode = PlaybackBufferMode.AUTO,
        streamInfo = null
    )

    fun forPlayback(
        resolvedStreamType: ResolvedStreamType,
        compatibilityMode: Boolean,
        lowMemoryDevice: Boolean,
        bufferMode: PlaybackBufferMode,
        streamInfo: StreamInfo? = null
    ): PlaybackBufferPolicy {
        val kind = streamInfo?.playbackContentKind ?: PlaybackContentKind.UNKNOWN
        val isLive = isLivePlayback(kind, resolvedStreamType)
        if (bufferMode == PlaybackBufferMode.MEDIUM && isLive) {
            return mediumLivePolicy(label = "medium-live", qualityReason = "user-medium")
        }
        if (bufferMode == PlaybackBufferMode.LARGE && isLive) {
            return largeLivePolicy(label = "large-live", qualityReason = "user-large")
        }
        if (!isLive && resolvedStreamType == ResolvedStreamType.HLS) {
            return baselineLivePolicy(
                resolvedStreamType = resolvedStreamType,
                compatibilityMode = compatibilityMode,
                lowMemoryDevice = lowMemoryDevice,
                isLive = true
            ).copy(label = "vod-hls-initial", qualityReason = "finite-content")
        }
        if (bufferMode == PlaybackBufferMode.AUTO && resolvedStreamType == ResolvedStreamType.HLS) {
            val qualityReason = highQualityLiveHlsReason(streamInfo)
            return when {
                qualityReason == null -> baselineLivePolicy(
                    resolvedStreamType = resolvedStreamType,
                    compatibilityMode = compatibilityMode,
                    lowMemoryDevice = lowMemoryDevice,
                    isLive = isLive
                )
                lowMemoryDevice -> mediumLivePolicy(
                    label = "auto-uhd-live-hls-capped",
                    qualityReason = qualityReason,
                    lowMemoryCapped = true
                )
                else -> largeLivePolicy(label = "auto-uhd-live-hls", qualityReason = qualityReason)
            }
        }
        return baselineLivePolicy(
            resolvedStreamType = resolvedStreamType,
            compatibilityMode = compatibilityMode,
            lowMemoryDevice = lowMemoryDevice,
            isLive = isLive
        )
    }

    private fun baselineLivePolicy(
        resolvedStreamType: ResolvedStreamType,
        compatibilityMode: Boolean,
        lowMemoryDevice: Boolean,
        isLive: Boolean = resolvedStreamType.isLive
    ): PlaybackBufferPolicy = when {
        lowMemoryDevice && resolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE ->
            PlaybackBufferPolicy(
                label = "lowmem-mpeg-ts-live",
                minBufferMs = LOW_MEMORY_LIVE_MIN_BUFFER_MS,
                maxBufferMs = LOW_MEMORY_LIVE_MAX_BUFFER_MS,
                playbackBufferMs = LOW_MEMORY_PLAYBACK_BUFFER_MS,
                rebufferMs = LOW_MEMORY_REBUFFER_MS,
                targetBufferBytes = MPEG_TS_LIVE_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = false
            )
        lowMemoryDevice && compatibilityMode && isLive ->
            PlaybackBufferPolicy(
                label = "lowmem-compat-live",
                minBufferMs = LOW_MEMORY_COMPAT_LIVE_MIN_BUFFER_MS,
                maxBufferMs = LOW_MEMORY_COMPAT_LIVE_MAX_BUFFER_MS,
                playbackBufferMs = LOW_MEMORY_PLAYBACK_BUFFER_MS,
                rebufferMs = LOW_MEMORY_REBUFFER_MS,
                targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = false
            )
        lowMemoryDevice && isLive ->
            PlaybackBufferPolicy(
                label = "lowmem-live",
                minBufferMs = LOW_MEMORY_LIVE_MIN_BUFFER_MS,
                maxBufferMs = LOW_MEMORY_LIVE_MAX_BUFFER_MS,
                playbackBufferMs = LOW_MEMORY_PLAYBACK_BUFFER_MS,
                rebufferMs = LOW_MEMORY_REBUFFER_MS,
                targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = false
            )
        lowMemoryDevice ->
            PlaybackBufferPolicy(
                label = "lowmem-vod",
                minBufferMs = LOW_MEMORY_VOD_MIN_BUFFER_MS,
                maxBufferMs = LOW_MEMORY_VOD_MAX_BUFFER_MS,
                playbackBufferMs = LOW_MEMORY_PLAYBACK_BUFFER_MS,
                rebufferMs = LOW_MEMORY_REBUFFER_MS,
                targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = false
            )
        resolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE ->
            PlaybackBufferPolicy(
                label = "mpeg-ts-live",
                minBufferMs = MPEG_TS_LIVE_MIN_BUFFER_MS,
                maxBufferMs = MPEG_TS_LIVE_MAX_BUFFER_MS,
                playbackBufferMs = PLAYBACK_BUFFER_MS,
                rebufferMs = REBUFFER_MS,
                targetBufferBytes = MPEG_TS_LIVE_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = false
            )
        compatibilityMode && isLive ->
            PlaybackBufferPolicy(
                label = "compat-live",
                minBufferMs = COMPAT_LIVE_MIN_BUFFER_MS,
                maxBufferMs = COMPAT_LIVE_MAX_BUFFER_MS,
                playbackBufferMs = PLAYBACK_BUFFER_MS,
                rebufferMs = REBUFFER_MS,
                targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = false
            )
        isLive ->
            PlaybackBufferPolicy(
                label = "stable-live",
                minBufferMs = LIVE_MIN_BUFFER_MS,
                maxBufferMs = LIVE_MAX_BUFFER_MS,
                playbackBufferMs = PLAYBACK_BUFFER_MS,
                rebufferMs = REBUFFER_MS,
                targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = false
            )
        else ->
            PlaybackBufferPolicy(
                label = "stable-vod",
                minBufferMs = VOD_MIN_BUFFER_MS,
                maxBufferMs = VOD_MAX_BUFFER_MS,
                playbackBufferMs = VOD_PLAYBACK_BUFFER_MS,
                rebufferMs = VOD_REBUFFER_MS,
                targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds = false
            )
    }

    private fun mediumLivePolicy(
        label: String,
        qualityReason: String,
        lowMemoryCapped: Boolean = false
    ): PlaybackBufferPolicy = PlaybackBufferPolicy(
        label = label,
        minBufferMs = MEDIUM_LIVE_MIN_BUFFER_MS,
        maxBufferMs = MEDIUM_LIVE_MAX_BUFFER_MS,
        playbackBufferMs = MEDIUM_LIVE_PLAYBACK_BUFFER_MS,
        rebufferMs = MEDIUM_LIVE_REBUFFER_MS,
        targetBufferBytes = if (lowMemoryCapped) DEFAULT_TARGET_BUFFER_BYTES else MEDIUM_LIVE_TARGET_BUFFER_BYTES,
        prioritizeTimeOverSizeThresholds = false,
        qualityReason = qualityReason,
        lowMemoryCapped = lowMemoryCapped
    )

    private fun largeLivePolicy(label: String, qualityReason: String): PlaybackBufferPolicy = PlaybackBufferPolicy(
        label = label,
        minBufferMs = LARGE_LIVE_MIN_BUFFER_MS,
        maxBufferMs = LARGE_LIVE_MAX_BUFFER_MS,
        playbackBufferMs = LARGE_LIVE_PLAYBACK_BUFFER_MS,
        rebufferMs = LARGE_LIVE_REBUFFER_MS,
        targetBufferBytes = LARGE_LIVE_TARGET_BUFFER_BYTES,
        prioritizeTimeOverSizeThresholds = false,
        qualityReason = qualityReason
    )

    fun highQualityLiveHlsReason(streamInfo: StreamInfo?): String? {
        val searchable = listOfNotNull(
            streamInfo?.title,
            streamInfo?.url,
            streamInfo?.containerExtension
        ).joinToString(" ").lowercase(Locale.ROOT)
        if (searchable.isBlank()) return null
        return when {
            "2160p" in searchable -> "metadata-2160p"
            "2160" in searchable -> "metadata-2160"
            "uhd" in searchable -> "metadata-uhd"
            "4k" in searchable -> "metadata-4k"
            "hdr" in searchable -> "metadata-hdr"
            else -> null
        }
    }

    private val ResolvedStreamType.isLive: Boolean
        get() = this == ResolvedStreamType.HLS ||
            this == ResolvedStreamType.SMOOTH_STREAMING ||
            this == ResolvedStreamType.MPEG_TS_LIVE ||
            this == ResolvedStreamType.RTSP
}
