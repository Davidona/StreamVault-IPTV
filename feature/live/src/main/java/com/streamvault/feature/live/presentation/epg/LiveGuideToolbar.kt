package com.streamvault.feature.live.presentation.epg

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight

data class LiveGuideToolbarLabels(
    val jumpNow: String,
    val search: String,
    val options: String
)

@Composable
fun LiveGuideToolbarRow(
    selectedCategoryName: String,
    labels: LiveGuideToolbarLabels,
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
        LiveGuideToolbarButton(
            label = selectedCategoryName,
            modifier = Modifier.widthIn(min = 200.dp, max = 280.dp),
            onClick = onOpenCategoryPicker,
            onFocused = onGuideInteract
        )
        LiveGuideToolbarButton(label = labels.jumpNow, onClick = onJumpToNow, onFocused = onGuideInteract)
        LiveGuideToolbarButton(label = labels.search, onClick = onOpenSearch, onFocused = onGuideInteract)
        LiveGuideToolbarButton(label = labels.options, onClick = onOpenOptions, onFocused = onGuideInteract)
    }
}

@Composable
fun LiveGuideToolbarButton(
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
