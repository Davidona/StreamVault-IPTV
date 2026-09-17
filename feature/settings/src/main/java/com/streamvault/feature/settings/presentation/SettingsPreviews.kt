package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.SearchInput
import com.streamvault.core.ui.components.dialogs.PinDialog
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.AppPalette
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.model.ProviderStatus

@Preview(name = "Settings · Classic blue", widthDp = 960, heightDp = 540)
@Composable
private fun SettingsClassicPreview() = SettingsOverviewPreview(AppPalette.CLASSIC_BLUE_ID)

@Preview(name = "Settings · M3 purple", widthDp = 960, heightDp = 540)
@Composable
private fun SettingsPurplePreview() = SettingsOverviewPreview(AppPalette.M3_PURPLE_ID)

@Preview(name = "Settings · Light", widthDp = 960, heightDp = 540)
@Composable
private fun SettingsLightPreview() = SettingsOverviewPreview(AppPalette.LIGHT_ID)

@Preview(name = "Live TV pair · Classic blue", widthDp = 1200, heightDp = 540)
@Composable
private fun SettingsLivePairClassicPreview() = SettingsLivePairPreview(AppPalette.CLASSIC_BLUE_ID)

@Preview(name = "Live TV pair · M3 purple", widthDp = 1200, heightDp = 540)
@Composable
private fun SettingsLivePairPurplePreview() = SettingsLivePairPreview(AppPalette.M3_PURPLE_ID)

@Preview(name = "Live TV pair · Light", widthDp = 1200, heightDp = 540)
@Composable
private fun SettingsLivePairLightPreview() = SettingsLivePairPreview(AppPalette.LIGHT_ID)

@Preview(name = "Playback pair · Classic blue", widthDp = 1200, heightDp = 540)
@Composable
private fun SettingsPlaybackPairClassicPreview() = SettingsPlaybackPairPreview(AppPalette.CLASSIC_BLUE_ID)

@Preview(name = "Playback pair · M3 purple", widthDp = 1200, heightDp = 540)
@Composable
private fun SettingsPlaybackPairPurplePreview() = SettingsPlaybackPairPreview(AppPalette.M3_PURPLE_ID)

@Preview(name = "Playback pair · Light", widthDp = 1200, heightDp = 540)
@Composable
private fun SettingsPlaybackPairLightPreview() = SettingsPlaybackPairPreview(AppPalette.LIGHT_ID)

@Preview(name = "Compact categories · Large text", widthDp = 412, heightDp = 892, fontScale = 1.3f)
@Composable
private fun SettingsCompactCategoriesPreview() {
    StreamVaultTheme {
        SettingsNavigationRail(
            selectedCategory = SettingsCategory.PLAYBACK.legacyId,
            focusRequester = remember { FocusRequester() },
            onCategorySelected = {},
            compact = true,
        )
    }
}

@Preview(name = "Compact detail · RTL", widthDp = 412, heightDp = 892, locale = "ar")
@Composable
private fun SettingsCompactRtlDetailPreview() {
    StreamVaultTheme {
        SettingsPreviewShell(fillWidth = true) {
            SettingsLocalHeader(
                title = "الصوت",
                description = "لغة الصوت المفضلة والمخرج والمزامنة.",
                parentTitle = "التشغيل",
                onBack = {},
                onSearch = {},
            )
            Column(verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4)) {
                SwitchSettingsRow("مزامنة الصوت والفيديو", "تصحيح اختلاف التوقيت", true, {})
                ClickableSettingsRow("لغة الصوت المفضلة", "تلقائي", {})
                ClickableSettingsRow("إخراج الصوت", "تلقائي", {})
            }
        }
    }
}

@Preview(name = "Playback · General", widthDp = 960, heightDp = 540)
@Composable
private fun SettingsPlaybackDetailPreview() {
    StreamVaultTheme {
        SettingsPreviewShell {
            SettingsLocalHeader(
                title = "General playback",
                description = "Choose the default player behavior.",
                parentTitle = "Playback",
                onBack = {},
                onSearch = {},
            )
            Column(verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4)) {
                SwitchSettingsRow("System media session", "Show playback in Android system controls", true, {})
                ClickableSettingsRow("Default playback app", "Internal player", {})
                ClickableSettingsRow("Default playback speed", "1×", {})
            }
        }
    }
}

@Preview(name = "Search · Empty", widthDp = 960, heightDp = 540)
@Composable
private fun SettingsSearchEmptyPreview() {
    StreamVaultTheme {
        SettingsSearchSurface("", {}, {}, {})
    }
}

@Preview(name = "Search · Results", widthDp = 960, heightDp = 540)
@Composable
private fun SettingsSearchResultsPreview() {
    StreamVaultTheme(themeId = AppPalette.LIGHT_ID) {
        SettingsSearchSurface("playback", {}, {}, {})
    }
}

