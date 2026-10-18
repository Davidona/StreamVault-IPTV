package com.streamvault.data.trakt

import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.mapper.toDomain
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.PlaybackWatchedStatus
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.trakt.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Manual additive import. All network pages are staged before the first local write. */
@Singleton
class TraktHistorySyncService @Inject constructor(
    private val account: TraktAccount,
    private val source: TraktHistorySource,
    private val movies: MovieDao,
    private val series: SeriesDao,
    private val episodes: EpisodeDao,
    private val history: PlaybackHistoryRepository,
) : TraktHistorySync {
    private val mutex = Mutex()
    override suspend fun sync(): TraktHistorySyncResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            var imported = 0
            var uploaded = 0
            try {
                if (history.isIncognitoModeEnabled()) {
                    return@withLock TraktHistorySyncResult.Failure(TraktSyncFailure.INCOGNITO)
                }
                val owner = account.state.value.username?.takeIf { account.state.value.connected }
                    ?: return@withLock TraktHistorySyncResult.Failure(TraktSyncFailure.DISCONNECTED)
                fun sameAccount() = account.state.value.connected && account.state.value.username == owner
                val entries = mutableListOf<TraktHistoryEntry>()
                var skipped = 0
                for (page in 1..50) {
                    if (!sameAccount()) return@withLock TraktHistorySyncResult.Failure(TraktSyncFailure.ACCOUNT_CHANGED)
                    when (val result = source.history(page)) {
                        is TraktHistoryResult.Success -> {
                            if (result.history.page != page || result.history.entries.size > 100) {
                                return@withLock TraktHistorySyncResult.Failure(TraktSyncFailure.READ_FAILED)
                            }
                            entries += result.history.entries
                            skipped += result.history.skippedEntries
                            if (!result.history.hasMore) break
                            if (page == 50) return@withLock TraktHistorySyncResult.Failure(TraktSyncFailure.READ_LIMIT)
                        }
                        else -> return@withLock TraktHistorySyncResult.Failure(TraktSyncFailure.READ_FAILED)
                    }
                }
                val index = TraktHistoryIndex(entries)
                if (!sameAccount()) return@withLock TraktHistorySyncResult.Failure(TraktSyncFailure.ACCOUNT_CHANGED)
                val identities = entries.map { it.media }.distinct()
                val movieIds = identities.filterIsInstance<TraktMediaIdentity.MovieId>().map { it.tmdbId }
                val showIds = identities.filterIsInstance<TraktMediaIdentity.EpisodeId>().map { it.showTmdbId }.distinct()
                val movieRows = movieIds.chunked(400).flatMap { movies.getByTmdbIdsSync(it) }.groupBy { it.tmdbId }
                val showRows = showIds.chunked(400).flatMap { series.getByTmdbIdsSync(it) }.map { it.toDomain() }.groupBy { it.tmdbId }
                val episodeCache = mutableMapOf<Long, List<com.streamvault.domain.model.Episode>>()
                var unchanged = 0
                var unmatched = 0
                for (identity in identities) {
                    if (!sameAccount()) return@withLock TraktHistorySyncResult.Failure(TraktSyncFailure.ACCOUNT_CHANGED, imported)
                    val match = index.find(identity)
                    if (match == null) { unmatched++; continue }
                    val candidates = when (identity) {
                        is TraktMediaIdentity.MovieId -> movieRows[identity.tmdbId].orEmpty().map { row ->
                            val movie = row.toDomain()
                            PlaybackHistory(contentId = movie.id, contentType = ContentType.MOVIE,
                                providerId = movie.providerId, title = movie.name, posterUrl = movie.posterUrl,
                                streamUrl = movie.streamUrl, totalDurationMs = movie.durationSeconds.toLong() * 1000,
                                resumePositionMs = movie.watchProgress, lastWatchedAt = movie.lastWatchedAt,
                                watchCount = row.watchCount.coerceAtLeast(1))
                        }
                        is TraktMediaIdentity.EpisodeId -> showRows[identity.showTmdbId].orEmpty().flatMap { show ->
                            val localEpisodes = episodeCache[show.id] ?: episodes.getEntitiesBySeriesSync(show.id)
                                .map { it.toDomain() }.also { episodeCache[show.id] = it }
                            localEpisodes.filter { TraktMediaIdentityResolver.episode(show, it) == identity }.map { episode ->
                                PlaybackHistory(contentId = episode.id, contentType = ContentType.SERIES_EPISODE,
                                    providerId = episode.providerId, title = "${show.name} - ${episode.title}",
                                    posterUrl = episode.coverUrl ?: show.posterUrl, streamUrl = episode.streamUrl,
                                    totalDurationMs = episode.durationSeconds.toLong() * 1000, seriesId = show.id,
                                    seasonNumber = episode.seasonNumber, episodeNumber = episode.episodeNumber,
                                    resumePositionMs = episode.watchProgress, lastWatchedAt = episode.lastWatchedAt)
                            }
                        }
                    }
                    if (candidates.isEmpty()) unmatched++
                    for (candidate in candidates) {
                        if (!sameAccount()) return@withLock TraktHistorySyncResult.Failure(TraktSyncFailure.ACCOUNT_CHANGED, imported)
                        when (val result = history.importTraktHistory(candidate, identity, match)) {
                            is Result.Success -> if (result.data) imported++ else unchanged++
                            else -> return@withLock TraktHistorySyncResult.Failure(TraktSyncFailure.IMPORT_FAILED, imported)
                        }
                    }
                }
                // Upload only the newest durable completion per exact media identity. Existing
                // Trakt entries (including imported entries) are reconciled before any POST.
                if (!sameAccount()) return@withLock TraktHistorySyncResult.Failure(
                    TraktSyncFailure.ACCOUNT_CHANGED, imported, uploaded)
                val firstLocalRows = history.getRecentlyWatched(limit = LOCAL_HISTORY_PAGE_SIZE + 1).first()
                var localRows = firstLocalRows.take(LOCAL_HISTORY_PAGE_SIZE)
                var localOffset = localRows.size
                var hasMoreLocalRows = firstLocalRows.size > LOCAL_HISTORY_PAGE_SIZE
                val localEpisodes = mutableMapOf<Long, List<com.streamvault.domain.model.Episode>>()
                val newestByIdentity = linkedMapOf<TraktMediaIdentity, TraktHistoryUpload>()
                var unresolvedLocal = 0
                while (localRows.isNotEmpty()) {
                    if (!sameAccount()) return@withLock TraktHistorySyncResult.Failure(
                        TraktSyncFailure.ACCOUNT_CHANGED, imported, uploaded)
                    val completedRows = localRows.filter { row ->
                        row.providerId > 0 && row.lastWatchedAt > 0 &&
                            row.watchedStatus in setOf(
                                PlaybackWatchedStatus.COMPLETED_AUTO,
                                PlaybackWatchedStatus.COMPLETED_MANUAL,
                            )
                    }
                    val movieIdsToResolve = completedRows
                        .filter { it.contentType == ContentType.MOVIE }
                        .map { it.contentId }
                        .distinct()
                    val localMovies = movieIdsToResolve.chunked(400)
                        .flatMap { movies.getByIdsSync(it) }
                        .associateBy { it.id }
                    val showIdsToResolve = completedRows
                        .filter { it.contentType == ContentType.SERIES_EPISODE }
                        .mapNotNull { it.seriesId }
                        .distinct()
                    val localShows = showIdsToResolve.chunked(400)
                        .flatMap { series.getByIdsSync(it) }
                        .map { it.toDomain() }
                        .associateBy { it.id }
                    for (row in completedRows) {
                        val identity = when (row.contentType) {
                            ContentType.MOVIE -> localMovies[row.contentId]?.toDomain()
                                ?.let(TraktMediaIdentityResolver::movie)
                            ContentType.SERIES_EPISODE -> {
                                val showId = row.seriesId
                                val show = showId?.let(localShows::get)
                                val episode = if (showId == null) null else (localEpisodes[showId]
                                    ?: episodes.getEntitiesBySeriesSync(showId).map { it.toDomain() }
                                        .also { localEpisodes[showId] = it })
                                    .firstOrNull { it.id == row.contentId && it.providerId == row.providerId }
                                if (show != null && episode != null) {
                                    TraktMediaIdentityResolver.episode(show, episode)
                                } else null
                            }
                            else -> null
                        }
                        if (identity == null) {
                            unresolvedLocal++
                        } else {
                            val upload = TraktHistoryUpload(identity, row.lastWatchedAt)
                            val existing = newestByIdentity[identity]
                            if (existing == null || upload.watchedAtMs > existing.watchedAtMs) {
                                newestByIdentity[identity] = upload
                            }
                        }
                    }

                    if (!hasMoreLocalRows) break
                    if (history.isIncognitoModeEnabled()) return@withLock TraktHistorySyncResult.Failure(
                        TraktSyncFailure.INCOGNITO, imported, uploaded)
                    localRows = history.getRecentlyWatchedPage(
                        offset = localOffset,
                        limit = LOCAL_HISTORY_PAGE_SIZE,
                    )
                    localOffset += localRows.size
                    hasMoreLocalRows = localRows.size == LOCAL_HISTORY_PAGE_SIZE
                }
                val missingEvents = newestByIdentity.values.filterNot { index.contains(it.media, it.watchedAtMs) }
                val uploadBatch = missingEvents.take(MAX_UPLOADS_PER_SYNC)
                if (!sameAccount()) return@withLock TraktHistorySyncResult.Failure(
                    TraktSyncFailure.ACCOUNT_CHANGED, imported, uploaded)
                if (history.isIncognitoModeEnabled()) return@withLock TraktHistorySyncResult.Failure(
                    TraktSyncFailure.INCOGNITO, imported, uploaded)
                if (uploadBatch.isNotEmpty()) {
                    when (val result = source.upload(owner, uploadBatch)) {
                        is TraktHistoryUploadResult.Success -> {
                            uploaded += result.uploaded
                            unmatched += result.unmatched + unresolvedLocal
                        }
                        TraktHistoryUploadResult.Disconnected -> return@withLock TraktHistorySyncResult.Failure(
                            TraktSyncFailure.DISCONNECTED, imported, uploaded)
                        TraktHistoryUploadResult.Failed -> return@withLock TraktHistorySyncResult.Failure(
                            TraktSyncFailure.UPLOAD_FAILED, imported, uploaded)
                    }
                } else {
                    unmatched += unresolvedLocal
                }
                TraktHistorySyncResult.Success(imported, unchanged, unmatched, skipped, uploaded,
                    moreUploads = missingEvents.size > MAX_UPLOADS_PER_SYNC)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                TraktHistorySyncResult.Failure(TraktSyncFailure.IMPORT_FAILED, imported, uploaded)
            }
        }
    }

    private companion object {
        const val LOCAL_HISTORY_PAGE_SIZE = 5_000
        const val MAX_UPLOADS_PER_SYNC = 100
    }
}
