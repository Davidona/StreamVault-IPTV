package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Series
import com.streamvault.domain.playback.isArchivePlayable
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.PlayerPreloadContentType
import com.streamvault.player.PlayerPreloadItem
import com.streamvault.player.PlayerPreloadWindow

internal fun buildEpisodePreloadNeighbors(
    series: Series,
    currentEpisode: Episode
): List<Episode>? {
    val orderedEpisodes = orderedEpisodesForPlayer(series)
    val currentIndex = orderedEpisodes.indexOfFirst {
        it.id == currentEpisode.id ||
            it.playbackEpisodeIdentity() == currentEpisode.playbackEpisodeIdentity() ||
            (it.seasonNumber == currentEpisode.seasonNumber &&
                it.episodeNumber == currentEpisode.episodeNumber)
    }
    if (currentIndex < 0) return null

    return buildList {
        orderedEpisodes.getOrNull(currentIndex - 1)?.let(::add)
        add(orderedEpisodes[currentIndex])
        orderedEpisodes.getOrNull(currentIndex + 1)?.let(::add)
    }
}

internal fun buildCatchUpPreloadNeighbors(
    selectedProgram: Program,
    channel: Channel,
    timelinePrograms: List<Program>,
    now: Long
): List<Program>? {
    val sortedPrograms = timelinePrograms.sortedBy { it.startTime }
    val selectedIndex = sortedPrograms.indexOfFirst { it.samePlaybackProgram(selectedProgram) }
    if (selectedIndex < 0) return null

    return buildList {
        sortedPrograms
            .subList(0, selectedIndex)
            .asReversed()
            .firstOrNull { channel.isArchivePlayable(it, now) }
            ?.let(::add)
        add(sortedPrograms[selectedIndex])
        sortedPrograms
            .drop(selectedIndex + 1)
            .firstOrNull { channel.isArchivePlayable(it, now) }
            ?.let(::add)
    }
}

internal fun buildCatchUpPreloadTimeline(
    programHistory: List<Program>,
    currentProgram: Program?,
    upcomingPrograms: List<Program>,
    selectedProgram: Program
): List<Program> {
    val candidates = buildList {
        addAll(programHistory)
        currentProgram?.let(::add)
        addAll(upcomingPrograms)
        add(selectedProgram)
    }.filter { it.channelId == selectedProgram.channelId }

    return candidates
        .sortedBy { it.startTime }
        .fold(mutableListOf()) { unique, program ->
            if (unique.none { it.samePlaybackProgram(program) }) {
                unique += program
            }
            unique
        }
}

internal fun episodePreloadKey(providerId: Long, episode: Episode): String =
    "episode:$providerId:${episode.playbackEpisodeIdentity()}"

internal fun catchUpPreloadKey(providerId: Long, channel: Channel, program: Program): String =
    "catchup:$providerId:${channel.id}:${program.startTime}:${program.endTime}"

internal fun buildPreloadWindowRefreshFingerprint(
    contentType: PlayerPreloadContentType,
    providerId: Long,
    currentKey: String,
    neighborKeys: List<String>,
    currentStreamInfo: StreamInfo
): String = listOf(
    contentType.name,
    providerId,
    currentKey,
    neighborKeys.joinToString(","),
    currentStreamInfo.hashCode()
).joinToString("|")

internal fun buildEpisodePreloadWindow(
    current: PlayerPreloadItem,
    series: Series,
    currentEpisode: Episode,
    providerId: Long,
    resolvedNeighbors: Map<Long, PlayerPreloadItem>
): PlayerPreloadWindow? {
    val neighbors = buildEpisodePreloadNeighbors(series, currentEpisode) ?: return null
    val items = neighbors.map { episode ->
        if (episode.playbackEpisodeIdentity() == currentEpisode.playbackEpisodeIdentity()) {
            current.copy(
                key = episodePreloadKey(providerId, episode),
                contentType = PlayerPreloadContentType.VOD
            )
        } else {
            resolvedNeighbors[episode.playbackEpisodeIdentity()] ?: return null
        }
    }
    return PlayerPreloadWindow(
        items = items,
        currentIndex = items.indexOfFirst { it.key == episodePreloadKey(providerId, currentEpisode) }
    )
}

private fun Program.samePlaybackProgram(other: Program): Boolean =
    (id > 0L && other.id > 0L && id == other.id) ||
        (channelId == other.channelId && startTime == other.startTime && endTime == other.endTime)
