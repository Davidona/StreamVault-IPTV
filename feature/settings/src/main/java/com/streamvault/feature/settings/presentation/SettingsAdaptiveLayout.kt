package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal enum class SettingsLayoutMode { COMPACT, TV }

internal fun settingsLayoutMode(widthDp: Int): SettingsLayoutMode =
    if (widthDp < 600) SettingsLayoutMode.COMPACT else SettingsLayoutMode.TV

@Composable
internal fun SettingsAdaptiveLayout(
    compactNavigationVisible: Boolean = false,
    navigation: @Composable (compact: Boolean) -> Unit,
    content: @Composable (compact: Boolean) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (settingsLayoutMode(maxWidth.value.toInt()) == SettingsLayoutMode.COMPACT) {
            Box(Modifier.fillMaxSize()) {
                if (compactNavigationVisible) navigation(true) else content(true)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                navigation(false)
                Box(Modifier.weight(1f).fillMaxHeight()) { content(false) }
            }
        }
    }
}
