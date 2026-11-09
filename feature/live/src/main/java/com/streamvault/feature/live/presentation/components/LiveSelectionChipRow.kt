package com.streamvault.feature.live.presentation.components

import androidx.compose.ui.graphics.Color
import com.streamvault.core.ui.design.selectableFocusBorder
import com.streamvault.core.ui.design.BrowseColors
import com.streamvault.core.ui.design.BrowseShapes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.interaction.rememberTvInteractionSounds
import com.streamvault.core.ui.design.browse.OnSurfaceDim

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
    supportingTextMaxLines: Int = Int.MAX_VALUE
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
                    // Secondary control: filled at rest, no stroke. Selected keeps its fill when focused; the ring carries focus.
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) BrowseColors.SelectedContainer else BrowseColors.ControlContainer,
                        focusedContainerColor = if (selected) BrowseColors.SelectedContainer else BrowseColors.SurfaceAccent,
                        contentColor = if (selected) BrowseColors.OnSelectedContainer else BrowseColors.TextPrimary,
                        focusedContentColor = if (selected) BrowseColors.OnSelectedContainer else BrowseColors.TextPrimary
                    ),
                    shape = ClickableSurfaceDefaults.shape(BrowseShapes.Control),
                    border = selectableFocusBorder(BrowseShapes.Control),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = chipHorizontalPadding.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(text = chip.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        chip.supportingText?.takeIf { it.isNotBlank() }?.let {
                            Text(text = it, style = supportingTextStyle, color = if (selected || wasFocused) Color.Unspecified else BrowseColors.TextSecondary, maxLines = supportingTextMaxLines, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
