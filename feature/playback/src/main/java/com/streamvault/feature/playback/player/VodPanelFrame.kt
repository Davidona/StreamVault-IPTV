package com.streamvault.feature.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.AppDialog
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.AppSpacing
import com.streamvault.core.ui.interaction.TvClickableSurface

private val PanelSpacing = AppSpacing()
private val PanelShape = RoundedCornerShape(24.dp)
private val PanelWidth = 400.dp
private val PanelRowShape = RoundedCornerShape(12.dp)

/** Clears the badge row at the top, so the stream badges stay visible beside an open panel. */
private val PanelTopMargin = VOD_OVERLAY_VERTICAL_PADDING + PanelSpacing.xxl

/**
 * Sits right above the button row, next to the buttons that open it. It covers the timeline's end and the time
 * labels on that side, which is the price of staying close to its button and of leaving room for a full list.
 */
private val PanelBottomMargin = VOD_OVERLAY_VERTICAL_PADDING + VOD_BUTTON_ROW_HEIGHT + VOD_OVERLAY_ROW_GAP

/**
 * Frame shared by the VOD side panels: an opaque, rounded surface anchored bottom-end above the button row, which
 * stays visible behind it. No dim, blur or gradient (P3: it covers a large part of the screen). The dialog
 * window keeps D-pad focus inside, so Left cannot reach the controls behind, and Back closes it.
 */
@Composable
internal fun VodPanelFrame(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = PanelTopMargin, end = VOD_OVERLAY_HORIZONTAL_PADDING, bottom = PanelBottomMargin),
            contentAlignment = Alignment.BottomEnd,
        ) {
            CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary) {
                Column(
                    modifier = Modifier
                        .width(PanelWidth)
                        .clip(PanelShape)
                        .background(AppColors.Surface)
                        .padding(PanelSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(PanelSpacing.md),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PanelSpacing.sm),
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    content()
                }
            }
        }
    }
}

/**
 * Row of a panel list. Resting rows are flat, the selected row is a tonal container with a check (never color
 * alone), and focus fills the row with [AppColors.Focus] and [AppColors.Canvas] text. [opensPage] shows a chevron.
 */
@Composable
internal fun VodPanelRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
    selected: Boolean = false,
    opensPage: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TvClickableSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(PanelRowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AppColors.SelectedContainer else Color.Transparent,
            contentColor = if (selected) AppColors.OnSelectedContainer else AppColors.TextPrimary,
            focusedContainerColor = AppColors.Focus,
            focusedContentColor = AppColors.Canvas,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = AppColors.TextDisabled,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = PanelSpacing.sm, vertical = PanelSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PanelSpacing.sm),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected || opensPage) {
                Icon(
                    imageVector = if (selected) Icons.Default.Check else Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
