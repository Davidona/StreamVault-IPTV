package com.streamvault.feature.playback.player

import com.streamvault.player.PlayerChapter
import java.util.Locale

internal data class VodOverlayState(
    val isVod: Boolean,
    val chapters: List<PlayerChapter>,
    val currentChapter: PlayerChapter?,
    val previousChapterTargetMs: Long?,
    val nextChapterTargetMs: Long?,
    val showChapterAction: Boolean,
    val showEpisodesAction: Boolean,
    val showSubtitleAction: Boolean,
    val showAudioAction: Boolean,
    val showVideoQualityAction: Boolean,
    val showExternalPlayerAction: Boolean,
    val showSettingsAction: Boolean
)

internal fun buildVodOverlayState(
    contentType: String,
    isCatchUpPlayback: Boolean,
    chapters: List<PlayerChapter>,
    currentPositionMs: Long,
    showEpisodesAction: Boolean,
    subtitleTrackCount: Int,
    audioTrackCount: Int,
    videoQualityCount: Int,
    showExternalPlayerAction: Boolean,
    isCastConnected: Boolean
): VodOverlayState {
    val normalizedContentType = contentType.uppercase(Locale.ROOT)
    val isVod = !isCatchUpPlayback && normalizedContentType in VOD_CONTENT_TYPES
    val visibleChapters = if (isVod) {
        chapters.sortedBy(PlayerChapter::startTimeMs)
    } else {
        emptyList()
    }

    return VodOverlayState(
        isVod = isVod,
        chapters = visibleChapters,
        currentChapter = currentChapter(visibleChapters, currentPositionMs),
        previousChapterTargetMs = previousChapterTarget(visibleChapters, currentPositionMs),
        nextChapterTargetMs = nextChapterTarget(visibleChapters, currentPositionMs),
        showChapterAction = isVod && visibleChapters.isNotEmpty() && !isCastConnected,
        showEpisodesAction = isVod && normalizedContentType == "SERIES_EPISODE" && showEpisodesAction,
        showSubtitleAction = isVod && subtitleTrackCount > 0,
        showAudioAction = isVod && audioTrackCount > 0,
        showVideoQualityAction = isVod && videoQualityCount > 0,
        showExternalPlayerAction = isVod && showExternalPlayerAction,
        showSettingsAction = isVod
    )
}

private val VOD_CONTENT_TYPES = setOf("VOD", "MOVIE", "SERIES_EPISODE")
