package com.streamvault.feature.settings.presentation

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
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.AccentAmber
import com.streamvault.core.ui.theme.AccentCyan
import com.streamvault.core.ui.theme.AccentGreen
import com.streamvault.core.ui.theme.AccentRed
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.AppTheme
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.domain.model.VodDuplicateHandlingMode

public fun LazyListScope.settingsBrowsingSection(
    uiState: SettingsUiState,
    page: SettingsPage? = null,
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
    onShowThemeDialogChange: (Boolean) -> Unit,
    onShowVodDuplicateHandlingDialogChange: (Boolean) -> Unit,
    onShowVodVariantPreferenceDialogChange: (Boolean) -> Unit,
    onCategorySortDialogTypeChange: (String?) -> Unit,
    onShowLanguageDialogChange: (Boolean) -> Unit,
    onRemoteShortcutDialogTargetChange: (RemoteShortcutDialogTarget?) -> Unit,
    targetItemId: String? = null,
    targetFocusModifier: Modifier = Modifier,
) {
    item {

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (page == null || page == SettingsPage.LIVE_LAYOUT) {
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_live_tv_channel_mode),
                    value = stringResource(uiState.liveTvChannelMode.labelResId()),
                    onClick = { onShowLiveTvModeDialogChange(true) },
                    modifier = if (targetItemId == "live.mode") targetFocusModifier else Modifier,
                )
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_live_tv_auto_hide_categories),
                    value = stringResource(
                        if (uiState.liveTvAutoHideCategories) {
                            R.string.settings_live_tv_auto_hide_categories_on
                        } else {
                            R.string.settings_live_tv_auto_hide_categories_off
                        }
                    ),
                    checked = uiState.liveTvAutoHideCategories,
                    onCheckedChange = viewModel::setLiveTvAutoHideCategories,
                    indent = 24.dp,
                    modifier = if (targetItemId == "live.auto_hide_categories") targetFocusModifier else Modifier,
                )
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_show_live_source_switcher),
                    value = stringResource(R.string.settings_show_live_source_switcher_subtitle),
                    checked = uiState.showLiveSourceSwitcher,
                    onCheckedChange = viewModel::setShowLiveSourceSwitcher
                ,
                    modifier = if (targetItemId == "live.source_switcher") targetFocusModifier else Modifier,)
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_show_favorites_category),
                    value = stringResource(R.string.settings_show_favorites_category_subtitle),
                    checked = uiState.showFavoritesCategory,
                    onCheckedChange = viewModel::setShowFavoritesCategory
                ,
                    modifier = if (targetItemId == "live.favorites_category") targetFocusModifier else Modifier,)
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_show_all_channels_category),
                    value = stringResource(R.string.settings_show_all_channels_category_subtitle),
                    checked = uiState.showAllChannelsCategory,
                    onCheckedChange = viewModel::setShowAllChannelsCategory
                ,
                    modifier = if (targetItemId == "live.all_category") targetFocusModifier else Modifier,)
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_show_recent_channels_category),
                    value = stringResource(R.string.settings_show_recent_channels_category_subtitle),
                    checked = uiState.showRecentChannelsCategory,
                    onCheckedChange = viewModel::setShowRecentChannelsCategory
                ,
                    modifier = if (targetItemId == "live.recent_category") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_guide_default_category),
                    value = guideDefaultCategoryLabel,
                    onClick = { onShowGuideDefaultCategoryDialogChange(true) },
                    modifier = if (targetItemId == "guide.default_category") targetFocusModifier else Modifier,
                )
            }
            if (page == null || page == SettingsPage.HOME) {
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_top_navigation),
                    value = topNavigationSummaryLabel,
                    onClick = { onShowTopNavigationDialogChange(true) }
                ,
                    modifier = if (targetItemId == "appearance.top_navigation") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_customize_home),
                    value = homeDashboardSummaryLabel,
                    onClick = { onShowHomeDashboardDialogChange(true) }
                ,
                    modifier = if (targetItemId == "appearance.home_shelves") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_default_landing_screen),
                    value = appLandingDestinationLabel,
                    onClick = { onShowLandingScreenDialogChange(true) }
                ,
                    modifier = if (targetItemId == "appearance.landing") targetFocusModifier else Modifier,)
            }
            if (page == null || page == SettingsPage.LIVE_FILTERS) {
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_live_tv_quick_filters),
                    value = formatLiveTvQuickFiltersValue(uiState.liveTvCategoryFilters, context),
                    onClick = { onShowLiveTvFiltersDialogChange(true) }
                ,
                    modifier = if (targetItemId == "live.quick_filters") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_live_tv_quick_filter_visibility),
                    value = stringResource(uiState.liveTvQuickFilterVisibilityMode.labelResId()),
                    onClick = { onShowLiveTvQuickFilterVisibilityDialogChange(true) }
                ,
                    modifier = if (targetItemId == "live.filter_visibility") targetFocusModifier else Modifier,)
            }
            if (page == null || page == SettingsPage.LIVE_CHANNELS) {
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_hide_decorative_live_rows),
                    value = stringResource(R.string.settings_hide_decorative_live_rows_subtitle),
                    checked = uiState.hideDecorativeLiveRows,
                    onCheckedChange = viewModel::setHideDecorativeLiveRows
                ,
                    modifier = if (targetItemId == "live.hide_decorative_rows") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_live_channel_numbering_mode),
                    value = stringResource(uiState.liveChannelNumberingMode.labelResId()),
                    onClick = { onShowLiveChannelNumberingDialogChange(true) }
                ,
                    modifier = if (targetItemId == "live.numbering") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_live_channel_grouping_mode),
                    value = stringResource(uiState.liveChannelGroupingMode.labelResId()),
                    onClick = { onShowLiveChannelGroupingDialogChange(true) }
                ,
                    modifier = if (targetItemId == "live.grouping") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_grouped_channel_label_mode),
                    value = stringResource(uiState.groupedChannelLabelMode.labelResId()),
                    onClick = { onShowGroupedChannelLabelDialogChange(true) },
                    enabled = uiState.liveChannelGroupingMode == LiveChannelGroupingMode.GROUPED,
                    indent = 24.dp
                ,
                    modifier = if (targetItemId == "live.group_label") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_live_variant_preference_mode),
                    value = stringResource(uiState.liveVariantPreferenceMode.labelResId()),
                    onClick = { onShowLiveVariantPreferenceDialogChange(true) },
                    enabled = uiState.liveChannelGroupingMode == LiveChannelGroupingMode.GROUPED,
                    indent = 24.dp
                ,
                    modifier = if (targetItemId == "live.variant_preference") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_category_sort_live),
                    value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.LIVE] ?: CategorySortMode.DEFAULT, context),
                    onClick = { onCategorySortDialogTypeChange(ContentType.LIVE.name) }
                ,
                    modifier = if (targetItemId == "live.sort") targetFocusModifier else Modifier,)
            }
            if (page == null || page == SettingsPage.APPEARANCE) {
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_time_format),
                    value = timeFormatLabel,
                    onClick = { onShowTimeFormatDialogChange(true) }
                ,
                    modifier = if (targetItemId == "appearance.time_format") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_theme),
                    value = stringResource(uiState.appTheme.labelResId()),
                    onClick = { onShowThemeDialogChange(true) }
                ,
                    modifier = if (targetItemId == "appearance.theme") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_app_language),
                    value = appLanguageLabel,
                    onClick = { onShowLanguageDialogChange(true) }
                ,
                    modifier = if (targetItemId == "appearance.language") targetFocusModifier else Modifier,)
            }
            if (page == null || page == SettingsPage.VOD_LIBRARY) {
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_vod_view_mode),
                    value = stringResource(uiState.vodViewMode.labelResId()),
                    onClick = { onShowVodViewModeDialogChange(true) }
                ,
                    modifier = if (targetItemId == "vod.view_mode") targetFocusModifier else Modifier,)
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_vod_type_badge_icons),
                    value = stringResource(
                        if (uiState.vodTypeBadgeAsIcon) R.string.settings_vod_type_badge_icons_on
                        else R.string.settings_vod_type_badge_icons_off
                    ),
                    checked = uiState.vodTypeBadgeAsIcon,
                    onCheckedChange = { viewModel.setVodTypeBadgeAsIcon(it) },
                    indent = 24.dp
                ,
                    modifier = if (targetItemId == "vod.type_badge_icon") targetFocusModifier else Modifier,)
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
                ,
                    modifier = if (targetItemId == "vod.complete_on_open") targetFocusModifier else Modifier,)
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_vod_infinite_scroll),
                    value = stringResource(
                        if (uiState.vodInfiniteScroll) R.string.settings_vod_infinite_scroll_on
                        else R.string.settings_vod_infinite_scroll_off
                    ),
                    checked = uiState.vodInfiniteScroll,
                    onCheckedChange = { viewModel.setVodInfiniteScroll(it) },
                    indent = 24.dp
                ,
                    modifier = if (targetItemId == "vod.infinite_scroll") targetFocusModifier else Modifier,)
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_vod_portal_search),
                    value = stringResource(
                        if (uiState.vodPortalSearch) R.string.settings_vod_portal_search_on
                        else R.string.settings_vod_portal_search_off
                    ),
                    checked = uiState.vodPortalSearch,
                    onCheckedChange = { viewModel.setVodPortalSearch(it) },
                    indent = 24.dp
                ,
                    modifier = if (targetItemId == "vod.portal_search") targetFocusModifier else Modifier,)
            }
            if (page == null || page == SettingsPage.VOD_ORGANIZATION) {
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_vod_duplicate_handling_mode),
                    value = stringResource(uiState.vodDuplicateHandlingMode.labelResId()),
                    onClick = { onShowVodDuplicateHandlingDialogChange(true) }
                ,
                    modifier = if (targetItemId == "vod.duplicate_handling") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_vod_variant_preference_mode),
                    value = stringResource(uiState.vodVariantPreferenceMode.labelResId()),
                    onClick = { onShowVodVariantPreferenceDialogChange(true) },
                    enabled = uiState.vodDuplicateHandlingMode != VodDuplicateHandlingMode.SHOW_ALL,
                    indent = 24.dp
                ,
                    modifier = if (targetItemId == "vod.variant_preference") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_category_sort_movies),
                    value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.MOVIE] ?: CategorySortMode.DEFAULT, context),
                    onClick = { onCategorySortDialogTypeChange(ContentType.MOVIE.name) }
                ,
                    modifier = if (targetItemId == "vod.movie_sort") targetFocusModifier else Modifier,)
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_category_sort_series),
                    value = formatCategorySortModeLabel(uiState.categorySortModes[ContentType.SERIES] ?: CategorySortMode.DEFAULT, context),
                    onClick = { onCategorySortDialogTypeChange(ContentType.SERIES.name) }
                ,
                    modifier = if (targetItemId == "vod.series_sort") targetFocusModifier else Modifier,)
            }
        }
    }
    if (page == null || page == SettingsPage.REMOTE) item {
        RemoteShortcutSettingsPanel(
            uiState = uiState,
            context = context,
            onRemoteShortcutDialogTargetChange = onRemoteShortcutDialogTargetChange,
            targetItemId = targetItemId,
            targetFocusModifier = targetFocusModifier,
        )
    }
}

