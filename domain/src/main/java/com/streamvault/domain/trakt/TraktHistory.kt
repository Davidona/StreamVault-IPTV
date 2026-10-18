package com.streamvault.domain.trakt

import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.PlaybackWatchedStatus
import com.streamvault.domain.model.ContentType

data class TraktHistoryEntry(val historyId: Long, val media: TraktMediaIdentity, val watchedAtMs: Long) {
    init { require(historyId > 0 && watchedAtMs >= 0) }
}
data class TraktHistoryPage(val entries: List<TraktHistoryEntry>, val page: Int,
    val hasMore: Boolean, val skippedEntries: Int = 0)
sealed interface TraktHistoryResult {
    data class Success(val history: TraktHistoryPage) : TraktHistoryResult
    data object Disconnected : TraktHistoryResult
    data class Failure(val message: TraktMessage) : TraktHistoryResult
}
interface TraktHistorySource {
    suspend fun history(page: Int = 1): TraktHistoryResult
    suspend fun upload(accountUsername: String,
        events: List<TraktHistoryUpload>): TraktHistoryUploadResult = TraktHistoryUploadResult.Failed
}

enum class TraktSyncFailure { DISCONNECTED, ACCOUNT_CHANGED, READ_FAILED, READ_LIMIT, IMPORT_FAILED, UPLOAD_FAILED, INCOGNITO }
sealed interface TraktHistorySyncResult {
    data class Success(val imported: Int, val unchanged: Int, val unmatched: Int, val skipped: Int,
        val uploaded: Int = 0, val moreUploads: Boolean = false) : TraktHistorySyncResult
    data class Failure(val reason: TraktSyncFailure, val imported: Int = 0, val uploaded: Int = 0) : TraktHistorySyncResult
}
interface TraktHistorySync {
    suspend fun sync(): TraktHistorySyncResult
}

data class TraktHistoryUpload(val media: TraktMediaIdentity, val watchedAtMs: Long) {
    init { require(watchedAtMs > 0) }
}
sealed interface TraktHistoryUploadResult {
    data class Success(val uploaded: Int, val unmatched: Int = 0) : TraktHistoryUploadResult
    data object Disconnected : TraktHistoryUploadResult
    data object Failed : TraktHistoryUploadResult
}

data class TraktHistoryMatch(val media: TraktMediaIdentity, val watchedAtMs: Long, val playCount: Int)

/** Duplicate pages do not add plays. Conflicting identities for one history ID are discarded. */
class TraktHistoryIndex(entries: List<TraktHistoryEntry>) {
    private val matches = entries.groupBy { it.historyId }.values
        .filter { copies -> copies.map { it.media }.distinct().size == 1 }
        .map { copies -> copies.maxBy { it.watchedAtMs } }
        .groupBy { it.media }
        .mapValues { (media, plays) -> TraktHistoryMatch(media, plays.maxOf { it.watchedAtMs }, plays.size) }

    fun find(media: TraktMediaIdentity): TraktHistoryMatch? = matches[media]

    /** Trakt now normalizes watched_at to minute precision, so compare at that precision. */
    fun contains(media: TraktMediaIdentity, watchedAtMs: Long): Boolean =
        matches[media]?.watchedAtMs?.let { it >= watchedAtMs / 60_000 * 60_000 } == true
}

/** Produces an import candidate only; callers must explicitly choose to apply it. */
object TraktHistoryMergePolicy {
    fun importCandidate(local: PlaybackHistory, identity: TraktMediaIdentity, match: TraktHistoryMatch?): PlaybackHistory? {
        val correctKind = when (identity) {
            is TraktMediaIdentity.MovieId -> local.contentType == ContentType.MOVIE
            is TraktMediaIdentity.EpisodeId -> local.contentType == ContentType.SERIES_EPISODE &&
                local.seasonNumber == identity.season && local.episodeNumber == identity.number
        }
        if (!correctKind || match?.media != identity || local.watchedStatus == PlaybackWatchedStatus.COMPLETED_MANUAL ||
            local.lastWatchedAt > match.watchedAtMs) return null
        val candidate = local.copy(
            // Existing catalog projections use playback position to display completion.
            resumePositionMs = maxOf(local.resumePositionMs, local.totalDurationMs),
            watchedStatus = PlaybackWatchedStatus.COMPLETED_AUTO,
            lastWatchedAt = maxOf(local.lastWatchedAt, match.watchedAtMs),
            watchCount = maxOf(local.watchCount, match.playCount),
        )
        return candidate.takeIf { it != local }
    }
}
