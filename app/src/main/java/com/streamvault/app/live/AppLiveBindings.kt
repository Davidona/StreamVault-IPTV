package com.streamvault.app.live

import com.streamvault.feature.live.api.LiveMultiViewStatusPort
import com.streamvault.feature.live.api.LivePreviewHandoffPort
import com.streamvault.feature.live.api.LivePreviewStreamPreparer
import com.streamvault.feature.live.api.LiveSurfaceRefreshPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppLiveBindings {
    @Binds
    @Singleton
    abstract fun bindLivePreviewStreamPreparer(
        adapter: AppLivePreviewStreamPreparer,
    ): LivePreviewStreamPreparer

    @Binds
    @Singleton
    abstract fun bindLiveSurfaceRefreshPort(
        adapter: AppLiveSurfaceRefreshAdapter,
    ): LiveSurfaceRefreshPort

    @Binds
    @Singleton
    abstract fun bindLiveMultiViewStatusPort(
        adapter: AppLiveMultiViewStatusAdapter,
    ): LiveMultiViewStatusPort

    @Binds
    @Singleton
    abstract fun bindLivePreviewHandoffPort(
        adapter: AppLivePreviewHandoffAdapter,
    ): LivePreviewHandoffPort
}
