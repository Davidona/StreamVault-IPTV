package com.streamvault.feature.settings.presentation

import com.streamvault.core.ui.layout.isPortraitLayout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.streamvault.core.ui.components.shell.CoreAppScreenScaffold
import com.streamvault.core.ui.components.shell.UiDestination
import com.streamvault.feature.settings.R

internal enum class SettingsLayoutMode { COMPACT, TV }

internal fun settingsLayoutMode(widthDp: Int): SettingsLayoutMode =
    if (widthDp < 600) SettingsLayoutMode.COMPACT else SettingsLayoutMode.TV

@Composable
internal fun SettingsScreenFrame(
    currentRoute: String,
    destinations: List<UiDestination>,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    if (isPortraitLayout() && destinations.isNotEmpty()) {
        CoreAppScreenScaffold(
            currentDestinationId = currentRoute,
            destinations = destinations,
            onDestinationSelected = onNavigate,
            title = stringResource(R.string.settings_title),
            modifier = modifier,
            showScreenHeader = false
        ) {
            Box(Modifier.fillMaxSize(), content = content)
        }
    } else {
        Box(modifier, content = content)
    }
}

@Composable
internal fun SettingsAdaptiveLayout(
    compactNavigationVisible: Boolean = false,
    navigation: @Composable (compact: Boolean) -> Unit,
    content: @Composable (compact: Boolean) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (isPortraitLayout() || settingsLayoutMode(maxWidth.value.toInt()) == SettingsLayoutMode.COMPACT) {
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
