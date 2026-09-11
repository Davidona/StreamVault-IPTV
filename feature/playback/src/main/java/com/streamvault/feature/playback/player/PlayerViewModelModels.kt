package com.streamvault.feature.playback.player

import android.graphics.Bitmap
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelQualityOption
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.ExternalPlaybackMode
import com.streamvault.domain.model.LiveClockFont
import com.streamvault.domain.model.LiveClockPosition
import com.streamvault.domain.model.LiveClockSize
import com.streamvault.domain.model.PlayerBackButtonVisibility
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.DecoderMode
import com.streamvault.player.timeshift.LiveTimeshiftState
import java.util.Locale

data class ResumePromptState(
    val show: Boolean = false,
    val positionMs: Long = 0L,
    val title: String = ""
)

data class NumericChannelInputState(
    val input: String = "",
    val matchedChannelName: String? = null,
    val invalid: Boolean = false
)

data class PlayerNoticeState(
    val message: String = "",
    val recoveryType: PlayerRecoveryType = PlayerRecoveryType.UNKNOWN,
    val actions: List<PlayerNoticeAction> = emptyList(),
    val isRetryNotice: Boolean = false
)

data class SeekPreviewState(
    val visible: Boolean = false,
    val positionMs: Long = 0L,
    val frameBitmap: Bitmap? = null,
    val artworkUrl: String? = null,
    val title: String = "",
    val isLoading: Boolean = false
)

data class PlayerPreferencesUiState(
    val externalPlaybackMode: ExternalPlaybackMode = ExternalPlaybackMode.INTERNAL_PLAYER,
    val timeFormat: AppTimeFormat = AppTimeFormat.SYSTEM,
    val liveClockEnabled: Boolean = false,
    val liveClockPosition: LiveClockPosition = LiveClockPosition.TOP_END,
    val liveClockSize: LiveClockSize = LiveClockSize.MEDIUM,
    val liveClockFont: LiveClockFont = LiveClockFont.DIGITAL_MONO,
    val backButtonVisibility: PlayerBackButtonVisibility = PlayerBackButtonVisibility.DEFAULT
)

data class PlayerDiagnosticsUiState(
    val providerName: String = "",
    val providerSourceLabel: String = "",
    val audioDecoderMode: DecoderMode = DecoderMode.AUTO,
    val videoDecoderMode: DecoderMode = DecoderMode.AUTO,
    val activeDecoderName: String = "Unknown",
    val activeAudioDecoderName: String = "Unknown",
    val ffmpegAvailable: Boolean = false,
    val ffmpegVersion: String? = null,
    val audioOutputPath: String = "UNKNOWN",
    val compatibilityDecisionSource: String = "DEFAULT",
    val activeDecoderPolicy: String = "AUTO",
    val renderSurfaceType: String = "SURFACE_VIEW",
    val videoStallCount: Int = 0,
    val lastVideoFrameAgoMs: Long = 0,
    val streamClassLabel: String = "Primary",
    val playbackStateLabel: String = "Idle",
    val audioVideoOffsetMs: Int = 0,
    val audioVideoSyncEnabled: Boolean = false,
    val audioVideoSyncSinkActive: Boolean = false,
    val alternativeStreamCount: Int = 0,
    val channelErrorCount: Int = 0,
    val archiveSupportLabel: String = "",
    val lastFailureReason: String? = null,
    val recentRecoveryActions: List<String> = emptyList(),
    val troubleshootingHints: List<String> = emptyList()
)

data class PlayerAudioVideoOffsetUiState(
    val globalOffsetMs: Int = 0,
    val channelOverrideMs: Int? = null,
    val previewOffsetMs: Int? = null,
    val effectiveOffsetMs: Int = 0
) {
    val hasChannelOverride: Boolean
        get() = channelOverrideMs != null
}

data class PlayerTimeshiftUiState(
    val available: Boolean = false,
    val enabledForSession: Boolean = false,
    val backendLabel: String = "",
    val bufferedBehindLiveMs: Long = 0L,
    val bufferDepthMs: Long = 0L,
    val canSeekToLive: Boolean = false,
    val statusMessage: String = "",
    val engineState: LiveTimeshiftState = LiveTimeshiftState()
)

data class SleepTimerUiState(
    val stopTimerMinutes: Int = 0,
    val stopRemainingMs: Long = 0L,
    val idleTimerMinutes: Int = 0,
    val idleRemainingMs: Long = 0L
) {
    val stopTimerActive: Boolean
        get() = stopTimerMinutes > 0 && stopRemainingMs > 0L

    val idleTimerActive: Boolean
        get() = idleTimerMinutes > 0 && idleRemainingMs > 0L

    val stopTimerWarningVisible: Boolean
        get() = stopTimerActive && stopRemainingMs <= 60_000L

    val idleTimerWarningVisible: Boolean
        get() = idleTimerActive && idleRemainingMs <= 60_000L
}

data class AutoPlayCountdownUiState(
    val episode: Episode,
    val secondsRemaining: Int
)

data class PlayerPrepareIdentity(
    val streamUrl: String,
    val epgChannelId: String?,
    val internalChannelId: Long,
    val categoryId: Long,
    val providerId: Long,
    val isVirtual: Boolean,
    val combinedProfileId: Long?,
    val combinedSourceFilterProviderId: Long?,
    val contentType: String,
    val archiveStartMs: Long?,
    val archiveEndMs: Long?
)

