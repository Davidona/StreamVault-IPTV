package com.streamvault.app.ui.test

import com.streamvault.feature.playback.player.NumericChannelInputState
import com.streamvault.feature.playback.player.PlayerNoticeAction
import com.streamvault.feature.playback.player.PlayerNoticeState
import com.streamvault.feature.playback.player.PlayerRecoveryType
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.Series
import com.streamvault.player.PlayerTrack
import com.streamvault.player.TrackType

internal object TestFixtures {
    val currentProgram = Program(
        id = 42L,
        channelId = "7",
        title = "World Cup Qualifiers",
        description = "Late match coverage",
        startTime = 1_710_000_000_000L,
        endTime = 1_710_003_600_000L,
        hasArchive = true,
        isNowPlaying = true
    )

    val numericInputState = NumericChannelInputState(
        input = "105",
        matchedChannelName = "Cinema Select HD",
        invalid = false
    )

    val invalidNumericInputState = NumericChannelInputState(
        input = "999",
        matchedChannelName = null,
        invalid = true
    )

    val notice = PlayerNoticeState(
        message = "Playback recovered on the alternate stream.",
        recoveryType = PlayerRecoveryType.NETWORK,
        actions = listOf(PlayerNoticeAction.RETRY, PlayerNoticeAction.OPEN_GUIDE)
    )

    val audioTracks = listOf(
        PlayerTrack("audio-en", "English 5.1", "en", TrackType.AUDIO, true),
        PlayerTrack("audio-es", "Spanish Stereo", "es", TrackType.AUDIO, false)
    )

    val subtitleTracks = listOf(
        PlayerTrack("sub-en", "English CC", "en", TrackType.TEXT, true),
        PlayerTrack("sub-es", "Spanish", "es", TrackType.TEXT, false)
    )

    val liveCategories = listOf(
        Category(id = 1L, name = "All Channels", type = ContentType.LIVE, count = 4820),
        Category(id = 2L, name = "Sports", type = ContentType.LIVE, count = 326),
        Category(id = 3L, name = "News", type = ContentType.LIVE, count = 118)
    )

    val moviesCategory = Category(id = 11L, name = "Top Picks", type = ContentType.MOVIE, count = 240)
    val seriesCategory = Category(id = 21L, name = "Trending Series", type = ContentType.SERIES, count = 180)

    val liveChannel = Channel(
        id = 7L,
        name = liveChannelName,
        streamUrl = "https://example.com/live.m3u8",
        number = displayChannelNumber,
        isFavorite = true,
        catchUpSupported = true,
        categoryName = "Sports",
        currentProgram = currentProgram
    )

    val movie = Movie(
        id = 100L,
        name = vodTitle,
        posterUrl = null,
        backdropUrl = null,
        categoryId = moviesCategory.id,
        categoryName = moviesCategory.name,
        plot = "A prestige thriller staged for a premium TV browse surface.",
        genre = "Thriller",
        duration = "2h 05m",
        rating = 8.8f,
        year = "2026",
        isFavorite = true
    )

    val series = Series(
        id = 200L,
        name = "Signal Fires",
        posterUrl = null,
        backdropUrl = null,
        categoryId = seriesCategory.id,
        categoryName = seriesCategory.name,
        plot = "A serialized drama used for TV layout regression tests.",
        genre = "Drama",
        releaseDate = "2026",
        rating = 8.5f,
        isFavorite = true
    )

    val episode = Episode(
        id = 301L,
        title = "Episode One",
        episodeNumber = 1,
        seasonNumber = 1,
        duration = "48m",
        plot = "Pilot episode for the premium series detail state."
    )

    const val fixedClock = "21:47"
    const val vodTitle = "The Long Night"
    const val liveTitle = "World Sports HD"
    const val liveChannelName = "World Sports HD"
    const val resolutionLabel = "3840x2160"
    const val aspectRatioLabel = "Original"
    const val displayChannelNumber = 105
    const val currentPositionMs = 4_200_000L
    const val durationMs = 7_200_000L
    val recordingStatus = RecordingStatus.SCHEDULED
}
