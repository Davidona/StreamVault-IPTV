package com.streamvault.feature.playback.player

import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.Result
import com.streamvault.player.playback.ResolvedStreamType
import com.streamvault.player.playback.StreamTypeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class EpisodeFallbackResult { NotEligible, Recovered, HandledFailure }

internal fun PlayerViewModel.retryPhysicalEpisode() {
    val token = episodeSourceState.retryPhysical(playerEngine.currentPosition.value, playerEngine.duration.value) ?: return
    val physical = episodeSourceState.source(token) ?: return
    val identity = currentStreamUrl
    val position = episodeSourceState.progressSnapshot(token, 0, 0)?.first ?: return
    fun current() = episodeSourceState.isCurrent(token) && !episodeSourceState.failed &&
        isActivePlaybackSession(token.requestVersion, identity)
    clearPreloadWindow()
    playerEngine.stop()
    playbackSessionScope(token.requestVersion)?.launch {
        try {
            val resolved = playerContentResolver.resolvePhysicalEpisodeStream(physical, currentTitle)
            if (!current()) return@launch
            val info = resolved.streamInfo
            if (info == null) {
                episodeSourceState.markFailed(token)
                val message = resolved.failureMessage ?: "Could not resolve the compatible episode."
                setLastFailureReason(message)
                showPlayerNotice(message, PlayerRecoveryType.SOURCE, buildRecoveryActions(PlayerRecoveryType.SOURCE))
                return@launch
            }
            if (!preparePlayer(info, token.requestVersion, preserveVodTrackPreferences = true,
                    episodeToken = token, requiredSourceType = ResolvedStreamType.PROGRESSIVE)) {
                if (current()) episodeSourceState.markFailed(token)
                return@launch
            }
            if (!current()) return@launch
            playerEngine.seekTo(position)
            playerEngine.play()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (current()) {
                episodeSourceState.markFailed(token)
                val message = "Could not prepare the compatible episode."
                setLastFailureReason(message)
                showPlayerNotice(message, PlayerRecoveryType.SOURCE, buildRecoveryActions(PlayerRecoveryType.SOURCE))
            }
        }
    }
}

internal interface EpisodeFallbackPort {
    val state: PlayerEpisodeSourceState
    val source: EpisodeFallbackSource
    val manualVideo: Boolean
    fun isCurrent(token: EpisodeSourceToken): Boolean
    fun stopFailedSource()
    suspend fun prepare(info: StreamInfo, token: EpisodeSourceToken): Boolean
    fun seekAndPlay(positionMs: Long)
    fun failure(message: String)
    fun recovered()
}

internal suspend fun runEpisodeFallback(resolver: PlayerContentResolver, port: EpisodeFallbackPort): EpisodeFallbackResult {
    val initial = port.state.token
    if (port.state.attempted || port.manualVideo || !port.isCurrent(initial)) return EpisodeFallbackResult.NotEligible
    var reserved: EpisodeSourceToken? = null
    fun current(token: EpisodeSourceToken) = port.isCurrent(token) && !port.state.failed && !port.manualVideo
    fun fail(message: String): EpisodeFallbackResult {
        reserved?.takeIf(port::isCurrent)?.let { port.state.markFailed(it); port.failure(message) }
        return EpisodeFallbackResult.HandledFailure
    }
    try {
        val logical = port.source
        if (logical.streamInfo.playbackContentKind != PlaybackContentKind.ON_DEMAND ||
            StreamTypeResolver.resolve(logical.streamInfo) != ResolvedStreamType.PROGRESSIVE) return EpisodeFallbackResult.NotEligible
        if (resolver.hasExplicitSeriesVariantSelection(logical.series.id) || !current(initial)) return EpisodeFallbackResult.NotEligible
        val rawOriginal = resolver.getRawSeries(logical.series.id) ?: return EpisodeFallbackResult.NotEligible
        if (!current(initial) || rawOriginal.providerId != logical.series.providerId) return EpisodeFallbackResult.NotEligible
        val variants = resolver.getSeriesVariants(rawOriginal.id)
        if (!current(initial) || variants.none { it.rawSeriesId == rawOriginal.id }) return EpisodeFallbackResult.NotEligible
        val raw = mutableListOf(rawOriginal)
        for (variant in variants.distinctBy { it.rawSeriesId }) {
            if (variant.rawSeriesId == rawOriginal.id) continue
            val member = resolver.getRawSeries(variant.rawSeriesId)
            if (!current(initial)) return EpisodeFallbackResult.NotEligible
            if (member?.providerId == rawOriginal.providerId) raw += member
        }
        val source = logical.copy(series = rawOriginal.copy(seasons = logical.series.seasons))
        val selected = selectEpisodeFallbackSeries(source, raw) ?: return EpisodeFallbackResult.NotEligible
        if (!current(initial)) return EpisodeFallbackResult.NotEligible
        val token = port.state.reserveFallback(initial, source.positionMs, source.durationMs) ?: return EpisodeFallbackResult.NotEligible
        reserved = token
        port.stopFailedSource()
        if (!current(token)) return fail("Automatic episode recovery was canceled.")
        val detailsResult = resolver.getSeriesDetails(selected.providerId, selected.id)
        if (!current(token)) return fail("Automatic episode recovery was canceled.")
        val details = detailsResult.getOrNull()
            ?: return fail((detailsResult as? Result.Error)?.message ?: "Could not load the compatible episode.")
        if (details.id != selected.id || details.providerId != selected.providerId) return fail("Could not verify the compatible episode.")
        val episode = verifyEpisodeFallback(source, details) ?: return fail("Could not verify the compatible episode.")
        if (resolver.hasExplicitSeriesVariantSelection(rawOriginal.id) || !current(token)) return fail("Automatic episode recovery was canceled.")
        val streamResult = resolver.getEpisodeStreamInfo(episode)
        if (!current(token)) return fail("Automatic episode recovery was canceled.")
        val stream = streamResult.getOrNull()
            ?: return fail((streamResult as? Result.Error)?.message ?: "Could not resolve the compatible episode.")
        val resolved = stream.copy(title = source.streamInfo.title, playbackContentKind = PlaybackContentKind.ON_DEMAND)
        if (StreamTypeResolver.resolve(resolved) != ResolvedStreamType.PROGRESSIVE) return fail("The compatible episode is not a progressive source.")
        if (!port.state.stage(token, details, episode)) return EpisodeFallbackResult.HandledFailure
        if (!port.prepare(resolved, token)) {
            if (port.isCurrent(token)) port.state.markFailed(token)
            return EpisodeFallbackResult.HandledFailure // Preparation owns its specific failure notice.
        }
        if (!current(token)) return fail("Automatic episode recovery was canceled.")
        port.seekAndPlay(source.positionMs)
        port.recovered()
        return EpisodeFallbackResult.Recovered
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        return if (reserved != null) fail("Could not prepare the compatible episode.") else EpisodeFallbackResult.NotEligible
    }
}

