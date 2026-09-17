package com.streamvault.feature.settings.presentation

import com.streamvault.feature.settings.presentation.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamvault.feature.settings.R

public fun LazyListScope.settingsPrivacySection(
    uiState: SettingsUiState,
    onToggleIncognitoMode: () -> Unit,
    onToggleXtreamTextClassification: () -> Unit,
    onToggleXtreamBase64TextCompatibility: () -> Unit,
    onPendingProtectionLevelChange: (Int?) -> Unit,
    onPendingActionChange: (ParentalAction?) -> Unit,
    onShowPinDialogChange: (Boolean) -> Unit,
    onShowLevelDialogChange: (Boolean) -> Unit,
    onShowClearHistoryDialogChange: (Boolean) -> Unit,
    onManageCategories: (() -> Unit)? = null,
    firstFocusModifier: Modifier = Modifier,
    targetItemId: String? = null,
    targetFocusModifier: Modifier = Modifier,
) {
    item {
        ParentalControlCard(
            level = uiState.parentalControlLevel,
            hasParentalPin = uiState.hasParentalPin,
            hasActiveProvider = uiState.activeProviderId != null,
            onChangeLevel = {
                onPendingProtectionLevelChange(null)
                if (uiState.hasParentalPin) {
                    onPendingActionChange(ParentalAction.ChangeLevel)
                    onShowPinDialogChange(true)
                } else {
                    onShowLevelDialogChange(true)
                }
            },
            onChangePin = {
                onPendingProtectionLevelChange(null)
                onPendingActionChange(
                    if (uiState.hasParentalPin) {
                        ParentalAction.ChangePin
                    } else {
                        ParentalAction.SetNewPin
                    }
                )
                onShowPinDialogChange(true)
            },
            firstActionModifier = if (targetItemId == "privacy.protection_level") {
                targetFocusModifier
            } else {
                firstFocusModifier
            },
            secondActionModifier = if (targetItemId == "privacy.pin") targetFocusModifier else Modifier,
        )
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onManageCategories != null) {
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_provider_category_controls_action),
                    value = stringResource(R.string.settings_provider_category_controls_subtitle),
                    onClick = onManageCategories,
                    modifier = if (targetItemId in setOf(
                            "sources.parental_categories",
                            "privacy.category_protection",
                            "privacy.category_visibility",
                        )) {
                        targetFocusModifier
                    } else Modifier,
                )
            }
            SwitchSettingsRow(
                label = stringResource(R.string.settings_incognito_mode),
                value = stringResource(R.string.settings_incognito_mode_subtitle),
                checked = uiState.isIncognitoMode,
                onCheckedChange = { onToggleIncognitoMode() },
                modifier = if (targetItemId == "privacy.incognito") targetFocusModifier else Modifier,
            )
            ClickableSettingsRow(
                label = stringResource(R.string.settings_clear_history),
                value = stringResource(R.string.settings_clear_history_subtitle),
                onClick = { onShowClearHistoryDialogChange(true) },
                modifier = if (targetItemId == "privacy.clear_history") targetFocusModifier else Modifier,
            )
        }
    }
}
