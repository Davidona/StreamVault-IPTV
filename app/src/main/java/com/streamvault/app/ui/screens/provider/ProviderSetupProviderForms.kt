package com.streamvault.app.ui.screens.provider

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.SurfaceDefaults
import com.streamvault.app.R
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.pairing.ProviderQrPairingState
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerCatalogMode
import com.streamvault.domain.model.StalkerProtocolPreference

@Composable
internal fun ProviderFormContent(
    sourceType: SourceType,
    uiState: ProviderSetupState,
    pairingState: ProviderQrPairingState,
    name: String,
    onNameChange: (String) -> Unit,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    m3uUrl: String,
    onM3uUrlChange: (String) -> Unit,
    httpUserAgent: String,
    onHttpUserAgentChange: (String) -> Unit,
    httpHeaders: String,
    onHttpHeadersChange: (String) -> Unit,
    stalkerMacAddress: String,
    onStalkerMacAddressChange: (String) -> Unit,
    stalkerAuthMode: StalkerAuthMode,
    onStalkerAuthModeChange: (StalkerAuthMode) -> Unit,
    stalkerDeviceProfile: String,
    onStalkerDeviceProfileChange: (String) -> Unit,
    stalkerDeviceTimezone: String,
    onStalkerDeviceTimezoneChange: (String) -> Unit,
    stalkerDeviceLocale: String,
    onStalkerDeviceLocaleChange: (String) -> Unit,
    stalkerSerialNumber: String,
    onStalkerSerialNumberChange: (String) -> Unit,
    stalkerDeviceId: String,
    onStalkerDeviceIdChange: (String) -> Unit,
    stalkerDeviceId2: String,
    onStalkerDeviceId2Change: (String) -> Unit,
    stalkerSignature: String,
    onStalkerSignatureChange: (String) -> Unit,
    stalkerHwVersion: String,
    onStalkerHwVersionChange: (String) -> Unit,
    stalkerApiUserAgent: String,
    onStalkerApiUserAgentChange: (String) -> Unit,
    stalkerPlayerUserAgent: String,
    onStalkerPlayerUserAgentChange: (String) -> Unit,
    stalkerPlayerHeaders: String,
    onStalkerPlayerHeadersChange: (String) -> Unit,
    stalkerXUserAgentLink: String,
    onStalkerXUserAgentLinkChange: (String) -> Unit,
    stalkerProxyEnabled: Boolean,
    onStalkerProxyEnabledChange: (Boolean) -> Unit,
    stalkerProxyHost: String,
    onStalkerProxyHostChange: (String) -> Unit,
    stalkerProxyPort: String,
    onStalkerProxyPortChange: (String) -> Unit,
    stalkerRequestRules: List<StalkerRequestRuleUiState>,
    onAddStalkerRequestRule: () -> Unit,
    onUpdateStalkerRequestRule: (Int, StalkerRequestRuleUiState) -> Unit,
    onRemoveStalkerRequestRule: (Int) -> Unit,
    fileImportError: String?,
    onFilePick: () -> Unit,
    onLoginXtream: () -> Unit,
    onLoginStalker: () -> Unit,
    onRepairStalker: () -> Unit,
    onAddM3u: () -> Unit,
    onLoginJellyfin: () -> Unit,
    quickConnectCode: String,
    onQuickConnectRequest: () -> Unit,
    onStartPhonePairing: () -> Unit,
    onStopPhonePairing: () -> Unit,
    onToggleM3uVodClassification: () -> Unit,
    onSelectEpgSyncMode: (ProviderEpgSyncMode) -> Unit,
    onSelectStalkerCatalogMode: (StalkerCatalogMode) -> Unit,
    onSelectStalkerProtocolPreference: (StalkerProtocolPreference) -> Unit,
    onSelectStalkerProfile: (String) -> Unit,
    onSelectXtreamLiveSyncMode: (ProviderXtreamLiveSyncMode) -> Unit,
    onSelectGuideSourcePolicy: (GuideSourcePolicy) -> Unit,
    onSelectChannelLogoSourcePolicy: (ChannelLogoSourcePolicy) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val advancedOptions = ProviderAdvancedOptions(
        sourceType = sourceType,
        uiState = uiState,
        httpUserAgent = httpUserAgent,
        onHttpUserAgentChange = onHttpUserAgentChange,
        httpHeaders = httpHeaders,
        onHttpHeadersChange = onHttpHeadersChange,
        onToggleM3uVodClassification = onToggleM3uVodClassification,
        onSelectEpgSyncMode = onSelectEpgSyncMode,
        onSelectStalkerCatalogMode = onSelectStalkerCatalogMode,
        onSelectXtreamLiveSyncMode = onSelectXtreamLiveSyncMode,
        onSelectGuideSourcePolicy = onSelectGuideSourcePolicy,
        onSelectChannelLogoSourcePolicy = onSelectChannelLogoSourcePolicy,
        username = username,
        onUsernameChange = onUsernameChange,
        password = password,
        onPasswordChange = onPasswordChange,
        stalkerAuthMode = stalkerAuthMode,
        onStalkerAuthModeChange = onStalkerAuthModeChange,
        stalkerMacAddress = stalkerMacAddress,
        stalkerDeviceProfile = stalkerDeviceProfile,
        onStalkerDeviceProfileChange = onStalkerDeviceProfileChange,
        stalkerDeviceTimezone = stalkerDeviceTimezone,
        onStalkerDeviceTimezoneChange = onStalkerDeviceTimezoneChange,
        stalkerDeviceLocale = stalkerDeviceLocale,
        onStalkerDeviceLocaleChange = onStalkerDeviceLocaleChange,
        stalkerSerialNumber = stalkerSerialNumber,
        onStalkerSerialNumberChange = onStalkerSerialNumberChange,
        stalkerDeviceId = stalkerDeviceId,
        onStalkerDeviceIdChange = onStalkerDeviceIdChange,
        stalkerDeviceId2 = stalkerDeviceId2,
        onStalkerDeviceId2Change = onStalkerDeviceId2Change,
        stalkerSignature = stalkerSignature,
        onStalkerSignatureChange = onStalkerSignatureChange,
        stalkerHwVersion = stalkerHwVersion,
        onStalkerHwVersionChange = onStalkerHwVersionChange,
        stalkerApiUserAgent = stalkerApiUserAgent,
        onStalkerApiUserAgentChange = onStalkerApiUserAgentChange,
        stalkerPlayerUserAgent = stalkerPlayerUserAgent,
        onStalkerPlayerUserAgentChange = onStalkerPlayerUserAgentChange,
        stalkerPlayerHeaders = stalkerPlayerHeaders,
        onStalkerPlayerHeadersChange = onStalkerPlayerHeadersChange,
        stalkerXUserAgentLink = stalkerXUserAgentLink,
        onStalkerXUserAgentLinkChange = onStalkerXUserAgentLinkChange,
        stalkerProxyEnabled = stalkerProxyEnabled,
        onStalkerProxyEnabledChange = onStalkerProxyEnabledChange,
        stalkerProxyHost = stalkerProxyHost,
        onStalkerProxyHostChange = onStalkerProxyHostChange,
        stalkerProxyPort = stalkerProxyPort,
        onStalkerProxyPortChange = onStalkerProxyPortChange,
        stalkerRequestRules = stalkerRequestRules,
        onAddStalkerRequestRule = onAddStalkerRequestRule,
        onUpdateStalkerRequestRule = onUpdateStalkerRequestRule,
        onRemoveStalkerRequestRule = onRemoveStalkerRequestRule
    )

    TvSurface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        border = Border(
            border = BorderStroke(1.dp, SurfaceHighlight),
            shape = RoundedCornerShape(20.dp)
        ),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated.copy(alpha = 0.95f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .imePadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProviderTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.setup_name_hint)
            )
            if (!uiState.isEditing) {
                PhonePairingCard(
                    pairingState = pairingState,
                    onStart = onStartPhonePairing,
                    onStop = onStopPhonePairing
                )
            }
            HorizontalDivider(color = SurfaceHighlight.copy(alpha = 0.6f))

            when (sourceType) {
                SourceType.XTREAM -> XtreamProviderForm(
                    uiState = uiState,
                    isTelevisionDevice = isTelevisionDevice,
                    serverUrl = serverUrl,
                    onServerUrlChange = onServerUrlChange,
                    username = username,
                    onUsernameChange = onUsernameChange,
                    password = password,
                    onPasswordChange = onPasswordChange,
                    options = advancedOptions,
                    onLogin = onLoginXtream
                )
                SourceType.STALKER -> StalkerProviderForm(
                    uiState = uiState,
                    isTelevisionDevice = isTelevisionDevice,
                    serverUrl = serverUrl,
                    onServerUrlChange = onServerUrlChange,
                    stalkerMacAddress = stalkerMacAddress,
                    onStalkerMacAddressChange = onStalkerMacAddressChange,
                    stalkerAuthMode = stalkerAuthMode,
                    options = advancedOptions,
                    onLogin = onLoginStalker,
                    onRepair = onRepairStalker,
                    onSelectProtocol = onSelectStalkerProtocolPreference,
                    onSelectProfile = onSelectStalkerProfile
                )
                SourceType.M3U_URL -> M3uProviderForm(
                    uiState = uiState,
                    m3uUrl = m3uUrl,
                    onM3uUrlChange = onM3uUrlChange,
                    fileImportError = fileImportError,
                    isFile = false,
                    onFilePick = onFilePick,
                    options = advancedOptions,
                    onAdd = onAddM3u
                )
                SourceType.M3U_FILE -> M3uProviderForm(
                    uiState = uiState,
                    m3uUrl = m3uUrl,
                    onM3uUrlChange = onM3uUrlChange,
                    fileImportError = fileImportError,
                    isFile = true,
                    onFilePick = onFilePick,
                    options = advancedOptions,
                    onAdd = onAddM3u
                )
                SourceType.JELLYFIN -> JellyfinProviderSetupForm(
                    uiState = uiState,
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
                    onLogin = onLoginJellyfin
                )
            }
        }
    }
}
