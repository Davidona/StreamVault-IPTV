package com.streamvault.data.trakt

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.domain.trakt.TraktFavoriteFailure
import com.streamvault.domain.trakt.TraktFavoriteIdentity
import com.streamvault.domain.trakt.TraktFavoriteImportResult
import com.streamvault.domain.trakt.TraktFavoriteImporter
import com.streamvault.domain.trakt.TraktFavoritePageResult
import com.streamvault.domain.trakt.TraktFavoriteType
import com.streamvault.domain.trakt.TraktFavoritesSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Imports Trakt's existing movie/show hearts into local Favorites for the active provider. */
@Singleton
class TraktFavoriteImporterService @Inject constructor(
    private val account: TraktAccount,
    private val source: TraktFavoritesSource,
    private val history: PlaybackHistoryRepository,
    private val providers: ProviderRepository,
    private val movies: MovieRepository,
    private val series: SeriesRepository,
    private val favorites: FavoriteRepository,
) : TraktFavoriteImporter {
    private val mutex = Mutex()

    override suspend fun importToLocal(): TraktFavoriteImportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            var imported = 0
            try {
                if (history.isIncognitoModeEnabled()) return@withLock fail(TraktFavoriteFailure.INCOGNITO)
                if (!account.state.value.connected) account.load()
                val owner = account.state.value.username?.takeIf { account.state.value.connected }
                    ?: return@withLock fail(TraktFavoriteFailure.DISCONNECTED)
                val provider = providers.getActiveProvider().first()
                    ?: return@withLock fail(TraktFavoriteFailure.NO_ACTIVE_PROVIDER)
                fun sameAccount() = account.state.value.connected && account.state.value.username == owner

                val staged = linkedSetOf<TraktFavoriteIdentity>()
                var skipped = 0
                for (type in TraktFavoriteType.entries) {
                    var hasMore = true
                    for (pageNumber in 1..MAX_PAGES) {
                        if (!sameAccount()) return@withLock fail(TraktFavoriteFailure.ACCOUNT_CHANGED)
                        if (history.isIncognitoModeEnabled()) return@withLock fail(TraktFavoriteFailure.INCOGNITO)
                        val page = when (val result = source.favorites(type, pageNumber)) {
                            is TraktFavoritePageResult.Success -> result.page
                            TraktFavoritePageResult.Disconnected -> return@withLock fail(TraktFavoriteFailure.DISCONNECTED)
                            TraktFavoritePageResult.Failed -> return@withLock fail(TraktFavoriteFailure.REQUEST_FAILED)
                        }
                        if (page.page != pageNumber || page.identities.any { it.type != type }) {
                            return@withLock fail(TraktFavoriteFailure.REQUEST_FAILED)
                        }
                        staged += page.identities
                        skipped += page.skippedItems
                        hasMore = page.hasMore
                        if (!hasMore) break
                        if (pageNumber == MAX_PAGES) return@withLock fail(TraktFavoriteFailure.READ_LIMIT)
                    }
                }

                if (!sameAccount()) return@withLock fail(TraktFavoriteFailure.ACCOUNT_CHANGED)
                if (history.isIncognitoModeEnabled()) return@withLock fail(TraktFavoriteFailure.INCOGNITO)
                val movieIds = staged.filter { it.type == TraktFavoriteType.MOVIE }.map { it.tmdbId }.distinct()
                val showIds = staged.filter { it.type == TraktFavoriteType.SHOW }.map { it.tmdbId }.distinct()
                val localMovies = movies.getMoviesByTmdbIds(provider.id, movieIds).filter { it.providerId == provider.id }
                val localShows = series.getSeriesByTmdbIds(provider.id, showIds).filter { it.providerId == provider.id }
                val moviesByTmdb = localMovies.filter { it.tmdbId != null }.groupBy { it.tmdbId!! }
                val showsByTmdb = localShows.filter { it.tmdbId != null }.groupBy { it.tmdbId!! }
                var alreadyPresent = 0
                var unmatched = 0
                for (identity in staged) {
                    if (!sameAccount()) return@withLock fail(TraktFavoriteFailure.ACCOUNT_CHANGED, imported)
                    if (history.isIncognitoModeEnabled()) return@withLock fail(TraktFavoriteFailure.INCOGNITO, imported)
                    val contentType = if (identity.type == TraktFavoriteType.MOVIE) ContentType.MOVIE else ContentType.SERIES
                    val matchingIds = if (identity.type == TraktFavoriteType.MOVIE) {
                        moviesByTmdb[identity.tmdbId].orEmpty().map { it.id }.distinct()
                    } else {
                        showsByTmdb[identity.tmdbId].orEmpty().map { it.id }.distinct()
                    }
                    if (matchingIds.isEmpty()) {
                        unmatched++
                        continue
                    }
                    for (contentId in matchingIds) {
                        if (favorites.isFavorite(provider.id, contentId, contentType)) {
                            alreadyPresent++
                        } else if (favorites.addFavorite(provider.id, contentId, contentType).isSuccess) {
                            imported++
                        } else {
                            return@withLock fail(TraktFavoriteFailure.REQUEST_FAILED, imported)
                        }
                    }
                }
                TraktFavoriteImportResult.Success(imported, alreadyPresent, unmatched, skipped)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                fail(TraktFavoriteFailure.REQUEST_FAILED, imported)
            }
        }
    }

    private fun fail(reason: TraktFavoriteFailure, imported: Int = 0) =
        TraktFavoriteImportResult.Failure(reason, imported)

    private companion object {
        const val MAX_PAGES = 50
    }
}
