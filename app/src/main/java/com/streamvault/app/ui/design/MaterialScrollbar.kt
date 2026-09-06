package com.streamvault.app.ui.design

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Material 3 style vertical scrollbar for [LazyListState]-backed lists.
 *
 * Thin (4dp) rounded thumb in a neutral `onSurfaceVariant`-adjacent tone;
 * hidden when idle and fades in while the list is scrolling, matching M3
 * scroll behavior. Call it inside a [androidx.compose.foundation.layout.Box]
 * that also holds the [androidx.compose.foundation.lazy.LazyColumn].
 *
 * Implemented against [LazyListState] directly (this Compose version removed
 * the `VerticalScrollbar`/`rememberScrollbarAdapter` foundation APIs).
 */
@Composable
fun BoxScope.MaterialVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }

    // Auto-hide: show while the list is being scrolled, then fade out.
    LaunchedEffect(state) {
        snapshotFlow {
            state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        }.collect {
            visible = true
            delay(650)
            visible = false
        }
    }

    val metrics: State<Pair<Float, Float>> = remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val total = info.totalItemsCount
            val visibleItems = info.visibleItemsInfo
            if (total == 0 || visibleItems.isEmpty()) {
                1f to 0f
            } else {
                val first = visibleItems.first().index.toFloat()
                val visibleCount = visibleItems.size.toFloat()
                val scrollableRange = (total - visibleCount).coerceAtLeast(1f)
                val thumbFraction = (visibleCount / total).coerceIn(0.04f, 1f)
                val offsetFraction = (first / scrollableRange).coerceIn(0f, 1f)
                thumbFraction to offsetFraction
            }
        }
    }
    val thumbFraction = metrics.value.first
    val offsetFraction = metrics.value.second

    if (visible) {
        androidx.compose.foundation.layout.Box(
            modifier = modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(4.dp)
                .padding(vertical = 28.dp)
                .drawBehind {
                    val trackHeight = size.height
                    val thumbHeight = trackHeight * thumbFraction
                    val maxOffset = (trackHeight - thumbHeight).coerceAtLeast(0f)
                    val thumbOffset = maxOffset * offsetFraction
                    drawRoundRect(
                        color = AppColors.TextTertiary.copy(alpha = 0.55f),
                        topLeft = Offset(0f, thumbOffset),
                        size = Size(size.width, thumbHeight),
                        cornerRadius = CornerRadius(size.width / 2f)
                    )
                }
        )
    }
}