package com.streamvault.feature.live.home

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragHandle
import androidx.tv.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.image.ChannelLogoBadge

@Composable
internal fun OrderEditorRow(name: String, position: Int, badge: String?, enabled: Boolean,
    modifier: Modifier = Modifier, showLogo: Boolean = false, logoUrl: String? = null, onClick: () -> Unit) {
    val spacing = LocalAppSpacing.current
    val shape = RoundedCornerShape(spacing.xs)
    var focused by remember { mutableStateOf(false) }
    TvClickableSurface(onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) AppColors.Focus else Color.Transparent, shape),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.Surface,
            focusedContainerColor = AppColors.SurfaceAccent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = if (showLogo) spacing.xs else spacing.sm),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            Text(position.toString(), Modifier.width(spacing.screenGutter), textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showLogo) {
                ChannelLogoBadge(channelName = name, logoUrl = logoUrl,
                    modifier = Modifier.size(spacing.xl).clearAndSetSemantics { },
                    shape = RoundedCornerShape(spacing.xs), backgroundColor = AppColors.SurfaceEmphasis,
                    textStyle = MaterialTheme.typography.labelLarge, textColor = AppColors.TextPrimary)
            }
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) Text(badge, style = MaterialTheme.typography.labelLarge,
                color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.DragHandle, contentDescription = null, tint = AppColors.TextSecondary)
        }
    }
}

@Composable
internal fun OrderEditorTab(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val spacing = LocalAppSpacing.current
    val shape = RoundedCornerShape(spacing.xs)
    var focused by remember { mutableStateOf(false) }
    TvClickableSurface(onClick = onClick, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = selected }.onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) AppColors.Focus else Color.Transparent, shape),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = if (selected) AppColors.SurfaceAccent else AppColors.Surface,
            focusedContainerColor = AppColors.SurfaceAccent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)) {
        Row(Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = AppColors.TextPrimary)
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = AppColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Shared, compact controls keep the ordered list visible on both TV and phone. */
@Composable
internal fun OrderEditorAction(label: String, enabled: Boolean = true, emphasized: Boolean = false,
    onClick: () -> Unit) {
    val spacing = LocalAppSpacing.current
    val shape = RoundedCornerShape(spacing.xs)
    var focused by remember { mutableStateOf(false) }
    if (!enabled) {
        Box(Modifier.heightIn(min = 48.dp).background(AppColors.Surface, shape)
            .semantics { disabled() }.padding(horizontal = spacing.md, vertical = spacing.sm),
            contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        TvClickableSurface(onClick = onClick,
            modifier = Modifier.heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) AppColors.Focus else Color.Transparent, shape),
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (emphasized) AppColors.SurfaceAccent else AppColors.Surface,
                focusedContainerColor = AppColors.SurfaceAccent),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)) {
            Box(Modifier.padding(horizontal = spacing.md, vertical = spacing.sm), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = AppColors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun OrderEditorHeader(title: String, enabled: Boolean, saveEnabled: Boolean,
    onCancel: () -> Unit, onSave: () -> Unit) {
    val spacing = LocalAppSpacing.current
    val cancelLabel = androidx.compose.ui.res.stringResource(com.streamvault.feature.live.R.string.action_cancel)
    val saveLabel = androidx.compose.ui.res.stringResource(com.streamvault.feature.live.R.string.action_save_order)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    OrderEditorAction(cancelLabel, enabled, onClick = onCancel)
                    OrderEditorAction(saveLabel, enabled && saveEnabled, emphasized = true, onClick = onSave)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                OrderEditorAction(cancelLabel, enabled, onClick = onCancel)
                OrderEditorAction(saveLabel, enabled && saveEnabled, emphasized = true, onClick = onSave)
            }
        }
    }
}
