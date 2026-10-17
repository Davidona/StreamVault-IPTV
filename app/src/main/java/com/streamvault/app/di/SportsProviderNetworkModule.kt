package com.streamvault.app.di

import com.streamvault.app.BuildConfig
import com.streamvault.data.remote.http.buildAppUserAgent
import com.streamvault.data.sports.provider.SportsProviderHttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SportsProviderNetworkModule {
    @Provides
    @Singleton
    fun httpClient(): SportsProviderHttpClient =
        SportsProviderHttpClient(buildAppUserAgent(BuildConfig.VERSION_NAME))
}
