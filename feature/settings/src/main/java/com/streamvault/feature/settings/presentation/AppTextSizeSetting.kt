package com.streamvault.feature.settings.presentation

import android.view.KeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Slider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.domain.model.AppTextSizeMode
import com.streamvault.feature.settings.R
import java.text.NumberFormat

@Composable
internal fun AppTextSizeSetting(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val value by viewModel.appTextSize.collectAsStateWithLifecycle()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.settings_text_size)
    val locale = LocalConfiguration.current.locales[0]
    val percent = remember(locale) { NumberFormat.getPercentInstance(locale) }
    val sliderFocusRequester = remember { FocusRequester() }
    var sliderFocused by remember { mutableStateOf(false) }
    val labels = mapOf(
        AppTextSizeMode.DEFAULT to stringResource(R.string.settings_text_size_default),
        AppTextSizeMode.SYSTEM to stringResource(R.string.settings_text_size_system),
        AppTextSizeMode.CUSTOM to stringResource(R.string.settings_text_size_custom),
    )
    ClickableSettingsRow(
        modifier = modifier,
        label = title,
        value = if (value.mode == AppTextSizeMode.CUSTOM) percent.format(value.normalizedCustomScale)
            else labels.getValue(value.mode),
        onClick = { showDialog = true },
    )
    if (showDialog) {
        LaunchedEffect(value.mode, value.customScale) {
            if (value.mode == AppTextSizeMode.CUSTOM) {
                sliderFocusRequester.requestFocusSafely(tag = "AppTextSize", target = "Custom size slider")
            }
        }
        SettingsSingleChoiceDialog(
            title = title,
            onDismiss = { showDialog = false },
            closeLabel = stringResource(R.string.player_close),
        ) {
            AppTextSizeMode.entries.forEach { mode ->
                SettingsChoiceOption(
                    label = labels.getValue(mode),
                    selected = value.mode == mode,
                    onSelect = { viewModel.setAppTextSize(value.copy(mode = mode)) },
                )
            }
            if (value.mode == AppTextSizeMode.CUSTOM) {
                var scale by remember(value.customScale) { mutableFloatStateOf(value.normalizedCustomScale) }
                Text(percent.format(scale))
                Slider(
                    value = scale,
                    onValueChange = { scale = it },
                    onValueChangeFinished = { viewModel.setAppTextSize(value.copy(customScale = scale)) },
                    valueRange = 0.8f..1.5f,
                    steps = 13,
                    modifier = Modifier
                        .border(
                            width = if (sliderFocused) 2.dp else 0.dp,
                            color = if (sliderFocused) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .focusRequester(sliderFocusRequester)
                        .focusProperties { canFocus = true }
                        .onFocusChanged { sliderFocused = it.isFocused || it.hasFocus }
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            val key = event.nativeKeyEvent
                            if (key.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                            val next = when (key.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> (scale - 0.05f).coerceAtLeast(0.8f)
                                KeyEvent.KEYCODE_DPAD_RIGHT -> (scale + 0.05f).coerceAtMost(1.5f)
                                else -> return@onPreviewKeyEvent false
                            }
                            scale = next
                            viewModel.setAppTextSize(value.copy(customScale = next))
                            true
                        }
                        .semantics { contentDescription = title },
                )
            }
        }
    }
}
