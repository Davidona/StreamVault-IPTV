package com.streamvault.feature.live.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.streamvault.core.ui.design.AppMotion
import com.streamvault.core.ui.design.FocusSpec
import com.streamvault.core.ui.image.ChannelLogoBadge
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.interaction.rememberTvInteractionSounds
import com.streamvault.domain.model.Channel
import com.streamvault.domain.playback.archivePlaybackCapability

@Composable
fun LiveChannelRowSurface(
    channel: Channel,
    nowMs: Long,
    onClick: () -> Unit,
    sourceBadgeLabel: String? = null,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isLocked: Boolean = false,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false,
    rowHeight: Dp = 68.dp,
    liveLabel: String = "LIVE",
    noScheduleLabel: String = "No schedule",
    lockedLabel: String = "Locked",
    movingLabel: String = "Moving",
    savedLabel: String = "Saved",
    catchUpLabel: String = "Catch-up",
    accessibilityDescription: String? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val sounds = rememberTvInteractionSounds()
    val focusRequester = remember { FocusRequester() }
    val hasArchive = channel.archivePlaybackCapability().canBuildReplayCandidate
    val resolvedAccessibilityDescription = accessibilityDescription ?: buildString {
        append(channel.number.takeIf { it > 0 }?.let { "Channel $it, ${channel.name}" } ?: channel.name)
        channel.currentProgram?.title?.takeIf { it.isNotBlank() }?.let { append(". Now playing $it") }
        if (channel.isFavorite) append(". Favorite")
        if (hasArchive) append(". Catch-up available")
    }
    val scale by animateFloatAsState(
        targetValue = if (isDragging) FocusSpec.FocusedScale else 1f,
        animationSpec = AppMotion.FocusSpec,
        label = "liveRowScale"
    )
    val channelDescription = buildString {
        append(channel.number.takeIf { it > 0 }?.let { "$it  ${channel.name}" } ?: channel.name)
        channel.currentProgram?.title?.takeIf { it.isNotBlank() }?.let { append(". $it") }
        if (channel.isFavorite) append(". Favorite")
        if (hasArchive) append(". Catch-up available")
    }
    Surface(
        onClick = { sounds.playSelect(); onClick() },
        onLongClick = onLongClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .fillMaxWidth()
            .mouseClickable(focusRequester = focusRequester, onLongClick = onLongClick, onClick = { sounds.playSelect(); onClick() })
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics(mergeDescendants = true) {
                contentDescription = resolvedAccessibilityDescription
                if (isLocked) stateDescription = lockedLabel
            }
            .onFocusChanged { if (it.isFocused && !isFocused) sounds.playNavigate(); isFocused = it.isFocused },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.SurfaceElevated, focusedContainerColor = AppColors.SurfaceEmphasis),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(if (isDragging) 4.dp else FocusSpec.BorderWidth, if (isDragging) AppColors.Warning else AppColors.Focus),
                shape = RoundedCornerShape(16.dp)
            )
        )
    ) {
        Box {
            LiveChannelRowCard(
                channel = channel,
                nowMs = nowMs,
                sourceBadgeLabel = sourceBadgeLabel,
                modifier = Modifier.fillMaxWidth(),
                rowHeight = rowHeight,
                liveLabel = liveLabel,
                noScheduleLabel = noScheduleLabel,
                savedLabel = savedLabel,
                catchUpLabel = catchUpLabel
            )
            if (isLocked) {
                Box(modifier = Modifier.fillMaxSize().background(AppColors.HeroBottom.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
                    StatusPill(label = lockedLabel, containerColor = AppColors.SurfaceEmphasis, contentColor = AppColors.TextPrimary)
                }
            }
            if (isReorderMode && isDragging) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    StatusPill(label = movingLabel, containerColor = AppColors.Warning, contentColor = Color.Black)
                }
            }
            if (!isLocked && !isReorderMode && channel.isFavorite) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(4.dp)) {
                    Icon(imageVector = Icons.Filled.Star, contentDescription = null, tint = AppColors.Warning, modifier = Modifier.size(11.dp))
                }
            }
        }
    }
}

@Composable
fun LiveChannelRowCard(
    channel: Channel,
    nowMs: Long,
    sourceBadgeLabel: String? = null,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 68.dp,
    liveLabel: String = "LIVE",
    noScheduleLabel: String = "No schedule",
    savedLabel: String = "Saved",
    catchUpLabel: String = "Catch-up"
) {
    val dense = rowHeight <= 56.dp
    val ultraCompact = rowHeight <= 60.dp
    val logoWidth = if (dense) 42.dp else if (ultraCompact) 46.dp else 52.dp
    val hasArchive = channel.archivePlaybackCapability().canBuildReplayCandidate
    Box(modifier = modifier.clip(RoundedCornerShape(18.dp)).background(AppColors.SurfaceElevated).fillMaxWidth().height(rowHeight)) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = if (ultraCompact) 8.dp else 10.dp, vertical = if (ultraCompact) 5.dp else 6.dp), horizontalArrangement = Arrangement.spacedBy(if (ultraCompact) 8.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(logoWidth).fillMaxHeight().clip(RoundedCornerShape(12.dp))) {
                ChannelLogoBadge(channelName = channel.name, logoUrl = channel.logoUrl, backgroundColor = AppColors.SurfaceEmphasis, contentPadding = PaddingValues(if (dense) 5.dp else 8.dp), textStyle = MaterialTheme.typography.titleLarge, textColor = AppColors.TextSecondary, modifier = Modifier.fillMaxSize())
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!dense) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(label = liveLabel, containerColor = AppColors.Live)
                        sourceBadgeLabel?.takeIf { it.isNotBlank() }?.let { StatusPill(label = it, containerColor = AppColors.SurfaceEmphasis, contentColor = AppColors.TextPrimary) }
                        if (channel.isFavorite) StatusPill(label = savedLabel, containerColor = AppColors.Warning, contentColor = Color.Black)
                        if (hasArchive) StatusPill(label = catchUpLabel, containerColor = AppColors.Brand)
                    }
                }
                Text(text = buildString { channel.number.takeIf { it > 0 }?.let { append(it.toString().padStart(2, '0')); append("  ") } ?: append("--  "); append(channel.name) }, style = if (dense) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                channel.currentProgram?.let { program ->
                    Text(text = program.title, style = if (dense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!dense) {
                        LinearProgressIndicator(progress = { liveChannelProgressFraction(nowMs, program.startTime, program.endTime) }, modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(999.dp)), color = AppColors.Info, trackColor = AppColors.SurfaceEmphasis)
                    }
                } ?: Text(text = noScheduleLabel, style = if (dense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall, color = AppColors.TextTertiary)
            }
        }
    }
}
