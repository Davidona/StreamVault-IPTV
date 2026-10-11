package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.feature.settings.R

@Composable
internal fun OpenSubtitlesAccountSettings(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    targetFocusModifier: Modifier = Modifier,
) {
    var showSignIn by rememberSaveable { mutableStateOf(false) }
    var showSignOut by rememberSaveable { mutableStateOf(false) }
    val account = uiState.openSubtitlesAccount
    val remainingDownloads = account?.remainingDownloads

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SettingsRow(
            label = stringResource(R.string.settings_opensubtitles_title),
            value = stringResource(R.string.settings_opensubtitles_description),
        )
        if (account == null) {
            ClickableSettingsRow(
                label = stringResource(R.string.settings_opensubtitles_connect),
                value = if (uiState.isOpenSubtitlesBusy) {
                    stringResource(R.string.settings_opensubtitles_signing_in)
                } else {
                    stringResource(R.string.settings_opensubtitles_signed_out)
                },
                enabled = !uiState.isOpenSubtitlesBusy,
                onClick = { showSignIn = true },
                modifier = targetFocusModifier,
            )
        } else {
            SettingsRow(
                label = account.username,
                value = when {
                    remainingDownloads != null && remainingDownloads == 1 ->
                        stringResource(R.string.settings_opensubtitles_remaining_one)
                    remainingDownloads != null -> stringResource(
                        R.string.settings_opensubtitles_remaining,
                        remainingDownloads,
                    )
                    account.allowedDownloads > 0 -> stringResource(
                        R.string.settings_opensubtitles_daily_allowance,
                        account.allowedDownloads,
                    )
                    else -> stringResource(R.string.settings_opensubtitles_quota_unknown)
                },
            )
            ClickableSettingsRow(
                label = stringResource(R.string.settings_opensubtitles_sign_out),
                value = if (uiState.isOpenSubtitlesBusy) {
                    stringResource(R.string.settings_opensubtitles_signing_out)
                } else {
                    ""
                },
                enabled = !uiState.isOpenSubtitlesBusy,
                onClick = { showSignOut = true },
                modifier = targetFocusModifier,
            )
        }
        uiState.openSubtitlesError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }

    if (showSignIn) {
        var username by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        LaunchedEffect(account) {
            if (account != null) showSignIn = false
        }
        PremiumDialog(
            title = stringResource(R.string.settings_opensubtitles_sign_in_title),
            subtitle = stringResource(R.string.settings_opensubtitles_sign_in_subtitle),
            onDismissRequest = { if (!uiState.isOpenSubtitlesBusy) showSignIn = false },
            widthFraction = 0.58f,
            heightFraction = 0.76f,
            bodyHeightFraction = 0.62f,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EpgSourceTextField(
                        value = username,
                        onValueChange = { username = it },
                        placeholder = stringResource(R.string.settings_opensubtitles_username),
                    )
                    EpgSourceTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = stringResource(R.string.settings_opensubtitles_password),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardType = KeyboardType.Password,
                    )
                    uiState.openSubtitlesError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            footer = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PremiumDialogFooterButton(
                        label = stringResource(R.string.settings_cancel),
                        onClick = { showSignIn = false },
                        enabled = !uiState.isOpenSubtitlesBusy,
                    )
                    PremiumDialogFooterButton(
                        label = if (uiState.isOpenSubtitlesBusy) {
                            stringResource(R.string.settings_opensubtitles_signing_in)
                        } else {
                            stringResource(R.string.settings_opensubtitles_sign_in)
                        },
                        onClick = {
                            viewModel.signInToOpenSubtitles(username, password)
                        },
                        enabled = username.isNotBlank() && password.isNotBlank() && !uiState.isOpenSubtitlesBusy,
                        emphasized = true,
                    )
                }
            },
        )
    }

    if (showSignOut && account != null) {
        PremiumDialog(
            title = stringResource(R.string.settings_opensubtitles_sign_out_title),
            subtitle = stringResource(R.string.settings_opensubtitles_sign_out_subtitle),
            onDismissRequest = { if (!uiState.isOpenSubtitlesBusy) showSignOut = false },
            content = {
                Text(
                    text = account.username,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnSurfaceDim,
                )
            },
            footer = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PremiumDialogFooterButton(
                        label = stringResource(R.string.settings_cancel),
                        onClick = { showSignOut = false },
                        enabled = !uiState.isOpenSubtitlesBusy,
                    )
                    PremiumDialogFooterButton(
                        label = stringResource(R.string.settings_opensubtitles_sign_out),
                        onClick = {
                            showSignOut = false
                            viewModel.signOutOfOpenSubtitles()
                        },
                        enabled = !uiState.isOpenSubtitlesBusy,
                        destructive = true,
                    )
                }
            },
        )
    }
}
