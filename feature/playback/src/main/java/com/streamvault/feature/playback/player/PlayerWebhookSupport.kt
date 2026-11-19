package com.streamvault.feature.playback.player

import android.os.SystemClock
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.playback.events.*
import com.streamvault.player.PlaybackState
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Cached metadata only; one database lookup per movie preparation, never network lookup. */
internal class PlayerWebhookState {
    val owner = UUID.randomUUID().toString()
    var key: String? = null
    var generation = 0L
    var movieVersion: Long? = null
    var movieIds = PlaybackExternalIds()
    var metadataReadAtMs = 0L
    var latest: PlaybackObservation? = null
    var discontinuity = false
    var userPaused = false
    var pauseSinceMs: Long? = null
    var handedOff = false
}

internal suspend fun PlayerViewModel.reportWebhookPlayback(state: PlaybackState = playerEngine.playbackState.value) {
    if (!playbackWebhooks.configuration.value.enabled) return
    if (webhookState.handedOff) return
    val stremio = currentStremioPlaybackReference
    if (isCatchUpPlayback() || currentContentId <= 0 && stremio == null ||
        currentContentType !in setOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES_EPISODE)) {
        finishWebhookPlayback()
        return
    }
    // Preparation can still contain old position/metadata. Do not start the next item with them.
    if (readySideEffectsRequestVersion != prepareRequestVersion) return
    val version = prepareRequestVersion
    val snapshot = webhookState
    if (currentContentType == ContentType.MOVIE && currentContentId > 0 && (snapshot.movieVersion != version ||
            snapshot.movieIds.tmdb == null && SystemClock.elapsedRealtime() - snapshot.metadataReadAtMs >= 30_000)) {
        val ids = try {
            playerContentResolver.getMovie(currentContentId)?.takeIf { it.providerId == currentProviderId }
                ?.tmdbId?.takeIf { it > 0 }?.let { PlaybackExternalIds(tmdb = it) } ?: PlaybackExternalIds()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            PlaybackExternalIds()
        }
        if (version != prepareRequestVersion) return
        // A late lookup from the previous route must not overwrite the new session.
        // Retain known identity if a physical variant lacks the same metadata.
        if (ids.tmdb != null) snapshot.movieIds = ids
        snapshot.movieVersion = version
        snapshot.metadataReadAtMs = SystemClock.elapsedRealtime()
    }
    val key = "$currentProviderId:$currentContentType:$currentContentId"
    // A logical viewing session survives physical variant/engine replacements. Explicit
    // prepare and zap actions finish it before changing the route.
    if (snapshot.key == null) {
        snapshot.key = key
        snapshot.generation++
        snapshot.discontinuity = true
        snapshot.pauseSinceMs = null
    }
    val media = when (currentContentType) {
        ContentType.MOVIE -> PlaybackMedia(PlaybackMediaType.MOVIE, currentTitle.take(300), ids =
            snapshot.movieIds.copy(imdb = webhookImdbId(stremio?.exactVideoId)))
        ContentType.SERIES_EPISODE -> {
            val episode = currentEpisode.value?.takeIf { it.id == currentContentId && it.providerId == currentProviderId }
            val series = currentSeries.value?.takeIf { it.id == currentSeriesId && it.providerId == currentProviderId }
            val consistent = episode != null && series != null && episode.seriesId == series.id && episode.providerId == series.providerId
            val parts = stremio?.exactVideoId?.split(':').orEmpty()
            PlaybackMedia(PlaybackMediaType.EPISODE, (episode?.title ?: currentTitle).take(300),
                (series?.name ?: stremio?.itemTitle)?.take(300),
                (episode?.seasonNumber ?: currentSeasonNumber ?: parts.getOrNull(1)?.toIntOrNull())?.takeIf { it >= 0 },
                (episode?.episodeNumber ?: currentEpisodeNumber ?: parts.getOrNull(2)?.toIntOrNull())?.takeIf { it > 0 },
                seriesIds = PlaybackExternalIds(tmdb = series?.tmdbId?.takeIf { consistent && it > 0 },
                    imdb = webhookImdbId(stremio?.parentSeriesId ?: parts.firstOrNull())))
        }
        else -> PlaybackMedia(PlaybackMediaType.LIVE, currentTitle.take(300))
    }
    val now = SystemClock.elapsedRealtime()
    var phase = webhookPlaybackPhase(state, playerEngine.isPlaying.value)
    if (phase == PlaybackEventPhase.PAUSED && !snapshot.userPaused) {
        val since = snapshot.pauseSinceMs ?: now.also { snapshot.pauseSinceMs = it }
        if (now - since < 300) phase = PlaybackEventPhase.BUFFERING
    } else snapshot.pauseSinceMs = null
    val observation = PlaybackObservation(snapshot.generation, media, phase,
        playerEngine.currentPosition.value, playerEngine.duration.value, SystemClock.elapsedRealtime(), Instant.now(),
        snapshot.discontinuity)
    snapshot.discontinuity = false
    snapshot.latest = observation
    playbackWebhooks.report(snapshot.owner, observation)
}

internal fun webhookPlaybackPhase(state: PlaybackState, isPlaying: Boolean): PlaybackEventPhase = when (state) {
    PlaybackState.READY -> if (isPlaying) PlaybackEventPhase.PLAYING else PlaybackEventPhase.PAUSED
    PlaybackState.ENDED -> PlaybackEventPhase.ENDED
    // Recovery is not a user pause or a final failure.
    else -> PlaybackEventPhase.BUFFERING
}

internal fun PlayerViewModel.finishWebhookPlayback(failed: Boolean = false) {
    webhookState.latest?.let {
        playbackWebhooks.report(webhookState.owner, it.copy(phase = if (failed) PlaybackEventPhase.FAILED else PlaybackEventPhase.STOPPED,
            positionMs = playerEngine.currentPosition.value, durationMs = playerEngine.duration.value,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(), timestamp = Instant.now(),
            discontinuity = webhookState.discontinuity))
    }
    playbackWebhooks.finish(webhookState.owner)
    webhookState.latest = null
    webhookState.key = null
    webhookState.userPaused = false
    webhookState.pauseSinceMs = null
    webhookState.handedOff = false
    webhookState.movieVersion = null
    webhookState.movieIds = PlaybackExternalIds()
    webhookState.metadataReadAtMs = 0
}

internal fun webhookImdbId(value: String?): String? = value?.takeIf { it.matches(Regex("tt[0-9]+")) }
