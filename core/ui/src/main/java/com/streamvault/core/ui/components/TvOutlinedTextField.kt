package com.streamvault.core.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.streamvault.core.ui.device.rememberIsTelevisionDevice
import com.streamvault.core.ui.interaction.mouseClickable

/**
 * An outlined field that stays in D-pad focus mode on TV until the user selects it.
 * This lets focus move through a form without opening the on-screen keyboard for
 * every text field it passes.
 */
@Composable
fun TvOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    colors: @Composable (containerFocused: Boolean) -> TextFieldColors,
) {
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val keyboardController = LocalSoftwareKeyboardController.current
    val containerFocusRequester = remember { FocusRequester() }
    val inputFocusRequester = remember { FocusRequester() }
    var hasContainerFocus by remember { mutableStateOf(false) }
    var hasInputFocus by remember { mutableStateOf(false) }
    var acceptsInput by remember(isTelevisionDevice) { mutableStateOf(!isTelevisionDevice) }
    var pendingInputActivation by remember { mutableStateOf(false) }

    fun activateInput() {
        if (!enabled) return
        acceptsInput = true
        if (isTelevisionDevice) {
            pendingInputActivation = true
        } else {
            inputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LaunchedEffect(isTelevisionDevice, enabled, pendingInputActivation) {
        if (!isTelevisionDevice || !enabled || !pendingInputActivation) return@LaunchedEffect
        inputFocusRequester.requestFocus()
        keyboardController?.show()
        pendingInputActivation = false
    }

    BackHandler(enabled = isTelevisionDevice && enabled && acceptsInput) {
        pendingInputActivation = false
        acceptsInput = false
        keyboardController?.hide()
        containerFocusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .focusRequester(containerFocusRequester)
            .onFocusChanged { hasContainerFocus = enabled && it.isFocused }
            .mouseClickable(enabled = enabled, focusRequester = containerFocusRequester) {
                activateInput()
            }
            .clickable(enabled = enabled, onClick = ::activateInput)
            .focusable(enabled = enabled),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            enabled = enabled,
            readOnly = isTelevisionDevice && !acceptsInput,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(inputFocusRequester)
                .focusProperties {
                    canFocus = enabled && (!isTelevisionDevice || acceptsInput)
                    if (isTelevisionDevice && acceptsInput) {
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    }
                }
                .onFocusChanged { focusState ->
                    val wasInputFocused = hasInputFocus
                    hasInputFocus = enabled && focusState.isFocused
                    if (!focusState.isFocused && wasInputFocused && isTelevisionDevice) {
                        acceptsInput = false
                        keyboardController?.hide()
                    }
                },
            colors = colors(hasContainerFocus || hasInputFocus),
        )
    }
}
