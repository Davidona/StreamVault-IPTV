package com.streamvault.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.interaction.mouseClickable
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A TV-optimized search input field.
 * The shell is focusable for D-pad navigation and only enters edit mode on click/select.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun SearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    imeAction: ImeAction = ImeAction.Search,
    onSearch: () -> Unit = {},
    enabled: Boolean = true
) {
    val isTelevisionDevice = rememberIsTelevisionDevice()
    var hasContainerFocus by remember { mutableStateOf(false) }
    var hasInputFocus by remember { mutableStateOf(false) }
    var acceptsInput by remember(isTelevisionDevice) { mutableStateOf(!isTelevisionDevice) }
    var pendingInputActivation by remember { mutableStateOf(false) }
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { FocusRequester() }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    val isFocused = enabled && (hasContainerFocus || hasInputFocus)

    fun requestBringIntoView(delayMillis: Long = 0L) {
        coroutineScope.launch {
            if (delayMillis > 0) {
                delay(delayMillis)
            }
            runCatching { bringIntoViewRequester.bringIntoView() }
        }
    }

    fun activateInput() {
        if (!enabled) {
            return
        }
        if (!isTelevisionDevice) {
            acceptsInput = true
            inputFocusRequester.requestFocus()
            keyboardController?.show()
            requestBringIntoView()
            requestBringIntoView(180)
            return
        }
        acceptsInput = true
        pendingInputActivation = true
        requestBringIntoView()
    }

    LaunchedEffect(value) {
        if (value != textFieldValue.text) {
            val coercedSelectionStart = textFieldValue.selection.start.coerceIn(0, value.length)
            val coercedSelectionEnd = textFieldValue.selection.end.coerceIn(0, value.length)
            val coercedComposition = textFieldValue.composition?.let { composition ->
                val compositionStart = composition.start.coerceIn(0, value.length)
                val compositionEnd = composition.end.coerceIn(0, value.length)
                if (compositionStart <= compositionEnd) {
                    TextRange(compositionStart, compositionEnd)
                } else {
                    null
                }
            }
            textFieldValue = textFieldValue.copy(
                text = value,
                selection = TextRange(coercedSelectionStart, coercedSelectionEnd),
                composition = coercedComposition
            )
        }
    }

    LaunchedEffect(acceptsInput, pendingInputActivation) {
        if (!isTelevisionDevice || !acceptsInput || !pendingInputActivation) {
            return@LaunchedEffect
        }
        inputFocusRequester.requestFocus()
        keyboardController?.show()
        requestBringIntoView(120)
        pendingInputActivation = false
    }

    val borderColor = if (isFocused) FocusBorder else OnSurfaceDim.copy(alpha = 0.5f)
    val backgroundColor = if (isFocused) SurfaceHighlight else SurfaceElevated
    val borderWidth = if (isFocused) 2.dp else 1.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .focusRequester(focusRequester)
            .bringIntoViewRequester(bringIntoViewRequester)
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = placeholder
                stateDescription = value.ifBlank { placeholder }
            }
            .onFocusChanged {
                hasContainerFocus = enabled && it.isFocused
            }
            .focusProperties {
                canFocus = enabled
            }
            .mouseClickable(enabled = enabled, focusRequester = focusRequester) {
                activateInput()
            }
            .clickable(enabled = enabled) {
                activateInput()
            }
            .focusable(enabled = enabled)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty() && !isFocused) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }

                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { updatedValue ->
                        textFieldValue = updatedValue
                        if (updatedValue.text != value) {
                            onValueChange(updatedValue.text)
                        }
                    },
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
                        .onFocusChanged {
                            hasInputFocus = enabled && it.hasFocus
                            if (it.hasFocus) {
                                requestBringIntoView(120)
                            } else {
                                if (isTelevisionDevice) {
                                    acceptsInput = false
                                }
                                keyboardController?.hide()
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (!isTelevisionDevice || !acceptsInput || event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                                return@onPreviewKeyEvent false
                            }
                            val cursor = textFieldValue.selection.end
                            when (event.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    val nextCursor = (cursor - 1).coerceAtLeast(0)
                                    textFieldValue = textFieldValue.copy(selection = TextRange(nextCursor))
                                    true
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    val nextCursor = (cursor + 1).coerceAtMost(textFieldValue.text.length)
                                    textFieldValue = textFieldValue.copy(selection = TextRange(nextCursor))
                                    true
                                }
                                else -> false
                            }
                        },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = OnSurface),
                    singleLine = true,
                    cursorBrush = SolidColor(Primary),
                    enabled = enabled,
                    readOnly = isTelevisionDevice && !acceptsInput,
                    keyboardOptions = KeyboardOptions(imeAction = imeAction),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            onSearch()
                            acceptsInput = false
                            keyboardController?.hide()
                            val movedFocus = focusManager.moveFocus(FocusDirection.Down)
                            if (!movedFocus) {
                                focusRequester.requestFocus()
                            }
                        }
                    )
                )
            }

            Icon(
                imageVector = if (value.isBlank()) Icons.Default.Search else Icons.Default.Close,
                contentDescription = null,
                tint = if (value.isBlank()) {
                    if (isFocused) Primary else OnSurfaceDim
                } else {
                    OnSurface
                },
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clickable(enabled = enabled && value.isNotBlank()) {
                        textFieldValue = TextFieldValue(text = "", selection = TextRange(0))
                        onValueChange("")
                        activateInput()
                    }
            )
        }
    }
}
