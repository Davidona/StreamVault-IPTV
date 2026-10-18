package com.streamvault.domain.trakt

/** The movie/show identities accepted by Trakt's personal favorites sync endpoints. */
enum class TraktFavoriteType(val apiPath: String, val payloadKey: String, val responseKey: String) {
    MOVIE("movies", "movies", "movie"),
    SHOW("shows", "shows", "show"),
}

data class TraktFavoriteIdentity(val type: TraktFavoriteType, val tmdbId: Long) {
    init { require(tmdbId > 0) }
}

enum class TraktFavoriteFailure {
    DISCONNECTED,
    INCOGNITO,
    ACCOUNT_CHANGED,
    NO_ACTIVE_PROVIDER,
    READ_LIMIT,
    REQUEST_FAILED,
}

data class TraktFavoritePage(
    val identities: List<TraktFavoriteIdentity>,
    val page: Int,
    val hasMore: Boolean,
    val skippedItems: Int = 0,
)

sealed interface TraktFavoritePageResult {
    data class Success(val page: TraktFavoritePage) : TraktFavoritePageResult
    data object Disconnected : TraktFavoritePageResult
    data object Failed : TraktFavoritePageResult
}

sealed interface TraktFavoriteImportResult {
    data class Success(val imported: Int, val alreadyPresent: Int, val unmatched: Int, val skipped: Int) : TraktFavoriteImportResult
    data class Failure(val reason: TraktFavoriteFailure, val imported: Int = 0) : TraktFavoriteImportResult
}

sealed interface TraktFavoriteActionResult {
    data object Success : TraktFavoriteActionResult
    data class Failure(val reason: TraktFavoriteFailure) : TraktFavoriteActionResult
}

sealed interface TraktFavoriteWriteResult {
    data object Success : TraktFavoriteWriteResult
    data object Disconnected : TraktFavoriteWriteResult
    data object Failed : TraktFavoriteWriteResult
}

/** Explicit write-through for StreamVault's movie/show heart action. */
interface TraktFavorites {
    suspend fun setFavorited(identity: TraktFavoriteIdentity, favorited: Boolean): TraktFavoriteActionResult
}

/** Authenticated transport used by the Trakt account service. */
interface TraktFavoritesSource {
    suspend fun favorites(type: TraktFavoriteType, page: Int): TraktFavoritePageResult =
        TraktFavoritePageResult.Failed
    suspend fun setFavorited(identity: TraktFavoriteIdentity, favorited: Boolean): TraktFavoriteWriteResult
}

/** Explicitly imports existing Trakt hearts into the currently active provider's local Favorites. */
interface TraktFavoriteImporter {
    suspend fun importToLocal(): TraktFavoriteImportResult
}
