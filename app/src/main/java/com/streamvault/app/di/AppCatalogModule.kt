package com.streamvault.app.di

import com.streamvault.app.catalog.AppCatalogCastPort
import com.streamvault.app.catalog.AppCatalogDownloadStarter
import com.streamvault.app.catalog.AppCatalogStreamPreparer
import com.streamvault.app.catalog.AppCatalogUpdatePort
import com.streamvault.feature.catalog.api.CatalogAppUpdatePort
import com.streamvault.feature.catalog.api.CatalogCastPort
import com.streamvault.feature.catalog.api.CatalogDownloadStarter
import com.streamvault.feature.catalog.api.CatalogStreamPreparer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppCatalogModule {
    @Binds
    @Singleton
    abstract fun bindStreamPreparer(impl: AppCatalogStreamPreparer): CatalogStreamPreparer

    @Binds
    @Singleton
    abstract fun bindDownloadStarter(impl: AppCatalogDownloadStarter): CatalogDownloadStarter

    @Binds
    @Singleton
    abstract fun bindCastPort(impl: AppCatalogCastPort): CatalogCastPort

    @Binds
    @Singleton
    abstract fun bindUpdatePort(impl: AppCatalogUpdatePort): CatalogAppUpdatePort
}
