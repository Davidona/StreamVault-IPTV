package com.streamvault.feature.playback.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val AUTO_PLAY_MIN_WATCHED_MS = 5_000L
private const val AUTO_PLAY_COUNTDOWN_SECONDS = 10

internal suspend fun PlayerViewModel.persistPlaybackCompletion() {
    val durationMs = playerEngine.duration.value
    val completedHistory = buildPlaybackHistorySnapshot(
        positionMs = durationMs.coerceAtLeast(playerEngine.currentPosition.value),
        durationMs = durationMs
    ) ?: return
    val result = playbackHistoryCoordinator.markAsWatched(completedHistory)
    logRepositoryFailure(
        operation = "Mark playback watched",
        result = result
    )
    if (result.isSuccess) {
        playbackHistoryCoordinator.refreshPlaybackSurfaces()
    }
}

internal fun PlayerViewModel.handlePlaybackEnded() {
    if (currentContentType == ContentType.LIVE) return
    val requestVersion = prepareRequestVersion
    playbackSessionScope(requestVersion)?.launch {
        persistPlaybackCompletion()
        if (currentContentType == ContentType.SERIES_EPISODE) {
            val position = playerEngine.currentPosition.value
            val duration = playerEngine.duration.value
            if (position > AUTO_PLAY_MIN_WATCHED_MS || duration > 0L) {
                val next = nextEpisode.value ?: return@launch
                if (autoPlayNextEpisodeEnabled && !creditsAutoPlayTriggeredForSession) {
                    startAutoPlayCountdown(next)
                }
            }
        }
    }
}

internal fun PlayerViewModel.handleCreditsChapterObservation(
    observation: PlayerChapterObservation
) {
    if (readySideEffectsRequestVersion != prepareRequestVersion) {
        return
    }
    val currentChapter = findCreditsChapter(
        chapters = observation.chapters,
        positionMs = observation.positionMs,
        durationMs = observation.durationMs
    )
    if (!shouldStartCreditsAutoPlay(
            currentChapter = currentChapter,
            lastTriggeredChapterStartMs = lastTriggeredCreditsChapterStartMs,
            hasNextEpisode = observation.nextEpisode != null,
            autoPlayEnabled = observation.autoPlayEnabled,
            contentType = currentContentType
        )
    ) {
        return
    }

    val nextEpisode = observation.nextEpisode ?: return
    lastTriggeredCreditsChapterStartMs = currentChapter?.startTimeMs
    creditsAutoPlayTriggeredForSession = true
    playbackSessionScope()?.launch {
        persistPlaybackCompletion()
    }
    startAutoPlayCountdown(nextEpisode)
}

internal fun PlayerViewModel.startAutoPlayCountdown(episode: Episode) {
    autoPlayCountdownJob?.cancel()
    autoPlayCountdownJob = playbackSessionScope()?.launch {
        for (remaining in AUTO_PLAY_COUNTDOWN_SECONDS downTo 1) {
            _autoPlayCountdown.value = AutoPlayCountdownUiState(
                episode = episode,
                secondsRemaining = remaining
            )
            delay(1_000L)
        }
        _autoPlayCountdown.value = null
        playEpisode(episode, showResumePrompt = false)
    }
}

fun PlayerViewModel.cancelAutoPlay() {
    autoPlayCountdownJob?.cancel()
    autoPlayCountdownJob = null
    _autoPlayCountdown.value = null
}

fun PlayerViewModel.playNextEpisodeNow() {
    val episode = autoPlayCountdown.value?.episode ?: nextEpisode.value ?: return
    cancelAutoPlay()
    playEpisode(episode, showResumePrompt = false)
}
