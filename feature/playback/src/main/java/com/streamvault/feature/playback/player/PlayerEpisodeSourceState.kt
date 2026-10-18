package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Series

internal data class EpisodeSourceToken(val requestVersion: Long, val generation: Long)
internal data class PhysicalEpisodeSource(val token: EpisodeSourceToken, val series: Series, val episode: Episode)

internal class PlayerEpisodeSourceState {
    var token = EpisodeSourceToken(0, 0)
        private set
    var attempted = false
        private set
    private var physical: PhysicalEpisodeSource? = null
    private var captured: Pair<Long, Long>? = null
    private var awaitingResume = false
    var failed = false
        private set
    val recoveryPending: Boolean get() = awaitingResume
    var manualVideoRevision = 0L
        private set
    fun onVideoSelection() { manualVideoRevision++ }
    fun retryPhysical(positionMs: Long, durationMs: Long): EpisodeSourceToken? {
        val previous = physical ?: return null
        val progress = if (awaitingResume) captured else positionMs to durationMs
        if (!attempted || progress == null || progress.first < 0 || progress.second <= 0) return null
        token = token.copy(generation = token.generation + 1)
        physical = previous.copy(token = token)
        captured = progress
        awaitingResume = true
        failed = false
        return token
    }

    fun reset(requestVersion: Long): EpisodeSourceToken {
        token = EpisodeSourceToken(requestVersion, token.generation + 1)
        attempted = false
        physical = null
        captured = null
        awaitingResume = false
        failed = false
        return token
    }
    fun isCurrent(token: EpisodeSourceToken) = token == this.token
    fun reserveFallback(token: EpisodeSourceToken, positionMs: Long, durationMs: Long): EpisodeSourceToken? {
        if (!isCurrent(token) || attempted || positionMs < 0 || durationMs <= 0) return null
        this.token = token.copy(generation = token.generation + 1)
        attempted = true
        captured = positionMs to durationMs
        awaitingResume = true
        return this.token
    }
    fun stage(token: EpisodeSourceToken, series: Series, episode: Episode): Boolean {
        if (!isCurrent(token) || !attempted) return false
        physical = PhysicalEpisodeSource(token, series, episode)
        return true
    }
    fun confirmResume(token: EpisodeSourceToken, positionMs: Long): Boolean {
        if (!isCurrent(token) || physical == null || !awaitingResume || failed) return false
        val position = captured?.first ?: return false
        if (positionMs < position - 2000L || positionMs > position + 2000L) return false
        awaitingResume = false
        return true
    }
    fun markFailed(token: EpisodeSourceToken) { if (isCurrent(token)) failed = true }
    fun source(token: EpisodeSourceToken): PhysicalEpisodeSource? = physical.takeIf { isCurrent(token) }
    fun progressSnapshot(token: EpisodeSourceToken, positionMs: Long, durationMs: Long): Pair<Long, Long>? =
        if (!isCurrent(token)) null else if (awaitingResume) captured else positionMs to durationMs
}

internal fun episodeRecoveryIdentity(logicalUrl: String, failingResolvedUrl: String): String =
    logicalUrl.takeIf { it.isNotBlank() } ?: failingResolvedUrl

internal fun isEpisodePreparationCurrent(state: PlayerEpisodeSourceState, token: EpisodeSourceToken,
    sessionActive: Boolean, manualVideo: Boolean, videoRevision: Long, requireAuto: Boolean): Boolean =
    sessionActive && state.isCurrent(token) && !state.failed &&
        (!requireAuto || (!manualVideo && videoRevision == state.manualVideoRevision))
