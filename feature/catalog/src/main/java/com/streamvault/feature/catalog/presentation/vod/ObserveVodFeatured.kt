package com.streamvault.feature.catalog.presentation.vod

import com.streamvault.domain.model.*
import com.streamvault.domain.repository.*
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.trakt.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeVodFeatured(
    series: Boolean, preferences: SettingsPreferences, providers: ProviderRepository,
    movies: MovieRepository?, shows: SeriesRepository?, playback: PlaybackHistoryRepository,
    watchlist: TraktWatchlist, account: TraktAccount,
): Flow<VodFeaturedSelection?> {
    val seed = kotlin.random.Random.nextInt()
    val activeWatchlist = combine(watchlist.state, account.state, preferences.isIncognitoMode) { watch, signedIn, incognito ->
        if (!incognito && signedIn.connected && signedIn.username == watch.accountUsername) watch
        else TraktWatchlistState()
    }
    val mode = (if (series) preferences.seriesFeaturedMode else preferences.movieFeaturedMode).distinctUntilChanged()
    return providers.getActiveProvider().distinctUntilChangedBy { it?.id }.flatMapLatest { provider ->
        if (provider == null) return@flatMapLatest flowOf(null)
        mode.flatMapLatest { stored ->
            val selectedMode = VodFeaturedMode.fromStorage(stored, series)
            if (selectedMode == VodFeaturedMode.HIDDEN) return@flatMapLatest flowOf(null)
            val recent = playback.getRecentlyWatchedByProvider(provider.id, 5000)
            val resumeMode = selectedMode == VodFeaturedMode.CONTINUE_WATCHING || selectedMode == VodFeaturedMode.UP_NEXT
            val movieRows = if (series) null else if (!resumeMode) movies!!.getMovies(provider.id)
                else recent.flatMapLatest { rows ->
                    val ids = rows.filter { it.contentType == ContentType.MOVIE || it.contentType == ContentType.VOD }
                        .map { it.contentId }.distinct()
                    if (ids.isEmpty()) flowOf(emptyList()) else movies!!.getMoviesByIds(ids)
                }
            val seriesRows = if (!series) null else if (!resumeMode) shows!!.getSeries(provider.id)
                else recent.flatMapLatest { rows ->
                    val ids = rows.filter { it.contentType == ContentType.SERIES || it.contentType == ContentType.SERIES_EPISODE }
                        .map { it.seriesId ?: it.contentId }.distinct()
                    if (ids.isEmpty()) flowOf(emptyList()) else shows!!.getSeriesByIds(ids)
                }
            val candidates = if (series) seriesRows!!.map { items -> items.map {
                FeaturedCandidate(it.id, it.providerId, it.name, it.plot, it.releaseDate, it.lastModified,
                    it.genre, it.rating, it.tmdbId, series = it)
            } } else movieRows!!.combine(activeWatchlist) { items, watch ->
                if (selectedMode != VodFeaturedMode.FOR_YOU || !watch.connected) items
                else {
                    // Browse projections omit movie TMDB IDs; enrich watchlist seeds through the identity query.
                    val ids = watch.items.filter { it.type == TraktWatchlistType.MOVIE }.map { it.tmdbId }
                    val seeds = if (ids.isEmpty()) emptyList() else movies!!.getMoviesByTmdbIds(provider.id, ids)
                    (items + seeds).associateBy { it.id }.values.toList()
                }
            }.map { items -> items.map {
                FeaturedCandidate(it.id, it.providerId, it.name, it.plot, it.releaseDate?.takeIf { date -> releaseTimestamp(date) != Long.MIN_VALUE } ?: it.year, it.addedAt,
                    it.genre, it.rating, it.tmdbId, movie = it)
            } }
            combine(candidates, recent,
                preferences.getHiddenCategoryIds(provider.id, if (series) ContentType.SERIES else ContentType.MOVIE),
                preferences.parentalControlLevel, activeWatchlist) { items, history, hidden, level, watch ->
                val safe = items.filter { item ->
                    val category = item.movie?.categoryId ?: item.series?.categoryId
                    category !in hidden && (level == 0 ||
                        !(item.movie?.let { it.isAdult || it.isUserProtected }
                            ?: item.series?.let { it.isAdult || it.isUserProtected } ?: false))
                }
                val relevantHistory = history.filter { if (series) it.contentType == ContentType.SERIES ||
                    it.contentType == ContentType.SERIES_EPISODE else it.contentType == ContentType.MOVIE || it.contentType == ContentType.VOD }
                val identities = if (watch.connected) watch.items.filter {
                    it.type == if (series) TraktWatchlistType.SHOW else TraktWatchlistType.MOVIE
                }.map { it.tmdbId }.toSet() else emptySet()
                Triple(selectFeatured(selectedMode, safe, relevantHistory, identities, seed), relevantHistory, selectedMode)
            }.distinctUntilChanged().mapLatest { (selection, history, selectedMode) ->
                if (selection == null || selectedMode != VodFeaturedMode.UP_NEXT) return@mapLatest selection
                // Try recently watched shows in order; a caught-up show must not mask another show's next episode.
                val remaining = mutableListOf<PlaybackHistory>().apply { addAll(history) }
                var proposal: VodFeaturedSelection? = selection
                val candidatesForNext = candidates.first()
                repeat(10) {
                    val proposed = proposal ?: return@mapLatest null
                    val current = proposed.history ?: return@mapLatest null
                    val completed = current.watchedStatus != PlaybackWatchedStatus.IN_PROGRESS ||
                        current.totalDurationMs > 0 && current.resumePositionMs.toDouble() / current.totalDurationMs >= .9
                    if (!completed) return@mapLatest proposed
                    val details = shows!!.getSeriesDetails(provider.id, proposed.candidate.id).getOrNull()
                    val next = details?.let { nextFeaturedEpisode(it, current, history) }
                    if (next != null) {
                        val progress = history.firstOrNull { it.providerId == provider.id && it.contentId == next.id }
                        return@mapLatest proposed.copy(episode = next.copy(
                            providerId = provider.id, seriesId = proposed.candidate.id,
                            watchProgress = progress?.resumePositionMs ?: next.watchProgress))
                    }
                    val showId = current.seriesId ?: current.contentId
                    remaining.removeAll { (it.seriesId ?: it.contentId) == showId }
                    proposal = selectFeatured(selectedMode, candidatesForNext, remaining, emptySet(), seed)
                }
                null
            }.catch { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                java.util.logging.Logger.getLogger("VodFeatured").warning("Selection unavailable: ${error.javaClass.simpleName}")
                emit(null)
            }
        }.onStart { emit(null) }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
}
