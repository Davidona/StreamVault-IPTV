package com.streamvault.feature.live.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.interaction.rememberTvInteractionSounds
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight

data class LiveSelectionChip(
    val key: String,
    val label: String,
    val supportingText: String? = null
)

data class LiveChipRowItem(
    val key: String,
    val label: String,
    val supportingText: String? = null,
    val onClick: () -> Unit
)

@Composable
fun LiveSelectionChipRow(
    title: String,
    chips: List<LiveSelectionChip>,
    selectedKey: String?,
    onChipSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp)
) {
    LiveChipRowSection(
        title = title,
        subtitle = subtitle,
        selectedKey = selectedKey,
        chips = chips.map { chip ->
            LiveChipRowItem(
                key = chip.key,
                label = chip.label,
                supportingText = chip.supportingText,
                onClick = { onChipSelected(chip.key) }
            )
        },
        modifier = modifier,
        contentPadding = contentPadding
    )
}

@Composable
fun LiveChipRowSection(
    chips: List<LiveChipRowItem>,
    selectedKey: String?,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    headerHorizontalPadding: Int = 24,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp),
    chipHorizontalPadding: Int = 16,
    supportingTextStyle: TextStyle = MaterialTheme.typography.bodySmall,
    supportingTextMaxLines: Int = Int.MAX_VALUE,
    focusedContainerBoostWhenSelected: Boolean = false
) {
    if (chips.isEmpty()) return
    val sounds = rememberTvInteractionSounds()
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceDim,
                modifier = Modifier.padding(horizontal = headerHorizontalPadding.dp)
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim,
                modifier = Modifier.padding(horizontal = headerHorizontalPadding.dp, vertical = 2.dp)
            )
        }
        LazyRow(contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items = chips, key = { it.key }, contentType = { "live_selection_chip" }) { chip ->
                val selected = chip.key == selectedKey
                var wasFocused by remember(chip.key) { mutableStateOf(false) }
                val requester = remember(chip.key) { FocusRequester() }
                Surface(
                    onClick = { sounds.playSelect(); chip.onClick() },
                    modifier = Modifier
                        .focusRequester(requester)
                        .mouseClickable(focusRequester = requester, onClick = { sounds.playSelect(); chip.onClick() })
                        .onFocusChanged {
                            if (it.isFocused && !wasFocused) sounds.playNavigate()
                            wasFocused = it.isFocused
                        },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                        focusedContainerColor = if (selected && focusedContainerBoostWhenSelected) Primary.copy(alpha = 0.28f) else SurfaceHighlight,
                        contentColor = if (selected) Primary else OnSurface,
                        focusedContentColor = OnSurface
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, FocusBorder),
                            shape = RoundedCornerShape(999.dp)
                        )
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = chipHorizontalPadding.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(text = chip.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        chip.supportingText?.takeIf { it.isNotBlank() }?.let {
                            Text(text = it, style = supportingTextStyle, color = OnSurfaceDim, maxLines = supportingTextMaxLines, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
