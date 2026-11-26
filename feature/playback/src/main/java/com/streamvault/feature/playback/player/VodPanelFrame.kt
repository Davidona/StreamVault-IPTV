package com.streamvault.feature.playback.player

import android.view.WindowManager
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.AppDialog
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.AppSpacing
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.feature.playback.R

private val PanelSpacing = AppSpacing()
private val PanelShape = RoundedCornerShape(24.dp)
private val PanelWidth = 400.dp
private val PanelRowShape = RoundedCornerShape(12.dp)
private val PanelValueMaxWidth = 120.dp
private val PanelChipShape = RoundedCornerShape(50)

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
 * window keeps D-pad focus inside, so Left cannot reach the controls behind, and Back closes it. [onBack] shows a
 * focusable back button in place of the title icon, for in-frame sub-pages.
 */
@Composable
internal fun VodPanelFrame(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ClearWindowDim()
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
                        if (onBack != null) {
                            VodControlButton(onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.player_back_to_menu),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        } else {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                        }
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
 * alone), and focus fills the row with [AppColors.Focus] and [AppColors.Canvas] text. [opensPage] shows a chevron,
 * [trailing] replaces it (for example a progress indicator). [value] is the current setting, shown at the end
 * before the chevron. [leading] replaces [icon] with larger content, for example an episode thumbnail.
 */
@Composable
internal fun VodPanelRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    opensPage: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
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
            if (leading != null) {
                leading()
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
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
            if (value != null) {
                Text(
                    text = value,
                    modifier = Modifier.widthIn(max = PanelValueMaxWidth),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) {
                trailing()
            } else if (selected || opensPage) {
                Icon(
                    imageVector = if (selected) Icons.Default.Check else Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Takes focus as soon as the row is first placed, instead of one frame later from an effect: the lazy list has already
 * scrolled to the row by then, so the request cannot make it jump.
 */
@Composable
internal fun rememberPlacementFocus(requester: FocusRequester, tag: String): Modifier {
    val requested = remember { BooleanArray(1) }
    return Modifier
        .focusRequester(requester)
        .onGloballyPositioned {
            if (!requested[0]) {
                requested[0] = true
                requester.requestFocusSafely(tag, "Target row on placement")
            }
        }
}

/**
 * Pill for a horizontal row of choices at the top of a panel, such as the seasons. The chosen one is a tonal
 * container with a check (never color alone), the others are filled with the control container, and focus fills the
 * chip with [AppColors.Focus] and [AppColors.Canvas] text, like a row.
 */
@Composable
internal fun VodPanelChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(PanelChipShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AppColors.SelectedContainer else AppColors.current.controlContainer,
            contentColor = if (selected) AppColors.OnSelectedContainer else AppColors.TextPrimary,
            focusedContainerColor = AppColors.Focus,
            focusedContentColor = AppColors.Canvas,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(horizontal = PanelSpacing.md, vertical = PanelSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PanelSpacing.xs),
        ) {
            if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A dialog window dims whatever is behind it by 60% black unless told otherwise. That would darken the video and
 * the overlay under the panel, a full-screen translucent layer (P3) that also lowers the contrast of the header.
 */
@Composable
private fun ClearWindowDim() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }
}
