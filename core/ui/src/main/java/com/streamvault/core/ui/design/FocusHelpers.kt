package com.streamvault.core.ui.design

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
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
