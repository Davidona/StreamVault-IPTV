package com.streamvault.feature.settings.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.design.FocusSpec
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SettingsOverviewStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .settingsDetailPanel()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceDim
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = OnBackground,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun CompactSettingsActionChip(
    label: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvClickableSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(SettingsDetailShapes.Control),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = accent.copy(alpha = if (enabled) 0.14f else 0.08f),
            contentColor = accent.copy(alpha = if (enabled) 1f else 0.42f),
            focusedContainerColor = accent.copy(alpha = if (enabled) 0.28f else 0.08f),
            focusedContentColor = accent.copy(alpha = if (enabled) 1f else 0.42f),
            disabledContainerColor = accent.copy(alpha = 0.08f),
            disabledContentColor = accent.copy(alpha = 0.42f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, com.streamvault.core.ui.design.AppColors.Divider),
                shape = SettingsDetailShapes.Control
            ),
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, com.streamvault.core.ui.theme.FocusBorder),
                shape = SettingsDetailShapes.Control
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = label,
            color = accent.copy(alpha = if (enabled) 1f else 0.42f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
fun EpgSourceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    val settingsColors = SettingsDesignTokens.colors(com.streamvault.core.ui.design.AppColors.current)
    val isTelevisionDevice = com.streamvault.core.ui.device.rememberIsTelevisionDevice()
    val containerFocusRequester = remember { FocusRequester() }
    val inputFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    var hasContainerFocus by remember { mutableStateOf(false) }
    var hasInputFocus by remember { mutableStateOf(false) }
    var acceptsInput by remember(isTelevisionDevice) { mutableStateOf(!isTelevisionDevice) }
    var pendingInputActivation by remember { mutableStateOf(false) }
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottom) {
        if ((hasInputFocus || hasContainerFocus) && imeBottom > 0) {
            runCatching { bringIntoViewRequester.bringIntoView() }
        }
    }

    fun requestBringIntoView(delayMillis: Long = 0L) {
        coroutineScope.launch {
            if (delayMillis > 0) {
                kotlinx.coroutines.delay(delayMillis)
            }
            runCatching { bringIntoViewRequester.bringIntoView() }
        }
    }

    fun dismissInput() {
        pendingInputActivation = false
        acceptsInput = false
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        if (isTelevisionDevice) {
            containerFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            val coercedSelectionStart = fieldValue.selection.start.coerceIn(0, value.length)
            val coercedSelectionEnd = fieldValue.selection.end.coerceIn(0, value.length)
            val coercedComposition = fieldValue.composition?.let { composition ->
                val compositionStart = composition.start.coerceIn(0, value.length)
                val compositionEnd = composition.end.coerceIn(0, value.length)
                if (compositionStart <= compositionEnd) {
                    TextRange(compositionStart, compositionEnd)
                } else {
                    null
                }
            }
            fieldValue = fieldValue.copy(
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

    BackHandler(
        enabled = shouldConsumeEpgSourceFieldBack(
            isTelevisionDevice = isTelevisionDevice,
            hasInputFocus = hasInputFocus,
            pendingInputActivation = pendingInputActivation,
            acceptsInput = acceptsInput,
        ),
        onBack = ::dismissInput,
    )

    TvClickableSurface(
        onClick = {
            if (!isTelevisionDevice) {
                acceptsInput = true
                inputFocusRequester.requestFocus()
                keyboardController?.show()
                requestBringIntoView()
                requestBringIntoView(180)
                return@TvClickableSurface
            }
            acceptsInput = true
            pendingInputActivation = true
            requestBringIntoView()
        },
        shape = ClickableSurfaceDefaults.shape(SettingsDetailShapes.Control),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = settingsColors.searchSurface,
            focusedContainerColor = settingsColors.searchFocusedSurface,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(1.dp, settingsColors.divider),
                shape = SettingsDetailShapes.Control,
            ),
            focusedBorder = Border(
                BorderStroke(SettingsDesignTokens.focusStroke, settingsColors.focusOutline),
                shape = SettingsDetailShapes.Control,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRequester(containerFocusRequester)
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { hasContainerFocus = it.isFocused }
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = settingsColors.searchPlaceholder)
            }
            BasicTextField(
                value = fieldValue,
                onValueChange = { updatedValue ->
                    fieldValue = updatedValue
                    if (updatedValue.text != value) {
                        onValueChange(updatedValue.text)
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = settingsColors.searchText),
                singleLine = singleLine,
                maxLines = maxLines,
                cursorBrush = SolidColor(settingsColors.cursor),
                visualTransformation = visualTransformation,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inputFocusRequester)
                    .focusProperties {
                        canFocus = !isTelevisionDevice || acceptsInput
                        if (isTelevisionDevice && acceptsInput) {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                            return@onPreviewKeyEvent false
                        }
                        if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                            (hasInputFocus || (isTelevisionDevice && acceptsInput))
                        ) {
                            dismissInput()
                            return@onPreviewKeyEvent true
                        }
                        if (!isTelevisionDevice || !acceptsInput) {
                            return@onPreviewKeyEvent false
                        }
                        val cursor = fieldValue.selection.end
                        when (event.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val nextCursor = (cursor - 1).coerceAtLeast(0)
                                fieldValue = fieldValue.copy(selection = TextRange(nextCursor))
                                true
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val nextCursor = (cursor + 1).coerceAtMost(fieldValue.text.length)
                                fieldValue = fieldValue.copy(selection = TextRange(nextCursor))
                                true
                            }
                            else -> false
                        }
                    }
                    .onFocusChanged {
                        hasInputFocus = it.isFocused
                        if (it.isFocused) {
                            requestBringIntoView(120)
                        } else {
                            if (isTelevisionDevice) {
                                acceptsInput = false
                            }
                            keyboardController?.hide()
                        }
                    },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { dismissInput() }),
                readOnly = isTelevisionDevice && !acceptsInput
            )
        }
    }
}

internal fun shouldConsumeEpgSourceFieldBack(
    isTelevisionDevice: Boolean,
    hasInputFocus: Boolean,
    pendingInputActivation: Boolean,
    acceptsInput: Boolean,
): Boolean = hasInputFocus || pendingInputActivation || (isTelevisionDevice && acceptsInput)
