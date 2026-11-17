package com.streamvault.feature.catalog.presentation.components

import androidx.compose.foundation.layout.*
import com.streamvault.core.ui.layout.AdaptiveRow
import com.streamvault.core.ui.layout.isPortraitLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.streamvault.core.ui.design.*
import com.streamvault.core.ui.interaction.TvClickableSurface

@Composable
fun VodHeroStrip(title: String, subtitle: String, actionLabel: String, onClick: () -> Unit,
    focusRequester: FocusRequester, modifier: Modifier = Modifier) {
    val spacing = LocalAppSpacing.current
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(horizontal = spacing.lg)
            .heightIn(min = 132.dp).focusRequester(focusRequester),
        shape = ClickableSurfaceDefaults.shape(BrowseShapes.Panel),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = BrowseColors.SurfaceElevated,
            focusedContainerColor = BrowseColors.SurfaceAccent,
            contentColor = BrowseColors.TextPrimary,
            focusedContentColor = BrowseColors.TextPrimary),
        border = selectableFocusBorder(BrowseShapes.Panel),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        AdaptiveRow(
            modifier = Modifier.fillMaxWidth().padding(spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = if (isPortraitLayout()) Modifier.fillMaxWidth() else Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = BrowseColors.TextPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = BrowseColors.TextSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Surface(shape = BrowseShapes.Control,
                colors = SurfaceDefaults.colors(containerColor = BrowseColors.Brand)) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = BrowseColors.OnPrimary,
                    modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
