package com.streamvault.data.trakt

import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.domain.trakt.TraktAccountState
import com.streamvault.domain.trakt.TraktMessage
import com.streamvault.domain.trakt.TraktScrobbler
import com.streamvault.domain.trakt.TraktScrobbleEvent
import com.streamvault.domain.trakt.TraktScrobbleAction
import com.streamvault.domain.trakt.TraktScrobbleOutcome
import com.streamvault.domain.trakt.TraktHistorySource
import com.streamvault.domain.trakt.TraktHistoryResult
import com.streamvault.domain.trakt.TraktHistoryUpload
import com.streamvault.domain.trakt.TraktHistoryUploadResult
import com.streamvault.domain.trakt.TraktWatchlistIdentity
import com.streamvault.domain.trakt.TraktWatchlistPageResult
import com.streamvault.domain.trakt.TraktWatchlistSource
import com.streamvault.domain.trakt.TraktWatchlistType
import com.streamvault.domain.trakt.TraktWatchlistWriteResult
import com.streamvault.domain.trakt.TraktFavoriteIdentity
import com.streamvault.domain.trakt.TraktFavoritePage
import com.streamvault.domain.trakt.TraktFavoritePageResult
import com.streamvault.domain.trakt.TraktFavoriteType
import com.streamvault.domain.trakt.TraktFavoritesSource
import com.streamvault.domain.trakt.TraktFavoriteWriteResult
import com.streamvault.domain.trakt.TraktRatingIdentity
import com.streamvault.domain.trakt.TraktRatingPageResult
import com.streamvault.domain.trakt.TraktRatingType
import com.streamvault.domain.trakt.TraktRatingsSource
import com.streamvault.domain.trakt.TraktRatingWriteResult
import com.streamvault.domain.trakt.TraktCustomListItemIdentity
import com.streamvault.domain.trakt.TraktCustomListItemType
import com.streamvault.domain.trakt.TraktCustomListItemsPageResult
import com.streamvault.domain.trakt.TraktCustomListsPageResult
import com.streamvault.domain.trakt.TraktCustomListsSource
import com.streamvault.domain.trakt.TraktCustomListWriteResult
import com.streamvault.domain.trakt.TraktDiscoveryCategory
import com.streamvault.domain.trakt.TraktDiscoveryPage
import com.streamvault.domain.trakt.TraktDiscoverySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

