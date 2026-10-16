package com.streamvault.data.trakt

import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.domain.trakt.TraktRatingActionResult
import com.streamvault.domain.trakt.TraktRatingFailure
import com.streamvault.domain.trakt.TraktRatingIdentity
import com.streamvault.domain.trakt.TraktRatingPageResult
import com.streamvault.domain.trakt.TraktRatingRefreshResult
import com.streamvault.domain.trakt.TraktRatingWriteResult
import com.streamvault.domain.trakt.TraktRatings
import com.streamvault.domain.trakt.TraktRatingsSource
import com.streamvault.domain.trakt.TraktRatingType
import com.streamvault.domain.trakt.TraktRatingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps explicit rating writes scoped to the connected account that initiated them. */
@Singleton
class TraktRatingsService @Inject constructor(
    private val account: TraktAccount,
    private val source: TraktRatingsSource,
    private val history: PlaybackHistoryRepository,
) : TraktRatings {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(TraktRatingsState())
    override val state = mutableState.asStateFlow()

    override suspend fun refresh(type: TraktRatingType, force: Boolean): TraktRatingRefreshResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    if (history.isIncognitoModeEnabled()) return@withLock refreshFailure(TraktRatingFailure.INCOGNITO)
                    if (!account.state.value.connected) account.load()
                    val owner = account.state.value.username?.takeIf { account.state.value.connected }
                        ?: return@withLock refreshFailure(TraktRatingFailure.DISCONNECTED)
                    fun sameAccount() = account.state.value.connected && account.state.value.username == owner

                    val current = mutableState.value
                    if (current.accountUsername != owner) {
                        mutableState.value = TraktRatingsState(accountUsername = owner, connected = true)
                    } else if (!force && current.loadedAtMs[type]?.let {
                            System.currentTimeMillis() - it < CACHE_TTL_MS
                        } == true
                    ) {
                        mutableState.value = current.copy(connected = true, failure = null)
                        return@withLock TraktRatingRefreshResult.Success(
                            current.ratings.keys.count { it.type == type },
                        )
                    }

                    mutableState.value = mutableState.value.copy(
                        connected = true,
                        refreshing = mutableState.value.refreshing + type,
                        failure = null,
                    )
                    val staged = linkedMapOf<TraktRatingIdentity, Int>()
                    var skipped = 0
                    var finished = false
                    for (page in 1..MAX_PAGES) {
                        if (!sameAccount()) return@withLock refreshFailure(TraktRatingFailure.ACCOUNT_CHANGED)
                        if (history.isIncognitoModeEnabled()) return@withLock refreshFailure(TraktRatingFailure.INCOGNITO)
                        when (val result = source.ratings(type, page)) {
                            is TraktRatingPageResult.Success -> {
                                val data = result.page
                                if (data.page != page || data.items.size > PAGE_SIZE ||
                                    data.items.any { (identity, rating) -> identity.type != type || rating !in 1..10 }
                                ) return@withLock refreshFailure(TraktRatingFailure.REQUEST_FAILED)
                                data.items.forEach { (identity, rating) -> staged[identity] = rating }
                                skipped += data.skippedItems
                                if (!data.hasMore) {
                                    finished = true
                                    break
                                }
                                if (page == MAX_PAGES) return@withLock refreshFailure(TraktRatingFailure.READ_LIMIT)
                            }
                            TraktRatingPageResult.Disconnected ->
                                return@withLock refreshFailure(TraktRatingFailure.DISCONNECTED)
                            TraktRatingPageResult.Failed ->
                                return@withLock refreshFailure(TraktRatingFailure.REQUEST_FAILED)
                        }
                    }
                    if (!finished) return@withLock refreshFailure(TraktRatingFailure.READ_LIMIT)
                    if (!sameAccount()) return@withLock refreshFailure(TraktRatingFailure.ACCOUNT_CHANGED)
                    if (history.isIncognitoModeEnabled()) return@withLock refreshFailure(TraktRatingFailure.INCOGNITO)

                    val afterRead = mutableState.value
                    val merged = afterRead.ratings.filterKeys { it.type != type } + staged
                    mutableState.value = afterRead.copy(
                        accountUsername = owner,
                        connected = true,
                        ratings = merged,
                        loadedTypes = afterRead.loadedTypes + type,
                        loadedAtMs = afterRead.loadedAtMs + (type to System.currentTimeMillis()),
                        refreshing = afterRead.refreshing - type,
                        failure = null,
                    )
                    TraktRatingRefreshResult.Success(staged.size, skipped)
                } catch (exception: Exception) {
                    if (exception is CancellationException) {
                        mutableState.value = mutableState.value.copy(refreshing = emptySet())
                        throw exception
                    }
                    refreshFailure(TraktRatingFailure.REQUEST_FAILED)
                }
            }
        }

    override suspend fun setRating(
        identity: TraktRatingIdentity,
        rating: Int,
    ): TraktRatingActionResult = withContext(Dispatchers.IO) {
        require(rating in 1..10)
        mutex.withLock {
            try {
                if (history.isIncognitoModeEnabled()) return@withLock failure(TraktRatingFailure.INCOGNITO)
                if (!account.state.value.connected) account.load()
                val owner = account.state.value.username?.takeIf { account.state.value.connected }
                    ?: return@withLock failure(TraktRatingFailure.DISCONNECTED)

                if (mutableState.value.accountUsername != owner) {
                    mutableState.value = TraktRatingsState(accountUsername = owner, connected = true)
                }

                mutableState.value = mutableState.value.copy(
                    accountUsername = owner,
                    connected = true,
                    updating = mutableState.value.updating + identity,
                    failure = null,
                )
                when (source.setRating(identity, rating)) {
                    TraktRatingWriteResult.Success -> {
                        if (!account.state.value.connected || account.state.value.username != owner) {
                            failure(TraktRatingFailure.ACCOUNT_CHANGED)
                        } else if (history.isIncognitoModeEnabled()) {
                            failure(TraktRatingFailure.INCOGNITO)
                        } else {
                            val current = mutableState.value
                            mutableState.value = current.copy(
                                ratings = current.ratings + (identity to rating),
                                updating = current.updating - identity,
                                failure = null,
                            )
                            TraktRatingActionResult.Success
                        }
                    }
                    TraktRatingWriteResult.Disconnected -> failure(TraktRatingFailure.DISCONNECTED)
                    TraktRatingWriteResult.Failed -> failure(TraktRatingFailure.REQUEST_FAILED)
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                failure(TraktRatingFailure.REQUEST_FAILED)
            } finally {
                val current = mutableState.value
                mutableState.value = current.copy(updating = current.updating - identity)
            }
        }
    }

    private fun refreshFailure(reason: TraktRatingFailure): TraktRatingRefreshResult.Failure {
        val current = mutableState.value
        val clear = reason == TraktRatingFailure.DISCONNECTED || reason == TraktRatingFailure.INCOGNITO ||
            reason == TraktRatingFailure.ACCOUNT_CHANGED
        mutableState.value = current.copy(
            connected = account.state.value.connected,
            ratings = if (clear) emptyMap() else current.ratings,
            loadedTypes = if (clear) emptySet() else current.loadedTypes,
            loadedAtMs = if (clear) emptyMap() else current.loadedAtMs,
            refreshing = emptySet(),
            updating = if (clear) emptySet() else current.updating,
            failure = reason,
        )
        return TraktRatingRefreshResult.Failure(reason)
    }

    private fun failure(reason: TraktRatingFailure): TraktRatingActionResult.Failure {
        val current = mutableState.value
        val clear = reason == TraktRatingFailure.DISCONNECTED || reason == TraktRatingFailure.INCOGNITO ||
            reason == TraktRatingFailure.ACCOUNT_CHANGED
        mutableState.value = current.copy(
            connected = account.state.value.connected,
            ratings = if (clear) emptyMap() else current.ratings,
            loadedTypes = if (clear) emptySet() else current.loadedTypes,
            loadedAtMs = if (clear) emptyMap() else current.loadedAtMs,
            updating = emptySet(),
            failure = reason,
        )
        return TraktRatingActionResult.Failure(reason)
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 50
        const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
