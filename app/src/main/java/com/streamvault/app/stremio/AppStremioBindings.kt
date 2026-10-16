package com.streamvault.app.stremio

import com.streamvault.feature.stremio.api.StremioTorrentPlaybackAvailability
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppStremioBindings {
    @Binds
    @Singleton
    abstract fun bindStremioTorrentPlaybackAvailability(
        impl: AppStremioTorrentPlaybackAvailability
    ): StremioTorrentPlaybackAvailability
}