class TraktAuthService(
    private val config: TraktConfig,
    private val store: TraktTokenStore,
    private val api: TraktApi,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : TraktAccount, TraktScrobbler, TraktHistorySource, TraktWatchlistSource, TraktFavoritesSource,
    TraktRatingsSource, TraktCustomListsSource, TraktDiscoverySource {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(TraktAccountState(configured = config.configured))
    override val state = mutableState.asStateFlow()

    override suspend fun load(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val session = store.read()
                mutableState.value = TraktAccountState(config.configured, session.tokens != null,
                    connecting = session.pending?.let { now() - it.createdAt in 0..600 } == true)
                if (session.tokens != null) updateProfile()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = TraktAccountState(config.configured, message = TraktMessage.RECONNECT_REQUIRED)
            }
        }
    }

    override suspend fun beginSignIn(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(config.configured) { "Trakt configuration required" }
            val pending = TraktPendingLogin(TraktPkce.randomValue(), TraktPkce.randomValue(), now())
            store.write(TraktStoredSession(pending = pending))
            mutableState.value = TraktAccountState(configured = true, connecting = true)
            val parameters = linkedMapOf(
                "client_id" to config.clientId, "redirect_uri" to config.redirectUri,
                "response_type" to "code", "state" to pending.state,
                "code_challenge" to TraktPkce.challenge(pending.verifier), "code_challenge_method" to "S256",
            )
            "https://auth.trakt.tv/oauth/authorize?" + parameters.entries.joinToString("&") {
                "${encode(it.key)}=${encode(it.value)}"
            }
        }
    }

    override suspend fun completeSignIn(callback: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                check(config.configured)
                val pending = store.read().pending ?: return@withLock
                // Consume once before network I/O, including rejected and cancelled callbacks.
                store.write(TraktStoredSession())
                val actual = URI(callback)
                val expected = URI(config.redirectUri)
                require(actual.scheme == expected.scheme && actual.rawAuthority == expected.rawAuthority &&
                    actual.rawPath == expected.rawPath && actual.rawFragment == null)
                val parameters = actual.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map {
                    val pair = it.split('=', limit = 2)
                    decode(pair[0]) to decode(pair.getOrElse(1) { "" })
                }
                require(parameters.map { it.first }.distinct().size == parameters.size)
                val values = parameters.toMap()
                require(now() - pending.createdAt in 0..600)
                require(MessageDigest.isEqual(pending.state.toByteArray(), values["state"].orEmpty().toByteArray()))
                if (values["error"] == "access_denied") {
                    mutableState.value = TraktAccountState(configured = true)
                    return@withLock
                }
                require(values["error"] == null)
                val code = values["code"]?.takeIf(String::isNotBlank) ?: error("Missing authorization code")
                val tokens = api.exchange(code, pending.verifier)
                store.write(TraktStoredSession(tokens))
                mutableState.value = TraktAccountState(configured = true, connected = true)
                updateProfile()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = TraktAccountState(config.configured, message = message(failure, TraktMessage.SIGN_IN_FAILED))
            }
        }
    }

    override suspend fun cancelSignIn(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = store.read()
            store.write(TraktStoredSession(session.tokens))
            mutableState.value = mutableState.value.copy(connecting = false)
        }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            store.write(TraktStoredSession())
            mutableState.value = TraktAccountState(config.configured)
        }
    }

    override suspend fun refreshProfile(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { updateProfile() }
    }

    override suspend fun history(page: Int): TraktHistoryResult = withContext(Dispatchers.IO) {
        require(page in 1..10000)
        mutex.withLock {
            try {
                var tokens = store.read().tokens ?: return@withLock TraktHistoryResult.Disconnected
                val historyApi = api as? TraktHistoryApi
                    ?: return@withLock TraktHistoryResult.Failure(TraktMessage.NETWORK_ERROR)
                var refreshed = false
                if (!tokens.usable(now())) {
                    tokens = refresh(tokens)
                    refreshed = true
                }
                val result = try {
                    historyApi.history(tokens.accessToken, page)
                } catch (failure: TraktApiException) {
                    if (failure.status != 401 || refreshed) throw failure
                    tokens = refresh(tokens)
                    historyApi.history(tokens.accessToken, page)
                }
                TraktHistoryResult.Success(result)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is TraktApiException && failure.status == 401) {
                    store.write(TraktStoredSession())
                    mutableState.value = TraktAccountState(config.configured, message = TraktMessage.RECONNECT_REQUIRED)
                }
                TraktHistoryResult.Failure(if (mutableState.value.message == TraktMessage.RECONNECT_REQUIRED)
                    TraktMessage.RECONNECT_REQUIRED else message(failure, TraktMessage.NETWORK_ERROR))
            }
        }
    }

    override suspend fun upload(accountUsername: String,
        events: List<TraktHistoryUpload>): TraktHistoryUploadResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val accountState = mutableState.value
                if (!accountState.connected || accountState.username != accountUsername) {
                    return@withLock TraktHistoryUploadResult.Disconnected
                }
                var tokens = store.read().tokens ?: return@withLock TraktHistoryUploadResult.Disconnected
                val historyApi = api as? TraktHistoryApi ?: return@withLock TraktHistoryUploadResult.Failed
                var refreshed = false
                if (!tokens.usable(now())) {
                    tokens = refresh(tokens)
                    refreshed = true
                }
                try {
                    historyApi.upload(tokens.accessToken, events)
                } catch (failure: TraktApiException) {
                    if (failure.status != 401 || refreshed) throw failure
                    tokens = refresh(tokens)
                    historyApi.upload(tokens.accessToken, events)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is TraktApiException && failure.status == 401) {
                    store.write(TraktStoredSession())
                    mutableState.value = TraktAccountState(config.configured, message = TraktMessage.RECONNECT_REQUIRED)
                    return@withLock TraktHistoryUploadResult.Disconnected
                }
                TraktHistoryUploadResult.Failed
            }
        }
    }

    override suspend fun watchlist(type: TraktWatchlistType, page: Int): TraktWatchlistPageResult =
        withContext(Dispatchers.IO) {
            require(page in 1..10000)
            mutex.withLock {
                try {
                    var tokens = store.read().tokens ?: return@withLock TraktWatchlistPageResult.Disconnected
                    val watchlistApi = api as? TraktWatchlistApi
                        ?: return@withLock TraktWatchlistPageResult.Failed
                    var refreshed = false
                    if (!tokens.usable(now())) {
                        tokens = refresh(tokens)
                        refreshed = true
                    }
                    val result = try {
                        watchlistApi.watchlist(tokens.accessToken, type, page)
                    } catch (failure: TraktApiException) {
                        if (failure.status != 401 || refreshed) throw failure
                        tokens = refresh(tokens)
                        watchlistApi.watchlist(tokens.accessToken, type, page)
                    }
                    TraktWatchlistPageResult.Success(result)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (failure is TraktApiException && failure.status == 401) {
                        store.write(TraktStoredSession())
                        mutableState.value = TraktAccountState(config.configured,
                            message = TraktMessage.RECONNECT_REQUIRED)
                        return@withLock TraktWatchlistPageResult.Disconnected
                    }
                    TraktWatchlistPageResult.Failed
                }
            }
        }

    override suspend fun discover(category: TraktDiscoveryCategory, page: Int): TraktDiscoveryPage {
        val discoveryApi = api as? TraktDiscoveryApi ?: error("Trakt discovery API is unavailable")
        return discoveryApi.discover(category, page)
    }

    override suspend fun setWatchlisted(
        identity: TraktWatchlistIdentity,
        watchlisted: Boolean,
    ): TraktWatchlistWriteResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                var tokens = store.read().tokens ?: return@withLock TraktWatchlistWriteResult.Disconnected
                val watchlistApi = api as? TraktWatchlistApi
                    ?: return@withLock TraktWatchlistWriteResult.Failed
                var refreshed = false
                if (!tokens.usable(now())) {
                    tokens = refresh(tokens)
                    refreshed = true
                }
                try {
                    watchlistApi.setWatchlisted(tokens.accessToken, identity, watchlisted)
                } catch (failure: TraktApiException) {
                    if (failure.status != 401 || refreshed) throw failure
                    tokens = refresh(tokens)
                    watchlistApi.setWatchlisted(tokens.accessToken, identity, watchlisted)
                }
                TraktWatchlistWriteResult.Success
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is TraktApiException && failure.status == 401) {
                    store.write(TraktStoredSession())
                    mutableState.value = TraktAccountState(config.configured,
                        message = TraktMessage.RECONNECT_REQUIRED)
                    return@withLock TraktWatchlistWriteResult.Disconnected
                }
                // A write can succeed before a timeout. Do not retry it automatically.
                TraktWatchlistWriteResult.Failed
            }
        }
    }

    override suspend fun setFavorited(
        identity: TraktFavoriteIdentity,
        favorited: Boolean,
    ): TraktFavoriteWriteResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                var tokens = store.read().tokens ?: return@withLock TraktFavoriteWriteResult.Disconnected
                val favoritesApi = api as? TraktFavoritesApi ?: return@withLock TraktFavoriteWriteResult.Failed
                var refreshed = false
                if (!tokens.usable(now())) {
                    tokens = refresh(tokens)
                    refreshed = true
                }
                try {
                    favoritesApi.setFavorited(tokens.accessToken, identity, favorited)
                } catch (failure: TraktApiException) {
                    if (failure.status != 401 || refreshed) throw failure
                    tokens = refresh(tokens)
                    favoritesApi.setFavorited(tokens.accessToken, identity, favorited)
                }
                TraktFavoriteWriteResult.Success
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is TraktApiException && failure.status == 401) {
                    store.write(TraktStoredSession())
                    mutableState.value = TraktAccountState(config.configured,
                        message = TraktMessage.RECONNECT_REQUIRED)
                    return@withLock TraktFavoriteWriteResult.Disconnected
                }
                // Do not automatically retry a request that might have succeeded before a timeout.
                TraktFavoriteWriteResult.Failed
            }
        }
    }

    override suspend fun favorites(type: TraktFavoriteType, page: Int): TraktFavoritePageResult =
        withContext(Dispatchers.IO) {
            require(page in 1..10000)
            mutex.withLock {
                try {
                    var tokens = store.read().tokens ?: return@withLock TraktFavoritePageResult.Disconnected
                    val favoritesApi = api as? TraktFavoritesApi ?: return@withLock TraktFavoritePageResult.Failed
                    var refreshed = false
                    if (!tokens.usable(now())) {
                        tokens = refresh(tokens)
                        refreshed = true
                    }
                    val result = try {
                        favoritesApi.favorites(tokens.accessToken, type, page)
                    } catch (failure: TraktApiException) {
                        if (failure.status != 401 || refreshed) throw failure
                        tokens = refresh(tokens)
                        favoritesApi.favorites(tokens.accessToken, type, page)
                    }
                    TraktFavoritePageResult.Success(result)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (failure is TraktApiException && failure.status == 401) {
                        store.write(TraktStoredSession())
                        mutableState.value = TraktAccountState(config.configured,
                            message = TraktMessage.RECONNECT_REQUIRED)
                        return@withLock TraktFavoritePageResult.Disconnected
                    }
                    TraktFavoritePageResult.Failed
                }
            }
        }

    override suspend fun setRating(
        identity: TraktRatingIdentity,
        rating: Int,
    ): TraktRatingWriteResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                require(rating in 1..10)
                var tokens = store.read().tokens ?: return@withLock TraktRatingWriteResult.Disconnected
                val ratingsApi = api as? TraktRatingsApi ?: return@withLock TraktRatingWriteResult.Failed
                var refreshed = false
                if (!tokens.usable(now())) {
                    tokens = refresh(tokens)
                    refreshed = true
                }
                try {
                    ratingsApi.setRating(tokens.accessToken, identity, rating)
                } catch (failure: TraktApiException) {
                    if (failure.status != 401 || refreshed) throw failure
                    tokens = refresh(tokens)
                    ratingsApi.setRating(tokens.accessToken, identity, rating)
                }
                TraktRatingWriteResult.Success
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is TraktApiException && failure.status == 401) {
                    store.write(TraktStoredSession())
                    mutableState.value = TraktAccountState(config.configured,
                        message = TraktMessage.RECONNECT_REQUIRED)
                    return@withLock TraktRatingWriteResult.Disconnected
                }
                // A write can succeed before a timeout. Do not retry it automatically.
                TraktRatingWriteResult.Failed
            }
        }
    }

    override suspend fun ratings(type: TraktRatingType, page: Int): TraktRatingPageResult =
        withContext(Dispatchers.IO) {
            require(page in 1..10000)
            mutex.withLock {
                try {
                    var tokens = store.read().tokens ?: return@withLock TraktRatingPageResult.Disconnected
                    val ratingsApi = api as? TraktRatingsApi ?: return@withLock TraktRatingPageResult.Failed
                    var refreshed = false
                    if (!tokens.usable(now())) {
                        tokens = refresh(tokens)
                        refreshed = true
                    }
                    val result = try {
                        ratingsApi.ratings(tokens.accessToken, type, page)
                    } catch (failure: TraktApiException) {
                        if (failure.status != 401 || refreshed) throw failure
                        tokens = refresh(tokens)
                        ratingsApi.ratings(tokens.accessToken, type, page)
                    }
                    TraktRatingPageResult.Success(result)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (failure is TraktApiException && failure.status == 401) {
                        store.write(TraktStoredSession())
                        mutableState.value = TraktAccountState(config.configured,
                            message = TraktMessage.RECONNECT_REQUIRED)
                        return@withLock TraktRatingPageResult.Disconnected
                    }
                    TraktRatingPageResult.Failed
                }
            }
        }

    override suspend fun send(event: TraktScrobbleEvent): TraktScrobbleOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                var tokens = store.read().tokens ?: return@withLock TraktScrobbleOutcome.DISCONNECTED
                val playback = api as? TraktPlaybackApi ?: return@withLock TraktScrobbleOutcome.FAILED
                var refreshed = false
                if (!tokens.usable(now())) {
                    tokens = refresh(tokens)
                    refreshed = true
                }
                val matched = try {
                    playback.scrobble(tokens.accessToken, event)
                } catch (failure: TraktApiException) {
                    if (failure.status != 401 || refreshed) throw failure
                    tokens = refresh(tokens)
                    playback.scrobble(tokens.accessToken, event)
                }
                if (matched) TraktScrobbleOutcome.SENT else TraktScrobbleOutcome.UNMATCHED
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is TraktApiException && failure.status == 409 && event.action == TraktScrobbleAction.STOP) {
                    return@withLock TraktScrobbleOutcome.DUPLICATE
                }
                if (failure is TraktApiException && failure.status == 401) {
                    store.write(TraktStoredSession())
                    mutableState.value = TraktAccountState(config.configured, message = TraktMessage.RECONNECT_REQUIRED)
                }
                // Never retry a possibly accepted write after timeout, 429, or a transport failure.
                TraktScrobbleOutcome.FAILED
            }
        }
    }

    override suspend fun lists(): TraktCustomListsPageResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                var tokens = store.read().tokens ?: return@withLock TraktCustomListsPageResult.Disconnected
                val listsApi = api as? TraktCustomListsApi ?: return@withLock TraktCustomListsPageResult.Failed
                var refreshed = false
                if (!tokens.usable(now())) {
                    tokens = refresh(tokens)
                    refreshed = true
                }
                val result = try {
                    listsApi.lists(tokens.accessToken)
                } catch (failure: TraktApiException) {
                    if (failure.status != 401 || refreshed) throw failure
                    tokens = refresh(tokens)
                    listsApi.lists(tokens.accessToken)
                }
                TraktCustomListsPageResult.Success(result)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is TraktApiException && failure.status == 401) {
                    store.write(TraktStoredSession())
                    mutableState.value = TraktAccountState(config.configured,
                        message = TraktMessage.RECONNECT_REQUIRED)
                    return@withLock TraktCustomListsPageResult.Disconnected
                }
                TraktCustomListsPageResult.Failed
            }
        }
    }

    override suspend fun items(
        listId: Long,
        type: TraktCustomListItemType,
        page: Int,
    ): TraktCustomListItemsPageResult = withContext(Dispatchers.IO) {
        require(listId > 0L && page in 1..10000)
        mutex.withLock {
            try {
                var tokens = store.read().tokens ?: return@withLock TraktCustomListItemsPageResult.Disconnected
                val listsApi = api as? TraktCustomListsApi ?: return@withLock TraktCustomListItemsPageResult.Failed
                var refreshed = false
                if (!tokens.usable(now())) {
                    tokens = refresh(tokens)
                    refreshed = true
                }
                val result = try {
                    listsApi.items(tokens.accessToken, listId, type, page)
                } catch (failure: TraktApiException) {
                    if (failure.status != 401 || refreshed) throw failure
                    tokens = refresh(tokens)
                    listsApi.items(tokens.accessToken, listId, type, page)
                }
                TraktCustomListItemsPageResult.Success(result)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is TraktApiException && failure.status == 401) {
                    store.write(TraktStoredSession())
                    mutableState.value = TraktAccountState(config.configured,
                        message = TraktMessage.RECONNECT_REQUIRED)
                    return@withLock TraktCustomListItemsPageResult.Disconnected
                }
                TraktCustomListItemsPageResult.Failed
            }
        }
    }

    override suspend fun createList(name: String, description: String?): TraktCustomListWriteResult =
        withContext(Dispatchers.IO) {
            require(name.isNotBlank())
            mutex.withLock {
                try {
                    var tokens = store.read().tokens ?: return@withLock TraktCustomListWriteResult.Disconnected
                    val listsApi = api as? TraktCustomListsApi ?: return@withLock TraktCustomListWriteResult.Failed
                    var refreshed = false
                    if (!tokens.usable(now())) {
                        tokens = refresh(tokens)
                        refreshed = true
                    }
                    try {
                        listsApi.createList(tokens.accessToken, name, description)
                    } catch (failure: TraktApiException) {
                        if (failure.status != 401 || refreshed) throw failure
                        tokens = refresh(tokens)
                        listsApi.createList(tokens.accessToken, name, description)
                    }
                    TraktCustomListWriteResult.Success
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (failure is TraktApiException && failure.status == 401) {
                        store.write(TraktStoredSession())
                        mutableState.value = TraktAccountState(config.configured,
                            message = TraktMessage.RECONNECT_REQUIRED)
                        return@withLock TraktCustomListWriteResult.Disconnected
                    }
                    // Creation is a write and is not retried after an ambiguous transport failure.
                    TraktCustomListWriteResult.Failed
                }
            }
        }

    override suspend fun deleteList(listId: Long): TraktCustomListWriteResult = withContext(Dispatchers.IO) {
        require(listId > 0L)
        mutex.withLock {
            try {
                var tokens = store.read().tokens ?: return@withLock TraktCustomListWriteResult.Disconnected
                val listsApi = api as? TraktCustomListsApi ?: return@withLock TraktCustomListWriteResult.Failed
                var refreshed = false
                if (!tokens.usable(now())) {
                    tokens = refresh(tokens)
                    refreshed = true
                }
                try {
                    listsApi.deleteList(tokens.accessToken, listId)
                } catch (failure: TraktApiException) {
                    if (failure.status != 401 || refreshed) throw failure
                    tokens = refresh(tokens)
                    listsApi.deleteList(tokens.accessToken, listId)
                }
                TraktCustomListWriteResult.Success
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is TraktApiException && failure.status == 401) {
                    store.write(TraktStoredSession())
                    mutableState.value = TraktAccountState(config.configured,
                        message = TraktMessage.RECONNECT_REQUIRED)
                    return@withLock TraktCustomListWriteResult.Disconnected
                }
                // A deletion may have reached Trakt before a transport failure, so do not retry it.
                TraktCustomListWriteResult.Failed
            }
        }
    }

    override suspend fun reorderLists(listIds: List<Long>): TraktCustomListWriteResult = withContext(Dispatchers.IO) {
        require(listIds.isNotEmpty() && listIds.all { it > 0L } && listIds.distinct().size == listIds.size)
        mutex.withLock {
            try {
                var tokens = store.read().tokens ?: return@withLock TraktCustomListWriteResult.Disconnected
                val listsApi = api as? TraktCustomListsApi ?: return@withLock TraktCustomListWriteResult.Failed
                var refreshed = false
                if (!tokens.usable(now())) {
                    tokens = refresh(tokens)
                    refreshed = true
                }
                try {
                    listsApi.reorderLists(tokens.accessToken, listIds)
                } catch (failure: TraktApiException) {
                    if (failure.status != 401 || refreshed) throw failure
                    tokens = refresh(tokens)
                    listsApi.reorderLists(tokens.accessToken, listIds)
                }
                TraktCustomListWriteResult.Success
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is TraktApiException && failure.status == 401) {
                    store.write(TraktStoredSession())
                    mutableState.value = TraktAccountState(config.configured,
                        message = TraktMessage.RECONNECT_REQUIRED)
                    return@withLock TraktCustomListWriteResult.Disconnected
                }
                // Reordering is a write and may have succeeded before a transport failure.
                TraktCustomListWriteResult.Failed
            }
        }
    }

    override suspend fun reorderItems(listId: Long, listItemIds: List<Long>): TraktCustomListWriteResult = withContext(Dispatchers.IO) {
        require(listId > 0L && listItemIds.isNotEmpty() && listItemIds.all { it > 0L } &&
            listItemIds.distinct().size == listItemIds.size)
        mutex.withLock {
            try {
                var tokens = store.read().tokens ?: return@withLock TraktCustomListWriteResult.Disconnected
                val listsApi = api as? TraktCustomListsApi ?: return@withLock TraktCustomListWriteResult.Failed
                var refreshed = false
                if (!tokens.usable(now())) {
                    tokens = refresh(tokens)
                    refreshed = true
                }
                try {
                    listsApi.reorderItems(tokens.accessToken, listId, listItemIds)
                } catch (failure: TraktApiException) {
                    if (failure.status != 401 || refreshed) throw failure
                    tokens = refresh(tokens)
                    listsApi.reorderItems(tokens.accessToken, listId, listItemIds)
                }
                TraktCustomListWriteResult.Success
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is TraktApiException && failure.status == 401) {
                    store.write(TraktStoredSession())
                    mutableState.value = TraktAccountState(config.configured,
                        message = TraktMessage.RECONNECT_REQUIRED)
                    return@withLock TraktCustomListWriteResult.Disconnected
                }
                // A reorder can reach Trakt before a transport failure; never retry it automatically.
                TraktCustomListWriteResult.Failed
            }
        }
    }

    override suspend fun setItem(
        listId: Long,
        identity: TraktCustomListItemIdentity,
        included: Boolean,
    ): TraktCustomListWriteResult = withContext(Dispatchers.IO) {
        require(listId > 0L)
        mutex.withLock {
            try {
                var tokens = store.read().tokens ?: return@withLock TraktCustomListWriteResult.Disconnected
                val listsApi = api as? TraktCustomListsApi ?: return@withLock TraktCustomListWriteResult.Failed
                var refreshed = false
                if (!tokens.usable(now())) {
                    tokens = refresh(tokens)
                    refreshed = true
                }
                try {
                    listsApi.setItem(tokens.accessToken, listId, identity, included)
                } catch (failure: TraktApiException) {
                    if (failure.status != 401 || refreshed) throw failure
                    tokens = refresh(tokens)
                    listsApi.setItem(tokens.accessToken, listId, identity, included)
                }
                TraktCustomListWriteResult.Success
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure is TraktApiException && failure.status == 401) {
                    store.write(TraktStoredSession())
                    mutableState.value = TraktAccountState(config.configured,
                        message = TraktMessage.RECONNECT_REQUIRED)
                    return@withLock TraktCustomListWriteResult.Disconnected
                }
                // A write can succeed before a timeout. Never retry it automatically.
                TraktCustomListWriteResult.Failed
            }
        }
    }

    /** All authenticated operations share this lock; a rotating token is consumed exactly once. */
    private suspend fun refresh(tokens: TraktTokens): TraktTokens {
        // Persist consumption BEFORE calling Trakt. A crash or ambiguous network result must not
        // reuse a single-use refresh token on the next launch. Reconnection is safer than reuse.
        store.write(TraktStoredSession())
        return try {
            api.refresh(tokens.refreshToken).also { store.write(TraktStoredSession(it)) }
        } catch (failure: Exception) {
            if (failure is TraktApiException && failure.status == 429) {
                // An explicit rate-limit rejection issued no new token. Keep the session so
                // the user can retry after the client's cooldown rather than sign in again.
                store.write(TraktStoredSession(tokens))
                mutableState.value = mutableState.value.copy(connected = true, message = TraktMessage.RATE_LIMITED)
                throw failure
            }
            mutableState.value = TraktAccountState(config.configured, message = TraktMessage.RECONNECT_REQUIRED)
            throw failure
        }
    }

    private suspend fun updateProfile() {
        try {
            var tokens = store.read().tokens ?: return
            var refreshed = false
            if (!tokens.usable(now())) {
                tokens = refresh(tokens)
                refreshed = true
            }
            val profile = try {
                api.profile(tokens.accessToken)
            } catch (failure: TraktApiException) {
                if (failure.status != 401 || refreshed) throw failure
                tokens = refresh(tokens)
                api.profile(tokens.accessToken)
            }
            require(profile.username.isNotBlank()) { "Missing Trakt username" }
            mutableState.value = TraktAccountState(config.configured, connected = true,
                username = profile.username, profile = profile)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (failure is TraktApiException && failure.status == 401) {
                store.write(TraktStoredSession())
                mutableState.value = TraktAccountState(config.configured, message = TraktMessage.RECONNECT_REQUIRED)
            } else if (mutableState.value.message != TraktMessage.RECONNECT_REQUIRED) {
                mutableState.value = mutableState.value.copy(message = message(failure, TraktMessage.NETWORK_ERROR))
            }
        }
    }

    private fun message(failure: Exception, fallback: TraktMessage) =
        if (failure is TraktApiException && failure.status == 429) TraktMessage.RATE_LIMITED else fallback

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
}