@Preview(name = "Search · No results", widthDp = 960, heightDp = 540)
@Composable
private fun SettingsSearchNoResultsPreview() {
    StreamVaultTheme {
        SettingsSearchSurface("no setting matches this", {}, {}, {})
    }
}

@Preview(name = "Privacy and parental", widthDp = 960, heightDp = 540)
@Composable
private fun SettingsPrivacyPreview() {
    StreamVaultTheme {
        SettingsPreviewShell {
            SettingsLocalHeader(
                title = "Privacy & parental",
                description = "Control protection, private viewing, and playback history.",
                parentTitle = null,
                onBack = null,
                onSearch = {},
            )
            Column(verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4)) {
                ClickableSettingsRow("Protection level", "Locked content", {})
                ClickableSettingsRow("Change PIN", "PIN configured", {})
                SwitchSettingsRow("Private viewing", "Viewing history is paused", true, {})
                ClickableSettingsRow("Clear playback history", "Remove saved progress and recent activity", {})
            }
        }
    }
}

@Preview(name = "Parental category management", widthDp = 960, heightDp = 540)
@Composable
private fun SettingsParentalCategoriesPreview() {
    StreamVaultTheme {
        SettingsPreviewShell {
            SettingsLocalHeader(
                title = "Category controls",
                description = "Choose which categories require a PIN.",
                parentTitle = "Privacy & parental",
                onBack = {},
            )
            SearchInput(
                value = "",
                onValueChange = {},
                placeholder = "Search categories",
                modifier = Modifier.fillMaxWidth(),
                onSearch = {},
            )
            Column(verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4)) {
                SwitchSettingsRow("News", "Live TV · Protected", true, {})
                SwitchSettingsRow("Sports", "Live TV · Visible without PIN", false, {})
                SwitchSettingsRow("Movies", "Live TV · Protected", true, {})
            }
        }
    }
}

@Preview(name = "PIN dialog · Error", widthDp = 960, heightDp = 540)
@Composable
private fun SettingsPinDialogPreview() {
    StreamVaultTheme {
        PinDialog(
            onDismissRequest = {},
            onPinEntered = {},
            title = "Enter parental PIN",
            cancelLabel = "Cancel",
            error = "Incorrect PIN. Try again.",
        )
    }
}

@Preview(name = "Source · Busy and error", widthDp = 960, heightDp = 540)
@Composable
private fun SettingsSourceStatesPreview() {
    StreamVaultTheme {
        SettingsPreviewShell {
            SettingsLocalHeader(
                title = "Sources",
                description = "Manage provider connections and catalog sync.",
                parentTitle = "Sources & guide",
                onBack = {},
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space12)) {
                ProviderCompactStat(
                    title = "Channels",
                    value = ProviderCatalogCountUiModel(1248, ProviderCatalogCountStatus.SYNCING),
                )
                ProviderCompactStat(
                    title = "Movies",
                    value = ProviderCatalogCountUiModel(0, ProviderCatalogCountStatus.FAILED),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4)) {
                ProviderStatusBadge(ProviderStatus.PARTIAL, requiresAttention = true)
                ClickableSettingsRow("Sync source", "Channel catalog is still updating", {}, enabled = false)
                ClickableSettingsRow("Retry movie catalog", "The previous request failed", {})
            }
        }
    }
}

@Preview(name = "Backup import · Conflicts", widthDp = 960, heightDp = 540)
@Composable
private fun SettingsBackupConflictPreview() {
    StreamVaultTheme {
        BackupImportPreviewDialog(
            preview = BackupPreview(
                version = 2,
                providerCount = 3,
                favoriteCount = 24,
                groupCount = 5,
                playbackHistoryCount = 118,
                multiViewPresetCount = 2,
                preferenceCount = 42,
                protectedCategoryCount = 8,
                scheduledRecordingCount = 4,
                providerConflicts = 1,
                favoriteConflicts = 6,
                groupConflicts = 0,
                historyConflicts = 12,
                protectedCategoryConflicts = 2,
                recordingConflicts = 1,
            ),
            plan = BackupImportPlan(conflictStrategy = BackupConflictStrategy.KEEP_EXISTING),
            onDismiss = {},
            onStrategySelected = {},
            onImportPreferencesChanged = {},
            onImportProvidersChanged = {},
            onImportSavedLibraryChanged = {},
            onImportPlaybackHistoryChanged = {},
            onImportMultiViewChanged = {},
            onImportRecordingSchedulesChanged = {},
            onConfirm = {},
        )
    }
}

@Preview(name = "Recording · Defaults", widthDp = 960, heightDp = 540)
@Composable
private fun SettingsRecordingDefaultsPreview() {
    StreamVaultTheme {
        SettingsPreviewShell {
            SettingsLocalHeader(
                title = "Recording defaults",
                description = "Set file naming, retention, padding, and network rules.",
                parentTitle = "Recordings",
                onBack = {},
            )
            Column(verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4)) {
                ClickableSettingsRow("Filename pattern", "{title} - {date}", {})
                ClickableSettingsRow("Retention policy", "Keep until deleted", {})
                ClickableSettingsRow("Max simultaneous recordings", "2", {})
                ClickableSettingsRow("Recording padding", "Start 2 min early · End 5 min late", {})
                SwitchSettingsRow("Wi-Fi only", "Record only on an unmetered network", true, {})
            }
        }
    }
}

