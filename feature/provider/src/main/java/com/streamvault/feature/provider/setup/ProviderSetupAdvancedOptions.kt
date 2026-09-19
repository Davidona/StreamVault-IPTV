package com.streamvault.feature.provider.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.Text
import com.streamvault.feature.provider.R
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.theme.*
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerCatalogMode

@Composable
internal fun AdvancedProviderOptionsSection(options: ProviderAdvancedOptions) {
    AdvancedProviderOptionsSection(
        sourceType = options.sourceType,
        uiState = options.uiState,
        httpUserAgent = options.httpUserAgent,
        onHttpUserAgentChange = options.onHttpUserAgentChange,
        httpHeaders = options.httpHeaders,
        onHttpHeadersChange = options.onHttpHeadersChange,
        onToggleM3uVodClassification = options.onToggleM3uVodClassification,
        onSelectEpgSyncMode = options.onSelectEpgSyncMode,
        onSelectStalkerCatalogMode = options.onSelectStalkerCatalogMode,
        onSelectXtreamLiveSyncMode = options.onSelectXtreamLiveSyncMode,
        onSelectGuideSourcePolicy = options.onSelectGuideSourcePolicy,
        onSelectChannelLogoSourcePolicy = options.onSelectChannelLogoSourcePolicy,
        username = options.username,
        onUsernameChange = options.onUsernameChange,
        password = options.password,
        onPasswordChange = options.onPasswordChange,
        stalkerAuthMode = options.stalkerAuthMode,
        onStalkerAuthModeChange = options.onStalkerAuthModeChange,
        stalkerMacAddress = options.stalkerMacAddress,
        stalkerDeviceProfile = options.stalkerDeviceProfile,
        onStalkerDeviceProfileChange = options.onStalkerDeviceProfileChange,
        stalkerDeviceTimezone = options.stalkerDeviceTimezone,
        onStalkerDeviceTimezoneChange = options.onStalkerDeviceTimezoneChange,
        stalkerDeviceLocale = options.stalkerDeviceLocale,
        onStalkerDeviceLocaleChange = options.onStalkerDeviceLocaleChange,
        stalkerSerialNumber = options.stalkerSerialNumber,
        onStalkerSerialNumberChange = options.onStalkerSerialNumberChange,
        stalkerDeviceId = options.stalkerDeviceId,
        onStalkerDeviceIdChange = options.onStalkerDeviceIdChange,
        stalkerDeviceId2 = options.stalkerDeviceId2,
        onStalkerDeviceId2Change = options.onStalkerDeviceId2Change,
        stalkerSignature = options.stalkerSignature,
        onStalkerSignatureChange = options.onStalkerSignatureChange,
        stalkerHwVersion = options.stalkerHwVersion,
        onStalkerHwVersionChange = options.onStalkerHwVersionChange,
        stalkerApiUserAgent = options.stalkerApiUserAgent,
        onStalkerApiUserAgentChange = options.onStalkerApiUserAgentChange,
        stalkerPlayerUserAgent = options.stalkerPlayerUserAgent,
        onStalkerPlayerUserAgentChange = options.onStalkerPlayerUserAgentChange,
        stalkerPlayerHeaders = options.stalkerPlayerHeaders,
        onStalkerPlayerHeadersChange = options.onStalkerPlayerHeadersChange,
        stalkerXUserAgentLink = options.stalkerXUserAgentLink,
        onStalkerXUserAgentLinkChange = options.onStalkerXUserAgentLinkChange,
        stalkerProxyEnabled = options.stalkerProxyEnabled,
        onStalkerProxyEnabledChange = options.onStalkerProxyEnabledChange,
        stalkerProxyHost = options.stalkerProxyHost,
        onStalkerProxyHostChange = options.onStalkerProxyHostChange,
        stalkerProxyPort = options.stalkerProxyPort,
        onStalkerProxyPortChange = options.onStalkerProxyPortChange,
        stalkerRequestRules = options.stalkerRequestRules,
        onAddStalkerRequestRule = options.onAddStalkerRequestRule,
        onUpdateStalkerRequestRule = options.onUpdateStalkerRequestRule,
        onRemoveStalkerRequestRule = options.onRemoveStalkerRequestRule
    )
}

