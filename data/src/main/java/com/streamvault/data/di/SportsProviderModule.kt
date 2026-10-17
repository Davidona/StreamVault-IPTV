package com.streamvault.data.di

import com.streamvault.data.sports.provider.EncryptedSportsProviderStore
import com.streamvault.data.sports.provider.InMemorySportsProviderEventLinkCache
import com.streamvault.data.sports.custom.CustomSportsProviderFactory
import com.streamvault.data.sports.balldontlie.BallDontLieProviderFactory
import com.streamvault.data.sports.thesportsdb.TheSportsDbProviderFactory
import com.streamvault.data.sports.highlightly.HighlightlyProviderFactory
import com.streamvault.data.sports.footballdata.FootballDataProviderFactory
import com.streamvault.data.sports.oddspapi.OddsPapiProviderFactory
import com.streamvault.data.sports.openligadb.OpenLigaDbProviderFactory
import com.streamvault.domain.sports.provider.SportsProviderFactory
import com.streamvault.domain.sports.provider.SportsProviderStore
import com.streamvault.domain.sports.provider.SportsProviderEventLinkCache
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import dagger.multibindings.IntoSet

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
    abstract fun bindBallDontLieFactory(impl: BallDontLieProviderFactory): SportsProviderFactory

    @Binds
    @IntoSet
    abstract fun bindHighlightlyFactory(impl: HighlightlyProviderFactory): SportsProviderFactory

    @Binds
    @IntoSet
    abstract fun bindFootballDataFactory(impl: FootballDataProviderFactory): SportsProviderFactory

    @Binds
    @IntoSet
    abstract fun bindOddsPapiFactory(impl: OddsPapiProviderFactory): SportsProviderFactory

    @Binds
    @IntoSet
    abstract fun bindOpenLigaDbProviderFactory(impl: OpenLigaDbProviderFactory): SportsProviderFactory

    @Binds
    @IntoSet
    abstract fun bindCustomSportsProviderFactory(impl: CustomSportsProviderFactory): SportsProviderFactory

    /** Reviewed adapters register factories with @IntoSet. */
    @Multibinds
    abstract fun factories(): Set<SportsProviderFactory>

}