internal fun buildPlayerPrepareIdentity(
    streamUrl: String,
    epgChannelId: String?,
    internalChannelId: Long,
    categoryId: Long?,
    providerId: Long?,
    isVirtual: Boolean,
    combinedProfileId: Long?,
    combinedSourceFilterProviderId: Long?,
    contentType: String,
    archiveStartMs: Long?,
    archiveEndMs: Long?
): PlayerPrepareIdentity = PlayerPrepareIdentity(
    streamUrl = streamUrl,
    epgChannelId = epgChannelId,
    internalChannelId = internalChannelId,
    categoryId = categoryId?.takeIf { it > 0L } ?: -1L,
    providerId = providerId?.takeIf { it > 0L } ?: -1L,
    isVirtual = isVirtual,
    combinedProfileId = combinedProfileId?.takeIf { it > 0L },
    combinedSourceFilterProviderId = combinedSourceFilterProviderId?.takeIf { it > 0L },
    contentType = contentType,
    archiveStartMs = archiveStartMs,
    archiveEndMs = archiveEndMs
)

internal fun hasArchivePlaybackIdentity(
    contentType: String,
    archiveStartMs: Long?,
    archiveEndMs: Long?
): Boolean = archiveStartMs != null &&
    archiveEndMs != null &&
    archiveStartMs > 0L &&
    archiveEndMs > archiveStartMs &&
    try {
        ContentType.valueOf(contentType)
    } catch (_: Exception) {
        ContentType.LIVE
    } == ContentType.LIVE

internal fun shouldResolveChannelPlaybackContext(
    contentType: String,
    internalContentId: Long
): Boolean = internalContentId > 0L &&
    try {
        ContentType.valueOf(contentType)
    } catch (_: Exception) {
        ContentType.LIVE
    } == ContentType.LIVE

internal fun resolveRouteDisplayTitle(
    title: String,
    contentType: String,
    archiveStartMs: Long?,
    archiveEndMs: Long?,
    archiveTitle: String?
): String = if (
    hasArchivePlaybackIdentity(
        contentType = contentType,
        archiveStartMs = archiveStartMs,
        archiveEndMs = archiveEndMs
    )
) {
    archiveTitle?.takeIf { it.isNotBlank() } ?: title
} else {
    title
}

enum class PlayerRecoveryType {
    NETWORK,
    SOURCE,
    DECODER,
    DRM,
    CATCH_UP,
    BUFFER_TIMEOUT,
    UNKNOWN
}

enum class PlayerNoticeAction {
    RETRY,
    LAST_CHANNEL,
    ALTERNATE_STREAM,
    OPEN_GUIDE
}

sealed interface PlaybackResolutionUiState {
    data object Idle : PlaybackResolutionUiState
    data object Resolving : PlaybackResolutionUiState
    data class Failure(val message: String) : PlaybackResolutionUiState
}

enum class AspectRatio(val modeName: String) {
    FIT("Fit"),
    FILL("Stretch"),
    ZOOM("Zoom")
}

internal data class EpgRequestKey(
    val providerId: Long,
    val internalChannelId: Long,
    val epgChannelId: String?,
    val streamId: Long
)

internal data class AudioVideoOffsetSnapshot(
    val globalOffsetMs: Int,
    val channelOverrideMs: Int?,
    val previewOffsetMs: Int?,
    val effectiveOffsetMs: Int,
    val engine: com.streamvault.player.PlayerEngine,
    val enabled: Boolean
)

internal data class PlayerUiTimeouts(
    val controlsMs: Long,
    val liveOverlayMs: Long,
    val noticeMs: Long,
    val diagnosticsMs: Long
)

internal fun resolvePreferredAudioLanguage(preferredAudioLanguage: String?, appLanguage: String): String? {
    val normalizedPreference = preferredAudioLanguage
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
    val effectiveTag = normalizedPreference ?: appLanguage.takeIf { it.isNotBlank() && it != "system" }
        ?: Locale.getDefault().toLanguageTag()
    return effectiveTag
        .takeIf { it.isNotBlank() }
        ?.let { Locale.forLanguageTag(it) }
        ?.takeIf { it.language.isNotBlank() }
        ?.toLanguageTag()
}

internal fun Series.sanitizedForPlayer(): Series = copy(
    seasons = seasons.sanitizedForPlayer()
)

internal fun List<Season>?.sanitizedForPlayer(): List<Season> = this.orEmpty().map { season ->
    val sanitizedEpisodes = season.episodes.orEmpty()
    season.copy(
        episodes = sanitizedEpisodes,
        episodeCount = season.episodeCount.takeIf { it > 0 } ?: sanitizedEpisodes.size
    )
}

internal fun Channel.sanitizedForPlayer(): Channel {
    val sanitizedQualityOptions = qualityOptions.orEmpty()
    val sanitizedVariants = variants.orEmpty()
        .filter { it.streamUrl.isNotBlank() }
        .distinctBy { it.rawChannelId }
    val sanitizedAlternativeStreams = alternativeStreams.orEmpty()
        .filter { it.isNotBlank() }
        .distinct()
        .ifEmpty {
            sanitizedVariants
                .map { it.streamUrl }
                .filter { it != streamUrl }
                .distinct()
        }
    return copy(
        qualityOptions = sanitizedQualityOptions,
        alternativeStreams = sanitizedAlternativeStreams,
        variants = sanitizedVariants
    )
}

internal fun List<Channel>?.sanitizedChannelsForPlayer(): List<Channel> =
    this.orEmpty().map(Channel::sanitizedForPlayer)
