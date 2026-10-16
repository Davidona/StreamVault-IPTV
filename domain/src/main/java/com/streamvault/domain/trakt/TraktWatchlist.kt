package com.streamvault.domain.trakt

import kotlinx.coroutines.flow.StateFlow

enum class TraktWatchlistType(val apiPath: String, val payloadKey: String) {
    MOVIE("movies", "movies"),
    SHOW("shows", "shows"),
}

data class TraktWatchlistIdentity(val type: TraktWatchlistType, val tmdbId: Long) {
    init { require(tmdbId > 0) }
}

data class TraktWatchlistPage(
    val items: List<TraktWatchlistIdentity>,
    val page: Int,
    val hasMore: Boolean,
    val skippedItems: Int = 0,
)

enum class TraktWatchlistFailure {
    DISCONNECTED,
    INCOGNITO,
    ACCOUNT_CHANGED,
    READ_LIMIT,
    REQUEST_FAILED,
}

sealed interface TraktWatchlistPageResult {
    data class Success(val page: TraktWatchlistPage) : TraktWatchlistPageResult
    data object Disconnected : TraktWatchlistPageResult
    data object Failed : TraktWatchlistPageResult
}

sealed interface TraktWatchlistWriteResult {
    data object Success : TraktWatchlistWriteResult
    data object Disconnected : TraktWatchlistWriteResult
    data object Failed : TraktWatchlistWriteResult
}

data class TraktWatchlistState(
    val accountUsername: String? = null,
    val connected: Boolean = false,
    val items: Set<TraktWatchlistIdentity> = emptySet(),
    val loadedAtMs: Long? = null,
    val isRefreshing: Boolean = false,
    val updating: Set<TraktWatchlistIdentity> = emptySet(),
    val failure: TraktWatchlistFailure? = null,
) {
    val loaded: Boolean get() = loadedAtMs != null
    fun contains(identity: TraktWatchlistIdentity): Boolean = identity in items
}

sealed interface TraktWatchlistRefreshResult {
    data class Success(val itemCount: Int, val skipped: Int = 0) : TraktWatchlistRefreshResult
    data class Failure(val reason: TraktWatchlistFailure) : TraktWatchlistRefreshResult
}

sealed interface TraktWatchlistActionResult {
    data object Success : TraktWatchlistActionResult
    data class Failure(val reason: TraktWatchlistFailure) : TraktWatchlistActionResult
}

/** Trakt watchlist operations are explicit and remain scoped to the signed-in account. */
interface TraktWatchlist {
    val state: StateFlow<TraktWatchlistState>
    suspend fun refresh(force: Boolean = false): TraktWatchlistRefreshResult
    suspend fun setWatchlisted(identity: TraktWatchlistIdentity, watchlisted: Boolean): TraktWatchlistActionResult
}

/** Authenticated transport exposed by the account service to the watchlist repository. */
interface TraktWatchlistSource {
    suspend fun watchlist(type: TraktWatchlistType, page: Int): TraktWatchlistPageResult
    suspend fun setWatchlisted(identity: TraktWatchlistIdentity, watchlisted: Boolean): TraktWatchlistWriteResult
}
