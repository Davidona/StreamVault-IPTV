package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.SurfaceHighlight
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun SettingsOverviewCard(
    activeProviderName: String,
    providerCount: Int,
    protectionSummary: String,
    languageLabel: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_overview_title),
                style = MaterialTheme.typography.titleLarge,
                color = OnBackground
            )
            Text(
                text = stringResource(R.string.settings_overview_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_provider_label),
                    value = activeProviderName
                )
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_count_label),
                    value = providerCount.toString()
                )
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_protection_label),
                    value = protectionSummary
                )
                SettingsOverviewStat(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.settings_overview_language_label),
                    value = languageLabel
                )
            }
        }
    }
}

@Composable
internal fun SettingsOverviewStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(SurfaceHighlight.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceDim
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = OnBackground,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun CompactSettingsActionChip(
    label: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
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
                border = BorderStroke(1.dp, Color.White.copy(alpha = if (enabled) 0.08f else 0.04f)),
                shape = RoundedCornerShape(8.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, FocusBorder),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = label,
            color = accent.copy(alpha = if (enabled) 1f else 0.42f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
internal fun EpgSourceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    val isTelevisionDevice = com.streamvault.app.device.rememberIsTelevisionDevice()
    val focusRequester = remember { FocusRequester() }
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
    val isFocused = hasContainerFocus || hasInputFocus

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
        focusRequester.requestFocus()
        keyboardController?.show()
        requestBringIntoView(120)
        pendingInputActivation = false
    }

    TvClickableSurface(
        onClick = {
            if (!isTelevisionDevice) {
                focusRequester.requestFocus()
                keyboardController?.show()
                requestBringIntoView()
                requestBringIntoView(180)
                return@TvClickableSurface
            }
            acceptsInput = true
            pendingInputActivation = true
            requestBringIntoView()
        },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.12f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { hasContainerFocus = it.isFocused }
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (value.isEmpty() && !isFocused) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
            }
            BasicTextField(
                value = fieldValue,
                onValueChange = { updatedValue ->
                    fieldValue = updatedValue
                    if (updatedValue.text != value) {
                        onValueChange(updatedValue.text)
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                singleLine = true,
                cursorBrush = SolidColor(Primary),
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
                    .onPreviewKeyEvent { event ->
                        if (!isTelevisionDevice || !acceptsInput || event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
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
                readOnly = isTelevisionDevice && !acceptsInput
            )
        }
    }
}