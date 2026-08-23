package com.streamvault.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ActiveLiveSourceOption
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Border

@Composable
fun LiveSourceSwitcher(
    currentSource: ActiveLiveSource?,
    options: List<ActiveLiveSourceOption>,
    onSourceSelected: (ActiveLiveSource) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showSourceList by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val toggleFocusRequester = remember { FocusRequester() }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val controlWidth = when {
        compact -> if (screenWidth < 700.dp) 132.dp else 156.dp
        screenWidth < 700.dp -> 180.dp
        else -> 220.dp
    }
    val dropdownWidth = when {
        compact -> if (screenWidth < 700.dp) 200.dp else 240.dp
        screenWidth < 700.dp -> 220.dp
        else -> 300.dp
    }
    val currentOption = options.firstOrNull { it.source == currentSource }

    BackHandler(enabled = showSourceList) { showSourceList = false }

    Box(modifier = modifier) {
        Surface(
            onClick = { showSourceList = !showSourceList },
            modifier = Modifier
                .defaultMinSize(minWidth = controlWidth)
                .focusRequester(toggleFocusRequester)
                .mouseClickable(
                    focusRequester = toggleFocusRequester,
                    onClick = { showSourceList = !showSourceList }
                )
                .onFocusChanged { isFocused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (isFocused) Primary.copy(alpha = 0.12f) else SurfaceElevated.copy(alpha = 0.96f),
                focusedContainerColor = Primary.copy(alpha = 0.18f)
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, Primary.copy(alpha = 0.14f)),
                    shape = RoundedCornerShape(12.dp)
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, FocusBorder),
                    shape = RoundedCornerShape(12.dp)
                )
            )
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = if (compact) 10.dp else 12.dp,
                    vertical = if (compact) 5.dp else 7.dp
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = currentOption?.title ?: stringResource(R.string.playlist_no_provider),
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = if (compact) 180.dp else 280.dp),
                    color = if (isFocused) Primary else OnBackground
                )
                Icon(
                    imageVector = if (showSourceList) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = OnSurfaceDim
                )
            }
        }

        if (showSourceList && options.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .padding(top = if (compact) 38.dp else 46.dp)
                    .width(dropdownWidth),
                shape = RoundedCornerShape(12.dp),
                colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    options.forEach { option ->
                        LiveSourceItem(
                            option = option,
                            isSelected = option.source == currentSource,
                            onClick = {
                                onSourceSelected(option.source)
                                showSourceList = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveSourceItem(
    option: ActiveLiveSourceOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .mouseClickable(
                focusRequester = focusRequester,
                onClick = onClick
            )
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = Primary.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFocused || isSelected) Primary else OnBackground
                )
                option.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceDim
                    )
                }
            }
            Text(
                text = when {
                    isSelected -> stringResource(R.string.label_selected)
                    !option.isEnabled -> stringResource(R.string.live_source_unavailable_short)
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) Primary else OnSurfaceDim
            )
        }
    }
}
