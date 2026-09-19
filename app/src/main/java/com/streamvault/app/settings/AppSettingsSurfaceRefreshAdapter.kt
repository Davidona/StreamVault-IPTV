package com.streamvault.app.settings

import android.content.Context
import com.streamvault.app.tv.LauncherRecommendationsManager
import com.streamvault.app.tv.WatchNextManager
import com.streamvault.app.tvinput.TvInputCatalogRefreshWorker
import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.feature.settings.api.SettingsSurfaceRefreshPort
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettingsSurfaceRefreshAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchNextManager: WatchNextManager,
    private val launcherRecommendationsManager: LauncherRecommendationsManager,
    private val tvInputChannelSyncManager: TvInputChannelSyncManager,
) : SettingsSurfaceRefreshPort {
    override suspend fun refreshWatchNext() {
        watchNextManager.refreshWatchNext()
    }

    override suspend fun refreshRecommendations() {
        launcherRecommendationsManager.refreshRecommendations(force = true)
    }

    override suspend fun refreshTvInputCatalog() {
        tvInputChannelSyncManager.refreshTvInputCatalog()
    }

    override fun enqueueTvInputCatalogRefresh() {
        TvInputCatalogRefreshWorker.enqueue(context)
    }
}
