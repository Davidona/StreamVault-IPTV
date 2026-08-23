package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.Primary

internal fun LazyListScope.settingsPrivacySection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onPendingProtectionLevelChange: (Int?) -> Unit,
    onPendingActionChange: (ParentalAction?) -> Unit,
    onShowPinDialogChange: (Boolean) -> Unit,
    onShowLevelDialogChange: (Boolean) -> Unit,
    onShowClearHistoryDialogChange: (Boolean) -> Unit
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
            }
        )
    }
    item {
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 4.dp))
        TvClickableSurface(
            onClick = { viewModel.toggleIncognitoMode() },
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
                    Text(text = stringResource(R.string.settings_incognito_mode), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_incognito_mode_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.isIncognitoMode, onCheckedChange = null)
            }
        }
        Spacer(Modifier.height(2.dp))
        TvClickableSurface(
            onClick = { viewModel.toggleXtreamTextClassification() },
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
                    Text(text = stringResource(R.string.settings_xtream_text_classification), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_xtream_text_classification_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.useXtreamTextClassification, onCheckedChange = null)
            }
        }
        Spacer(Modifier.height(2.dp))
        TvClickableSurface(
            onClick = { viewModel.toggleXtreamBase64TextCompatibility() },
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
                    Text(text = stringResource(R.string.settings_xtream_base64_compatibility), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_xtream_base64_compatibility_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Switch(checked = uiState.xtreamBase64TextCompatibility, onCheckedChange = null)
            }
        }
        Spacer(Modifier.height(2.dp))
        TvClickableSurface(
            onClick = { onShowClearHistoryDialogChange(true) },
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
                    Text(text = stringResource(R.string.settings_clear_history), style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                    Text(text = stringResource(R.string.settings_clear_history_subtitle), style = MaterialTheme.typography.bodySmall, color = OnBackground.copy(alpha = 0.6f))
                }
                Text(text = stringResource(R.string.settings_clear_history_confirm), style = MaterialTheme.typography.labelLarge, color = Primary)
            }
        }
    }
}