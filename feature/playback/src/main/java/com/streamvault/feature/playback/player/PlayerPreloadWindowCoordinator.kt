package com.streamvault.feature.playback.player

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.player.PlayerPreloadContentType
import com.streamvault.player.PlayerPreloadItem
import com.streamvault.player.PlayerPreloadWindow
import android.util.Log
import javax.inject.Inject

/** Resolves only the bounded neighbors needed by the player preload window. */
class PlayerPreloadWindowCoordinator @Inject constructor(
    private val playerContentResolver: PlayerContentResolver,
    private val playerProviderCoordinator: PlayerProviderCoordinator,
    private val playerPreparationCoordinator: PlayerPreparationCoordinator
) {

    internal suspend fun buildEpisodeWindow(
        current: PlayerPreloadItem,
        series: Series,
        currentEpisode: Episode,
        providerId: Long,
        isCurrent: () -> Boolean
    ): PlayerPreloadWindow? {
        if (!isCurrent()) return null
        val neighbors = buildEpisodePreloadNeighbors(series, currentEpisode) ?: return null
        val items = mutableListOf<PlayerPreloadItem>()

        neighbors.forEach { episode ->
            if (!isCurrent()) return null
            val key = episodePreloadKey(providerId, episode)
            if (episodeMatches(episode, currentEpisode)) {
                items += current.copy(
                    key = key,
                    contentType = PlayerPreloadContentType.VOD
                )
                return@forEach
            }

            val logicalUrl = episode.streamUrl.trim()
            val resolution = playerContentResolver.resolvePlaybackStream(
                logicalUrl = logicalUrl,
                internalContentId = episode.id,
                providerId = providerId,
                contentType = ContentType.SERIES_EPISODE,
                currentTitle = buildEpisodePlaybackTitle(episode),
                currentSeries = series,
                currentEpisode = episode
            )
            val streamInfo = resolution.streamInfo ?: run {
                logCandidateFailure(key, PlayerPreloadContentType.VOD, "unresolved")
                return@forEach
            }
            if (!isCurrent()) return null
            val preparedStreamInfo = playerPreparationCoordinator
                .prepareStreamForPreload(streamInfo)
                ?: run {
                    logCandidateFailure(key, PlayerPreloadContentType.VOD, "preparation-failed")
                    return@forEach
                }
            if (!isCurrent()) return null
            items += PlayerPreloadItem(
                key = key,
                streamInfo = preparedStreamInfo,
                contentType = PlayerPreloadContentType.VOD
            )
        }

        val currentIndex = items.indexOfFirst { it.key == episodePreloadKey(providerId, currentEpisode) }
        if (currentIndex < 0) return null
        return PlayerPreloadWindow(items = items, currentIndex = currentIndex)
    }

    internal suspend fun buildCatchUpWindow(
        current: PlayerPreloadItem,
        selectedProgram: Program,
        channel: Channel,
        timelinePrograms: List<Program>,
        providerId: Long,
        isCurrent: () -> Boolean
    ): PlayerPreloadWindow? {
        if (!isCurrent()) return null
        val neighbors = buildCatchUpPreloadNeighbors(
            selectedProgram = selectedProgram,
            channel = channel,
            timelinePrograms = timelinePrograms,
            now = System.currentTimeMillis()
        ) ?: return null
        val items = mutableListOf<PlayerPreloadItem>()

        neighbors.forEach { program ->
            if (!isCurrent()) return null
            val key = catchUpPreloadKey(providerId, channel, program)
            if (program.samePlaybackProgramAs(selectedProgram)) {
                items += current.copy(
                    key = key,
                    contentType = PlayerPreloadContentType.CATCH_UP
                )
                return@forEach
            }
            if (channel.streamId <= 0L) {
                logCandidateFailure(key, PlayerPreloadContentType.CATCH_UP, "missing-stream-id")
                return@forEach
            }

            val urls = when (
                val result = playerProviderCoordinator.buildCatchUpUrls(
                    providerId = providerId,
                    streamId = channel.id,
                    start = program.startTime / 1_000L,
                    end = program.endTime / 1_000L
                )
            ) {
                is Result.Success -> result.data
                is Result.Error -> {
                    logCandidateFailure(key, PlayerPreloadContentType.CATCH_UP, "url-build-failed")
                    emptyList()
                }
                is Result.Loading -> {
                    logCandidateFailure(key, PlayerPreloadContentType.CATCH_UP, "url-build-loading")
                    emptyList()
                }
            }
            var resolvedCandidate = false
            for (url in urls) {
                if (!isCurrent()) return null
                val resolved = playerContentResolver.resolvePlaybackStream(
                    logicalUrl = url,
                    internalContentId = channel.id,
                    providerId = providerId,
                    contentType = ContentType.LIVE,
                    currentTitle = program.title,
                    currentSeries = null,
                    currentEpisode = null
                ).streamInfo ?: continue
                if (!isCurrent()) return null
                val prepared = playerPreparationCoordinator.prepareStreamForPreload(resolved) ?: continue
                if (!isCurrent()) return null
                items += PlayerPreloadItem(
                    key = key,
                    streamInfo = prepared,
                    contentType = PlayerPreloadContentType.CATCH_UP
                )
                resolvedCandidate = true
                break
            }
            if (!resolvedCandidate && urls.isNotEmpty()) {
                logCandidateFailure(key, PlayerPreloadContentType.CATCH_UP, "unresolved")
            }
        }

        val currentIndex = items.indexOfFirst {
            it.key == catchUpPreloadKey(providerId, channel, selectedProgram)
        }
        if (currentIndex < 0) return null
        return PlayerPreloadWindow(items = items, currentIndex = currentIndex)
    }

    private fun episodeMatches(left: Episode, right: Episode): Boolean =
        left.id == right.id ||
            left.playbackEpisodeIdentity() == right.playbackEpisodeIdentity() ||
            (left.seasonNumber == right.seasonNumber && left.episodeNumber == right.episodeNumber)
}

private fun logCandidateFailure(key: String, contentType: PlayerPreloadContentType, reason: String) {
    runCatching {
        Log.w(
            "PlayerPreloadWindow",
            "candidate failed key=${key.take(80)} kind=$contentType reason=$reason"
        )
    }
}

private fun Program.samePlaybackProgramAs(other: Program): Boolean =
    (id > 0L && other.id > 0L && id == other.id) ||
        (channelId == other.channelId && startTime == other.startTime && endTime == other.endTime)
