package com.streamvault.app.ui.screens.provider

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.tv.material3.MaterialTheme
import com.streamvault.app.R
import com.streamvault.app.pairing.ProviderQrPairingState
import com.streamvault.core.ui.theme.ErrorColor
import com.streamvault.domain.model.StalkerAuthMode

@Composable
internal fun XtreamProviderForm(
    uiState: ProviderSetupState,
    isTelevisionDevice: Boolean,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    options: ProviderAdvancedOptions,
    onLogin: () -> Unit
) {
    ProviderTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        placeholder = stringResource(R.string.setup_server_hint),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (isTelevisionDevice) KeyboardType.Ascii else KeyboardType.Uri,
            imeAction = ImeAction.Next
        )
    )
    ProviderTextField(
        value = username,
        onValueChange = onUsernameChange,
        placeholder = stringResource(R.string.setup_user_hint),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next
        )
    )
    ProviderTextField(
        value = password,
        onValueChange = onPasswordChange,
        placeholder = stringResource(R.string.setup_pass_hint),
        isPassword = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (isTelevisionDevice) KeyboardType.Ascii else KeyboardType.Password,
            imeAction = ImeAction.Done
        )
    )
    AdvancedProviderOptionsSection(options)
    FormErrors(uiState.validationError, uiState.error)
    ActionButton(
        text = when {
            uiState.isLoading -> stringResource(R.string.setup_connecting)
            uiState.isEditing -> stringResource(R.string.setup_save)
            else -> stringResource(R.string.setup_login)
        },
        isLoading = uiState.isLoading,
        onClick = onLogin
    )
}

@Composable
internal fun StalkerProviderForm(
    uiState: ProviderSetupState,
    isTelevisionDevice: Boolean,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    stalkerMacAddress: String,
    onStalkerMacAddressChange: (String) -> Unit,
    stalkerAuthMode: StalkerAuthMode,
    options: ProviderAdvancedOptions,
    onLogin: () -> Unit,
    onRepair: () -> Unit,
    onSelectProtocol: (com.streamvault.domain.model.StalkerProtocolPreference) -> Unit,
    onSelectProfile: (String) -> Unit
) {
    ProviderTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        placeholder = "Portal URL",
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (isTelevisionDevice) KeyboardType.Ascii else KeyboardType.Uri,
            imeAction = ImeAction.Next
        )
    )
    ProviderTextField(
        value = stalkerMacAddress,
        onValueChange = onStalkerMacAddressChange,
        placeholder = if (stalkerAuthMode == StalkerAuthMode.CREDENTIALS_ONLY) {
            "MAC address (optional)"
        } else {
            "MAC address"
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next
        )
    )
    StalkerCompatibilitySelector(
        protocol = uiState.stalkerProtocolPreference,
        profileId = uiState.stalkerRequestedProfileId,
        onProtocolSelected = onSelectProtocol,
        onProfileSelected = onSelectProfile
    )
    AdvancedProviderOptionsSection(options)
    FormErrors(uiState.validationError, uiState.error)
    ActionButton(
        text = when {
            uiState.isLoading -> stringResource(R.string.setup_connecting)
            uiState.isEditing -> stringResource(R.string.setup_save)
            else -> stringResource(R.string.setup_login)
        },
        isLoading = uiState.isLoading,
        onClick = onLogin
    )
    if (uiState.isEditing) {
        SmallActionButton(
            text = stringResource(R.string.stalker_repair_connection),
            isLoading = uiState.isLoading,
            onClick = onRepair
        )
    }
}

@Composable
internal fun M3uProviderForm(
    uiState: ProviderSetupState,
    m3uUrl: String,
    onM3uUrlChange: (String) -> Unit,
    fileImportError: String?,
    isFile: Boolean,
    onFilePick: () -> Unit,
    options: ProviderAdvancedOptions,
    onAdd: () -> Unit
) {
    if (isFile) {
        FileSelectorCard(
            fileName = if (m3uUrl.startsWith("file://")) m3uUrl.substringAfterLast("/") else null,
            fileSelectedHint = stringResource(R.string.setup_file_replace_hint),
            emptySelectionTitle = stringResource(R.string.setup_file_select_title),
            emptySelectionHint = stringResource(R.string.setup_file_browse_hint),
            onClick = onFilePick
        )
        fileImportError?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = ErrorColor)
        }
    } else {
        ProviderTextField(
            value = m3uUrl,
            onValueChange = onM3uUrlChange,
            placeholder = stringResource(R.string.setup_m3u_hint),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            )
        )
    }
    AdvancedProviderOptionsSection(options)
    FormErrors(uiState.validationError, uiState.error)
    ActionButton(
        text = when {
            uiState.isLoading -> stringResource(R.string.setup_validating)
            uiState.isEditing -> stringResource(R.string.setup_save)
            else -> stringResource(R.string.setup_add)
        },
        isLoading = uiState.isLoading,
        onClick = onAdd
    )
}

@Composable
internal fun JellyfinProviderSetupForm(
    uiState: ProviderSetupState,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    quickConnectCode: String,
    onQuickConnectRequest: () -> Unit,
    onLogin: () -> Unit
) {
    JellyfinProviderForm(
        serverUrl = serverUrl,
        onServerUrlChange = onServerUrlChange,
        username = username,
        onUsernameChange = onUsernameChange,
        password = password,
        onPasswordChange = onPasswordChange,
        name = name,
        onNameChange = onNameChange,
        quickConnectCode = quickConnectCode,
        onQuickConnectRequest = onQuickConnectRequest,
        isEditing = uiState.isEditing
    )
    FormErrors(uiState.validationError, uiState.error)
    ActionButton(
        text = when {
            uiState.isLoading -> stringResource(R.string.setup_validating)
            uiState.isEditing -> stringResource(R.string.setup_save)
            else -> stringResource(R.string.setup_add)
        },
        isLoading = uiState.isLoading,
        onClick = onLogin
    )
}
