package com.streamvault.data.sync

import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Owns the playlist provider's catalog import policy.
 *
 * The manager still supplies shared guide/job callbacks, but playlist admission,
 * metadata updates, and playlist-provided EPG source assignment live here.
 */
internal class M3uCatalogSyncExecutor(
    private val importer: SyncManagerM3uImporter,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val epgSourceRepository: EpgSourceRepository,
    private val countPrograms: suspend (Long) -> Int,
    private val shouldSyncEpgUpfront: (Provider) -> Boolean,
    private val updateEpgJobState: suspend (
        Provider,
        String,
        Long,
        Int?,
        Long?,
        Long?,
        String?
    ) -> Unit,
    private val refreshGuide: suspend (
        Provider,
        SyncMetadata,
        Long,
        Boolean,
        ((String) -> Unit)?
    ) -> ProviderGuideSyncResult,
    private val markBackgroundEpgQueued: suspend (Long, Long) -> Unit,
    private val progress: (Long, ((String) -> Unit)?, String) -> Unit,
    private val sanitizeThrowableMessage: (Throwable) -> String
) {
    suspend fun refreshCatalog(provider: Provider, live: Boolean, movies: Boolean): SyncOutcome {
        val stats = withContext(Dispatchers.IO) { importer.importPlaylist(provider, null, includeLive = live, includeMovies = movies) }
        if (stats.liveCount == 0 && stats.movieCount == 0) throw IllegalStateException("Playlist contains no supported entries for the selected sections")
        val now = System.currentTimeMillis()
        val metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        syncMetadataRepository.updateMetadata(metadata.copy(
            lastLiveSync = if (live) now else metadata.lastLiveSync,
            lastLiveSuccess = if (live) now else metadata.lastLiveSuccess,
            liveCount = if (live) stats.liveCount else metadata.liveCount,
            lastMovieSync = if (movies) now else metadata.lastMovieSync,
            lastMovieSuccess = if (movies) now else metadata.lastMovieSuccess,
            movieCount = if (movies) stats.movieCount else metadata.movieCount
        ))
        if (provider.epgUrl.isBlank()) assignPlaylistEpgSources(provider, stats, mutableListOf())
        return SyncOutcome(partial = stats.warnings.isNotEmpty(), warnings = stats.warnings,
            activation = SyncActivation.ACTIVATED_CATALOG)
    }

    suspend fun syncFull(
        provider: Provider,
        force: Boolean,
        onProgress: ((String) -> Unit)?,
        afterCatalogApply: suspend () -> Unit = {}
    ): SyncOutcome {
        val warnings = mutableListOf<String>()
        val continuationWork = mutableListOf<SyncContinuation>()
        var stagedMutations = 0
        var metadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val now = System.currentTimeMillis()

        if (force || ContentCachePolicy.shouldRefresh(metadata.lastLiveSuccess, ContentCachePolicy.CATALOG_TTL_MILLIS, now)) {
            val stats = withContext(Dispatchers.IO) {
                importer.importPlaylist(provider, onProgress, afterCatalogApply = afterCatalogApply)
            }
            if (stats.liveCount == 0 && stats.movieCount == 0) {
                throw IllegalStateException("Playlist is empty or contains no supported entries")
            }
            stagedMutations = stats.liveCount + stats.movieCount
            warnings += stats.warnings
            if (provider.epgUrl.isBlank()) {
                assignPlaylistEpgSources(provider, stats, warnings)
            }
            metadata = metadata.copy(
                lastLiveSync = now,
                lastLiveSuccess = now,
                lastMovieSync = now,
                lastSeriesSync = now,
                lastSeriesSuccess = now,
                lastMovieAttempt = now,
                lastMovieSuccess = now,
                liveCount = stats.liveCount,
                movieCount = stats.movieCount,
                movieSyncMode = VodSyncMode.FULL,
                movieWarningsCount = stats.warnings.size,
                movieCatalogStale = false,
                movieHealthySyncStreak = 0
            )
            syncMetadataRepository.updateMetadata(metadata)
        }

        if (shouldSyncEpgUpfront(provider)) {
            updateEpgJobState(provider, "RUNNING", now, null, now, null, null)
            val epgResult = refreshGuide(provider, metadata, now, force, onProgress)
            val finishedAt = System.currentTimeMillis()
            updateEpgJobState(
                provider,
                when {
                    epgResult.warnings.isEmpty() -> "SUCCESS"
                    epgResult.hasRetryableFailure -> "FAILED_RETRYABLE"
                    else -> "PARTIAL"
                },
                finishedAt,
                countPrograms(provider.id),
                now,
                finishedAt.takeIf { epgResult.warnings.isEmpty() },
                epgResult.warnings.takeIf { it.isNotEmpty() }?.joinToString("; ")
            )
            warnings += epgResult.warnings
        } else if (provider.epgSyncMode != ProviderEpgSyncMode.SKIP) {
            markBackgroundEpgQueued(provider.id, now)
            continuationWork += SyncContinuation(
                operation = SyncContinuationOperation.REFRESH_GUIDE,
                reason = "playlist guide refresh was handed off to background work",
                force = force
            )
        }

        return SyncOutcome(
            partial = warnings.isNotEmpty(),
            warnings = warnings.distinct(),
            stagedMutations = stagedMutations,
            continuationWork = continuationWork,
            activation = if (stagedMutations > 0) {
                SyncActivation.ACTIVATED_CATALOG
            } else {
                SyncActivation.NO_CATALOG_CHANGE
            }
        )
    }

    suspend fun syncLive(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val now = System.currentTimeMillis()
        progress(provider.id, onProgress, "Retrying Live TV...")
        val stats = withContext(Dispatchers.IO) {
            importer.importPlaylist(provider, onProgress, includeLive = true, includeMovies = false)
        }
        if (stats.liveCount == 0) {
            throw IllegalStateException("Playlist contains no live TV entries")
        }
        val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
            .copy(lastLiveSync = now, lastLiveSuccess = now, liveCount = stats.liveCount)
        syncMetadataRepository.updateMetadata(metadata)
        return SyncOutcome(
            partial = stats.warnings.isNotEmpty(),
            warnings = stats.warnings.distinct(),
            stagedMutations = stats.liveCount,
            activation = SyncActivation.ACTIVATED_CATALOG
        )
    }

    suspend fun syncMovies(
        provider: Provider,
        onProgress: ((String) -> Unit)?
    ): SyncOutcome {
        val now = System.currentTimeMillis()
        progress(provider.id, onProgress, "Retrying Movies...")
        val stats = withContext(Dispatchers.IO) {
            importer.importPlaylist(provider, onProgress, includeLive = false, includeMovies = true)
        }
        if (stats.movieCount == 0) {
            throw IllegalStateException("Playlist contains no movie entries")
        }
        val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id))
            .copy(
                lastMovieSync = now,
                lastMovieAttempt = now,
                lastMovieSuccess = now,
                movieCount = stats.movieCount,
                movieSyncMode = VodSyncMode.FULL,
                movieWarningsCount = stats.warnings.size,
                movieCatalogStale = false
            )
        syncMetadataRepository.updateMetadata(metadata)
        return SyncOutcome(
            partial = stats.warnings.isNotEmpty(),
            warnings = stats.warnings.distinct(),
            stagedMutations = stats.movieCount,
            activation = SyncActivation.ACTIVATED_CATALOG
        )
    }

    private suspend fun assignPlaylistEpgSources(
        provider: Provider,
        stats: M3uImportStats,
        warnings: MutableList<String>
    ) {
        val existingSourcesByUrl = epgSourceRepository.getAllSources().first().associateBy { it.url }
        val assignedSourceIds = epgSourceRepository.getAssignmentsForProvider(provider.id)
            .first()
            .mapTo(mutableSetOf()) { it.epgSourceId }
        stats.header.tvgUrls.forEachIndexed { priority, url ->
            try {
                val source = existingSourcesByUrl[url] ?: when (
                    val addResult = epgSourceRepository.addSource("Playlist EPG ${priority + 1}", url)
                ) {
                    is Result.Success -> addResult.data
                    is Result.Error -> {
                        warnings += "Could not add playlist EPG source ${priority + 1}: ${addResult.message}"
                        null
                    }
                    Result.Loading -> null
                }
                if (source != null && assignedSourceIds.add(source.id)) {
                    when (val assignment = epgSourceRepository.assignSourceToProvider(provider.id, source.id, priority)) {
                        is Result.Error -> warnings += "Could not assign playlist EPG source ${priority + 1}: ${assignment.message}"
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warnings += "Could not configure playlist EPG source ${priority + 1}: ${sanitizeThrowableMessage(e)}"
            }
        }
    }
}
