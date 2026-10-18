package com.streamvault.core.ui.layout

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Re-evaluates on rotation and resizing; larger landscape windows keep the TV layout. */
@Composable
fun isPortraitLayout(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.orientation == Configuration.ORIENTATION_PORTRAIT ||
        configuration.screenWidthDp < 600
}

/** Wrap narrow-screen controls while retaining their original landscape alignment. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AdaptiveRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit
) {
    if (isPortraitLayout()) {
        FlowRow(modifier, horizontalArrangement = horizontalArrangement, verticalArrangement = verticalArrangement) {
            content()
        }
    } else {
        Row(modifier, horizontalArrangement = horizontalArrangement, verticalAlignment = verticalAlignment, content = content)
    }
}
