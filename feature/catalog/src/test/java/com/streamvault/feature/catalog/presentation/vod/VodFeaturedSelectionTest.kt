package com.streamvault.feature.catalog.presentation.vod

import com.streamvault.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class VodFeaturedSelectionTest {
    private fun candidate(id: Long, release: String = "2020-01-01", added: Long = 1, genre: String = "Drama") =
        FeaturedCandidate(id, 7, "Title", null, release, added, genre, 7f, id)
    private fun history(id: Long, progress: Long = 20, duration: Long = 100) = PlaybackHistory(
        contentId = id, contentType = ContentType.MOVIE, providerId = 7, title = "Title", streamUrl = "stream",
        resumePositionMs = progress, totalDurationMs = duration, seasonNumber = 1, episodeNumber = 1)
    @Test fun hiddenHasNoSelection() {
        assertNull(selectFeatured(VodFeaturedMode.HIDDEN, listOf(candidate(1)), emptyList(), emptySet(), 1))
    }
    @Test fun releaseAndAdditionAreIndependent() {
        val items = listOf(candidate(1, "2025-01-01", 1), candidate(2, "2020-01-01", 200))
        assertEquals(1L, selectFeatured(VodFeaturedMode.LATEST_RELEASE, items, emptyList(), emptySet(), 1)?.candidate?.id)
        assertEquals(2L, selectFeatured(VodFeaturedMode.RECENTLY_ADDED, items, emptyList(), emptySet(), 1)?.candidate?.id)
        assertNull(selectFeatured(VodFeaturedMode.LATEST_RELEASE, listOf(candidate(1, "unknown")), emptyList(), emptySet(), 1))
    }
    @Test fun resumeRespectsProviderAndCompletion() {
        assertNull(selectFeatured(VodFeaturedMode.CONTINUE_WATCHING, listOf(candidate(1)), listOf(history(1, 90)), emptySet(), 1))
        assertNull(selectFeatured(VodFeaturedMode.CONTINUE_WATCHING, listOf(candidate(1)), listOf(history(1).copy(providerId = 8)), emptySet(), 1))
        assertNotNull(selectFeatured(VodFeaturedMode.CONTINUE_WATCHING, listOf(candidate(1)), listOf(history(1, 89)), emptySet(), 1))
    }
    @Test fun randomIsStableAndExcludesWatched() {
        val items = (1L..20L).map { candidate(it) }
        val first = selectFeatured(VodFeaturedMode.RANDOM, items, listOf(history(1)), emptySet(), 42)
        assertNotEquals(1L, first?.candidate?.id)
        assertEquals(first, selectFeatured(VodFeaturedMode.RANDOM, items, listOf(history(1)), emptySet(), 42))
    }
    @Test fun watchlistGenresInfluenceRecommendations() {
        val items = listOf(candidate(1, genre = "Crime"), candidate(2, genre = "Comedy"), candidate(3, genre = "Crime"))
        assertEquals(3L, selectFeatured(VodFeaturedMode.FOR_YOU, items, listOf(history(1)), setOf(1), 1)?.candidate?.id)
    }
    @Test fun nextEpisodeCrossesSeasonAndPreservesUnknownDuration() {
        val next = Episode(id = 99, title = "Next", episodeNumber = 1, seasonNumber = 2, providerId = 7)
        val show = Series(id = 8, name = "Show", seasons = listOf(Season(2, episodes = listOf(next))))
        assertEquals(next, nextFeaturedEpisode(show, history(1, 90), emptyList()))
        assertNull(nextFeaturedEpisode(show, history(1, 89), emptyList()))
        assertNull(nextFeaturedEpisode(show, history(1, 90, 0), emptyList()))
    }
}
