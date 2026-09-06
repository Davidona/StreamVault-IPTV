package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.model.VodViewMode
import com.streamvault.app.ui.theme.AccentAmber
import com.streamvault.app.ui.theme.AccentCyan
import com.streamvault.app.ui.theme.AccentGreen
import com.streamvault.app.ui.theme.AccentRed
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.domain.model.VodDuplicateHandlingMode

internal fun LazyListScope.settingsBrowsingSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    context: android.content.Context,
    appLandingDestinationLabel: String,
    topNavigationSummaryLabel: String,
    homeDashboardSummaryLabel: String,
    guideDefaultCategoryLabel: String,
    timeFormatLabel: String,
    appLanguageLabel: String,
    onShowLiveTvModeDialogChange: (Boolean) -> Unit,
    onShowLiveTvFiltersDialogChange: (Boolean) -> Unit,
    onShowLiveTvQuickFilterVisibilityDialogChange: (Boolean) -> Unit,
    onShowLiveChannelNumberingDialogChange: (Boolean) -> Unit,
    onShowLiveChannelGroupingDialogChange: (Boolean) -> Unit,
    onShowGroupedChannelLabelDialogChange: (Boolean) -> Unit,
    onShowLiveVariantPreferenceDialogChange: (Boolean) -> Unit,
    onShowTopNavigationDialogChange: (Boolean) -> Unit,
    onShowHomeDashboardDialogChange: (Boolean) -> Unit,
    onShowLandingScreenDialogChange: (Boolean) -> Unit,
    onShowGuideDefaultCategoryDialogChange: (Boolean) -> Unit,
    onShowTimeFormatDialogChange: (Boolean) -> Unit,
    onShowVodViewModeDialogChange: (Boolean) -> Unit,
    onShowVodDuplicateHandlingDialogChange: (Boolean) -> Unit,
    onShowVodVariantPreferenceDialogChange: (Boolean) -> Unit,
    onCategorySortDialogTypeChange: (String?) -> Unit,
    onShowLanguageDialogChange: (Boolean) -> Unit,
    onRemoteShortcutDialogTargetChange: (RemoteShortcutDialogTarget?) -> Unit
) {
    item {
        SwitchSettingsRow(
            label = stringResource(R.string.settings_dark_theme),
            value = stringResource(
                if (uiState.darkTheme) R.string.settings_enabled else R.string.settings_disabled
            ),
            checked = uiState.darkTheme,
            onCheckedChange = viewModel::setDarkTheme
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_tv_channel_mode),
            value = stringResource(uiState.liveTvChannelMode.labelResId()),
            onClick = { onShowLiveTvModeDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_top_navigation),
            value = topNavigationSummaryLabel,
            onClick = { onShowTopNavigationDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_customize_home),
            value = homeDashboardSummaryLabel,
            onClick = { onShowHomeDashboardDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_default_landing_screen),
            value = appLandingDestinationLabel,
            onClick = { onShowLandingScreenDialogChange(true) }
        )
        TvClickableSurface(
            onClick = { viewModel.setShowLiveSourceSwitcher(!uiState.showLiveSourceSwitcher) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_show_live_source_switcher), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_show_live_source_switcher_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.showLiveSourceSwitcher, onCheckedChange = { viewModel.setShowLiveSourceSwitcher(it) })
            }
        }
        TvClickableSurface(
            onClick = { viewModel.setShowFavoritesCategory(!uiState.showFavoritesCategory) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_show_favorites_category), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_show_favorites_category_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.showFavoritesCategory, onCheckedChange = { viewModel.setShowFavoritesCategory(it) })
            }
        }
        TvClickableSurface(
            onClick = { viewModel.setShowAllChannelsCategory(!uiState.showAllChannelsCategory) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_show_all_channels_category), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_show_all_channels_category_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.showAllChannelsCategory, onCheckedChange = { viewModel.setShowAllChannelsCategory(it) })
            }
        }
        TvClickableSurface(
            onClick = { viewModel.setShowRecentChannelsCategory(!uiState.showRecentChannelsCategory) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_show_recent_channels_category), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_show_recent_channels_category_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.showRecentChannelsCategory, onCheckedChange = { viewModel.setShowRecentChannelsCategory(it) })
            }
        }
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_tv_quick_filters),
            value = formatLiveTvQuickFiltersValue(uiState.liveTvCategoryFilters, context),
            onClick = { onShowLiveTvFiltersDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_tv_quick_filter_visibility),
            value = stringResource(uiState.liveTvQuickFilterVisibilityMode.labelResId()),
            onClick = { onShowLiveTvQuickFilterVisibilityDialogChange(true) }
        )
        TvClickableSurface(
            onClick = { viewModel.setHideDecorativeLiveRows(!uiState.hideDecorativeLiveRows) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.settings_hide_decorative_live_rows), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_hide_decorative_live_rows_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.hideDecorativeLiveRows, onCheckedChange = { viewModel.setHideDecorativeLiveRows(it) })
            }
        }
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_channel_numbering_mode),
            value = stringResource(uiState.liveChannelNumberingMode.labelResId()),
            onClick = { onShowLiveChannelNumberingDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_channel_grouping_mode),
            value = stringResource(uiState.liveChannelGroupingMode.labelResId()),
            onClick = { onShowLiveChannelGroupingDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_grouped_channel_label_mode),
            value = stringResource(uiState.groupedChannelLabelMode.labelResId()),
            onClick = { onShowGroupedChannelLabelDialogChange(true) },
            enabled = uiState.liveChannelGroupingMode == LiveChannelGroupingMode.GROUPED,
            indent = 24.dp
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_live_variant_preference_mode),
            value = stringResource(uiState.liveVariantPreferenceMode.labelResId()),
            onClick = { onShowLiveVariantPreferenceDialogChange(true) },
            enabled = uiState.liveChannelGroupingMode == LiveChannelGroupingMode.GROUPED,
            indent = 24.dp
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_guide_default_category),
            value = guideDefaultCategoryLabel,
            onClick = { onShowGuideDefaultCategoryDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_time_format),
            value = timeFormatLabel,
            onClick = { onShowTimeFormatDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_vod_view_mode),
            value = stringResource(uiState.vodViewMode.labelResId()),
            onClick = { onShowVodViewModeDialogChange(true) }
        )
        SwitchSettingsRow(
            label = stringResource(R.string.settings_vod_complete_on_open),
            value = stringResource(
                if (uiState.vodCategoryLoadMode == com.streamvault.domain.model.VodCategoryLoadMode.COMPLETE_ON_OPEN) {
                    R.string.settings_vod_complete_on_open_on
                } else {
                    R.string.settings_vod_complete_on_open_off
                }
            ),
            checked = uiState.vodCategoryLoadMode == com.streamvault.domain.model.VodCategoryLoadMode.COMPLETE_ON_OPEN,
            onCheckedChange = { viewModel.setVodCompleteOnOpen(it) },
            indent = 24.dp
        )
        SwitchSettingsRow(
            label = stringResource(R.string.settings_vod_infinite_scroll),
            value = stringResource(
                if (uiState.vodInfiniteScroll) R.string.settings_vod_infinite_scroll_on
                else R.string.settings_vod_infinite_scroll_off
            ),
            checked = uiState.vodInfiniteScroll,
            onCheckedChange = { viewModel.setVodInfiniteScroll(it) },
            indent = 24.dp
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_vod_duplicate_handling_mode),
            value = stringResource(uiState.vodDuplicateHandlingMode.labelResId()),
            onClick = { onShowVodDuplicateHandlingDialogChange(true) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_vod_variant_preference_mode),
            value = stringResource(uiState.vodVariantPreferenceMode.labelResId()),
            onClick = { onShowVodVariantPreferenceDialogChange(true) },
            enabled = uiState.vodDuplicateHandlingMode != VodDuplicateHandlingMode.SHOW_ALL,
            indent = 24.dp
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        ClickableSettingsRow(
            label = stringResource(R.string.settings_category_sort_live),
            value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.LIVE] ?: CategorySortMode.DEFAULT, context),
            onClick = { onCategorySortDialogTypeChange(ContentType.LIVE.name) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_category_sort_movies),
            value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.MOVIE] ?: CategorySortMode.DEFAULT, context),
            onClick = { onCategorySortDialogTypeChange(ContentType.MOVIE.name) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_category_sort_series),
            value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.SERIES] ?: CategorySortMode.DEFAULT, context),
            onClick = { onCategorySortDialogTypeChange(ContentType.SERIES.name) }
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        TvClickableSurface(
            onClick = { onShowLanguageDialogChange(true) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Primary.copy(alpha = 0.15f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.settings_app_language), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                Text(text = appLanguageLabel, style = MaterialTheme.typography.bodyMedium, color = Primary)
            }
        }
    }
    item {
        RemoteShortcutSettingsPanel(
            uiState = uiState,
            context = context,
            onRemoteShortcutDialogTargetChange = onRemoteShortcutDialogTargetChange
        )
    }
}

