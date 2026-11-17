package com.streamvault.core.ui.device

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/**
 * Keeps the display on - and so keeps the Fire TV / Android TV screensaver away - while [enabled]
 * and this composable is in the composition.
 *
 * Uses the host [View] (`View.keepScreenOn`) instead of `(LocalContext.current as? Activity).window`:
 * MainActivity wraps LocalContext in a localized ContextWrapper for in-app language switching, so
 * that cast is always null and the window flag silently never gets set.
 *
 * Requests are reference-counted per view, so two callers (e.g. Home and Guide previews briefly
 * overlapping during navigation) cannot switch each other off.
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        if (enabled) KeepScreenOnRequests.acquire(view)
        onDispose {
            if (enabled) KeepScreenOnRequests.release(view)
        }
    }
}

internal object KeepScreenOnRequests {
    private val counts = WeakHashMap<View, Int>()

    fun acquire(view: View) {
        val count = (counts[view] ?: 0) + 1
        counts[view] = count
        view.keepScreenOn = true
    }

    fun release(view: View) {
        val count = ((counts[view] ?: 0) - 1).coerceAtLeast(0)
        if (count == 0) {
            counts.remove(view)
            view.keepScreenOn = false
        } else {
            counts[view] = count
        }
    }

    fun activeCount(view: View): Int = counts[view] ?: 0
}