@Preview(name = "Updates · Downloading", widthDp = 960, heightDp = 540)
@Composable
private fun SettingsUpdateProgressPreview() {
    StreamVaultTheme {
        SettingsPreviewShell {
            SettingsLocalHeader(
                title = "App updates",
                description = "Version 2.8 is downloading.",
                parentTitle = "About & support",
                onBack = {},
            )
            Column(verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8)) {
                SettingsRow("Status", "Downloading · 62%")
                LinearProgressIndicator(
                    progress = { 0.62f },
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.Brand,
                    trackColor = AppColors.SurfaceElevated,
                )
                ClickableSettingsRow("Install update", "Available when the download finishes", {}, enabled = false)
                Text(
                    text = "You can continue using StreamVault while the update downloads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SettingsOverviewPreview(themeId: String) {
    StreamVaultTheme(themeId = themeId) {
        Row(Modifier.fillMaxSize().background(AppColors.Canvas)) {
            SettingsNavigationRail(
                selectedCategory = SettingsCategory.LIVE_TV.legacyId,
                focusRequester = remember { FocusRequester() },
                onCategorySelected = {},
                compact = false,
            )
            SettingsPreviewShell {
                SettingsLocalHeader(
                    title = "Live TV",
                    description = "Channels, categories, filters, and guide behavior.",
                    parentTitle = null,
                    onBack = null,
                )
                Column(verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8)) {
                    SettingsPageCard(SettingsPage.LIVE_LAYOUT) {}
                    SettingsPageCard(SettingsPage.LIVE_CHANNELS) {}
                    SettingsPageCard(SettingsPage.LIVE_FILTERS) {}
                    SettingsPageCard(SettingsPage.TIMESHIFT) {}
                }
            }
        }
    }
}

@Composable
private fun SettingsLivePairPreview(themeId: String) {
    SettingsPairedPreview(themeId = themeId, category = SettingsCategory.LIVE_TV, page = SettingsPage.LIVE_LAYOUT) {
        ClickableSettingsRow("Live TV channel mode", "Preview before playback", {})
        SwitchSettingsRow("Auto-hide categories", "Hide the category rail during browsing", false, {})
        SwitchSettingsRow("Show Live source browser", "Choose a source from the channel list", true, {})
        SwitchSettingsRow("Show Favorites", "Keep Favorites in the category list", true, {})
    }
}

@Composable
private fun SettingsPlaybackPairPreview(themeId: String) {
    SettingsPairedPreview(themeId = themeId, category = SettingsCategory.PLAYBACK, page = SettingsPage.GENERAL) {
        SwitchSettingsRow("System media session", "Show playback in Android system controls", true, {})
        ClickableSettingsRow("Default playback app", "Internal player", {})
        ClickableSettingsRow("Default playback speed", "1×", {})
    }
}

@Composable
private fun SettingsPairedPreview(
    themeId: String,
    category: SettingsCategory,
    page: SettingsPage,
    detailRows: @Composable ColumnScope.() -> Unit,
) {
    StreamVaultTheme(themeId = themeId) {
        Row(
            modifier = Modifier.fillMaxSize().background(AppColors.Canvas),
            horizontalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space24),
        ) {
            SettingsPreviewShell(modifier = Modifier.weight(1f), fillWidth = true) {
                SettingsLocalHeader(
                    title = androidx.compose.ui.res.stringResource(category.title),
                    description = androidx.compose.ui.res.stringResource(category.description),
                    parentTitle = null,
                    onBack = null,
                    onSearch = {},
                )
                Column(verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8)) {
                    category.pages.take(4).forEach { SettingsPageCard(it) {} }
                }
            }
            SettingsPreviewShell(modifier = Modifier.weight(1f), fillWidth = true) {
                SettingsLocalHeader(
                    title = androidx.compose.ui.res.stringResource(page.title),
                    description = androidx.compose.ui.res.stringResource(page.description),
                    parentTitle = androidx.compose.ui.res.stringResource(category.title),
                    onBack = {},
                    onSearch = {},
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4),
                    content = detailRows,
                )
            }
        }
    }
}

@Composable
private fun SettingsPreviewShell(
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .then(
                if (fillWidth) Modifier.fillMaxWidth()
                else Modifier.width(SettingsDesignTokens.contentMaxWidth)
            )
            .fillMaxHeight()
            .padding(
                start = SettingsDesignTokens.space24,
                top = SettingsDesignTokens.space16,
                end = SettingsDesignTokens.tvHorizontalInset,
            ),
        verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space12),
    ) {
        content()
    }
}
