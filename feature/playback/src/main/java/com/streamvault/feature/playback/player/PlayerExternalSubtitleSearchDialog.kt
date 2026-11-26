package com.streamvault.feature.playback.player

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.AppSpacing
import com.streamvault.core.ui.design.FocusSafeLazyColumn
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.core.ui.design.scrollListToTopOnFocus
import com.streamvault.feature.playback.R
import java.text.NumberFormat

@Composable
internal fun PlayerExternalSubtitleSearchDialog(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.externalSubtitleSearchState.collectAsStateWithLifecycle()
    val playbackTitle by viewModel.playbackTitle.collectAsStateWithLifecycle()
    val description = when {
        state.account == null -> stringResource(R.string.player_subtitle_search_signed_out)
        state.stage == ExternalSubtitleSearchStage.LANGUAGES -> stringResource(R.string.player_subtitle_search_languages_hint)
        playbackTitle.isBlank() -> stringResource(R.string.player_subtitle_search_signed_in)
        else -> stringResource(R.string.player_subtitle_search_results_for, playbackTitle)
    }
    val selectedLanguageLabel = state.selectedLanguageName
        ?: stringResource(R.string.player_subtitle_search_all_languages)
    val listState = rememberLazyListState()
    val allLanguagesIsFirstRow = state.account != null &&
        !(state.errorMessage != null && state.languages.isEmpty() && !state.isLoadingLanguages)
    val firstFocusRequester = remember { FocusRequester() }
    val firstLanguageFocusRequester = remember { FocusRequester() }
    val dismissSearch = {
        viewModel.cancelExternalSubtitleSearch()
        onDismiss()
    }

    LaunchedEffect(Unit) { viewModel.prepareExternalSubtitleSearch() }
    LaunchedEffect(
        state.stage,
        state.isSearching,
        state.isLoadingLanguages,
        state.languages,
        state.subtitles.firstOrNull()?.id,
    ) {
        when (state.stage) {
            ExternalSubtitleSearchStage.LANGUAGES -> {
                if (!state.isLoadingLanguages && state.languages.isNotEmpty()) {
                    firstLanguageFocusRequester.requestFocusSafely(
                        tag = "PlayerExternalSubtitleSearchDialog",
                        target = "First subtitle language",
                    )
                }
            }
            ExternalSubtitleSearchStage.RESULTS -> {
                if (!state.isSearching && !state.isLoadingLanguages) {
                    firstFocusRequester.requestFocusSafely(
                        tag = "PlayerExternalSubtitleSearchDialog",
                        target = "First subtitle result or language back row",
                    )
                }
            }
        }
    }

    VodPanelFrame(
        title = stringResource(R.string.player_subtitle_search_title),
        icon = Icons.Default.CloudDownload,
        onDismiss = dismissSearch,
    ) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
        )

        FocusSafeLazyColumn(
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(AppSpacing().xs),
        ) {
            if (state.account == null) {
                item {
                    VodPanelRow(
                        title = stringResource(R.string.player_subtitle_search_open_settings),
                        icon = Icons.Default.Settings,
                        onClick = onOpenSettings,
                    )
                }
            }
            when (state.stage) {
                ExternalSubtitleSearchStage.LANGUAGES -> {
                    if (state.isLoadingLanguages) {
                        item { SearchProgressRow(R.string.player_subtitle_search_loading_languages) }
                    }
                    state.errorMessage?.let { error ->
                        item { SearchErrorText(error) }
                        if (!state.isLoadingLanguages && state.languages.isEmpty()) {
                            item {
                                VodPanelRow(
                                    title = stringResource(R.string.player_subtitle_search_retry),
                                    icon = Icons.Default.Refresh,
                                    onClick = viewModel::loadExternalSubtitleLanguages,
                                )
                            }
                        }
                    }
                    item {
                        VodPanelRow(
                            title = stringResource(R.string.player_subtitle_search_all_languages),
                            detail = stringResource(R.string.player_subtitle_search_all_languages_hint),
                            icon = Icons.Default.Language,
                            selected = state.selectedLanguageCode == null,
                            // Loading or error text above the first focusable row stays cut off by the fade unless
                            // entering that row scrolls back up. Rows above it (account, retry) make it not the first.
                            modifier = (if (allLanguagesIsFirstRow) Modifier.scrollListToTopOnFocus(listState) else Modifier)
                                .then(
                                    if (state.languages.isEmpty()) Modifier.focusRequester(firstLanguageFocusRequester)
                                    else Modifier
                                ),
                        ) { viewModel.selectExternalSubtitleLanguage(null) }
                    }
                    items(state.languages, key = { it.code }) { language ->
                        VodPanelRow(
                            title = language.name,
                            icon = Icons.Default.Language,
                            selected = state.selectedLanguageCode == language.code,
                            modifier = if (language == state.languages.firstOrNull()) {
                                Modifier.focusRequester(firstLanguageFocusRequester)
                            } else {
                                Modifier
                            },
                        ) { viewModel.selectExternalSubtitleLanguage(language) }
                    }
                }
                ExternalSubtitleSearchStage.RESULTS -> {
                    item {
                        VodPanelRow(
                            title = stringResource(R.string.player_subtitle_search_change_language),
                            detail = stringResource(
                                R.string.player_subtitle_search_back_to_languages,
                                selectedLanguageLabel,
                            ),
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            modifier = if (state.subtitles.isEmpty()) {
                                Modifier.focusRequester(firstFocusRequester)
                            } else {
                                Modifier
                            },
                        ) { viewModel.backToExternalSubtitleLanguages() }
                    }
                    if (state.isSearching) {
                        item { SearchProgressRow(R.string.player_subtitle_search_loading) }
                    }
                    state.errorMessage?.let { error ->
                        item { SearchErrorText(error) }
                        if (!state.isSearching && state.subtitles.isEmpty()) {
                            item {
                                VodPanelRow(
                                    title = stringResource(R.string.player_subtitle_search_retry),
                                    icon = Icons.Default.Refresh,
                                    onClick = viewModel::searchExternalSubtitles,
                                )
                            }
                        }
                    }
                    if (!state.isSearching && state.errorMessage == null && state.subtitles.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.player_subtitle_search_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextSecondary,
                                modifier = Modifier.padding(AppSpacing().sm),
                            )
                        }
                    }
                    state.appliedSubtitleName?.let { name ->
                        item {
                            Text(
                                text = stringResource(R.string.player_subtitle_search_applied, name),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextPrimary,
                                modifier = Modifier.padding(AppSpacing().sm),
                            )
                        }
                    }
                    items(state.subtitles, key = { it.id }) { subtitle ->
                        val release = subtitle.releaseName.ifBlank {
                            stringResource(R.string.player_subtitle_search_release_unknown)
                        }
                        val downloads = stringResource(
                            R.string.player_subtitle_search_download_count,
                            NumberFormat.getIntegerInstance().format(subtitle.downloadCount),
                        )
                        VodPanelRow(
                            title = subtitle.displayLanguage,
                            detail = "$release\n$downloads",
                            enabled = state.downloadingSubtitleId == null,
                            trailing = if (state.downloadingSubtitleId == subtitle.id) {
                                {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = LocalContentColor.current,
                                        strokeWidth = 2.dp,
                                    )
                                }
                            } else {
                                null
                            },
                            modifier = if (subtitle == state.subtitles.firstOrNull()) {
                                Modifier.focusRequester(firstFocusRequester)
                            } else {
                                Modifier
                            },
                        ) { viewModel.downloadExternalSubtitle(subtitle.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchProgressRow(@StringRes textRes: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(AppSpacing().sm),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing().sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = AppColors.TextPrimary,
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
        )
    }
}

@Composable
private fun SearchErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(AppSpacing().sm),
    )
}
