package com.streamvault.data.trakt

import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.domain.trakt.TraktFavoriteActionResult
import com.streamvault.domain.trakt.TraktFavoriteFailure
import com.streamvault.domain.trakt.TraktFavoriteIdentity
import com.streamvault.domain.trakt.TraktFavoriteWriteResult
import com.streamvault.domain.trakt.TraktFavorites
import com.streamvault.domain.trakt.TraktFavoritesSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps Trakt favorites writes scoped to the account that initiated the action. */
@Singleton
class TraktFavoritesService @Inject constructor(
    private val account: TraktAccount,
    private val source: TraktFavoritesSource,
    private val history: PlaybackHistoryRepository,
) : TraktFavorites {
    private val mutex = Mutex()

    override suspend fun setFavorited(
        identity: TraktFavoriteIdentity,
        favorited: Boolean,
    ): TraktFavoriteActionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (history.isIncognitoModeEnabled()) return@withLock failure(TraktFavoriteFailure.INCOGNITO)
                if (!account.state.value.connected) account.load()
                val owner = account.state.value.username?.takeIf { account.state.value.connected }
                    ?: return@withLock failure(TraktFavoriteFailure.DISCONNECTED)

                when (source.setFavorited(identity, favorited)) {
                    TraktFavoriteWriteResult.Success -> {
                        if (!account.state.value.connected || account.state.value.username != owner) {
                            failure(TraktFavoriteFailure.ACCOUNT_CHANGED)
                        } else if (history.isIncognitoModeEnabled()) {
                            failure(TraktFavoriteFailure.INCOGNITO)
                        } else {
                            TraktFavoriteActionResult.Success
                        }
                    }
                    TraktFavoriteWriteResult.Disconnected -> failure(TraktFavoriteFailure.DISCONNECTED)
                    TraktFavoriteWriteResult.Failed -> failure(TraktFavoriteFailure.REQUEST_FAILED)
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                failure(TraktFavoriteFailure.REQUEST_FAILED)
            }
        }
    }

    private fun failure(reason: TraktFavoriteFailure) = TraktFavoriteActionResult.Failure(reason)
}
