package com.streamvault.app.playback

import com.streamvault.feature.playback.api.CastUrlRewriter
import com.streamvault.feature.playback.api.PlaybackStreamPreparer
import com.streamvault.feature.playback.api.PlaybackSurfaceRefreshPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackIntegrationModule {
    @Binds
    @Singleton
    abstract fun bindPlaybackStreamPreparer(
        adapter: StreamVaultPluginPlaybackServiceAdapter
    ): PlaybackStreamPreparer

    @Binds
    @Singleton
    abstract fun bindCastUrlRewriter(
        adapter: StreamVaultPluginPlaybackServiceAdapter
    ): CastUrlRewriter

    @Binds
    @Singleton
    abstract fun bindPlaybackSurfaceRefreshPort(
        adapter: AppPlaybackSurfaceRefreshAdapter
    ): PlaybackSurfaceRefreshPort
}
