package com.streamvault.app.system

import com.streamvault.feature.system.api.SystemPluginManagementPort
import com.streamvault.feature.system.api.SystemWelcomePort
import com.streamvault.domain.manager.TorrentCacheManager
import com.streamvault.domain.manager.TorrentDownloadStreamResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppSystemBindings {
    @Binds
    @Singleton
    abstract fun bindTorrentDownloadStreamResolver(
        impl: AppTorrentDownloadStreamResolver
    ): TorrentDownloadStreamResolver

    @Binds
    @Singleton
    abstract fun bindTorrentCacheManager(impl: AppTorrentCacheManager): TorrentCacheManager

    @Binds
    @Singleton
    abstract fun bindSystemWelcomePort(impl: AppSystemWelcomeAdapter): SystemWelcomePort

    @Binds
    @Singleton
    abstract fun bindSystemPluginManagementPort(
        impl: AppSystemPluginManagementAdapter,
    ): SystemPluginManagementPort
}
