package com.streamvault.feature.live.presentation.epg

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.image.ChannelLogoBadge
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.feature.live.presentation.time.LocalLiveTimeFormat
import com.streamvault.feature.live.presentation.time.createLiveTimeFormat
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerRenderSurfaceType
import com.streamvault.player.PlayerSurfaceResizeMode
import com.streamvault.player.ui.PlayerRenderView
import java.util.Date

data class LiveGuidePreviewLabels(
    val title: String,
    val placeholderTitle: String,
    val noSchedule: String
)

@Composable
fun LiveGuidePreviewPane(
    previewPlayerEngine: PlayerEngine?,
    isPreviewLoading: Boolean,
    focusedChannel: Channel?,
    focusedProgram: Program?,
    labels: LiveGuidePreviewLabels,
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
            modifier = Modifier.fillMaxSize().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                            text = labels.placeholderTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = OnSurfaceDim,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

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
                            text = "${timeFormat.format(Date(program.startTime))} – ${timeFormat.format(Date(program.endTime))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceDim
                        )
                        if (now in program.startTime until program.endTime) {
                            LinearProgressIndicator(
                                progress = { ((now - program.startTime).toFloat() / (program.endTime - program.startTime).toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(0.6f).height(3.dp),
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
                        Text(text = labels.noSchedule, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                    }
                }
            } else {
                Text(
                    text = labels.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurfaceDim,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
