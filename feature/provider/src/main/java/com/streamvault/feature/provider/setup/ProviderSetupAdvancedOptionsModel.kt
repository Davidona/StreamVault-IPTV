package com.streamvault.feature.provider.setup

import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerCatalogMode

/** UI values and callbacks shared by the provider-specific advanced sections. */
internal data class ProviderAdvancedOptions(
    val sourceType: SourceType,
    val uiState: ProviderSetupState,
    val httpUserAgent: String,
    val onHttpUserAgentChange: (String) -> Unit,
    val httpHeaders: String,
    val onHttpHeadersChange: (String) -> Unit,
    val onToggleM3uVodClassification: () -> Unit,
    val onSelectEpgSyncMode: (ProviderEpgSyncMode) -> Unit,
    val onSelectStalkerCatalogMode: (StalkerCatalogMode) -> Unit,
    val onSelectXtreamLiveSyncMode: (ProviderXtreamLiveSyncMode) -> Unit,
    val onSelectGuideSourcePolicy: (GuideSourcePolicy) -> Unit,
    val onSelectChannelLogoSourcePolicy: (ChannelLogoSourcePolicy) -> Unit,
    val username: String,
    val onUsernameChange: (String) -> Unit,
    val password: String,
    val onPasswordChange: (String) -> Unit,
    val stalkerAuthMode: StalkerAuthMode,
    val onStalkerAuthModeChange: (StalkerAuthMode) -> Unit,
    val stalkerMacAddress: String,
    val stalkerDeviceProfile: String,
    val onStalkerDeviceProfileChange: (String) -> Unit,
    val stalkerDeviceTimezone: String,
    val onStalkerDeviceTimezoneChange: (String) -> Unit,
    val stalkerDeviceLocale: String,
    val onStalkerDeviceLocaleChange: (String) -> Unit,
    val stalkerSerialNumber: String,
    val onStalkerSerialNumberChange: (String) -> Unit,
    val stalkerDeviceId: String,
    val onStalkerDeviceIdChange: (String) -> Unit,
    val stalkerDeviceId2: String,
    val onStalkerDeviceId2Change: (String) -> Unit,
    val stalkerSignature: String,
    val onStalkerSignatureChange: (String) -> Unit,
    val stalkerHwVersion: String,
    val onStalkerHwVersionChange: (String) -> Unit,
    val stalkerApiUserAgent: String,
    val onStalkerApiUserAgentChange: (String) -> Unit,
    val stalkerPlayerUserAgent: String,
    val onStalkerPlayerUserAgentChange: (String) -> Unit,
    val stalkerPlayerHeaders: String,
    val onStalkerPlayerHeadersChange: (String) -> Unit,
    val stalkerXUserAgentLink: String = ProviderStalkerAdvancedOptions.LINK_ETHERNET,
    val onStalkerXUserAgentLinkChange: (String) -> Unit = {},
    val stalkerProxyEnabled: Boolean = false,
    val onStalkerProxyEnabledChange: (Boolean) -> Unit = {},
    val stalkerProxyHost: String = "",
    val onStalkerProxyHostChange: (String) -> Unit = {},
    val stalkerProxyPort: String = "",
    val onStalkerProxyPortChange: (String) -> Unit = {},
    val stalkerRequestRules: List<StalkerRequestRuleUiState> = emptyList(),
    val onAddStalkerRequestRule: () -> Unit = {},
    val onUpdateStalkerRequestRule: (Int, StalkerRequestRuleUiState) -> Unit = { _, _ -> },
    val onRemoveStalkerRequestRule: (Int) -> Unit = {}
)
