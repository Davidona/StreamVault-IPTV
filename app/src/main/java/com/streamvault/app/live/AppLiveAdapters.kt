package com.streamvault.app.live

import com.streamvault.app.plugins.StreamVaultPluginManager
import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.feature.live.api.LiveMultiViewStatus
import com.streamvault.feature.live.api.LiveMultiViewStatusPort
import com.streamvault.feature.live.api.LivePreviewHandoffPort
import com.streamvault.feature.live.api.LivePreviewOrigin
import com.streamvault.feature.live.api.LivePreviewSession
import com.streamvault.feature.live.api.LivePreviewStreamPreparer
import com.streamvault.feature.live.api.LiveSurfaceRefreshPort
import com.streamvault.feature.playback.multiview.MultiViewManager
import com.streamvault.feature.playback.preview.LivePreviewHandoffManager
import com.streamvault.feature.playback.preview.PreviewHandoffSource
import com.streamvault.player.PlayerEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class AppLivePreviewStreamPreparer @Inject constructor(
    private val pluginManager: StreamVaultPluginManager,
) : LivePreviewStreamPreparer {
    override suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo> =
        pluginManager.preparePlaybackStreamInfo(streamInfo)
}

@Singleton
class AppLiveSurfaceRefreshAdapter @Inject constructor(
    private val syncManager: TvInputChannelSyncManager,
) : LiveSurfaceRefreshPort {
    override suspend fun refreshTvInputCatalog() {
        syncManager.refreshTvInputCatalog()
    }
}

@Singleton
class AppLiveMultiViewStatusAdapter @Inject constructor(
    multiViewManager: MultiViewManager,
    preferencesRepository: PreferencesRepository,
) : LiveMultiViewStatusPort {
    override val status: Flow<LiveMultiViewStatus> = combine(
        multiViewManager.slots,
        preferencesRepository.multiViewCenterTwoSlotLayout,
    ) { slots, centeredCompactLayoutEnabled ->
        LiveMultiViewStatus(
            channelCount = slots.count { it != null },
            slotCapacity = if (centeredCompactLayoutEnabled) 2 else MultiViewManager.MAX_SLOTS,
        )
    }
}

@Singleton
class AppLivePreviewHandoffAdapter @Inject constructor(
    private val manager: LivePreviewHandoffManager,
) : LivePreviewHandoffPort {
    override val reverseHandoffOrigin: Flow<LivePreviewOrigin?> = manager.reverseSessionFlow
        .map { session -> session?.source?.toLivePreviewOrigin() }

    override fun registerPreviewSession(
        channel: Channel,
        streamInfo: StreamInfo,
        engine: PlayerEngine,
        origin: LivePreviewOrigin,
    ) {
        manager.registerPreviewSession(
            channel = channel,
            streamInfo = streamInfo,
            engine = engine,
            source = origin.toPreviewHandoffSource(),
        )
    }

    override fun registerPreviewSession(
        channelId: Long,
        providerId: Long,
        streamInfo: StreamInfo,
        engine: PlayerEngine,
        origin: LivePreviewOrigin,
    ) {
        manager.registerPreviewSession(
            channelId = channelId,
            providerId = providerId,
            streamInfo = streamInfo,
            engine = engine,
            source = origin.toPreviewHandoffSource(),
        )
    }

    override fun beginFullscreenHandoff(channelId: Long, engine: PlayerEngine?): Boolean =
        manager.beginFullscreenHandoff(channelId, engine)

    override fun consumeReverseHandoff(origin: LivePreviewOrigin): LivePreviewSession? =
        manager.consumeReverseHandoff(origin.toPreviewHandoffSource())?.let { session ->
            LivePreviewSession(
                engine = session.engine,
                channelId = session.channelId,
                providerId = session.providerId,
                streamInfo = session.streamInfo,
            )
        }

    override fun clear(engine: PlayerEngine?) {
        manager.clear(engine)
    }
}

internal fun LivePreviewOrigin.toPreviewHandoffSource(): PreviewHandoffSource = when (this) {
    LivePreviewOrigin.HOME -> PreviewHandoffSource.HOME
    LivePreviewOrigin.GUIDE -> PreviewHandoffSource.GUIDE
}

internal fun PreviewHandoffSource.toLivePreviewOrigin(): LivePreviewOrigin = when (this) {
    PreviewHandoffSource.HOME -> LivePreviewOrigin.HOME
    PreviewHandoffSource.GUIDE -> LivePreviewOrigin.GUIDE
}
