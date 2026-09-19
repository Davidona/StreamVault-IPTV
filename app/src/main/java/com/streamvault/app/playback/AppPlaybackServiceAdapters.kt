package com.streamvault.app.playback

import com.streamvault.app.plugins.StreamVaultPluginManager
import com.streamvault.app.tv.LauncherRecommendationsManager
import com.streamvault.app.tv.WatchNextManager
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.feature.playback.api.CastMediaRequest
import com.streamvault.feature.playback.api.CastUrlRewriter
import com.streamvault.feature.playback.api.PlaybackStreamPreparer
import com.streamvault.feature.playback.api.PlaybackSurfaceRefreshPort
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamVaultPluginPlaybackServiceAdapter internal constructor(
    private val operations: StreamVaultPluginPlaybackOperations
) : PlaybackStreamPreparer, CastUrlRewriter {
    @Inject
    constructor(pluginManager: StreamVaultPluginManager) : this(
        StreamVaultPluginPlaybackManager(pluginManager)
    )

    override suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo> =
        operations.preparePlaybackStreamInfo(streamInfo)

    override suspend fun rewrite(request: CastMediaRequest): String? = operations.rewriteCastUrl(request)
}

@Singleton
class AppPlaybackSurfaceRefreshAdapter internal constructor(
    private val watchNext: WatchNextOperations,
    private val launcherRecommendations: LauncherRecommendationsOperations
) : PlaybackSurfaceRefreshPort {
    @Inject
    constructor(
        watchNextManager: WatchNextManager,
        launcherRecommendationsManager: LauncherRecommendationsManager
    ) : this(
        WatchNextManagerOperations(watchNextManager),
        LauncherRecommendationsManagerOperations(launcherRecommendationsManager)
    )

    override suspend fun updateWatchNextProgress(history: PlaybackHistory) {
        watchNext.updateWatchNextProgress(history)
    }

    override suspend fun refreshWatchNext() {
        watchNext.refreshWatchNext()
    }

    override suspend fun refreshRecommendations() {
        launcherRecommendations.refreshRecommendations()
    }
}

internal interface StreamVaultPluginPlaybackOperations {
    suspend fun preparePlaybackStreamInfo(streamInfo: StreamInfo): Result<StreamInfo>

    suspend fun rewriteCastUrl(request: CastMediaRequest): String?
}

internal interface WatchNextOperations {
    suspend fun updateWatchNextProgress(history: PlaybackHistory)

    suspend fun refreshWatchNext()
}

internal interface LauncherRecommendationsOperations {
    suspend fun refreshRecommendations()
}

private class StreamVaultPluginPlaybackManager(
    private val pluginManager: StreamVaultPluginManager
) : StreamVaultPluginPlaybackOperations {
    override suspend fun preparePlaybackStreamInfo(streamInfo: StreamInfo): Result<StreamInfo> =
        pluginManager.preparePlaybackStreamInfo(streamInfo)

    override suspend fun rewriteCastUrl(request: CastMediaRequest): String? =
        pluginManager.rewriteCastUrl(request)
}

private class WatchNextManagerOperations(
    private val watchNextManager: WatchNextManager
) : WatchNextOperations {
    override suspend fun updateWatchNextProgress(history: PlaybackHistory) {
        watchNextManager.updateWatchNextProgress(history)
    }

    override suspend fun refreshWatchNext() {
        watchNextManager.refreshWatchNext()
    }
}

private class LauncherRecommendationsManagerOperations(
    private val launcherRecommendationsManager: LauncherRecommendationsManager
) : LauncherRecommendationsOperations {
    override suspend fun refreshRecommendations() {
        launcherRecommendationsManager.refreshRecommendations()
    }
}
