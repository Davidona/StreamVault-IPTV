package com.streamvault.feature.playback.player

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.core.ui.interaction.TvClickableSurface
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
    val firstFocusRequester = androidx.compose.runtime.remember { FocusRequester() }
    val firstLanguageFocusRequester = androidx.compose.runtime.remember { FocusRequester() }
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

    VodSelectionSheet(
        title = stringResource(R.string.player_subtitle_search_title),
        icon = androidx.compose.material.icons.Icons.Default.CloudDownload,
        onDismiss = dismissSearch,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.account == null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(onClick = onOpenSettings) {
                            Text(stringResource(R.string.player_subtitle_search_open_settings))
                        }
                    }
                }
            }
            when (state.stage) {
                ExternalSubtitleSearchStage.LANGUAGES -> {
                    if (state.isLoadingLanguages) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = AppColors.Brand,
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = stringResource(R.string.player_subtitle_search_loading_languages),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.TextSecondary,
                                )
                            }
                        }
                    }

                    state.errorMessage?.let { error ->
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(12.dp),
                                )
                                if (!state.isLoadingLanguages && state.languages.isEmpty()) {
                                    Button(onClick = viewModel::loadExternalSubtitleLanguages) {
                                        Text(stringResource(R.string.player_subtitle_search_retry))
                                    }
                                }
                            }
                        }
                    }

                    item {
                        VodSelectionRow(
                            title = stringResource(R.string.player_subtitle_search_all_languages),
                            detail = stringResource(R.string.player_subtitle_search_all_languages_hint),
                            icon = androidx.compose.material.icons.Icons.Default.Language,
                            selected = state.selectedLanguageCode == null,
                            modifier = if (state.languages.isEmpty()) {
                                Modifier.focusRequester(firstLanguageFocusRequester)
                            } else {
                                Modifier
                            },
                        ) { viewModel.selectExternalSubtitleLanguage(null) }
                    }
                    items(state.languages, key = { it.code }) { language ->
                        VodSelectionRow(
                            title = language.name,
                            icon = androidx.compose.material.icons.Icons.Default.Language,
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
                        VodSelectionRow(
                            title = stringResource(R.string.player_subtitle_search_change_language),
                            detail = stringResource(
                                R.string.player_subtitle_search_back_to_languages,
                                selectedLanguageLabel,
                            ),
                            icon = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            modifier = if (state.subtitles.isEmpty()) {
                                Modifier.focusRequester(firstFocusRequester)
                            } else {
                                Modifier
                            },
                        ) { viewModel.backToExternalSubtitleLanguages() }
                    }

                    if (state.isSearching) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = AppColors.Brand,
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = stringResource(R.string.player_subtitle_search_loading),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.TextSecondary,
                                )
                            }
                        }
                    }

                    state.errorMessage?.let { error ->
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(12.dp),
                                )
                                if (!state.isSearching && state.subtitles.isEmpty()) {
                                    Button(onClick = viewModel::searchExternalSubtitles) {
                                        Text(stringResource(R.string.player_subtitle_search_retry))
                                    }
                                }
                            }
                        }
                    }

                    if (!state.isSearching && state.errorMessage == null && state.subtitles.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.player_subtitle_search_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextSecondary,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }

                    state.appliedSubtitleName?.let { name ->
                        item {
                            Text(
                                text = stringResource(R.string.player_subtitle_search_applied, name),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.Brand,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

                    items(state.subtitles, key = { it.id }) { subtitle ->
                        val downloading = state.downloadingSubtitleId != null
                        TvClickableSurface(
                            onClick = { viewModel.downloadExternalSubtitle(subtitle.id) },
                            enabled = !downloading,
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.035f),
                                contentColor = androidx.compose.ui.graphics.Color(0xFFDCE3ED),
                                focusedContainerColor = androidx.compose.ui.graphics.Color(0xFFDFEBFA),
                                focusedContentColor = androidx.compose.ui.graphics.Color(0xFF10151D),
                            ),
                            modifier = if (subtitle == state.subtitles.firstOrNull()) {
                                Modifier.focusRequester(firstFocusRequester)
                            } else {
                                Modifier
                            },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = subtitle.displayLanguage,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = androidx.tv.material3.LocalContentColor.current,
                                    )
                                    Text(
                                        text = subtitle.releaseName.ifBlank {
                                            stringResource(R.string.player_subtitle_search_release_unknown)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.65f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.player_subtitle_search_download_count,
                                            NumberFormat.getIntegerInstance().format(subtitle.downloadCount),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.65f),
                                    )
                                }
                                if (state.downloadingSubtitleId == subtitle.id) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(26.dp),
                                        color = AppColors.Brand,
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    }
}
