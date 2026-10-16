package com.streamvault.data.trakt

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.domain.trakt.TraktAccountState
import com.streamvault.domain.trakt.TraktFavoriteIdentity
import com.streamvault.domain.trakt.TraktFavoritePage
import com.streamvault.domain.trakt.TraktFavoritePageResult
import com.streamvault.domain.trakt.TraktFavoriteType
import com.streamvault.domain.trakt.TraktFavoritesSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TraktFavoriteImporterServiceTest {
    private class Account : TraktAccount {
        override val state = MutableStateFlow(TraktAccountState(configured = true, connected = true, username = "viewer"))
        override suspend fun load() = Unit
        override suspend fun beginSignIn() = error("unused")
        override suspend fun completeSignIn(callback: String) = Unit
        override suspend fun cancelSignIn() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun refreshProfile() = Unit
    }

    @Test fun `imports exact provider matches and reports already present and unmatched`() = runBlocking {
        val provider = LegacyProvider(id = 7, name = "Provider", type = ProviderType.M3U, serverUrl = "https://example.org")
        val providers = mock<ProviderRepository>()
        whenever(providers.getActiveProvider()).thenReturn(flowOf(provider))
        val movies = mock<MovieRepository>()
        whenever(movies.getMoviesByTmdbIds(7, listOf(603L, 999L))).thenReturn(
            listOf(Movie(id = 10, name = "Fight Club", tmdbId = 603, providerId = 7)))
        val series = mock<SeriesRepository>()
        whenever(series.getSeriesByTmdbIds(7, listOf(1396L))).thenReturn(
            listOf(Series(id = 20, name = "Breaking Bad", tmdbId = 1396, providerId = 7)))
        val history = mock<PlaybackHistoryRepository>()
        whenever(history.isIncognitoModeEnabled()).thenReturn(false)
        val favorites = mock<FavoriteRepository>()
        whenever(favorites.isFavorite(7, 10, ContentType.MOVIE)).thenReturn(true)
        whenever(favorites.isFavorite(7, 20, ContentType.SERIES)).thenReturn(false)
        whenever(favorites.addFavorite(7, 20, ContentType.SERIES, null)).thenReturn(Result.success(Unit))
        val source = object : TraktFavoritesSource {
            override suspend fun favorites(type: TraktFavoriteType, page: Int): TraktFavoritePageResult =
                TraktFavoritePageResult.Success(TraktFavoritePage(
                    identities = if (type == TraktFavoriteType.MOVIE) listOf(
                        TraktFavoriteIdentity(type, 603), TraktFavoriteIdentity(type, 999),
                    ) else listOf(TraktFavoriteIdentity(type, 1396)),
                    page = page,
                    hasMore = false,
                ))
            override suspend fun setFavorited(
                identity: TraktFavoriteIdentity,
                favorited: Boolean,
            ) = com.streamvault.domain.trakt.TraktFavoriteWriteResult.Success
        }

        val result = TraktFavoriteImporterService(Account(), source, history, providers, movies, series, favorites)
            .importToLocal()

        assertEquals(com.streamvault.domain.trakt.TraktFavoriteImportResult.Success(
            imported = 1, alreadyPresent = 1, unmatched = 1, skipped = 0), result)
    }
}
