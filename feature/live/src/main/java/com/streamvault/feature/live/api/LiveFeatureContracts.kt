package com.streamvault.feature.live.api

import androidx.compose.runtime.Composable
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.PlayerEngine
import kotlinx.coroutines.flow.Flow

/** Presentation-owned origin values used when preview playback moves between Home and Guide. */
enum class LivePreviewOrigin {
    HOME,
    GUIDE,
}

/** A preview engine and its stream identity handed between live surfaces and the host player. */
data class LivePreviewSession(
    val engine: PlayerEngine,
    val channelId: Long,
    val providerId: Long,
    val streamInfo: StreamInfo,
)

/** Host seam for the existing playback-owned preview handoff manager. */
interface LivePreviewHandoffPort {
    val reverseHandoffOrigin: Flow<LivePreviewOrigin?>

    fun registerPreviewSession(
        channelId: Long,
        providerId: Long,
        streamInfo: StreamInfo,
        engine: PlayerEngine,
        origin: LivePreviewOrigin = LivePreviewOrigin.HOME,
    )

    fun registerPreviewSession(
        channel: Channel,
        streamInfo: StreamInfo,
        engine: PlayerEngine,
        origin: LivePreviewOrigin = LivePreviewOrigin.HOME,
    ) = registerPreviewSession(channel.id, channel.providerId, streamInfo, engine, origin)

    fun beginFullscreenHandoff(channelId: Long, engine: PlayerEngine?): Boolean

    fun consumeReverseHandoff(origin: LivePreviewOrigin): LivePreviewSession?

    fun clear(engine: PlayerEngine?)
}

/** Feature seam for host-provided stream preparation and URL renewal policy. */
interface LivePreviewStreamPreparer {
    suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo>
}

/** Host seam for optional platform refresh work triggered by live presentation. */
interface LiveSurfaceRefreshPort {
    suspend fun refreshTvInputCatalog()
}

data class LiveMultiViewStatus(
    val channelCount: Int = 0,
    val slotCapacity: Int = 4,
)

interface LiveMultiViewStatusPort {
    val status: Flow<LiveMultiViewStatus>
}

/** Typed Home channel intent consumed by the app composition root. */
data class LiveChannelPlaybackRequest(
    val channel: Channel,
    val categoryId: Long?,
    val providerId: Long?,
    val isVirtual: Boolean,
    val combinedProfileId: Long?,
    val combinedSourceFilterProviderId: Long?,
    val returnRoute: String?,
)

/** Typed Guide live/archive intent consumed by the app composition root. */
data class LiveArchivePlaybackRequest(
    val channel: Channel,
    val program: Program,
    val categoryId: Long?,
    val isVirtual: Boolean,
    val combinedProfileId: Long?,
    val returnRoute: String?,
)

/** App-owned multi-view planner UI injected into Home without a feature-to-feature dependency. */
typealias LiveMultiViewPlannerContent = @Composable (
    selectedChannel: Channel?,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
) -> Unit

/** Home content injected by the composition root into the feature-owned route. */
typealias LiveTvContent = @Composable (
    initialCategoryId: Long?,
    onPlaybackRequested: (LiveChannelPlaybackRequest) -> Unit,
    onNavigate: (String) -> Unit,
) -> Unit

/** Guide content injected by the composition root into the feature-owned route. */
typealias LiveEpgContent = @Composable (
    initialCategoryId: Long?,
    initialAnchorTime: Long?,
    initialFavoritesOnly: Boolean,
    onChannelPlaybackRequested: (LiveChannelPlaybackRequest) -> Unit,
    onArchivePlaybackRequested: (LiveArchivePlaybackRequest) -> Unit,
    onNavigate: (String) -> Unit,
) -> Unit
