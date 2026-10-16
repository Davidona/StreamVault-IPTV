package com.streamvault.app.di

import android.content.Context
import com.streamvault.app.BuildConfig
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.trakt.EncryptedTraktTokenStore
import com.streamvault.data.trakt.TraktApiClient
import com.streamvault.data.trakt.TraktAuthService
import com.streamvault.data.trakt.TraktConfig
import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.domain.trakt.TraktScrobbler
import com.streamvault.domain.trakt.TraktHistorySource
import com.streamvault.domain.trakt.TraktWatchlist
import com.streamvault.domain.trakt.TraktWatchlistSource
import com.streamvault.domain.trakt.TraktFavorites
import com.streamvault.domain.trakt.TraktFavoritesSource
import com.streamvault.domain.trakt.TraktFavoriteImporter
import com.streamvault.domain.trakt.TraktRatings
import com.streamvault.domain.trakt.TraktRatingsSource
import com.streamvault.domain.trakt.TraktCustomLists
import com.streamvault.domain.trakt.TraktCustomListsSource
import com.streamvault.domain.trakt.TraktDiscovery
import com.streamvault.domain.trakt.TraktDiscoverySource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TraktModule {
    @Provides @Singleton
    fun sync(service: com.streamvault.data.trakt.TraktHistorySyncService): com.streamvault.domain.trakt.TraktHistorySync = service
    @Provides @Singleton
    fun history(account: TraktAccount): TraktHistorySource = account as TraktHistorySource

    @Provides @Singleton
    fun watchlist(service: com.streamvault.data.trakt.TraktWatchlistService): TraktWatchlist = service

    @Provides @Singleton
    fun watchlistSource(account: TraktAccount): TraktWatchlistSource = account as TraktWatchlistSource

    @Provides @Singleton
    fun favorites(service: com.streamvault.data.trakt.TraktFavoritesService): TraktFavorites = service

    @Provides @Singleton
    fun favoriteImporter(service: com.streamvault.data.trakt.TraktFavoriteImporterService): TraktFavoriteImporter = service

    @Provides @Singleton
    fun favoritesSource(account: TraktAccount): TraktFavoritesSource = account as TraktFavoritesSource

    @Provides @Singleton
    fun ratings(service: com.streamvault.data.trakt.TraktRatingsService): TraktRatings = service

    @Provides @Singleton
    fun ratingsSource(account: TraktAccount): TraktRatingsSource = account as TraktRatingsSource

    @Provides @Singleton
    fun customLists(service: com.streamvault.data.trakt.TraktCustomListsService): TraktCustomLists = service

    @Provides @Singleton
    fun customListsSource(account: TraktAccount): TraktCustomListsSource = account as TraktCustomListsSource

    @Provides @Singleton
    fun discovery(service: com.streamvault.data.trakt.TraktDiscoveryService): TraktDiscovery = service

    @Provides @Singleton
    fun discoverySource(account: TraktAccount): TraktDiscoverySource = account as TraktDiscoverySource

    @Provides @Singleton
    fun scrobbler(account: TraktAccount): TraktScrobbler = account as TraktScrobbler

    @Provides @Singleton
    fun account(@ApplicationContext context: Context, crypto: CredentialCrypto): TraktAccount {
        val config = TraktConfig(BuildConfig.TRAKT_CLIENT_ID, BuildConfig.TRAKT_REDIRECT_URI)
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        return TraktAuthService(config, EncryptedTraktTokenStore(context, crypto),
            TraktApiClient(config, client, userAgent = "StreamVault/${BuildConfig.VERSION_NAME}"))
    }
}