@Composable
private fun RemoteShortcutSettingsPanel(
    uiState: SettingsUiState,
    context: android.content.Context,
    onRemoteShortcutDialogTargetChange: (RemoteShortcutDialogTarget?) -> Unit,
    targetItemId: String? = null,
    targetFocusModifier: Modifier = Modifier,
) {
    val requestedTarget = remoteShortcutTargetFromId(targetItemId)
    var selectedProfileStorage by rememberSaveable {
        mutableStateOf((requestedTarget?.first ?: RemoteShortcutProfile.GLOBAL).storageValue)
    }
    androidx.compose.runtime.LaunchedEffect(requestedTarget?.first) {
        requestedTarget?.first?.let { selectedProfileStorage = it.storageValue }
    }
    val selectedProfile = RemoteShortcutProfile.fromStorage(selectedProfileStorage)
        ?: RemoteShortcutProfile.GLOBAL

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider(
            color = com.streamvault.core.ui.design.AppColors.Divider,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(com.streamvault.core.ui.theme.SurfaceElevated, RoundedCornerShape(18.dp))
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
                onRemoteShortcutDialogTargetChange = onRemoteShortcutDialogTargetChange,
                targetButton = requestedTarget?.takeIf { it.first == selectedProfile }?.second,
                targetFocusModifier = targetFocusModifier,
            )
        }
    }
}

@Composable
private fun RemoteShortcutLegendChip(button: RemoteColorButton) {
    Row(
        modifier = Modifier
            .background(com.streamvault.core.ui.theme.SurfaceElevated, RoundedCornerShape(999.dp))
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
            containerColor = if (selected) Primary.copy(alpha = 0.16f) else com.streamvault.core.ui.theme.SurfaceElevated,
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
    onRemoteShortcutDialogTargetChange: (RemoteShortcutDialogTarget?) -> Unit,
    targetButton: RemoteColorButton? = null,
    targetFocusModifier: Modifier = Modifier,
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
                        modifier = Modifier
                            .weight(1f)
                            .then(if (button == targetButton) targetFocusModifier else Modifier),
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

private fun AppTheme.labelResId(): Int = when (this) {
    AppTheme.CLASSIC_BLUE -> R.string.settings_theme_classic_blue
    AppTheme.M3_PURPLE -> R.string.settings_theme_m3_purple
    AppTheme.LIGHT -> R.string.settings_theme_light
}
