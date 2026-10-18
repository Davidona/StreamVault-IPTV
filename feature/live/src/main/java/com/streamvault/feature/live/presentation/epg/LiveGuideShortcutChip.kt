package com.streamvault.feature.live.presentation.epg

import com.streamvault.core.ui.design.BrowseShapes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.design.browse.FocusBorder
import com.streamvault.core.ui.design.browse.OnSurface
import com.streamvault.core.ui.design.browse.Primary
import com.streamvault.core.ui.design.browse.SurfaceElevated
import com.streamvault.core.ui.design.browse.SurfaceHighlight

@Composable
fun LiveGuideShortcutChip(
    modifier: Modifier = Modifier,
    label: String,
    onClick: () -> Unit,
    isSelected: Boolean = false
) {
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
            focusedContainerColor = SurfaceHighlight,
            contentColor = if (isSelected) Primary else OnSurface,
            focusedContentColor = OnSurface
        ),
        shape = ClickableSurfaceDefaults.shape(BrowseShapes.Control),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = BrowseShapes.Control
            )
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
