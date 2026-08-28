package com.streamvault.feature.live.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import com.streamvault.core.ui.components.shell.AppSectionHeader
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight

/** Netflix-style horizontal category row for Live browse surfaces. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun <T : Any> LiveCategoryRow(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    onPinToggle: (() -> Unit)? = null,
    isPinned: Boolean = false,
    keySelector: (T) -> Any,
    contentTypeSelector: ((T) -> Any?)? = null,
    seeAllLabel: String = "See all",
    pinLabel: String = "Pin category",
    unpinLabel: String = "Unpin category",
    itemContent: @Composable (T) -> Unit
) {
    val resolvedContentTypeSelector: (T) -> Any? = contentTypeSelector ?: { null }
    Column(modifier = modifier.fillMaxWidth()) {
        if (onSeeAll != null) {
            val seeAllRequester = remember { FocusRequester() }
            val pinRequester = remember { FocusRequester() }
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppSectionHeader(title = title, modifier = Modifier.weight(1f))
                    LiveCategoryAction(
                        label = seeAllLabel,
                        onClick = onSeeAll,
                        requester = seeAllRequester,
                        contentColor = Primary
                    )
                    if (onPinToggle != null) {
                        Surface(
                            onClick = onPinToggle,
                            modifier = Modifier.focusRequester(pinRequester).mouseClickable(focusRequester = pinRequester, onClick = onPinToggle),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isPinned) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                                focusedContainerColor = SurfaceHighlight,
                                contentColor = if (isPinned) Primary else OnSurface,
                                focusedContentColor = OnSurface
                            ),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, FocusBorder),
                                    shape = RoundedCornerShape(999.dp)
                                )
                            )
                        ) {
                            Icon(
                                imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = if (isPinned) unpinLabel else pinLabel,
                                modifier = Modifier.padding(8.dp),
                                tint = if (isPinned) Primary else OnSurface
                            )
                        }
                    }
                }
            }
        } else {
            AppSectionHeader(title = title, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp))
        }
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items = items, key = keySelector, contentType = resolvedContentTypeSelector) { item -> itemContent(item) }
        }
    }
}

@Composable
private fun LiveCategoryAction(
    label: String,
    onClick: () -> Unit,
    requester: FocusRequester,
    contentColor: Color
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.focusRequester(requester).mouseClickable(focusRequester = requester, onClick = onClick),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = SurfaceHighlight,
            contentColor = contentColor,
            focusedContentColor = OnSurface
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(999.dp)
            )
        )
    ) {
        Text(text = label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
    }
}