internal suspend fun PlayerViewModel.tryFallbackToAvcEpisodeVariant(
    requestVersion: Long,
    playbackUrl: String
): EpisodeFallbackResult {
    if (currentContentType != ContentType.SERIES_EPISODE || !isActivePlaybackSession(requestVersion, playbackUrl)) return EpisodeFallbackResult.NotEligible
    val logicalIdentity = episodeRecoveryIdentity(currentStreamUrl, playbackUrl)
    val series = currentSeries.value ?: return EpisodeFallbackResult.NotEligible
    val episode = currentEpisode.value ?: return EpisodeFallbackResult.NotEligible
    if (series.providerId != currentProviderId || episode.providerId != currentProviderId) return EpisodeFallbackResult.NotEligible
    val stream = currentResolvedStreamInfo ?: return EpisodeFallbackResult.NotEligible
    val stats = playerEngine.playerStats.value
    val source = EpisodeFallbackSource(series, episode, stream, stats.videoCodec,
        playerEngine.videoFormat.value.height.takeIf { it > 0 }, playerEngine.currentPosition.value,
        playerEngine.duration.value.takeIf { it > 0 } ?: episode.durationSeconds.toLong() * 1000)
    return runEpisodeFallback(playerContentResolver, object : EpisodeFallbackPort {
        override val state = episodeSourceState
        override val source = source
        override val manualVideo: Boolean get() = hasManualVideoTrackChoice(playerEngine.availableVideoTracks.value)
        override fun isCurrent(token: EpisodeSourceToken) =
            state.isCurrent(token) && !state.failed && isActivePlaybackSession(requestVersion, logicalIdentity)
        override fun stopFailedSource() { clearPreloadWindow(); playerEngine.stop() }
        override suspend fun prepare(info: StreamInfo, token: EpisodeSourceToken) = preparePlayer(
            info.copy(title = currentTitle), requestVersion, preserveVodTrackPreferences = true,
            episodeToken = token, requiredSourceType = ResolvedStreamType.PROGRESSIVE, requireAutoVideoSelection = true)
        override fun seekAndPlay(positionMs: Long) { playerEngine.seekTo(positionMs); playerEngine.play() }
        override fun failure(message: String) {
            setLastFailureReason(message)
            showPlayerNotice(message, PlayerRecoveryType.SOURCE, buildRecoveryActions(PlayerRecoveryType.SOURCE))
        }
        override fun recovered() {
            appendRecoveryAction("Switched to compatible AVC/H.264 episode variant")
            showPlayerNotice("Retrying with a compatible AVC/H.264 episode variant.", PlayerRecoveryType.DECODER,
                buildRecoveryActions(PlayerRecoveryType.DECODER))
        }
    })
}
