package com.streamvault.feature.settings.presentation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.streamvault.domain.model.VodFeaturedMode
import com.streamvault.feature.settings.R

internal fun VodFeaturedMode.labelRes(series: Boolean): Int = when (this) {
    VodFeaturedMode.HIDDEN -> R.string.featured_hidden
    VodFeaturedMode.LATEST_RELEASE -> R.string.featured_latest
    VodFeaturedMode.RECENTLY_ADDED -> if (series) R.string.featured_updated else R.string.featured_added
    VodFeaturedMode.CONTINUE_WATCHING -> R.string.featured_continue
    VodFeaturedMode.RANDOM -> R.string.featured_random
    VodFeaturedMode.FOR_YOU -> R.string.featured_for_you
    VodFeaturedMode.UP_NEXT -> R.string.featured_up_next
}

@Composable
internal fun FeaturedCardSettings(uiState: SettingsUiState, viewModel: SettingsViewModel, targetItemId: String? = null, targetFocusModifier: Modifier = Modifier) {
    var editing by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val initialFocus = com.streamvault.core.ui.design.rememberInitialFocusModifier(
        key = "featured-settings", enabled = targetItemId == null,
    )
    listOf(false, true).forEach { series ->
        val mode = VodFeaturedMode.fromStorage(if (series) uiState.seriesFeaturedMode else uiState.movieFeaturedMode, series)
        ClickableSettingsRow(
            label = stringResource(if (series) R.string.featured_series else R.string.featured_movies),
            value = stringResource(mode.labelRes(series)), onClick = { editing = series },
            modifier = if (targetItemId == if (series) "vod.featured_series" else "vod.featured_movies") targetFocusModifier
                else if (!series && targetItemId == null) initialFocus else Modifier,
        )
    }
    editing?.let { series ->
        val mode = VodFeaturedMode.fromStorage(if (series) uiState.seriesFeaturedMode else uiState.movieFeaturedMode, series)
        val focus = remember { FocusRequester() }
        SettingsSingleChoiceDialog(
            title = stringResource(if (series) R.string.featured_series else R.string.featured_movies),
            onDismiss = { editing = null },
        ) {
            VodFeaturedMode.entries.filter { series || it != VodFeaturedMode.UP_NEXT }.forEach { option ->
                SettingsChoiceOption(
                    label = stringResource(option.labelRes(series)), selected = mode == option,
                    modifier = if (option == mode) Modifier.focusRequester(focus) else Modifier,
                    onSelect = { viewModel.setVodFeaturedMode(series, option); editing = null },
                )
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }
}