@Composable
private fun RemoteShortcutSettingsPanel(
    uiState: SettingsUiState,
    context: android.content.Context,
    onRemoteShortcutDialogTargetChange: (RemoteShortcutDialogTarget?) -> Unit
) {
    var selectedProfileStorage by rememberSaveable {
        mutableStateOf(RemoteShortcutProfile.GLOBAL.storageValue)
    }
    val selectedProfile = RemoteShortcutProfile.fromStorage(selectedProfileStorage)
        ?: RemoteShortcutProfile.GLOBAL

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider(
            color = Color.White.copy(alpha = 0.07f),
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.035f), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.settings_remote_shortcuts_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface
                )
                Text(
                    text = stringResource(R.string.settings_remote_shortcuts_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RemoteShortcutLegendChip(RemoteColorButton.RED)
                RemoteShortcutLegendChip(RemoteColorButton.GREEN)
                RemoteShortcutLegendChip(RemoteColorButton.YELLOW)
                RemoteShortcutLegendChip(RemoteColorButton.BLUE)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RemoteShortcutProfile.entries.forEach { profile ->
                    RemoteShortcutProfileTab(
                        profile = profile,
                        selected = profile == selectedProfile,
                        onClick = { selectedProfileStorage = profile.storageValue },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            RemoteShortcutGrid(
                profile = selectedProfile,
                uiState = uiState,
                context = context,
                onRemoteShortcutDialogTargetChange = onRemoteShortcutDialogTargetChange
            )
        }
    }
}

@Composable
private fun RemoteShortcutLegendChip(button: RemoteColorButton) {
    Row(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(button.accentColor(), CircleShape)
        )
        Text(
            text = stringResource(button.labelResId()),
            style = MaterialTheme.typography.labelMedium,
            color = OnSurface
        )
    }
}