@Composable
internal fun AdvancedProviderOptionsSection(
    sourceType: SourceType,
    uiState: ProviderSetupState,
    httpUserAgent: String,
    onHttpUserAgentChange: (String) -> Unit,
    httpHeaders: String,
    onHttpHeadersChange: (String) -> Unit,
    onToggleM3uVodClassification: () -> Unit,
    onSelectEpgSyncMode: (ProviderEpgSyncMode) -> Unit,
    onSelectStalkerCatalogMode: (StalkerCatalogMode) -> Unit,
    onSelectXtreamLiveSyncMode: (ProviderXtreamLiveSyncMode) -> Unit,
    onSelectGuideSourcePolicy: (GuideSourcePolicy) -> Unit,
    onSelectChannelLogoSourcePolicy: (ChannelLogoSourcePolicy) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    stalkerAuthMode: StalkerAuthMode,
    onStalkerAuthModeChange: (StalkerAuthMode) -> Unit,
    stalkerMacAddress: String,
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
    stalkerHwVersion: String = "",
    onStalkerHwVersionChange: (String) -> Unit = {},
    stalkerApiUserAgent: String = "",
    onStalkerApiUserAgentChange: (String) -> Unit = {},
    stalkerPlayerUserAgent: String = "",
    onStalkerPlayerUserAgentChange: (String) -> Unit = {},
    stalkerPlayerHeaders: String = "",
    onStalkerPlayerHeadersChange: (String) -> Unit = {},
    stalkerXUserAgentLink: String = ProviderStalkerAdvancedOptions.LINK_ETHERNET,
    onStalkerXUserAgentLinkChange: (String) -> Unit = {},
    stalkerProxyEnabled: Boolean = false,
    onStalkerProxyEnabledChange: (Boolean) -> Unit = {},
    stalkerProxyHost: String = "",
    onStalkerProxyHostChange: (String) -> Unit = {},
    stalkerProxyPort: String = "",
    onStalkerProxyPortChange: (String) -> Unit = {},
    stalkerRequestRules: List<StalkerRequestRuleUiState> = emptyList(),
    onAddStalkerRequestRule: () -> Unit = {},
    onUpdateStalkerRequestRule: (Int, StalkerRequestRuleUiState) -> Unit = { _, _ -> },
    onRemoveStalkerRequestRule: (Int) -> Unit = {}
) {
    var showAdvancedOptions by rememberSaveable(sourceType) { mutableStateOf(false) }
    val defaultEpgSyncMode = when (sourceType) {
        SourceType.STALKER -> ProviderEpgSyncMode.BACKGROUND
        SourceType.XTREAM,
        SourceType.M3U_URL,
        SourceType.M3U_FILE,
        SourceType.JELLYFIN -> ProviderEpgSyncMode.UPFRONT
    }

    LaunchedEffect(uiState.isEditing, uiState.epgSyncMode, uiState.stalkerCatalogMode, uiState.xtreamLiveSyncMode, uiState.guideSourcePolicy, uiState.channelLogoSourcePolicy, sourceType) {
        val hasNonDefaultSelection = ((sourceType == SourceType.XTREAM || sourceType == SourceType.STALKER) && uiState.epgSyncMode != defaultEpgSyncMode) ||
            (sourceType == SourceType.STALKER && uiState.stalkerCatalogMode != StalkerCatalogMode.ON_DEMAND) ||
            (sourceType == SourceType.XTREAM && uiState.xtreamLiveSyncMode != ProviderXtreamLiveSyncMode.AUTO) ||
            (supportsGuideAndLogoPolicy(sourceType) && uiState.guideSourcePolicy != GuideSourcePolicy.AUTO) ||
            (supportsGuideAndLogoPolicy(sourceType) && uiState.channelLogoSourcePolicy != ChannelLogoSourcePolicy.SUPPLIER_PREFERRED) ||
            ((sourceType == SourceType.M3U_URL || sourceType == SourceType.M3U_FILE) && !uiState.m3uVodClassificationEnabled) ||
            ((sourceType == SourceType.XTREAM || sourceType == SourceType.M3U_URL || sourceType == SourceType.M3U_FILE) &&
                httpUserAgent.isNotBlank()) ||
            ((sourceType == SourceType.XTREAM || sourceType == SourceType.STALKER || sourceType == SourceType.M3U_URL || sourceType == SourceType.M3U_FILE) &&
                httpHeaders.isNotBlank()) ||
            (sourceType == SourceType.STALKER && (
                stalkerAuthMode != StalkerAuthMode.AUTO ||
                    username.isNotBlank() ||
                    password.isNotBlank() ||
                    stalkerMacAddress.isBlank() ||
                    stalkerDeviceProfile.isNotBlank() ||
                    stalkerDeviceTimezone.isNotBlank() ||
                    stalkerDeviceLocale.isNotBlank() ||
                    stalkerSerialNumber.isNotBlank() ||
                    stalkerDeviceId.isNotBlank() ||
                    stalkerDeviceId2.isNotBlank() ||
                    stalkerSignature.isNotBlank() ||
                    stalkerHwVersion.isNotBlank() ||
                    stalkerApiUserAgent.isNotBlank() ||
                    stalkerPlayerUserAgent.isNotBlank() ||
                    stalkerPlayerHeaders.isNotBlank() ||
                    stalkerXUserAgentLink != ProviderStalkerAdvancedOptions.LINK_ETHERNET ||
                    stalkerProxyEnabled ||
                    stalkerProxyHost.isNotBlank() ||
                    stalkerProxyPort.isNotBlank() ||
                    stalkerRequestRules.isNotEmpty()
                ))
        if (uiState.isEditing && hasNonDefaultSelection) {
            showAdvancedOptions = true
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TvSurface(
            onClick = { showAdvancedOptions = !showAdvancedOptions },
            modifier = Modifier
                .fillMaxWidth()
                .mouseClickable(onClick = { showAdvancedOptions = !showAdvancedOptions }),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (showAdvancedOptions) Primary.copy(alpha = 0.12f) else Surface,
                focusedContainerColor = Primary.copy(alpha = 0.24f)
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    BorderStroke(
                        1.dp,
                        if (showAdvancedOptions) Primary.copy(alpha = 0.45f) else SurfaceHighlight
                    )
                ),
                focusedBorder = Border(BorderStroke(3.dp, PrimaryLight))
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.setup_advanced_options_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.setup_advanced_options_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
                Text(
                    text = if (showAdvancedOptions) {
                        androidx.compose.ui.res.stringResource(R.string.setup_advanced_options_hide)
                    } else {
                        androidx.compose.ui.res.stringResource(R.string.setup_advanced_options_show)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = PrimaryLight
                )
            }
        }

        AnimatedVisibility(visible = showAdvancedOptions) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sourceType == SourceType.M3U_URL || sourceType == SourceType.M3U_FILE) {
                    TvSurface(
                        onClick = onToggleM3uVodClassification,
                        modifier = Modifier
                            .fillMaxWidth()
                            .mouseClickable(onClick = onToggleM3uVodClassification),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (uiState.m3uVodClassificationEnabled) Primary.copy(alpha = 0.1f) else Surface,
                            focusedContainerColor = Primary.copy(alpha = 0.22f)
                        ),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(
                                BorderStroke(
                                    1.dp,
                                    if (uiState.m3uVodClassificationEnabled) Primary.copy(alpha = 0.4f) else SurfaceHighlight
                                )
                            ),
                            focusedBorder = Border(BorderStroke(3.dp, PrimaryLight))
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = androidx.compose.ui.res.stringResource(R.string.setup_m3u_vod_classification_label),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = androidx.compose.ui.res.stringResource(R.string.setup_m3u_vod_classification_helper),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceDim
                                )
                            }
                            Switch(
                                checked = uiState.m3uVodClassificationEnabled,
                                onCheckedChange = { onToggleM3uVodClassification() }
                            )
                        }
                    }
                }

                if (sourceType == SourceType.XTREAM || sourceType == SourceType.M3U_URL || sourceType == SourceType.M3U_FILE) {
                    ProviderTextField(
                        value = httpUserAgent,
                        onValueChange = onHttpUserAgentChange,
                        placeholder = "User-Agent override (optional)",
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next
                        )
                    )
                }

                if (sourceType == SourceType.XTREAM || sourceType == SourceType.STALKER || sourceType == SourceType.M3U_URL || sourceType == SourceType.M3U_FILE) {
                    ProviderTextField(
                        value = httpHeaders,
                        onValueChange = onHttpHeadersChange,
                        placeholder = if (sourceType == SourceType.STALKER) {
                            "Headers (API, optional, Header: Value | HeaderToRemove:)"
                        } else {
                            "Custom headers (optional, Header: Value | Header2: Value)"
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next
                        )
                    )
                }

                if (supportsGuideAndLogoPolicy(sourceType)) {
                    if (sourceType == SourceType.XTREAM) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Surface, RoundedCornerShape(12.dp))
                                .border(1.dp, SurfaceHighlight, RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.setup_xtream_live_sync_mode_label),
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = androidx.compose.ui.res.stringResource(R.string.setup_xtream_live_sync_mode_helper),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                            ProviderXtreamLiveSyncMode.entries.forEach { mode ->
                                XtreamLiveSyncModeOptionRow(
                                    mode = mode,
                                    selected = uiState.xtreamLiveSyncMode == mode,
                                    onSelect = { onSelectXtreamLiveSyncMode(mode) }
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface, RoundedCornerShape(12.dp))
                            .border(1.dp, SurfaceHighlight, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Guide source",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Choose whether this provider uses its supplier guide, your assigned external XMLTV sources, or no guide at all.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                        GuideSourcePolicy.entries.forEach { policy ->
                            GuideSourcePolicyOptionRow(
                                policy = policy,
                                selected = uiState.guideSourcePolicy == policy,
                                onSelect = { onSelectGuideSourcePolicy(policy) }
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface, RoundedCornerShape(12.dp))
                            .border(1.dp, SurfaceHighlight, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Channel logos",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Pick whether channel logos come from the supplier, the matched EPG channel icon, or a strict single source.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                        ChannelLogoSourcePolicy.entries.forEach { policy ->
                            ChannelLogoSourcePolicyOptionRow(
                                policy = policy,
                                selected = uiState.channelLogoSourcePolicy == policy,
                                onSelect = { onSelectChannelLogoSourcePolicy(policy) }
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface, RoundedCornerShape(12.dp))
                            .border(1.dp, SurfaceHighlight, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.setup_epg_sync_mode_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = androidx.compose.ui.res.stringResource(
                                if (sourceType == SourceType.STALKER) {
                                    R.string.setup_stalker_epg_sync_mode_helper
                                } else {
                                    R.string.setup_epg_sync_mode_helper
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                        ProviderEpgSyncMode.entries.forEach { mode ->
                            EpgSyncModeOptionRow(
                                mode = mode,
                                sourceType = sourceType,
                                selected = uiState.epgSyncMode == mode,
                                onSelect = { onSelectEpgSyncMode(mode) }
                            )
                        }
                    }
                }

                if (sourceType == SourceType.STALKER) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface, RoundedCornerShape(12.dp))
                            .border(1.dp, SurfaceHighlight, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Movie and series catalog",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "This is separate from EPG loading. On demand makes the provider ready after categories load; background indexing downloads the complete catalog later.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                        PolicyOptionRow(
                            title = "On demand (recommended)",
                            description = "Load visible shelves and opened categories as needed. Existing cached items are kept.",
                            selected = uiState.stalkerCatalogMode == StalkerCatalogMode.ON_DEMAND,
                            onSelect = { onSelectStalkerCatalogMode(StalkerCatalogMode.ON_DEMAND) }
                        )
                        PolicyOptionRow(
                            title = "Complete background index",
                            description = "Make the provider ready first, then maintain a complete searchable catalog in the background.",
                            selected = uiState.stalkerCatalogMode == StalkerCatalogMode.BACKGROUND_INDEX,
                            onSelect = { onSelectStalkerCatalogMode(StalkerCatalogMode.BACKGROUND_INDEX) }
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface, RoundedCornerShape(12.dp))
                            .border(1.dp, SurfaceHighlight, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Stalker auth mode",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Auto-detect is the default. Override it only when the portal needs credentials or a mixed MAG + account login flow.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                        StalkerAuthMode.entries.forEach { mode ->
                            StalkerAuthModeOptionRow(
                                mode = mode,
                                selected = stalkerAuthMode == mode,
                                onSelect = { onStalkerAuthModeChange(mode) }
                            )
                        }
                    }
                    if (stalkerAuthMode != StalkerAuthMode.MAC_ONLY) {
                        ProviderTextField(
                            value = username,
                            onValueChange = onUsernameChange,
                            placeholder = if (stalkerAuthMode == StalkerAuthMode.AUTO) {
                                "Portal username (optional)"
                            } else {
                                "Portal username"
                            },
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
                            placeholder = if (stalkerAuthMode == StalkerAuthMode.AUTO) {
                                "Portal password (optional)"
                            } else {
                                "Portal password"
                            },
                            isPassword = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Next
                            )
                        )
                    }
                    ProviderTextField(
                        value = stalkerDeviceProfile,
                        onValueChange = onStalkerDeviceProfileChange,
                        placeholder = "MAG Type (optional)"
                    )
                    ProviderTextField(
                        value = stalkerDeviceTimezone,
                        onValueChange = onStalkerDeviceTimezoneChange,
                        placeholder = "Timezone (optional)"
                    )
                    ProviderTextField(
                        value = stalkerDeviceLocale,
                        onValueChange = onStalkerDeviceLocaleChange,
                        placeholder = "Locale (optional)"
                    )
                    ProviderTextField(
                        value = stalkerSerialNumber,
                        onValueChange = onStalkerSerialNumberChange,
                        placeholder = "Serial number (optional)"
                    )
                    ProviderTextField(
                        value = stalkerDeviceId,
                        onValueChange = onStalkerDeviceIdChange,
                        placeholder = "Device ID (optional)"
                    )
                    ProviderTextField(
                        value = stalkerDeviceId2,
                        onValueChange = onStalkerDeviceId2Change,
                        placeholder = "Device ID2 (optional)"
                    )
                    ProviderTextField(
                        value = stalkerSignature,
                        onValueChange = onStalkerSignatureChange,
                        placeholder = "Signature (optional)"
                    )
                    HorizontalDivider(color = SurfaceHighlight.copy(alpha = 0.45f))
                    Text(
                        text = "Stalker compatibility",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "Portal-specific overrides for stubborn MAG/Stalker servers. Leave fields empty unless a server needs them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                    ProviderTextField(
                        value = stalkerHwVersion,
                        onValueChange = onStalkerHwVersionChange,
                        placeholder = "hw_version override (optional)"
                    )
                    ProviderTextField(
                        value = stalkerApiUserAgent,
                        onValueChange = onStalkerApiUserAgentChange,
                        placeholder = "User-Agent (API, optional)"
                    )
                    ProviderTextField(
                        value = stalkerPlayerUserAgent,
                        onValueChange = onStalkerPlayerUserAgentChange,
                        placeholder = "User-Agent (Player, optional)"
                    )
                    ProviderTextField(
                        value = stalkerPlayerHeaders,
                        onValueChange = onStalkerPlayerHeadersChange,
                        placeholder = "Headers (Player, optional, Header: Value | HeaderToRemove:)"
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "X-User-Agent Link",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StalkerLinkOptionButton(
                                text = ProviderStalkerAdvancedOptions.LINK_ETHERNET,
                                selected = stalkerXUserAgentLink == ProviderStalkerAdvancedOptions.LINK_ETHERNET,
                                onClick = { onStalkerXUserAgentLinkChange(ProviderStalkerAdvancedOptions.LINK_ETHERNET) }
                            )
                            StalkerLinkOptionButton(
                                text = ProviderStalkerAdvancedOptions.LINK_WIFI,
                                selected = stalkerXUserAgentLink == ProviderStalkerAdvancedOptions.LINK_WIFI,
                                onClick = { onStalkerXUserAgentLinkChange(ProviderStalkerAdvancedOptions.LINK_WIFI) }
                            )
                        }
                    }
                    TvClickableSurface(
                        onClick = { onStalkerProxyEnabledChange(!stalkerProxyEnabled) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Use HTTP proxy" },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (stalkerProxyEnabled) Primary.copy(alpha = 0.1f) else Surface,
                            focusedContainerColor = Primary.copy(alpha = 0.22f)
                        ),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(
                                BorderStroke(
                                    1.dp,
                                    if (stalkerProxyEnabled) Primary.copy(alpha = 0.4f) else SurfaceHighlight
                                )
                            ),
                            focusedBorder = Border(BorderStroke(3.dp, PrimaryLight))
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Use HTTP proxy",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Applies to Stalker API and playback only.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceDim
                                )
                            }
                            Switch(
                                checked = stalkerProxyEnabled,
                                onCheckedChange = onStalkerProxyEnabledChange
                            )
                        }
                    }
                    AnimatedVisibility(stalkerProxyEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProviderTextField(
                                value = stalkerProxyHost,
                                onValueChange = onStalkerProxyHostChange,
                                placeholder = "Proxy host"
                            )
                            ProviderTextField(
                                value = stalkerProxyPort,
                                onValueChange = onStalkerProxyPortChange,
                                placeholder = "Proxy port",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    }
                    StalkerRequestRulesEditor(
                        rules = stalkerRequestRules,
                        onAddRule = onAddStalkerRequestRule,
                        onUpdateRule = onUpdateStalkerRequestRule,
                        onRemoveRule = onRemoveStalkerRequestRule
                    )
                }
            }
        }
    }
}
