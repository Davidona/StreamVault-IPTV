package com.streamvault.feature.playback.player.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.tv.material3.ClickableSurfaceDefaults
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.LocalAppShapes
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.core.ui.design.selectableFocusBorder
import com.streamvault.core.ui.interaction.TvClickableSurface

/** The same frame and row treatment for both levels of the playback browser. */
@Composable
internal fun PlayerBrowsePanel(content: @Composable ColumnScope.() -> Unit) {
    val spacing = LocalAppSpacing.current
    val shape = LocalAppShapes.current.medium
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.CenterStart)
                .widthIn(max = maxWidth).width(spacing.channelListOverlayWidth)
                .fillMaxHeight().padding(spacing.sm).clip(shape)
                .background(AppColors.Canvas).padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
            content = content
        )
    }
}

@Composable
internal fun PlayerBrowseRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = LocalAppShapes.current.xSmall
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = LocalAppSpacing.current.channelListMinTouchTarget),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AppColors.SurfaceElevated else AppColors.Canvas,
            focusedContainerColor = AppColors.SurfaceEmphasis,
            pressedContainerColor = AppColors.SurfaceEmphasis,
            contentColor = AppColors.TextPrimary,
            focusedContentColor = AppColors.TextPrimary
        ),
        border = selectableFocusBorder(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        content = content
    )
}

@Composable
internal fun PlayerBrowseAction(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tertiary: Boolean = false,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val spacing = LocalAppSpacing.current
    val shape = LocalAppShapes.current.xSmall
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = spacing.channelListMinTouchTarget)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (tertiary) AppColors.Canvas else AppColors.SurfaceElevated,
            focusedContainerColor = AppColors.SurfaceEmphasis,
            pressedContainerColor = AppColors.SurfaceEmphasis,
            contentColor = AppColors.TextPrimary,
            focusedContentColor = AppColors.TextPrimary
        ),
        border = selectableFocusBorder(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        if (icon != null) {
            Box(Modifier.size(spacing.channelListMinTouchTarget), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = AppColors.TextPrimary, modifier = Modifier.size(spacing.lg))
            }
        } else {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (tertiary) AppColors.TextSecondary else AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = spacing.xs, vertical = spacing.xs)
            )
        }
    }
}
