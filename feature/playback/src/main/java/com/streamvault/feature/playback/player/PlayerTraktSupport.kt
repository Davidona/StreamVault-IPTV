package com.streamvault.feature.playback.player

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.trakt.TraktMediaIdentityResolver
import com.streamvault.domain.trakt.TraktPlaybackPhase
import com.streamvault.player.PlaybackState
import kotlinx.coroutines.CancellationException

internal suspend fun PlayerViewModel.reportTraktPlayback(state: PlaybackState = playerEngine.playbackState.value) {
    val version = prepareRequestVersion
    val identity = if (readySideEffectsRequestVersion != version) null else when (currentContentType) {
        ContentType.MOVIE -> {
            if (traktMovieSession != version) {
                val movie = try {
                    playerContentResolver.getMovie(currentContentId)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    null // Tracking metadata failure must not interrupt playback or auto-play.
                }
                if (version != prepareRequestVersion) return
                traktMovieIdentity = movie?.takeIf { it.providerId == currentProviderId }
                    ?.let(TraktMediaIdentityResolver::movie)
                traktMovieSession = version
            }
            traktMovieIdentity
        }
        ContentType.SERIES_EPISODE -> currentSeries.value?.let { series ->
            currentEpisode.value?.let { episode -> TraktMediaIdentityResolver.episode(series, episode) }
        }
        else -> null // Live, catch-up, recordings, trailers, and unidentified external URLs are excluded.
    }
    if (version != prepareRequestVersion) return
    playerTraktCoordinator.report(version, identity, traktPlaybackPhase(state, playerEngine.isPlaying.value),
        playerEngine.currentPosition.value, playerEngine.duration.value,
        eligibility = when {
            isCatchUpPlayback() -> TraktPlaybackEligibility.CATCH_UP
            currentContentType == ContentType.LIVE -> TraktPlaybackEligibility.LIVE
            currentContentType == ContentType.MOVIE || currentContentType == ContentType.SERIES_EPISODE ->
                TraktPlaybackEligibility.SUPPORTED
            else -> TraktPlaybackEligibility.OTHER
        })
}

internal fun traktPlaybackPhase(state: PlaybackState, isPlaying: Boolean): TraktPlaybackPhase =
    when (state) {
        PlaybackState.READY -> if (isPlaying) TraktPlaybackPhase.PLAYING else TraktPlaybackPhase.PAUSED
        PlaybackState.BUFFERING -> TraktPlaybackPhase.BUFFERING
        PlaybackState.ENDED -> TraktPlaybackPhase.ENDED
        // Engine replacement/recovery can temporarily enter IDLE/ERROR within the same session.
        // Final stop is owned by session replacement or ViewModel cleanup.
        PlaybackState.IDLE -> TraktPlaybackPhase.BUFFERING
        PlaybackState.ERROR -> TraktPlaybackPhase.PAUSED
    }
