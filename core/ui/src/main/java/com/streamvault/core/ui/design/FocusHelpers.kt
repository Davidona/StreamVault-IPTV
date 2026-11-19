package com.streamvault.core.ui.design

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val FOCUS_TAG = "FocusHelpers"

fun FocusRequester.requestFocusSafely(tag: String = FOCUS_TAG, target: String = "Focus target"): Boolean {
    return try {
        requestFocus()
        true
    } catch (e: IllegalStateException) {
        Log.d(tag, "$target focus request failed: ${e.message}")
        false
    }
}

/**
 * Modifier for an initial-focus target inside a lazy list. Focus is requested only after the node's first
 * placement: lazy items attach during layout, and a request before placement makes the enclosing list
 * scroll to a rect that is not final yet (the page jumped down on screen entry).
 */
@Composable
fun rememberInitialFocusModifier(
    key: Any?,
    enabled: Boolean = true,
    tag: String = FOCUS_TAG,
    target: String = "Initial focus target"
): Modifier {
    val focusRequester = remember { FocusRequester() }
    var placed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, placed, enabled) {
        if (placed && enabled) {
            focusRequester.requestFocusSafely(tag = tag, target = target)
        }
    }
    return Modifier
        .focusRequester(focusRequester)
        .onGloballyPositioned { placed = true }
}

/**
 * Leaves the list alone while the focused item sits inside a comfort zone (the middle of the viewport),
 * and scrolls just far enough to bring it back to the zone edge otherwise. Compose's default rule pulls
 * the focused item toward a pivot even when it is fully visible, which made the detail pages jump when
 * Play took initial focus. A strict "only if off screen" rule fixed that but left rows hugging the screen
 * edge while moving down, so the zone keeps a margin on both sides.
 */
@OptIn(ExperimentalFoundationApi::class)
private object ComfortZoneBringIntoViewSpec : BringIntoViewSpec {
    private const val MARGIN_FRACTION = 0.2f

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        val zoneStart = containerSize * MARGIN_FRACTION
        val zoneEnd = containerSize - zoneStart
        val fitsInZone = size <= zoneEnd - zoneStart
        return when {
            // Taller than the zone: show its leading edge unless it already fills the viewport.
            !fitsInZone -> if (offset < 0f && trailing > containerSize) 0f else offset - zoneStart
            offset < zoneStart -> offset - zoneStart
            trailing > zoneEnd -> trailing - zoneEnd
            else -> 0f
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComfortZoneScrollOnFocus(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides ComfortZoneBringIntoViewSpec, content = content)
}

@Composable
fun TvInitialFocus(
    focusRequester: FocusRequester,
    enabled: Boolean = true
) {
    LaunchedEffect(focusRequester, enabled) {
        if (enabled) {
            focusRequester.requestFocusSafely(target = "Initial TV focus")
        }
    }
}

@Composable
fun FocusRestoreHost(
    enabled: Boolean = true,
    onRestore: suspend () -> Unit = {},
    content: @Composable () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnRestore by rememberUpdatedState(onRestore)
    var restoreTick by remember { mutableIntStateOf(0) }
    var hasComposed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, enabled) {
        if (!enabled) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && hasComposed) {
                    restoreTick++
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(enabled) {
        if (enabled) {
            hasComposed = true
            restoreTick++
        }
    }

    LaunchedEffect(enabled, restoreTick) {
        if (enabled) {
            latestOnRestore()
        }
    }

    content()
}
