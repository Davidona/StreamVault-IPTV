package com.streamvault.app.catalog

import com.streamvault.domain.model.StreamInfo
import com.streamvault.feature.catalog.api.CatalogCastPlaybackEvent
import com.streamvault.feature.catalog.api.CatalogCastPort
import com.streamvault.feature.catalog.api.CatalogCastRequest
import com.streamvault.feature.catalog.api.CatalogCastStartResult
import com.streamvault.feature.catalog.api.CatalogMessage
import com.streamvault.feature.playback.api.CastMediaRequest
import com.streamvault.feature.playback.cast.CastMediaRequestBuildResult
import com.streamvault.feature.playback.cast.CastMediaRequestFactory
import com.streamvault.feature.playback.cast.CastPlaybackCoordinator
import com.streamvault.feature.playback.cast.CastPlaybackEvent
import com.streamvault.feature.playback.cast.CastStartResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal interface AppCatalogCastOperations {
    val playbackEvents: Flow<CastPlaybackEvent>

    fun buildRequest(
        streamInfo: StreamInfo,
        title: String,
        subtitle: String?,
        artworkUrl: String?,
        startPositionMs: Long,
    ): CastMediaRequestBuildResult

    suspend fun startCasting(request: CastMediaRequest): CastStartResult
}

@Singleton
class AppCatalogCastPort internal constructor(
    private val operations: AppCatalogCastOperations,
) : CatalogCastPort {
    @Inject
    constructor(
        requestFactory: CastMediaRequestFactory,
        playbackCoordinator: CastPlaybackCoordinator,
    ) : this(
        object : AppCatalogCastOperations {
            override val playbackEvents: Flow<CastPlaybackEvent> = playbackCoordinator.playbackEvents

            override fun buildRequest(
                streamInfo: StreamInfo,
                title: String,
                subtitle: String?,
                artworkUrl: String?,
                startPositionMs: Long,
            ): CastMediaRequestBuildResult = requestFactory.buildFromStreamInfo(
                streamInfo = streamInfo,
                title = title,
                subtitle = subtitle,
                artworkUrl = artworkUrl,
                isLive = false,
                startPositionMs = startPositionMs,
            )

            override suspend fun startCasting(request: CastMediaRequest): CastStartResult =
                playbackCoordinator.startCasting(request)
        }
    )

    override val playbackEvents: Flow<CatalogCastPlaybackEvent> =
        operations.playbackEvents.map(::mapPlaybackEvent)

    override suspend fun startCasting(request: CatalogCastRequest): CatalogCastStartResult {
        return when (val buildResult = operations.buildRequest(
            streamInfo = request.streamInfo,
            title = request.title,
            subtitle = request.subtitle,
            artworkUrl = request.artworkUrl,
            startPositionMs = request.startPositionMs,
        )) {
            is CastMediaRequestBuildResult.Unsupported ->
                CatalogCastStartResult.Unsupported(CatalogMessage.CastUnsupported)

            is CastMediaRequestBuildResult.Success ->
                mapStartResult(operations.startCasting(buildResult.request))
        }
    }

    private fun mapStartResult(result: CastStartResult): CatalogCastStartResult = when (result) {
        CastStartResult.STARTED -> CatalogCastStartResult.Started
        CastStartResult.ROUTE_SELECTION_REQUIRED -> CatalogCastStartResult.RouteSelectionRequired
        CastStartResult.UNAVAILABLE -> CatalogCastStartResult.Unavailable
        CastStartResult.UNSUPPORTED ->
            CatalogCastStartResult.Unsupported(CatalogMessage.CastUnsupported)
    }

    private fun mapPlaybackEvent(event: CastPlaybackEvent): CatalogCastPlaybackEvent = when (event) {
        CastPlaybackEvent.RouteSelectionCancelled ->
            CatalogCastPlaybackEvent.RouteSelectionCancelled
        is CastPlaybackEvent.MediaLoadSucceeded ->
            CatalogCastPlaybackEvent.Finished(true, CatalogMessage.CastStarted)
        is CastPlaybackEvent.MediaLoadFailed ->
            CatalogCastPlaybackEvent.Finished(false, CatalogMessage.CastLoadFailed)
        is CastPlaybackEvent.SessionStartFailed ->
            CatalogCastPlaybackEvent.Finished(false, CatalogMessage.CastSessionFailed)
        is CastPlaybackEvent.ReceiverUnavailable ->
            CatalogCastPlaybackEvent.Finished(false, CatalogMessage.CastUnavailable)
    }
}
