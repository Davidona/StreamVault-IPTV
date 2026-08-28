package com.streamvault.feature.live.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.shell.StatusPill
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.FocusSpec
import com.streamvault.core.ui.image.ChannelLogoBadge
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.interaction.rememberTvInteractionSounds
import com.streamvault.domain.model.Channel
import com.streamvault.domain.playback.archivePlaybackCapability

@Composable
fun LiveChannelCard(
    channel: Channel,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isLocked: Boolean = false,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false,
    isRecording: Boolean = false,
    isScheduledRecording: Boolean = false,
    lockedLabel: String = "Locked",
    liveLabel: String = "LIVE",
    catchUpLabel: String = "Catch-up",
    recordingLabel: String = "Recording",
    scheduledLabel: String = "Scheduled"
) {
    val hasArchive = channel.archivePlaybackCapability().canBuildReplayCandidate
    val description = buildString {
        append(channel.number.takeIf { it > 0 }?.let { "$it  ${channel.name}" } ?: channel.name)
        if (!isLocked) {
            channel.currentProgram?.title?.takeIf { it.isNotBlank() }?.let { append(". $it") }
            if (channel.isFavorite) append(". Favorite")
            if (hasArchive) append(". Catch-up available")
        }
    }
    LiveFocusableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        isReorderMode = isReorderMode,
        isDragging = isDragging,
        semanticsDescription = description,
        semanticsStateDescription = if (isLocked) lockedLabel else null
    ) { isFocused ->
        if (!isLocked) {
            ChannelLogoBadge(
                channelName = channel.name,
                logoUrl = channel.logoUrl,
                textStyle = MaterialTheme.typography.titleMedium,
                textColor = AppColors.TextSecondary,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.55f).background(Brush.verticalGradient(listOf(Color.Transparent, AppColors.HeroBottom))))
        Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = if (isLocked) lockedLabel else channel.name, style = MaterialTheme.typography.titleSmall, color = if (isFocused) AppColors.TextPrimary else AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!isLocked) {
                channel.currentProgram?.let { program ->
                    Text(text = program.title, style = MaterialTheme.typography.labelMedium, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { liveChannelProgressFraction(nowMs, program.startTime, program.endTime) }, modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)), color = AppColors.Info, trackColor = AppColors.SurfaceEmphasis)
                }
            }
        }
        if (!isLocked) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (channel.isFavorite) Icon(imageVector = Icons.Filled.Star, contentDescription = null, tint = AppColors.Warning, modifier = Modifier.size(14.dp))
                    if (hasArchive) StatusPill(label = catchUpLabel, containerColor = AppColors.Brand)
                    if (isRecording) StatusPill(label = recordingLabel, containerColor = AppColors.Live)
                    else if (isScheduledRecording) StatusPill(label = scheduledLabel, containerColor = AppColors.Warning, contentColor = Color.Black)
                    StatusPill(label = liveLabel, containerColor = AppColors.Live)
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StatusPill(label = lockedLabel, containerColor = AppColors.SurfaceEmphasis, contentColor = AppColors.TextPrimary) }
        }
    }
}

@Composable
private fun LiveFocusableCard(
    onClick: () -> Unit,
    modifier: Modifier,
    onLongClick: (() -> Unit)?,
    isReorderMode: Boolean,
    isDragging: Boolean,
    semanticsDescription: String?,
    semanticsStateDescription: String?,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val sounds = rememberTvInteractionSounds()
    val requester = remember { FocusRequester() }
    val scale by animateFloatAsState(targetValue = if (isFocused && (!isReorderMode || isDragging)) FocusSpec.FocusedScale else 1f, animationSpec = tween(160), label = "liveCardScale")
    Surface(
        onClick = { sounds.playSelect(); onClick() },
        onLongClick = onLongClick,
        modifier = modifier.width(220.dp).height(124.dp).focusRequester(requester).mouseClickable(focusRequester = requester, onLongClick = onLongClick, onClick = { sounds.playSelect(); onClick() }).graphicsLayer { scaleX = scale; scaleY = scale }.semantics(mergeDescendants = true) {
            semanticsDescription?.let { contentDescription = it }
            semanticsStateDescription?.let { stateDescription = it }
        }.onFocusChanged { if (it.isFocused && !isFocused) sounds.playNavigate(); isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.Surface, focusedContainerColor = AppColors.SurfaceEmphasis),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(border = BorderStroke(if (isDragging) 4.dp else FocusSpec.CardBorderWidth, if (isDragging) AppColors.Warning else AppColors.Focus), shape = RoundedCornerShape(12.dp))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) { Box(modifier = Modifier.fillMaxSize()) { content(isFocused) } }
}
