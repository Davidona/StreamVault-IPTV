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
import com.streamvault.feature.live.api.LivePreviewTranslationPort
import com.streamvault.feature.live.api.LiveSurfaceRefreshPort
import com.streamvault.feature.playback.multiview.MultiViewManager
import com.streamvault.feature.playback.preview.LivePreviewHandoffManager
import com.streamvault.feature.playback.preview.PreviewHandoffSource
import com.streamvault.feature.playback.translation.LiveTranslationClient
import com.streamvault.feature.playback.translation.LiveTranslationSession
import com.streamvault.player.PlayerEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class AppLivePreviewStreamPreparer internal constructor(
    private val delegate: LivePreviewStreamPreparationDelegate,
) : LivePreviewStreamPreparer {
    @Inject
    constructor(pluginManager: StreamVaultPluginManager) : this(
        LivePreviewStreamPreparationDelegate(pluginManager::preparePlaybackStreamInfo),
    )

    override suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo> =
        delegate.prepare(streamInfo)
}

@Singleton
class AppLiveSurfaceRefreshAdapter internal constructor(
    private val delegate: LiveSurfaceRefreshDelegate,
) : LiveSurfaceRefreshPort {
    @Inject
    constructor(syncManager: TvInputChannelSyncManager) : this(
        LiveSurfaceRefreshDelegate(syncManager::refreshTvInputCatalog),
    )

    override suspend fun refreshTvInputCatalog() {
        delegate.refresh()
    }
}

@Singleton
class AppLiveMultiViewStatusAdapter internal constructor(
    slots: Flow<List<Channel?>>,
    centeredCompactLayoutEnabled: Flow<Boolean>,
) : LiveMultiViewStatusPort {
    @Inject
    constructor(
        multiViewManager: MultiViewManager,
        preferencesRepository: PreferencesRepository,
    ) : this(
        slots = multiViewManager.slots,
        centeredCompactLayoutEnabled = preferencesRepository.multiViewCenterTwoSlotLayout,
    )

    override val status: Flow<LiveMultiViewStatus> = combine(
        slots,
        centeredCompactLayoutEnabled,
    ) { slots, centeredCompactLayoutEnabled ->
        LiveMultiViewStatus(
            channelCount = slots.count { it != null },
            slotCapacity = if (centeredCompactLayoutEnabled) 2 else MultiViewManager.MAX_SLOTS,
        )
    }
}

internal fun interface LivePreviewStreamPreparationDelegate {
    suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo>
}

internal fun interface LiveSurfaceRefreshDelegate {
    suspend fun refresh()
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

/**
 * Runs the full player's real-time translation session on the inline preview engine, so the
 * Live TV inline fullscreen can offer "Real-time translation" in its subtitle picker.
 */
@Singleton
class AppLivePreviewTranslationAdapter @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : LivePreviewTranslationPort {
    private var session: LiveTranslationSession? = null

    override fun start(
        scope: CoroutineScope,
        engine: PlayerEngine,
        endpoint: String,
        logicalUrl: String,
        providerId: Long,
        contentId: Long,
        onError: (String) -> Unit,
    ) {
        stop(engine = null)
        session = LiveTranslationSession(
            scope = scope,
            playerEngine = engine,
            client = LiveTranslationClient(okHttpClient, endpoint),
            logicalUrl = logicalUrl,
            providerId = providerId,
            contentId = contentId,
            onSourceLanguageDetected = {},
            onError = onError,
        ).also { it.start() }
    }

    override fun stop(engine: PlayerEngine?) {
        session?.stop()
        session = null
        engine?.setInjectedSubtitleText(null)
    }
}
