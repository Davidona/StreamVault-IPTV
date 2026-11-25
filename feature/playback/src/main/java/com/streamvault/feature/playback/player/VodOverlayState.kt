package com.streamvault.feature.playback.player

import com.streamvault.player.PlayerChapter
import java.util.Locale

internal data class VodOverlayState(
    val isVod: Boolean,
    val chapters: List<PlayerChapter>,
    val currentChapter: PlayerChapter?,
    val showChapterAction: Boolean,
    val showChapterSkipActions: Boolean,
    val showPreviousEpisodeAction: Boolean,
    val showNextEpisodeAction: Boolean,
    val showEpisodesAction: Boolean,
    val showSubtitleAction: Boolean,
    val showAudioAction: Boolean,
    val showVideoQualityAction: Boolean,
    val showAudioVideoSyncAction: Boolean,
    val showExternalPlayerAction: Boolean,
    val showSettingsAction: Boolean
)

internal fun buildVodOverlayState(
    contentType: String,
    isCatchUpPlayback: Boolean,
    chapters: List<PlayerChapter>,
    currentPositionMs: Long,
    showEpisodesAction: Boolean,
    hasPreviousEpisode: Boolean = false,
    hasNextEpisode: Boolean = false,
    subtitleTrackCount: Int,
    showStremioSubtitleAction: Boolean = false,
    audioTrackCount: Int,
    videoQualityCount: Int,
    audioVideoSyncEnabled: Boolean = false,
    onlineSubtitleSearchAvailable: Boolean = false,
    localSubtitleLoadingAvailable: Boolean = true,
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
        showChapterAction = isVod && visibleChapters.isNotEmpty() && !isCastConnected,
        showChapterSkipActions = isVod && visibleChapters.size > 1 && !isCastConnected,
        showPreviousEpisodeAction = isVod && normalizedContentType == "SERIES_EPISODE" && hasPreviousEpisode,
        showNextEpisodeAction = isVod && normalizedContentType == "SERIES_EPISODE" && hasNextEpisode,
        showEpisodesAction = isVod && normalizedContentType == "SERIES_EPISODE" && showEpisodesAction,
        showSubtitleAction = isVod &&
            (subtitleTrackCount > 0 || showStremioSubtitleAction || onlineSubtitleSearchAvailable || localSubtitleLoadingAvailable),
        showAudioAction = isVod && audioTrackCount > 0,
        showVideoQualityAction = isVod && videoQualityCount > 0,
        showAudioVideoSyncAction = isVod && audioVideoSyncEnabled && !isCastConnected,
        showExternalPlayerAction = isVod && showExternalPlayerAction,
        showSettingsAction = isVod
    )
}

private val VOD_CONTENT_TYPES = setOf("VOD", "MOVIE", "SERIES_EPISODE")
