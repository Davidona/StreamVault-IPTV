package com.streamvault.core.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Handles remote navigation while a TV search field is being edited. */
fun handleTvSearchKey(
    event: KeyEvent,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    moveFocus: (FocusDirection) -> Boolean,
    onFocusMoved: () -> Unit
): Boolean {
    if (event.nativeKeyEvent.action != AndroidKeyEvent.ACTION_DOWN) return false

    val direction = when (event.nativeKeyEvent.keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
            if (!value.selection.collapsed || value.selection.end > 0) {
                val cursor = if (value.selection.collapsed) value.selection.end - 1 else value.selection.min
                onValueChange(value.copy(selection = TextRange(cursor)))
                return true
            }
            FocusDirection.Left
        }
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (!value.selection.collapsed || value.selection.end < value.text.length) {
                val cursor = if (value.selection.collapsed) value.selection.end + 1 else value.selection.max
                onValueChange(value.copy(selection = TextRange(cursor)))
                return true
            }
            FocusDirection.Right
        }
        else -> return false
    }
    if (moveFocus(direction)) onFocusMoved()
    return true
}
