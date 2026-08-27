package com.streamvault.app.ui.screens.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import com.streamvault.core.ui.components.dialogs.PinDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun SettingsProtectionDialogs(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
    scope: CoroutineScope,
    showPinDialog: Boolean,
    onShowPinDialogChange: (Boolean) -> Unit,
    showLevelDialog: Boolean,
    onShowLevelDialogChange: (Boolean) -> Unit,
    pinError: String?,
    onPinErrorChange: (String?) -> Unit,
    pendingAction: ParentalAction?,
    onPendingActionChange: (ParentalAction?) -> Unit,
    pendingProtectionLevel: Int?,
    onPendingProtectionLevelChange: (Int?) -> Unit
) {
    if (showPinDialog) {
        PinDialog(
            cancelLabel = stringResource(R.string.pin_dialog_cancel),
            onDismissRequest = {
                onShowPinDialogChange(false)
                onPinErrorChange(null)
                if (pendingAction == ParentalAction.SetNewPin) {
                    onPendingActionChange(null)
                    onPendingProtectionLevelChange(null)
                }
            },
            onPinEntered = { pin ->
                scope.launch {
                    if (pendingAction == ParentalAction.SetNewPin) {
                        viewModel.changePin(pin)
                        pendingProtectionLevel?.let(viewModel::setParentalControlLevel)
                        onPendingProtectionLevelChange(null)
                        onShowPinDialogChange(false)
                        onPendingActionChange(null)
                    } else {
                        if (viewModel.verifyPin(pin)) {
                            onShowPinDialogChange(false)
                            onPinErrorChange(null)
                            when (pendingAction) {
                                ParentalAction.ChangeLevel -> onShowLevelDialogChange(true)
                                ParentalAction.ChangePin -> {
                                    onPendingActionChange(ParentalAction.SetNewPin)
                                    onShowPinDialogChange(true)
                                }
                                else -> onPendingActionChange(null)
                            }
                        } else {
                            onPinErrorChange(context.getString(R.string.home_incorrect_pin))
                        }
                    }
                }
            },
            title = if (pendingAction == ParentalAction.SetNewPin) {
                stringResource(R.string.settings_enter_new_pin)
            } else {
                stringResource(R.string.settings_enter_pin)
            },
            error = pinError
        )
    }

    if (showLevelDialog) {
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_select_level),
            onDismiss = { onShowLevelDialogChange(false) }
        ) {
            LevelOption(
                level = 0,
                text = stringResource(R.string.settings_level_off_desc),
                subtitle = stringResource(R.string.settings_level_off_subtitle),
                currentLevel = uiState.parentalControlLevel
            ) {
                viewModel.setParentalControlLevel(0)
                onShowLevelDialogChange(false)
            }
            LevelOption(
                level = 1,
                text = stringResource(R.string.settings_level_locked_desc),
                subtitle = stringResource(R.string.settings_level_locked_subtitle),
                currentLevel = uiState.parentalControlLevel
            ) {
                if (uiState.hasParentalPin) {
                    viewModel.setParentalControlLevel(1)
                } else {
                    onPendingProtectionLevelChange(1)
                    onPendingActionChange(ParentalAction.SetNewPin)
                    onShowPinDialogChange(true)
                }
                onShowLevelDialogChange(false)
            }
            LevelOption(
                level = 2,
                text = stringResource(R.string.settings_level_private_desc),
                subtitle = stringResource(R.string.settings_level_private_subtitle),
                currentLevel = uiState.parentalControlLevel
            ) {
                if (uiState.hasParentalPin) {
                    viewModel.setParentalControlLevel(2)
                } else {
                    onPendingProtectionLevelChange(2)
                    onPendingActionChange(ParentalAction.SetNewPin)
                    onShowPinDialogChange(true)
                }
                onShowLevelDialogChange(false)
            }
            LevelOption(
                level = 3,
                text = stringResource(R.string.settings_level_hidden_desc),
                subtitle = stringResource(R.string.settings_level_hidden_subtitle),
                currentLevel = uiState.parentalControlLevel
            ) {
                if (uiState.hasParentalPin) {
                    viewModel.setParentalControlLevel(3)
                } else {
                    onPendingProtectionLevelChange(3)
                    onPendingActionChange(ParentalAction.SetNewPin)
                    onShowPinDialogChange(true)
                }
                onShowLevelDialogChange(false)
            }
        }
    }
}
