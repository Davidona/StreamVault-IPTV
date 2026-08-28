package com.streamvault.app.ui.screens.epg

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.feature.live.presentation.epg.GuideDensity
import com.streamvault.feature.live.presentation.epg.currentLiveGuideNow
import com.streamvault.core.ui.image.ChannelLogoBadge
import com.streamvault.player.ui.PlayerRenderView
import com.streamvault.domain.playback.isArchivePlayable
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.feature.live.presentation.model.guideLookupKey
import com.streamvault.feature.live.presentation.time.LocalLiveTimeFormat
import com.streamvault.feature.live.presentation.time.createLiveTimeFormat
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerRenderSurfaceType
import com.streamvault.player.PlayerSurfaceResizeMode
import java.util.Date

internal data class GuideHeroSelection(
    val channel: Channel,
    val program: Program?,
    val isFallbackToChannel: Boolean
)

internal fun resolveGuideHeroSelection(
    uiState: EpgUiState,
    focusedChannel: Channel?,
    focusedProgram: Program?,
    now: Long
): GuideHeroSelection? {
    val resolvedChannel = focusedChannel
        ?.let { current -> uiState.channels.firstOrNull { it.id == current.id } }
        ?: uiState.channels.firstOrNull()
        ?: return null
    val programs = resolvedChannel.guideLookupKey()?.let { lookupKey ->
        uiState.programsByChannel[lookupKey].orEmpty()
    }.orEmpty()
    val resolvedProgram = focusedProgram?.let { focused ->
        programs.firstOrNull {
            it.startTime == focused.startTime &&
                it.endTime == focused.endTime &&
                it.title == focused.title
        }
    } ?: programs.firstOrNull { now in it.startTime until it.endTime } ?: programs.firstOrNull()
    return GuideHeroSelection(
        channel = resolvedChannel,
        program = resolvedProgram,
        isFallbackToChannel = resolvedProgram == null
    )
}

@Composable
internal fun GuideHeroSection(
    uiState: EpgUiState,
    focusedChannel: Channel?,
    focusedProgram: Program?,
    modifier: Modifier = Modifier
) {
    val now = currentLiveGuideNow()
    val heroSelection by remember(uiState, focusedChannel, focusedProgram, now) {
        derivedStateOf {
            resolveGuideHeroSelection(
                uiState = uiState,
                focusedChannel = focusedChannel,
                focusedProgram = focusedProgram,
                now = now
            )
        }
    }

    ImmersiveGuideHero(
        selection = heroSelection,
        providerLabel = uiState.providerSourceLabel,
        selectedCategoryName = uiState.categories
            .firstOrNull { it.id == uiState.selectedCategoryId }
            ?.name
            .orEmpty(),
        isGuideStale = uiState.isGuideStale,
        channelCount = uiState.totalChannelCount,
        channelsWithSchedule = uiState.channelsWithSchedule,
        lastUpdatedAt = uiState.lastUpdatedAt,
        isRefreshing = uiState.isRefreshing,
        modifier = modifier
    )
}

