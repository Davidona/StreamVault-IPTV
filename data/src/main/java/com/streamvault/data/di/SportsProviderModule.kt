package com.streamvault.data.di

import com.streamvault.data.sports.provider.EncryptedSportsProviderStore
import com.streamvault.data.sports.provider.InMemorySportsProviderEventLinkCache
import com.streamvault.data.sports.custom.CustomSportsProviderFactory
import com.streamvault.data.sports.thesportsdb.TheSportsDbProviderFactory
import com.streamvault.domain.sports.provider.SportsProviderFactory
import com.streamvault.domain.sports.provider.SportsProviderRegistry
import com.streamvault.domain.sports.provider.SportsProviderStore
import com.streamvault.domain.sports.provider.SportsProviderEventLinkCache
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SportsProviderModule {
    @Binds
    abstract fun bindStore(impl: EncryptedSportsProviderStore): SportsProviderStore

    @Binds
    abstract fun bindEventLinkCache(impl: InMemorySportsProviderEventLinkCache): SportsProviderEventLinkCache

    @Binds
    @IntoSet
    abstract fun bindTheSportsDbFactory(impl: TheSportsDbProviderFactory): SportsProviderFactory

    @Binds
    @IntoSet
    abstract fun bindCustomSportsProviderFactory(impl: CustomSportsProviderFactory): SportsProviderFactory

    /** Reviewed adapters register factories with @IntoSet. */
    @Multibinds
    abstract fun factories(): Set<SportsProviderFactory>

    companion object {
        @Provides
        @Singleton
        fun registry(factories: Set<@JvmSuppressWildcards SportsProviderFactory>): SportsProviderRegistry =
            SportsProviderRegistry(factories)
    }
}