@Composable
private fun RemoteShortcutProfileTab(
    profile: RemoteShortcutProfile,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f),
            focusedContainerColor = Primary.copy(alpha = 0.22f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(profile.labelResId()),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Primary else OnSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun RemoteShortcutGrid(
    profile: RemoteShortcutProfile,
    uiState: SettingsUiState,
    context: android.content.Context,
    onRemoteShortcutDialogTargetChange: (RemoteShortcutDialogTarget?) -> Unit
) {
    val buttons = RemoteColorButton.entries
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        buttons.chunked(2).forEach { rowButtons ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowButtons.forEach { button ->
                    RemoteShortcutButtonCard(
                        profile = profile,
                        button = button,
                        uiState = uiState,
                        context = context,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onRemoteShortcutDialogTargetChange(
                                RemoteShortcutDialogTarget(profile, button)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteShortcutButtonCard(
    profile: RemoteShortcutProfile,
    button: RemoteColorButton,
    uiState: SettingsUiState,
    context: android.content.Context,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val selection = uiState.remoteShortcutPreferences.selection(profile, button)
    val resolvedLabel = context.getString(selection.resolve(profile, button).labelResId())
    val detailLabel = formatRemoteShortcutSelectionLabel(selection, profile, button, context)

    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = button.accentColor().copy(alpha = 0.10f),
            focusedContainerColor = button.accentColor().copy(alpha = 0.18f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(button.accentColor(), CircleShape)
                )
                Text(
                    text = stringResource(button.labelResId()),
                    style = MaterialTheme.typography.labelLarge,
                    color = OnSurface
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = resolvedLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface
                )
                Text(
                    text = detailLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

private fun RemoteColorButton.accentColor(): Color = when (this) {
    RemoteColorButton.RED -> AccentRed
    RemoteColorButton.GREEN -> AccentGreen
    RemoteColorButton.YELLOW -> AccentAmber
    RemoteColorButton.BLUE -> AccentCyan
}
