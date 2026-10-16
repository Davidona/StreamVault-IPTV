package com.streamvault.domain.trakt

enum class TraktRatingType(val apiPath: String, val payloadKey: String, val responseKey: String) {
    MOVIE("movies", "movies", "movie"),
    SHOW("shows", "shows", "show"),
}

data class TraktRatingIdentity(val type: TraktRatingType, val tmdbId: Long) {
    init { require(tmdbId > 0L) }
}

enum class TraktRatingFailure {
    DISCONNECTED,
    INCOGNITO,
    ACCOUNT_CHANGED,
    READ_LIMIT,
    REQUEST_FAILED,
}

data class TraktRatingPage(
    val items: List<Pair<TraktRatingIdentity, Int>>,
    val page: Int,
    val hasMore: Boolean,
    val skippedItems: Int = 0,
)

sealed interface TraktRatingPageResult {
    data class Success(val page: TraktRatingPage) : TraktRatingPageResult
    data object Disconnected : TraktRatingPageResult
    data object Failed : TraktRatingPageResult
}

data class TraktRatingsState(
    val accountUsername: String? = null,
    val connected: Boolean = false,
    val ratings: Map<TraktRatingIdentity, Int> = emptyMap(),
    val loadedTypes: Set<TraktRatingType> = emptySet(),
    val loadedAtMs: Map<TraktRatingType, Long> = emptyMap(),
    val refreshing: Set<TraktRatingType> = emptySet(),
    val updating: Set<TraktRatingIdentity> = emptySet(),
    val failure: TraktRatingFailure? = null,
) {
    fun rating(identity: TraktRatingIdentity): Int? = ratings[identity]
    fun isLoaded(type: TraktRatingType): Boolean = type in loadedTypes
}

sealed interface TraktRatingRefreshResult {
    data class Success(val itemCount: Int, val skipped: Int = 0) : TraktRatingRefreshResult
    data class Failure(val reason: TraktRatingFailure) : TraktRatingRefreshResult
}

sealed interface TraktRatingActionResult {
    data object Success : TraktRatingActionResult
    data class Failure(val reason: TraktRatingFailure) : TraktRatingActionResult
}

sealed interface TraktRatingWriteResult {
    data object Success : TraktRatingWriteResult
    data object Disconnected : TraktRatingWriteResult
    data object Failed : TraktRatingWriteResult
}

/** Explicit user ratings for exact TMDB-matched movies and shows. */
interface TraktRatings {
    val state: kotlinx.coroutines.flow.StateFlow<TraktRatingsState>
    suspend fun refresh(type: TraktRatingType, force: Boolean = false): TraktRatingRefreshResult
    suspend fun setRating(identity: TraktRatingIdentity, rating: Int): TraktRatingActionResult
}

/** Authenticated transport exposed by the Trakt account service. */
interface TraktRatingsSource {
    suspend fun ratings(type: TraktRatingType, page: Int): TraktRatingPageResult
    suspend fun setRating(identity: TraktRatingIdentity, rating: Int): TraktRatingWriteResult
}
