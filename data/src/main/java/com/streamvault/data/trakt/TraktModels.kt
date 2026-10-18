package com.streamvault.data.trakt

import com.google.gson.annotations.SerializedName
import com.streamvault.domain.trakt.TraktFavoriteIdentity
import com.streamvault.domain.trakt.TraktFavoritePage
import com.streamvault.domain.trakt.TraktFavoriteType
import com.streamvault.domain.trakt.TraktUserProfile
import com.streamvault.domain.trakt.TraktRatingIdentity
import com.streamvault.domain.trakt.TraktRatingPage
import com.streamvault.domain.trakt.TraktRatingType
import com.streamvault.domain.trakt.TraktCustomListItemIdentity
import com.streamvault.domain.trakt.TraktCustomListItemType
import com.streamvault.domain.trakt.TraktCustomListItemsPage
import com.streamvault.domain.trakt.TraktCustomListsPageResult
import com.streamvault.domain.trakt.TraktDiscoveryCategory
import com.streamvault.domain.trakt.TraktDiscoveryPage
import java.net.URI

class TraktConfig(val clientId: String, val redirectUri: String) {
    val configured: Boolean get() = clientId.isNotBlank() && runCatching {
        val uri = URI(redirectUri)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.host != "localhost" &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.port == -1 && !uri.path.isNullOrBlank()
    }.getOrDefault(false)
}

// Deliberately avoid data-class toString() on credential-bearing types.
class TraktTokens(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("expires_in") val expiresIn: Long,
) {
    fun usable(now: Long): Boolean = accessToken.isNotBlank() && refreshToken.isNotBlank() &&
        expiresIn > 0 && createdAt > 0 && now < createdAt + expiresIn - 60
}

class TraktPendingLogin(
    @SerializedName("verifier") val verifier: String,
    @SerializedName("state") val state: String,
    @SerializedName("created_at") val createdAt: Long,
)
class TraktStoredSession(
    @SerializedName("tokens") val tokens: TraktTokens? = null,
    @SerializedName("pending") val pending: TraktPendingLogin? = null,
)

interface TraktTokenStore {
    fun read(): TraktStoredSession
    /** Replace the entire record atomically, including both rotating tokens. */
    fun write(session: TraktStoredSession)
}

class TraktApiException(val status: Int, val oauthError: String? = null, val retryAfterSeconds: Long? = null) :
    Exception("Trakt request failed (HTTP $status)")

interface TraktApi {
    suspend fun exchange(code: String, verifier: String): TraktTokens
    suspend fun refresh(refreshToken: String): TraktTokens
    suspend fun username(accessToken: String): String
    suspend fun profile(accessToken: String): TraktUserProfile = TraktUserProfile(username(accessToken))
}

interface TraktPlaybackApi {
    /** False means metadata did not resolve unambiguously; no scrobble was submitted. */
    suspend fun scrobble(accessToken: String, event: com.streamvault.domain.trakt.TraktScrobbleEvent): Boolean
}

interface TraktHistoryApi {
    suspend fun history(accessToken: String, page: Int): com.streamvault.domain.trakt.TraktHistoryPage
    suspend fun upload(accessToken: String,
        events: List<com.streamvault.domain.trakt.TraktHistoryUpload>): com.streamvault.domain.trakt.TraktHistoryUploadResult
}

interface TraktWatchlistApi {
    suspend fun watchlist(accessToken: String, type: com.streamvault.domain.trakt.TraktWatchlistType,
        page: Int): com.streamvault.domain.trakt.TraktWatchlistPage
    /** A successful response means Trakt processed the request; transport failures are not retried. */
    suspend fun setWatchlisted(accessToken: String,
        identity: com.streamvault.domain.trakt.TraktWatchlistIdentity, watchlisted: Boolean)
}

interface TraktFavoritesApi {
    suspend fun favorites(accessToken: String, type: TraktFavoriteType, page: Int): TraktFavoritePage =
        TraktFavoritePage(emptyList(), page, hasMore = false)
    /** A successful response means Trakt processed the favorite add/remove request. */
    suspend fun setFavorited(accessToken: String, identity: TraktFavoriteIdentity, favorited: Boolean)
}

interface TraktRatingsApi {
    suspend fun ratings(accessToken: String, type: TraktRatingType, page: Int): TraktRatingPage
    /** A successful response means Trakt accepted or replaced the user's rating. */
    suspend fun setRating(accessToken: String, identity: TraktRatingIdentity, rating: Int)
}

interface TraktCustomListsApi {
    suspend fun lists(accessToken: String): List<com.streamvault.domain.trakt.TraktCustomList>
    suspend fun items(
        accessToken: String,
        listId: Long,
        type: TraktCustomListItemType,
        page: Int,
    ): TraktCustomListItemsPage
    suspend fun createList(accessToken: String, name: String, description: String?): com.streamvault.domain.trakt.TraktCustomList
    suspend fun deleteList(accessToken: String, listId: Long)
    suspend fun reorderLists(accessToken: String, listIds: List<Long>)
    suspend fun reorderItems(accessToken: String, listId: Long, listItemIds: List<Long>)
    suspend fun setItem(accessToken: String, listId: Long, identity: TraktCustomListItemIdentity, included: Boolean)
}

interface TraktDiscoveryApi {
    /** Public endpoint: only the Trakt Client ID header is required. */
    suspend fun discover(category: TraktDiscoveryCategory, page: Int): TraktDiscoveryPage
}
