package com.streamvault.core.ui.interaction

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import com.streamvault.core.ui.device.isTelevisionDevice

/**
 * Makes a TV-Surface-wrapped composable respond correctly to both remote-pointer
 * (mouse/trackpad) and finger-touch input.
 *
 * - **TV / no touchscreen**: existing `pointerInteropFilter` path (mouse pointer clicks).
 * - **Phone / tablet**: `pointerInput + detectTapGestures` fires [onClick] on the FIRST
 *   tap (and [onLongClick] on a long-press) by intercepting the pointer event before the
 *   TV Surface's focus-first machinery can suppress it.  `detectTapGestures` consumes
 *   the event so the inner TV Surface's `combinedClickable` does not double-fire.
 */
fun Modifier.mouseClickable(
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    val isTv = remember(context) { context.isTelevisionDevice() }
    var pressedFromPointer by remember { mutableStateOf(false) }

    if (!isTv) {
        if (!enabled) {
            this
        } else if (onLongClick != null) {
            // Phone / tablet path with long-press support.
            this.pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onTap = { _ -> onClick() },
                    onLongPress = onLongClick?.let { lc -> { _ -> lc() } }
                )
            }
        } else {
            // Phone / tablet path for simple clicks. Interop receives the raw
            // down/up pair before TV Material's focus-first touch handling.
            this.pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_BUTTON_PRESS -> {
                        pressedFromPointer = true
                        focusRequester?.requestFocus()
                        true
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_BUTTON_RELEASE -> {
                        val shouldClick = pressedFromPointer
                        pressedFromPointer = false
                        if (shouldClick) onClick()
                        shouldClick
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        pressedFromPointer = false
                        false
                    }
                    else -> false
                }
            }
        }
    } else {
        // TV path: existing mouse / pointer-device interop filter.
        this.pointerInteropFilter { event ->
            if (!enabled || !event.isMouseLikePrimaryPress()) return@pointerInteropFilter false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_BUTTON_PRESS -> {
                    pressedFromPointer = true
                    focusRequester?.requestFocus()
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_BUTTON_RELEASE -> {
                    val shouldClick = pressedFromPointer
                    pressedFromPointer = false
                    if (shouldClick) onClick()
                    shouldClick
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressedFromPointer = false
                    false
                }
                else -> false
            }
        }
    }
}

private fun MotionEvent.isMouseLikePrimaryPress(): Boolean {
    val isPointerSource = isFromSource(InputDevice.SOURCE_MOUSE) ||
        isFromSource(InputDevice.SOURCE_TOUCHPAD) ||
        isFromSource(InputDevice.SOURCE_STYLUS)

    if (!isPointerSource) {
        return false
    }

    return when (actionMasked) {
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_UP -> buttonState == 0 || buttonState and MotionEvent.BUTTON_PRIMARY != 0
        MotionEvent.ACTION_BUTTON_PRESS,
        MotionEvent.ACTION_BUTTON_RELEASE -> actionButton == MotionEvent.BUTTON_PRIMARY
        else -> false
    }
}
