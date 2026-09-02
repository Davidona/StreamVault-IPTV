package com.streamvault.feature.catalog.api

import com.streamvault.core.navigation.AppDestination
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import kotlinx.coroutines.flow.Flow

interface CatalogPlatformHost {
    fun openCastRouteChooser()
}

interface CatalogStreamPreparer {
    suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo>
}

interface CatalogDownloadStarter {
    fun startDownload(downloadId: String)
}

data class CatalogChannelPlaybackContext(
    val categoryId: Long?,
    val providerId: Long,
    val isVirtual: Boolean,
    val combinedProfileId: Long?,
    val returnDestination: AppDestination,
)

data class CatalogCastRequest(
    val streamInfo: StreamInfo,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val startPositionMs: Long,
)

enum class CatalogMessage {
    CastStarted,
    CastUnavailable,
    CastUnsupported,
    CastItemUnavailable,
    CastSessionFailed,
    CastLoadFailed,
    DownloadStarted,
    DownloadFailed,
    DownloadUrlUnavailable,
}

sealed interface CatalogCastStartResult {
    data object Started : CatalogCastStartResult
    data object RouteSelectionRequired : CatalogCastStartResult
    data object Unavailable : CatalogCastStartResult
    data class Unsupported(val message: CatalogMessage) : CatalogCastStartResult
}

sealed interface CatalogCastPlaybackEvent {
    data object RouteSelectionCancelled : CatalogCastPlaybackEvent
    data class Finished(
        val succeeded: Boolean,
        val message: CatalogMessage,
    ) : CatalogCastPlaybackEvent
}

interface CatalogCastPort {
    val playbackEvents: Flow<CatalogCastPlaybackEvent>
    suspend fun startCasting(request: CatalogCastRequest): CatalogCastStartResult
}

sealed interface CatalogUiEvent {
    data object OpenCastRouteChooser : CatalogUiEvent
    data class ShowMessage(val message: CatalogMessage) : CatalogUiEvent
}

enum class CatalogUpdateAction {
    None,
    DownloadLatest,
    Downloading,
    InstallLatest,
    InstallPermissionRequired,
}

data class CatalogUpdateNotice(
    val latestVersionName: String,
    val downloadSha256: String?,
    val action: CatalogUpdateAction,
)

interface CatalogAppUpdatePort {
    val notice: Flow<CatalogUpdateNotice?>
    suspend fun installDownloadedUpdate(expectedSha256: String?): Result<Unit>
}
