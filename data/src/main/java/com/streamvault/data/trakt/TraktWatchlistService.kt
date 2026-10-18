package com.streamvault.data.trakt

import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.domain.trakt.TraktWatchlist
import com.streamvault.domain.trakt.TraktWatchlistActionResult
import com.streamvault.domain.trakt.TraktWatchlistFailure
import com.streamvault.domain.trakt.TraktWatchlistIdentity
import com.streamvault.domain.trakt.TraktWatchlistPageResult
import com.streamvault.domain.trakt.TraktWatchlistRefreshResult
import com.streamvault.domain.trakt.TraktWatchlistSource
import com.streamvault.domain.trakt.TraktWatchlistState
import com.streamvault.domain.trakt.TraktWatchlistType
import com.streamvault.domain.trakt.TraktWatchlistWriteResult
import com.streamvault.domain.repository.PlaybackHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Manual Trakt watchlist access. Pages are staged before replacing the account-scoped cache. */
@Singleton
class TraktWatchlistService @Inject constructor(
    private val account: TraktAccount,
    private val source: TraktWatchlistSource,
    private val history: PlaybackHistoryRepository,
) : TraktWatchlist {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(TraktWatchlistState())
    override val state = mutableState.asStateFlow()

    override suspend fun refresh(force: Boolean): TraktWatchlistRefreshResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (history.isIncognitoModeEnabled()) return@withLock fail(TraktWatchlistFailure.INCOGNITO)
                if (!account.state.value.connected) account.load()
                val owner = account.state.value.username?.takeIf { account.state.value.connected }
                    ?: return@withLock fail(TraktWatchlistFailure.DISCONNECTED)
                fun sameAccount() = account.state.value.connected && account.state.value.username == owner

                val current = mutableState.value
                if (current.accountUsername != owner) {
                    mutableState.value = TraktWatchlistState(accountUsername = owner, connected = true)
                } else if (!force && current.loadedAtMs?.let { System.currentTimeMillis() - it < CACHE_TTL_MS } == true) {
                    mutableState.value = current.copy(connected = true, failure = null)
                    return@withLock TraktWatchlistRefreshResult.Success(current.items.size)
                }
                mutableState.value = mutableState.value.copy(connected = true, isRefreshing = true, failure = null)

                val staged = linkedSetOf<TraktWatchlistIdentity>()
                var skipped = 0
                for (type in TraktWatchlistType.entries) {
                    var finished = false
                    for (page in 1..MAX_PAGES) {
                        if (!sameAccount()) return@withLock fail(TraktWatchlistFailure.ACCOUNT_CHANGED)
                        if (history.isIncognitoModeEnabled()) return@withLock fail(TraktWatchlistFailure.INCOGNITO)
                        when (val result = source.watchlist(type, page)) {
                            is TraktWatchlistPageResult.Success -> {
                                val data = result.page
                                if (data.page != page || data.items.size > PAGE_SIZE) {
                                    return@withLock fail(TraktWatchlistFailure.REQUEST_FAILED)
                                }
                                staged.addAll(data.items.filter { it.type == type })
                                skipped += data.skippedItems
                                if (!data.hasMore) {
                                    finished = true
                                    break
                                }
                                if (page == MAX_PAGES) return@withLock fail(TraktWatchlistFailure.READ_LIMIT)
                            }
                            TraktWatchlistPageResult.Disconnected -> return@withLock fail(TraktWatchlistFailure.DISCONNECTED)
                            TraktWatchlistPageResult.Failed -> return@withLock fail(TraktWatchlistFailure.REQUEST_FAILED)
                        }
                    }
                    if (!finished) return@withLock fail(TraktWatchlistFailure.READ_LIMIT)
                }
                if (!sameAccount()) return@withLock fail(TraktWatchlistFailure.ACCOUNT_CHANGED)
                if (history.isIncognitoModeEnabled()) return@withLock fail(TraktWatchlistFailure.INCOGNITO)
                mutableState.value = TraktWatchlistState(
                    accountUsername = owner,
                    connected = true,
                    items = staged,
                    loadedAtMs = System.currentTimeMillis(),
                )
                TraktWatchlistRefreshResult.Success(staged.size, skipped)
            } catch (failure: Exception) {
                if (failure is CancellationException) {
                    mutableState.value = mutableState.value.copy(isRefreshing = false, loadedAtMs = null)
                    throw failure
                }
                fail(TraktWatchlistFailure.REQUEST_FAILED)
            }
        }
    }

    override suspend fun setWatchlisted(
        identity: TraktWatchlistIdentity,
        watchlisted: Boolean,
    ): TraktWatchlistActionResult {
        val loaded = mutableState.value
        if (!loaded.connected || loaded.accountUsername != account.state.value.username ||
            loaded.loadedAtMs?.let { System.currentTimeMillis() - it >= CACHE_TTL_MS } != false
        ) {
            when (val result = refresh()) {
                is TraktWatchlistRefreshResult.Failure -> return TraktWatchlistActionResult.Failure(result.reason)
                is TraktWatchlistRefreshResult.Success -> Unit
            }
        }

        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    if (history.isIncognitoModeEnabled()) return@withLock actionFailure(TraktWatchlistFailure.INCOGNITO)
                    val owner = account.state.value.username?.takeIf { account.state.value.connected }
                        ?: return@withLock actionFailure(TraktWatchlistFailure.DISCONNECTED)
                    val current = mutableState.value
                    if (current.accountUsername != owner || !current.loaded) {
                        return@withLock actionFailure(TraktWatchlistFailure.ACCOUNT_CHANGED)
                    }
                    val alreadyWatchlisted = current.contains(identity)
                    if (alreadyWatchlisted == watchlisted) return@withLock TraktWatchlistActionResult.Success

                    mutableState.value = current.copy(updating = current.updating + identity, failure = null)
                    when (source.setWatchlisted(identity, watchlisted)) {
                        TraktWatchlistWriteResult.Success -> {
                            if (!account.state.value.connected || account.state.value.username != owner) {
                                return@withLock actionFailure(TraktWatchlistFailure.ACCOUNT_CHANGED)
                            }
                            if (history.isIncognitoModeEnabled()) return@withLock actionFailure(TraktWatchlistFailure.INCOGNITO)
                            val updated = mutableState.value.items.toMutableSet().apply {
                                if (watchlisted) add(identity) else remove(identity)
                            }
                            mutableState.value = mutableState.value.copy(
                                items = updated,
                                loadedAtMs = System.currentTimeMillis(),
                                updating = mutableState.value.updating - identity,
                                failure = null,
                            )
                            TraktWatchlistActionResult.Success
                        }
                        TraktWatchlistWriteResult.Disconnected -> actionFailure(TraktWatchlistFailure.DISCONNECTED)
                        TraktWatchlistWriteResult.Failed -> actionFailure(TraktWatchlistFailure.REQUEST_FAILED)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    actionFailure(TraktWatchlistFailure.REQUEST_FAILED)
                } finally {
                    val current = mutableState.value
                    if (identity in current.updating) {
                        mutableState.value = current.copy(updating = current.updating - identity)
                    }
                }
            }
        }
    }

    private fun fail(reason: TraktWatchlistFailure): TraktWatchlistRefreshResult.Failure {
        val current = mutableState.value
        mutableState.value = current.copy(
            connected = account.state.value.connected,
            isRefreshing = false,
            loadedAtMs = null,
            updating = emptySet(),
            failure = reason,
            items = if (reason == TraktWatchlistFailure.DISCONNECTED || reason == TraktWatchlistFailure.INCOGNITO ||
                reason == TraktWatchlistFailure.ACCOUNT_CHANGED) emptySet() else current.items,
        )
        return TraktWatchlistRefreshResult.Failure(reason)
    }

    private fun actionFailure(reason: TraktWatchlistFailure): TraktWatchlistActionResult.Failure {
        val current = mutableState.value
        mutableState.value = current.copy(
            connected = account.state.value.connected,
            loadedAtMs = null,
            updating = emptySet(),
            failure = reason,
            items = if (reason == TraktWatchlistFailure.DISCONNECTED || reason == TraktWatchlistFailure.INCOGNITO ||
                reason == TraktWatchlistFailure.ACCOUNT_CHANGED) emptySet() else current.items,
        )
        return TraktWatchlistActionResult.Failure(reason)
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 50
        const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
