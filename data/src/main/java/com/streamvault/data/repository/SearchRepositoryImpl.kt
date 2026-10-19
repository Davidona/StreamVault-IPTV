package com.streamvault.data.repository

import android.database.sqlite.SQLiteException
import android.util.Log
import com.streamvault.data.local.dao.SearchDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.dao.StalkerIndexJobDao
import com.streamvault.data.local.dao.SearchHitEntity
import com.streamvault.data.util.toFtsPrefixQuery
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.CatalogCompleteness
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerIndexState
import com.streamvault.domain.model.VodCatalogItem
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.SearchRepository
import com.streamvault.domain.repository.SearchRepositoryResult
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.repository.VodRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SearchRepositoryImpl @Inject constructor(
    private val searchDao: SearchDao,
    private val channelRepository: ChannelRepository,
    private val movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository,
    private val providerDao: ProviderDao,
    private val stalkerIndexJobDao: StalkerIndexJobDao,
    private val providerSnapshotDao: ProviderSnapshotDao,
    private val vodRepository: VodRepository
) : SearchRepository {
    override fun searchContent(
        providerId: Long,
        query: String,
        includeLive: Boolean,
        includeMovies: Boolean,
        includeSeries: Boolean,
        maxResultsPerSection: Int
    ): Flow<SearchRepositoryResult> {
        val normalizedQuery = query.trim()
        val ftsQuery = normalizedQuery.toFtsPrefixQuery()
        val startedAtNanos = System.nanoTime()
        val scopeLabel = searchScopeLabel(includeLive, includeMovies, includeSeries)
        if (ftsQuery.isNullOrBlank() || maxResultsPerSection <= 0) {
            return flowOf(SearchRepositoryResult())
        }

        val localResults = searchHits(
            providerId = providerId,
            ftsQuery = ftsQuery,
            rawQuery = normalizedQuery,
            prefixLike = normalizedQuery.toSqlPrefixLikePattern(),
            includeLive = includeLive,
            includeMovies = includeMovies,
            includeSeries = includeSeries,
            maxResultsPerSection = maxResultsPerSection
        )
            .map { hits: List<SearchHitEntity> ->
                logFtsLookup(
                    providerId = providerId,
                    queryLength = normalizedQuery.length,
                    scopeLabel = scopeLabel,
                    startedAtNanos = startedAtNanos,
                    hits = hits
                )
                SearchLookupState.FtsResult(hits) as SearchLookupState
            }
            .catch { error ->
                if (error is SQLiteException) {
                    Log.w(
                        TAG,
                        "Unified FTS search failed; using section search. " +
                            "provider=$providerId scope=$scopeLabel queryLength=${normalizedQuery.length}",
                        error
                    )
                    logFallback(
                        providerId = providerId,
                        queryLength = normalizedQuery.length,
                        scopeLabel = scopeLabel,
                        startedAtNanos = startedAtNanos
                    )
                    emit(SearchLookupState.FtsUnavailable)
                } else {
                    throw error
                }
            }
            .flatMapLatest { lookupState ->
                when (lookupState) {
                    SearchLookupState.FtsUnavailable -> legacySearchContent(
                        providerId = providerId,
                        query = normalizedQuery,
                        includeLive = includeLive,
                        includeMovies = includeMovies,
                        includeSeries = includeSeries,
                        maxResultsPerSection = maxResultsPerSection
                    ).onEach { result ->
                        logSearchResult(
                            source = "legacy-fallback",
                            providerId = providerId,
                            queryLength = normalizedQuery.length,
                            scopeLabel = scopeLabel,
                            startedAtNanos = startedAtNanos,
                            result = result
                        )
                    }
                    is SearchLookupState.FtsResult -> {
                        val hits = lookupState.hits
                        if (hits.isEmpty()) {
                            logSearchResult(
                                source = "unified-fts-empty",
                                providerId = providerId,
                                queryLength = normalizedQuery.length,
                                scopeLabel = scopeLabel,
                                startedAtNanos = startedAtNanos,
                                result = SearchRepositoryResult()
                            )
                            flowOf(SearchRepositoryResult())
                        } else {
                            hydrateHits(hits, includeLive, includeMovies, includeSeries)
                                .onEach { result ->
                                    logSearchResult(
                                        source = "unified-fts",
                                        providerId = providerId,
                                        queryLength = normalizedQuery.length,
                                        scopeLabel = scopeLabel,
                                        startedAtNanos = startedAtNanos,
                                        result = result
                                    )
                                }
                        }
                    }
                }
            }
            .map { result ->
                result.copy(
                    catalogCompleteness = catalogCompleteness(
                        providerId = providerId,
                        includeMovies = includeMovies,
                        includeSeries = includeSeries
                    )
                )
            }

        return flow {
            val provider = providerDao.getById(providerId)
            if (provider?.type != ProviderType.STALKER_PORTAL || (!includeMovies && !includeSeries)) {
                emitAll(localResults)
                return@flow
            }

            val completeness = catalogCompleteness(providerId, includeMovies, includeSeries)
            if (completeness == CatalogCompleteness.COMPLETE) {
                emitAll(localResults)
                return@flow
            }

            emitAll(
                combine(
                    localResults,
                    searchPortalVod(
                        providerId = providerId,
                        query = normalizedQuery,
                        includeMovies = includeMovies,
                        includeSeries = includeSeries,
                        maxResultsPerSection = maxResultsPerSection
                    )
                        .onStart { emit(SearchRepositoryResult()) }
                ) { local, remote -> mergeVodSearchResults(local, remote, maxResultsPerSection) }
            )
        }
    }

    private suspend fun catalogCompleteness(
        providerId: Long,
        includeMovies: Boolean,
        includeSeries: Boolean
    ): CatalogCompleteness {
        if (!includeMovies && !includeSeries) return CatalogCompleteness.COMPLETE
        val provider = providerDao.getById(providerId) ?: return CatalogCompleteness.PARTIAL
        if (provider.type != ProviderType.STALKER_PORTAL) return CatalogCompleteness.COMPLETE
        val sections = if (
            providerSnapshotDao.getRuntime(providerId)?.catalogLayout == CatalogLayout.UNIFIED_VOD
        ) {
            listOf(ContentType.MOVIE)
        } else {
            buildList {
                if (includeMovies) add(ContentType.MOVIE)
                if (includeSeries) add(ContentType.SERIES)
            }
        }
        val states = sections.map { section -> stalkerIndexJobDao.get(providerId, section.name)?.state }
        return when {
            states.any { it == StalkerIndexState.TRUNCATED } -> CatalogCompleteness.TRUNCATED
            states.any { it in setOf(StalkerIndexState.QUEUED, StalkerIndexState.RUNNING, StalkerIndexState.RETRY_WAIT) } ->
                CatalogCompleteness.INDEXING
            states.isNotEmpty() && states.all { it == StalkerIndexState.COMPLETE } -> CatalogCompleteness.COMPLETE
            else -> CatalogCompleteness.PARTIAL
        }
    }

    private fun searchPortalVod(
        providerId: Long,
        query: String,
        includeMovies: Boolean,
        includeSeries: Boolean,
        maxResultsPerSection: Int
    ): Flow<SearchRepositoryResult> = flow {
        val movies = linkedMapOf<Long, Movie>()
        val series = linkedMapOf<String, Series>()
        var page = 1
        var hasMore: Boolean
        do {
            val result = try {
                vodRepository.searchVod(providerId, query, page)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                break
            }
            when (result) {
                is Result.Success -> {
                    result.data.items.forEach { item ->
                        when (item) {
                            is VodCatalogItem.MovieItem -> if (includeMovies) {
                                movies.putIfAbsent(item.movie.id, item.movie)
                            }
                            is VodCatalogItem.SeriesItem -> if (includeSeries) {
                                series.putIfAbsent(item.series.id.toString(), item.series)
                            }
                        }
                    }
                    hasMore = result.data.hasMore
                    page = result.data.page + 1
                }
                is Result.Error,
                is Result.Loading -> break
            }
        } while (
            hasMore && page <= MAX_PORTAL_SEARCH_PAGES &&
                ((includeMovies && movies.size < maxResultsPerSection) ||
                    (includeSeries && series.size < maxResultsPerSection))
        )

        emit(
            SearchRepositoryResult(
                movies = movies.values.take(maxResultsPerSection),
                series = series.values.take(maxResultsPerSection)
            )
        )
    }

    private fun mergeVodSearchResults(
        local: SearchRepositoryResult,
        remote: SearchRepositoryResult,
        maxResultsPerSection: Int
    ): SearchRepositoryResult = local.copy(
        movies = (local.movies + remote.movies).distinctBy(Movie::id).take(maxResultsPerSection),
        series = (local.series + remote.series).distinctBy {
            it.id
        }.take(maxResultsPerSection)
    )

    override fun searchChannels(providerId: Long, query: String): Flow<List<Channel>> =
        channelRepository.searchChannels(providerId, query)

    override fun searchMovies(providerId: Long, query: String): Flow<List<Movie>> =
        movieRepository.searchMovies(providerId, query)

    override fun searchSeries(providerId: Long, query: String): Flow<List<Series>> =
        seriesRepository.searchSeries(providerId, query)

    private fun searchHits(
        providerId: Long,
        ftsQuery: String,
        rawQuery: String,
        prefixLike: String,
        includeLive: Boolean,
        includeMovies: Boolean,
        includeSeries: Boolean,
        maxResultsPerSection: Int
    ): Flow<List<SearchHitEntity>> =
        when {
            includeLive && includeMovies && includeSeries ->
                searchDao.searchAll(providerId, ftsQuery, rawQuery, prefixLike, maxResultsPerSection)
            includeLive && !includeMovies && !includeSeries ->
                searchDao.searchLive(providerId, ftsQuery, rawQuery, prefixLike, maxResultsPerSection)
            !includeLive && includeMovies && !includeSeries ->
                searchDao.searchMovies(providerId, ftsQuery, rawQuery, prefixLike, maxResultsPerSection)
            !includeLive && !includeMovies && includeSeries ->
                searchDao.searchSeries(providerId, ftsQuery, rawQuery, prefixLike, maxResultsPerSection)
            includeLive || includeMovies || includeSeries ->
                searchDao.searchAll(providerId, ftsQuery, rawQuery, prefixLike, maxResultsPerSection)
                    .map { hits ->
                        hits.filter { hit ->
                            (includeLive && hit.contentType == LIVE_TYPE) ||
                                (includeMovies && hit.contentType == MOVIE_TYPE) ||
                                (includeSeries && hit.contentType == SERIES_TYPE)
                        }
                    }
            else -> flowOf(emptyList())
        }

    private fun hydrateHits(
        hits: List<SearchHitEntity>,
        includeLive: Boolean,
        includeMovies: Boolean,
        includeSeries: Boolean
    ): Flow<SearchRepositoryResult> {
        val channelIds = hits.orderedIds(LIVE_TYPE).takeIf { includeLive } ?: emptyList()
        val movieIds = hits.orderedIds(MOVIE_TYPE).takeIf { includeMovies } ?: emptyList()
        val seriesIds = hits.orderedIds(SERIES_TYPE).takeIf { includeSeries } ?: emptyList()

        return combine(
            if (channelIds.isEmpty()) flowOf(emptyList()) else channelRepository.getChannelsByIds(channelIds),
            if (movieIds.isEmpty()) flowOf(emptyList()) else movieRepository.getMoviesByIds(movieIds),
            if (seriesIds.isEmpty()) flowOf(emptyList()) else seriesRepository.getSeriesByIds(seriesIds)
        ) { channels, movies, series ->
            SearchRepositoryResult(
                channels = channels.reorderByIds(channelIds) { it.id },
                movies = movies.reorderByIds(movieIds) { it.id },
                series = series.reorderByIds(seriesIds) { it.id }
            )
        }
    }

    private fun legacySearchContent(
        providerId: Long,
        query: String,
        includeLive: Boolean,
        includeMovies: Boolean,
        includeSeries: Boolean,
        maxResultsPerSection: Int
    ): Flow<SearchRepositoryResult> =
        combine(
            if (includeLive) searchChannels(providerId, query) else flowOf(emptyList()),
            if (includeMovies) searchMovies(providerId, query) else flowOf(emptyList()),
            if (includeSeries) searchSeries(providerId, query) else flowOf(emptyList())
        ) { channels, movies, series ->
            SearchRepositoryResult(
                channels = channels.take(maxResultsPerSection),
                movies = movies.take(maxResultsPerSection),
                series = series.take(maxResultsPerSection)
            )
        }

    private fun List<SearchHitEntity>.orderedIds(contentType: String): List<Long> =
        filter { it.contentType == contentType }
            .sortedWith(compareBy<SearchHitEntity> { it.matchRank }.thenBy { it.title })
            .map { it.contentId }
            .distinct()

    private fun <T> List<T>.reorderByIds(ids: List<Long>, idSelector: (T) -> Long): List<T> {
        val byId = associateBy(idSelector)
        return ids.mapNotNull(byId::get)
    }

    private fun String.toSqlPrefixLikePattern(): String = buildString {
        this@toSqlPrefixLikePattern.forEach { char ->
            when (char) {
                '\\', '%', '_' -> append('\\').append(char)
                else -> append(char)
            }
        }
        append('%')
    }

    private fun logFtsLookup(
        providerId: Long,
        queryLength: Int,
        scopeLabel: String,
        startedAtNanos: Long,
        hits: List<SearchHitEntity>
    ) {
        val elapsedMs = elapsedMsSince(startedAtNanos)
        if (elapsedMs >= SLOW_FTS_LOG_THRESHOLD_MS) {
            Log.w(
                TAG,
                "Slow unified FTS search (${elapsedMs}ms): provider=$providerId " +
                    "scope=$scopeLabel queryLength=$queryLength hits=${hits.size} " +
                    "liveHits=${hits.countType(LIVE_TYPE)} movieHits=${hits.countType(MOVIE_TYPE)} " +
                    "seriesHits=${hits.countType(SERIES_TYPE)}"
            )
        }
    }

    private fun logFallback(
        providerId: Long,
        queryLength: Int,
        scopeLabel: String,
        startedAtNanos: Long
    ) {
        Log.w(
            TAG,
            "Search fallback activated after ${elapsedMsSince(startedAtNanos)}ms: " +
                "provider=$providerId scope=$scopeLabel queryLength=$queryLength"
        )
    }

    private fun logSearchResult(
        source: String,
        providerId: Long,
        queryLength: Int,
        scopeLabel: String,
        startedAtNanos: Long,
        result: SearchRepositoryResult
    ) {
        val elapsedMs = elapsedMsSince(startedAtNanos)
        if (elapsedMs >= SLOW_TOTAL_LOG_THRESHOLD_MS || source != "unified-fts") {
            Log.i(
                TAG,
                "Search completed via $source in ${elapsedMs}ms: provider=$providerId " +
                    "scope=$scopeLabel queryLength=$queryLength channels=${result.channels.size} " +
                    "movies=${result.movies.size} series=${result.series.size}"
            )
        }
    }

    private fun elapsedMsSince(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L

    private fun List<SearchHitEntity>.countType(contentType: String): Int =
        count { it.contentType == contentType }

    private fun searchScopeLabel(includeLive: Boolean, includeMovies: Boolean, includeSeries: Boolean): String =
        buildList {
            if (includeLive) add(LIVE_TYPE)
            if (includeMovies) add(MOVIE_TYPE)
            if (includeSeries) add(SERIES_TYPE)
        }.joinToString(separator = "+").ifBlank { "NONE" }

    private companion object {
        const val TAG = "SearchRepository"
        const val LIVE_TYPE = "LIVE"
        const val MOVIE_TYPE = "MOVIE"
        const val SERIES_TYPE = "SERIES"
        const val SLOW_FTS_LOG_THRESHOLD_MS = 250L
        const val SLOW_TOTAL_LOG_THRESHOLD_MS = 500L
        const val MAX_PORTAL_SEARCH_PAGES = 12
    }

    private sealed interface SearchLookupState {
        data class FtsResult(val hits: List<SearchHitEntity>) : SearchLookupState
        data object FtsUnavailable : SearchLookupState
    }
}
