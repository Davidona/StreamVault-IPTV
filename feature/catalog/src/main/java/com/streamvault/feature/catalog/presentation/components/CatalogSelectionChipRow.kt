package com.streamvault.feature.catalog.presentation.components

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.interaction.rememberTvInteractionSounds

data class SelectionChip(
    val key: String,
    val label: String,
    val supportingText: String? = null
)

data class ChipRowItem(
    val key: String,
    val label: String,
    val supportingText: String? = null,
    val onClick: () -> Unit
)

@Composable
fun ChipRowSection(
    chips: List<ChipRowItem>,
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .suppressParentVerticalScroll()
    ) {
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
        LazyRow(
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = chips,
                key = { it.key },
                contentType = { "selection_chip" }
            ) { chip ->
                val isSelected = chip.key == selectedKey
                var wasFocused by remember(chip.key) { mutableStateOf(false) }
                val focusRequester = remember(chip.key) { FocusRequester() }
                Surface(
                    onClick = {
                        sounds.playSelect()
                        chip.onClick()
                    },
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .mouseClickable(
                            focusRequester = focusRequester,
                            onClick = {
                                sounds.playSelect()
                                chip.onClick()
                            }
                        )
                        .onFocusChanged {
                            if (it.isFocused && !wasFocused) {
                                sounds.playNavigate()
                            }
                            wasFocused = it.isFocused
                        }
                        .semantics(mergeDescendants = true) { selected = isSelected },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                        focusedContainerColor = if (isSelected && focusedContainerBoostWhenSelected) Primary.copy(alpha = 0.28f) else SurfaceHighlight,
                        contentColor = if (isSelected) Primary else OnSurface,
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
                        Text(
                            text = chip.label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        chip.supportingText?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = supportingTextStyle,
                                color = OnSurfaceDim,
                                maxLines = supportingTextMaxLines,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SelectionChipRow(
    title: String,
    chips: List<SelectionChip>,
    selectedKey: String?,
    onChipSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp)
) {
    ChipRowSection(
        title = title,
        subtitle = subtitle,
        chips = chips.map { chip ->
            ChipRowItem(
                key = chip.key,
                label = chip.label,
                supportingText = chip.supportingText,
                onClick = { onChipSelected(chip.key) }
            )
        },
        selectedKey = selectedKey,
        modifier = modifier,
        contentPadding = contentPadding
    )
}

