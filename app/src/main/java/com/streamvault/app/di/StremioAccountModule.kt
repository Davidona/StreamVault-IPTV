package com.streamvault.app.di

import android.content.Context
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.stremio.account.*
import com.streamvault.domain.model.stremio.StremioAccount
import com.streamvault.domain.repository.StremioAddonRepository
import com.streamvault.domain.repository.StremioPlaybackHistoryRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object StremioAccountModule {
    @Provides @Singleton
    fun streamingService(@ApplicationContext context: Context, crypto: CredentialCrypto): com.streamvault.domain.model.stremio.StremioStreamingService =
        StremioStreamingServiceImpl(EncryptedStremioAccountStore(context, crypto, "stremio_streaming_service"))
    @Provides @Singleton
    fun account(@ApplicationContext context: Context,crypto: CredentialCrypto,
        addons: StremioAddonRepository,histories: StremioPlaybackHistoryRepository,
        privacy: com.streamvault.domain.repository.PlaybackHistoryRepository): StremioAccount =
        StremioAccountService(StremioAccountApi(OkHttpClient()),EncryptedStremioAccountStore(context,crypto),addons,histories,
            allowed={ !privacy.isIncognitoModeEnabled() })
}
