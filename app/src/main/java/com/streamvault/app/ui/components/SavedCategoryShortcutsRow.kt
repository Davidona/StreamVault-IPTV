package com.streamvault.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.core.ui.design.FocusSpec
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.interaction.mouseClickable

data class SavedCategoryShortcut(
    val name: String,
    val count: Int
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SavedCategoryShortcutsRow(
    title: String,
    subtitle: String,
    emptyHint: String,
    shortcuts: List<SavedCategoryShortcut>,
    managementHint: String? = null,
    primaryShortcutLabel: String? = null,
    isPrimaryShortcutSelected: Boolean = false,
    onPrimaryShortcutClick: (() -> Unit)? = null,
    selectedShortcutName: String? = null,
    onShortcutLongClick: ((String) -> Unit)? = null,
    onShortcutClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val shortcutWidth = if (screenWidth < 700.dp) 156.dp else 192.dp

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
                if (!managementHint.isNullOrBlank()) {
                    Text(
                        text = managementHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = Primary.copy(alpha = 0.85f)
                    )
                }
            }
        }

        if (shortcuts.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(14.dp),
                colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
            ) {
                Text(
                    text = emptyHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            return
        }

        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (primaryShortcutLabel != null && onPrimaryShortcutClick != null) {
                item(key = "primary_shortcut") {
                    Surface(
                        onClick = onPrimaryShortcutClick,
                        modifier = Modifier.width(shortcutWidth).mouseClickable(onClick = onPrimaryShortcutClick),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isPrimaryShortcutSelected) {
                                Primary.copy(alpha = 0.18f)
                            } else {
                                SurfaceElevated
                            },
                            focusedContainerColor = Primary.copy(alpha = 0.22f),
                            contentColor = if (isPrimaryShortcutSelected) Primary else OnSurface,
                            focusedContentColor = OnSurface
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(FocusSpec.BorderWidth, FocusBorder),
                                shape = RoundedCornerShape(14.dp)
                            )
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = FocusSpec.FocusedScale)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = primaryShortcutLabel,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isPrimaryShortcutSelected) Primary else OnSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.library_saved_primary_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            items(items = shortcuts, key = { it.name }) { shortcut ->
                val isSelected = shortcut.name == selectedShortcutName
                Surface(
                    onClick = { onShortcutClick(shortcut.name) },
                    onLongClick = onShortcutLongClick?.let { handler ->
                        { handler(shortcut.name) }
                    },
                    modifier = Modifier.width(shortcutWidth).mouseClickable(
                        onClick = { onShortcutClick(shortcut.name) },
                        onLongClick = onShortcutLongClick?.let { handler -> { handler(shortcut.name) } }
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                        focusedContainerColor = Primary.copy(alpha = 0.22f),
                        contentColor = if (isSelected) Primary else OnSurface,
                        focusedContentColor = OnSurface
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(FocusSpec.BorderWidth, FocusBorder),
                            shape = RoundedCornerShape(14.dp)
                        )
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = FocusSpec.FocusedScale)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = shortcut.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isSelected) Primary else OnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.library_saved_items_count, shortcut.count),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
