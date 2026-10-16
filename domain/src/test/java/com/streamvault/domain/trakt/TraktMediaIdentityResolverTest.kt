package com.streamvault.domain.trakt

import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktMediaIdentityResolverTest {
    private val series = Series(id = 42, name = "Show", providerId = 7, tmdbId = 1396)
    private val episode = Episode(id = 99, title = "Episode", seasonNumber = 2,
        episodeNumber = 3, seriesId = 42, providerId = 7)

    @Test fun movieRequiresAttachedPositiveIdRatherThanRecognizableTitle() {
        listOf(null, 0L, -1L).forEach { id ->
            assertNull(TraktMediaIdentityResolver.movie(Movie(id = 100, name = "The Matrix (1999)", tmdbId = id)))
        }
        assertEquals(TraktMediaIdentity.MovieId(603),
            TraktMediaIdentityResolver.movie(Movie(id = 100, name = "Provider label", tmdbId = 603)))
    }

    @Test fun movieIdentityIsIndependentOfProviderAndStreamVariant() {
        val first = Movie(id = 100, name = "A", providerId = 7, tmdbId = 603, streamUrl = "first")
        assertEquals(TraktMediaIdentityResolver.movie(first), TraktMediaIdentityResolver.movie(
            first.copy(id = 200, name = "B", providerId = 8, streamUrl = "second")))
    }

    @Test fun episodeUsesShowIdAndExactNumbersRatherThanLocalEpisodeId() {
        assertEquals(TraktMediaIdentity.EpisodeId(1396, 2, 3),
            TraktMediaIdentityResolver.episode(series, episode))
        assertNotEquals(TraktMediaIdentityResolver.episode(series, episode),
            TraktMediaIdentityResolver.episode(series, episode.copy(episodeNumber = 4)))
    }

    @Test fun wrongSeriesOrProviderCannotMatchEvenWithSameEpisodeNumbers() {
        assertNull(TraktMediaIdentityResolver.episode(series.copy(id = 43), episode))
        assertNull(TraktMediaIdentityResolver.episode(series.copy(providerId = 8), episode))
        assertNull(TraktMediaIdentityResolver.episode(series.copy(id = 0), episode.copy(seriesId = 0)))
    }

    @Test fun missingShowIdOrUnknownEpisodeIsNotEligible() {
        listOf(null, 0L, -1L).forEach { id ->
            assertNull(TraktMediaIdentityResolver.episode(series.copy(tmdbId = id), episode))
        }
        assertNull(TraktMediaIdentityResolver.episode(series, episode.copy(episodeNumber = 0)))
    }

    @Test fun specialsAreEligibleAndIdentitySurvivesProviderMigration() {
        val special = episode.copy(seasonNumber = 0)
        assertEquals(TraktMediaIdentity.EpisodeId(1396, 0, 3),
            TraktMediaIdentityResolver.episode(series, special))
        assertEquals(TraktMediaIdentityResolver.episode(series, special),
            TraktMediaIdentityResolver.episode(series.copy(id = 500, providerId = 9),
                special.copy(id = 600, seriesId = 500, providerId = 9)))
    }
}
