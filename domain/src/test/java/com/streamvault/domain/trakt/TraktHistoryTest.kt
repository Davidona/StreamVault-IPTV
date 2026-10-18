package com.streamvault.domain.trakt

import com.streamvault.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TraktHistoryTest {
    private val movie = TraktMediaIdentity.MovieId(603)
    private val episode = TraktMediaIdentity.EpisodeId(1396, 2, 3)
    private val local = PlaybackHistory(contentId = 42, contentType = ContentType.MOVIE,
        providerId = 7, title = "Movie", streamUrl = "", resumePositionMs = 50,
        totalDurationMs = 100, lastWatchedAt = 1000)
    private val match = TraktHistoryMatch(movie, 2000, 2)

    @Test fun duplicatePagesCountEachHistoryIdOnceAndKeepLatestWatch() {
        val index = TraktHistoryIndex(listOf(TraktHistoryEntry(1, movie, 1000),
            TraktHistoryEntry(1, movie, 1000), TraktHistoryEntry(2, movie, 2000),
            TraktHistoryEntry(3, episode, 3000)))
        assertEquals(match, index.find(movie))
        assertEquals(TraktHistoryMatch(episode, 3000, 1), index.find(episode))
    }

    @Test fun conflictingHistoryIdDoesNotMatchEitherMedia() {
        val index = TraktHistoryIndex(listOf(TraktHistoryEntry(1, movie, 1000), TraktHistoryEntry(1, episode, 2000)))
        assertNull(index.find(movie))
        assertNull(index.find(episode))
    }

    @Test fun importCompletesKnownDurationPreservesLocalCountAndIsIdempotent() {
        val original = local.copy(watchCount = 4)
        val imported = requireNotNull(TraktHistoryMergePolicy.importCandidate(original, movie, match))
        assertEquals(PlaybackWatchedStatus.COMPLETED_AUTO, imported.watchedStatus)
        assertEquals(4, imported.watchCount)
        assertEquals(2000, imported.lastWatchedAt)
        assertEquals(original.totalDurationMs, imported.resumePositionMs)
        assertNull(TraktHistoryMergePolicy.importCandidate(imported, movie, match))
    }

    @Test fun newerLocalRewatchAndManualCompletionArePreserved() {
        assertNull(TraktHistoryMergePolicy.importCandidate(local.copy(lastWatchedAt = 3000), movie, match))
        assertNull(TraktHistoryMergePolicy.importCandidate(local.copy(watchedStatus = PlaybackWatchedStatus.COMPLETED_MANUAL), movie, match))
    }

    @Test fun absenceMismatchedIdentityAndLiveContentNeverProduceImport() {
        assertNull(TraktHistoryMergePolicy.importCandidate(local, movie, null))
        assertNull(TraktHistoryMergePolicy.importCandidate(local, movie, match.copy(media = episode)))
        assertNull(TraktHistoryMergePolicy.importCandidate(local.copy(contentType = ContentType.LIVE), movie, match))
    }

    @Test fun episodeNumbersMustMatchForImport() {
        val history = local.copy(contentType = ContentType.SERIES_EPISODE, seasonNumber = 2, episodeNumber = 3)
        val episodeMatch = match.copy(media = episode)
        assertNotNull(TraktHistoryMergePolicy.importCandidate(history, episode, episodeMatch))
        assertNull(TraktHistoryMergePolicy.importCandidate(history.copy(episodeNumber = 4), episode, episodeMatch))
    }
}