@Composable
internal fun ImmersiveGuideHero(
    selection: GuideHeroSelection?,
    providerLabel: String,
    selectedCategoryName: String,
    isGuideStale: Boolean,
    channelCount: Int,
    channelsWithSchedule: Int,
    lastUpdatedAt: Long?,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier
) {
    val appTimeFormat = LocalLiveTimeFormat.current
    val format = remember(appTimeFormat) { appTimeFormat.createLiveTimeFormat() }
    val currentTime = System.currentTimeMillis()
    val lastUpdatedLabel = remember(lastUpdatedAt, currentTime) {
        lastUpdatedAt?.let { updatedAt ->
            val minutes = ((currentTime - updatedAt).coerceAtLeast(0L) / 60_000L).toInt()
            if (minutes <= 0) {
                "Updated just now"
            } else {
                "$minutes min ago"
            }
        }
    }

    Surface(
        modifier = modifier.height(96.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val channel = selection?.channel
            ChannelLogoBadge(
                channelName = channel?.name ?: stringResource(R.string.epg_title),
                logoUrl = channel?.logoUrl,
                modifier = Modifier
                    .width(54.dp)
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                val program = selection?.program
                Text(
                    text = program?.title ?: channel?.name ?: stringResource(R.string.epg_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 15.sp,
                        lineHeight = 18.sp
                    ),
                    color = OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        channel?.let { append("${it.number}. ${it.name}") }
                        if (providerLabel.isNotBlank()) {
                            if (isNotEmpty()) append("  |  ")
                            append(providerLabel)
                        }
                        if (selectedCategoryName.isNotBlank()) {
                            if (isNotEmpty()) append("  |  ")
                            append(selectedCategoryName)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (program != null) {
                    Text(
                        text = "${format.format(Date(program.startTime))} - ${format.format(Date(program.endTime))}",
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 11.sp),
                        color = OnSurface
                    )
                    if (currentTime in program.startTime until program.endTime) {
                        LinearProgressIndicator(
                            progress = { ((currentTime - program.startTime).toFloat() / (program.endTime - program.startTime).toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = Primary,
                            trackColor = SurfaceHighlight
                        )
                    }
                    Text(
                        text = program.description.ifBlank { stringResource(R.string.epg_hero_no_program_description) },
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = stringResource(R.string.epg_no_schedule),
                        style = MaterialTheme.typography.labelLarge,
                        color = OnSurface
                    )
                    Text(
                        text = stringResource(R.string.epg_hero_no_schedule_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(
                modifier = Modifier.widthIn(min = 176.dp, max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                GuideHeroBadge(text = stringResource(R.string.epg_schedule_summary_short, channelsWithSchedule, channelCount))
                if (isRefreshing) {
                    GuideHeroBadge(text = stringResource(R.string.epg_loading))
                }
                if (selection?.program != null && selection.channel.isArchivePlayable(selection.program, currentLiveGuideNow())) {
                    GuideHeroBadge(
                        text = if (selection.program.hasArchive) {
                            stringResource(R.string.epg_program_replay_ready)
                        } else {
                            stringResource(R.string.epg_program_replay_partial)
                        },
                        highlight = true
                    )
                }
                if (isGuideStale) {
                    GuideHeroBadge(
                        text = stringResource(R.string.epg_stale_short),
                        accentColor = Color(0xFFFF6B6B)
                    )
                }
                if (!lastUpdatedLabel.isNullOrBlank()) {
                    Text(
                        text = lastUpdatedLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceDim
                    )
                }
            }
        }
    }
}

@Composable
internal fun GuideHeroBadge(
    text: String,
    highlight: Boolean = false,
    accentColor: Color = Primary
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        colors = SurfaceDefaults.colors(
            containerColor = if (highlight) accentColor.copy(alpha = 0.18f) else SurfaceHighlight
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (highlight) accentColor else OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun GuidePreviewPane(
    previewPlayerEngine: PlayerEngine?,
    isPreviewLoading: Boolean,
    focusedChannel: Channel?,
    focusedProgram: Program?,
    modifier: Modifier = Modifier
) {
    val renderSurfaceType by (previewPlayerEngine?.renderSurfaceType)?.collectAsStateWithLifecycle(
        initialValue = PlayerRenderSurfaceType.SURFACE_VIEW
    ) ?: remember { mutableStateOf(PlayerRenderSurfaceType.SURFACE_VIEW) }
    val now = currentLiveGuideNow()
    val appTimeFormat = LocalLiveTimeFormat.current
    val timeFormat = remember(appTimeFormat) { appTimeFormat.createLiveTimeFormat() }

    Surface(
        modifier = modifier.height(150.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Video preview area (16:9 at 90dp height = 160dp wide)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (previewPlayerEngine != null) {
                    PlayerRenderView(
                        playerEngine = previewPlayerEngine,
                        resizeMode = PlayerSurfaceResizeMode.FIT,
                        surfaceType = renderSurfaceType,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (isPreviewLoading) {
                        CircularProgressIndicator(
                            color = Primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.live_preview_placeholder_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = OnSurfaceDim,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Program info
            val channel = focusedChannel
            val program = focusedProgram
            if (channel != null) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChannelLogoBadge(
                            channelName = channel.name,
                            logoUrl = channel.logoUrl,
                            modifier = Modifier.size(32.dp),
                            shape = RoundedCornerShape(8.dp)
                        )
                        Text(
                            text = if (channel.number > 0) "${channel.number}. ${channel.name}" else channel.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = OnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (program != null) {
                        Text(
                            text = program.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${timeFormat.format(Date(program.startTime))} ג€“ ${timeFormat.format(Date(program.endTime))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceDim
                        )
                        if (now in program.startTime until program.endTime) {
                            LinearProgressIndicator(
                                progress = { ((now - program.startTime).toFloat() / (program.endTime - program.startTime).toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth(0.6f)
                                    .height(3.dp),
                                color = Primary,
                                trackColor = SurfaceHighlight
                            )
                        }
                        if (program.description.isNotBlank()) {
                            Text(
                                text = program.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = OnSurfaceDim,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.epg_no_schedule),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.epg_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurfaceDim,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun GuideToolbarRow(
    selectedCategoryName: String,
    onOpenCategoryPicker: () -> Unit,
    onJumpToNow: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenOptions: () -> Unit,
    onGuideInteract: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GuideToolbarButton(
            label = selectedCategoryName,
            modifier = Modifier.widthIn(min = 200.dp, max = 280.dp),
            onClick = onOpenCategoryPicker,
            onFocused = onGuideInteract
        )
        GuideToolbarButton(
            label = stringResource(R.string.epg_jump_now),
            onClick = onJumpToNow,
            onFocused = onGuideInteract
        )
        GuideToolbarButton(
            label = stringResource(R.string.epg_search_label),
            onClick = onOpenSearch,
            onFocused = onGuideInteract
        )
        GuideToolbarButton(
            label = stringResource(R.string.epg_options_short),
            onClick = onOpenOptions,
            onFocused = onGuideInteract
        )
    }
}

@Composable
internal fun GuideToolbarButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier.onFocusChanged {
            if (it.isFocused && !focused) onFocused()
            focused = it.isFocused
        },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = SurfaceHighlight
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(14.dp)
            )
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
