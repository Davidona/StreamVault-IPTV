package com.streamvault.app.di

import com.streamvault.app.stremio.AppStremioDownloadStarter
import com.streamvault.feature.stremio.api.StremioDownloadStarter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppStremioDownloadModule {
    @Binds
    @Singleton
    abstract fun bindStremioDownloadStarter(impl: AppStremioDownloadStarter): StremioDownloadStarter
}
