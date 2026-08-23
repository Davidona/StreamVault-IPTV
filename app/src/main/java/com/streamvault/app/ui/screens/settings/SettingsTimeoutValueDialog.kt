package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary

@Composable
internal fun TimeoutValueDialog(
    title: String,
    subtitle: String,
    initialValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue.toString()) }
    val parsedValue = value.toIntOrNull()
    val isValid = parsedValue != null && parsedValue in 2..60

    PremiumDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        widthFraction = 0.42f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_timeout_seconds_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface
                )
                NumericSettingsTextField(
                    value = value,
                    onValueChange = { updated -> value = updated.filter(Char::isDigit).take(2) },
                    placeholder = stringResource(R.string.settings_timeout_seconds_placeholder)
                )
                Text(
                    text = if (isValid) {
                        formatTimeoutSecondsLabel(parsedValue ?: initialValue, LocalContext.current)
                    } else {
                        stringResource(R.string.settings_timeout_validation)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isValid) OnSurfaceDim else Color(0xFFFF8A80)
                )
            }
        },
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = onDismiss
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_timeout_apply),
                    onClick = { parsedValue?.let(onConfirm) },
                    enabled = isValid
                )
            }
        }
    )
}

@Composable
private fun NumericSettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    val isTelevisionDevice = com.streamvault.app.device.rememberIsTelevisionDevice()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var hasContainerFocus by remember { mutableStateOf(false) }
    var hasInputFocus by remember { mutableStateOf(false) }
    var acceptsInput by remember(isTelevisionDevice) { mutableStateOf(!isTelevisionDevice) }
    var pendingInputActivation by remember { mutableStateOf(false) }
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    val isFocused = hasContainerFocus || hasInputFocus

    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(text = value, selection = TextRange(value.length))
        }
    }

    LaunchedEffect(acceptsInput, pendingInputActivation) {
        if (!isTelevisionDevice || !acceptsInput || !pendingInputActivation) return@LaunchedEffect
        focusRequester.requestFocus()
        keyboardController?.show()
        pendingInputActivation = false
    }

    TvClickableSurface(
        onClick = {
            if (!isTelevisionDevice) {
                focusRequester.requestFocus()
                keyboardController?.show()
                return@TvClickableSurface
            }
            acceptsInput = true
            pendingInputActivation = true
        },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.12f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { hasContainerFocus = it.isFocused }
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (value.isEmpty() && !isFocused) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
            }
            BasicTextField(
                value = fieldValue,
                onValueChange = { updatedValue ->
                    val digitsOnly = updatedValue.text.filter(Char::isDigit).take(2)
                    fieldValue = updatedValue.copy(
                        text = digitsOnly,
                        selection = TextRange(digitsOnly.length.coerceAtMost(digitsOnly.length))
                    )
                    if (digitsOnly != value) {
                        onValueChange(digitsOnly)
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                singleLine = true,
                cursorBrush = SolidColor(Primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .focusProperties {
                        canFocus = !isTelevisionDevice || acceptsInput
                        if (isTelevisionDevice && acceptsInput) {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }
                    }
                    .onFocusChanged { focusState ->
                        hasInputFocus = focusState.isFocused
                        if (!focusState.isFocused && isTelevisionDevice) {
                            acceptsInput = false
                        }
                    },
                decorationBox = { innerTextField ->
                    if (fieldValue.text.isEmpty() && isFocused) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurfaceDim
                        )
                    }
                    innerTextField()
                }
            )
        }
    }
}