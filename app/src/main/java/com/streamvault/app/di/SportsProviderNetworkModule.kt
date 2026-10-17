package com.streamvault.app.di

import com.streamvault.app.BuildConfig
import com.streamvault.data.remote.http.buildAppUserAgent
import com.streamvault.data.sports.provider.SportsProviderHttpClient
import com.streamvault.domain.sports.provider.SportsProviderFactory
import com.streamvault.domain.sports.provider.SportsProviderRegistry
import com.streamvault.domain.sports.provider.SportsProviderReleasePolicy
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
    fun registry(factories: Set<@JvmSuppressWildcards SportsProviderFactory>): SportsProviderRegistry =
        SportsProviderRegistry(
            factories,
            SportsProviderReleasePolicy(
                development = BuildConfig.BUILD_TYPE == "debug",
                attributionSupportedTypes = setOf("thesportsdb", "football-data", "openligadb"),
            ),
        )

    @Provides
    @Singleton
    fun httpClient(): SportsProviderHttpClient =
        SportsProviderHttpClient(buildAppUserAgent(BuildConfig.VERSION_NAME))
}
