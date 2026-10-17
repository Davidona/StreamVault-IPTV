package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material3.RadioButton
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.design.AppColors
import com.streamvault.domain.sports.provider.SportsCapability
import com.streamvault.domain.sports.provider.SportsEventSourceMode
import com.streamvault.domain.sports.provider.SportsEventSourcePreference
import com.streamvault.domain.sports.provider.SportsProviderAuth
import com.streamvault.domain.sports.provider.SportsProviderConfig
import com.streamvault.domain.sports.provider.SportsProviderDescriptor
import com.streamvault.domain.sports.provider.SportsProviderUsageSnapshot
import com.streamvault.domain.sports.provider.ProviderAttributionFormat
import com.streamvault.domain.sports.provider.CUSTOM_SPORTS_PROVIDER_TYPE_ID
import com.streamvault.domain.sports.provider.SportsScoreSourceMode
import com.streamvault.domain.sports.provider.SportsScoreSourcePreference
import com.streamvault.domain.sports.provider.SportsStandingsSourceMode
import com.streamvault.domain.sports.provider.SportsStandingsSourcePreference
import com.streamvault.domain.sports.provider.SportsMetadataSourceMode
import com.streamvault.domain.sports.provider.SportsMetadataSourcePreference
import com.streamvault.feature.settings.R
import java.net.URI
import org.json.JSONObject

@Composable
internal fun SportsDataProviderSettingsPage(
    viewModel: SportsProviderSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsState()
    val colors = SettingsDesignTokens.colors(AppColors.current)
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedDescriptor by remember { mutableStateOf<SportsProviderDescriptor?>(null) }
    var editingConfig by remember { mutableStateOf<SportsProviderConfig?>(null) }
    var removingConfig by remember { mutableStateOf<SportsProviderConfig?>(null) }

    LaunchedEffect(uiState.lastSavedInstanceId) {
        val savedId = uiState.lastSavedInstanceId
        if (savedId != null && (editingConfig == null || editingConfig?.instanceId == savedId)) {
            selectedDescriptor = null
            editingConfig = null
        }
    }
    LaunchedEffect(uiState.lastRemovedInstanceId) {
        if (uiState.lastRemovedInstanceId != null) removingConfig = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space12)) {
        Text(
            text = stringResource(R.string.settings_sports_event_source),
            color = colors.primaryText,
            style = MaterialTheme.typography.titleMedium,
        )
        SportsEventSourceOption(
            label = stringResource(R.string.settings_sports_event_source_automatic),
            selected = uiState.eventSourcePreference.mode == SportsEventSourceMode.AUTOMATIC,
            onClick = { viewModel.setEventSourcePreference(SportsEventSourcePreference.AUTOMATIC) },
        )
        SportsEventSourceOption(
            label = stringResource(R.string.settings_sports_event_source_epg),
            selected = uiState.eventSourcePreference.mode == SportsEventSourceMode.EPG,
            onClick = { viewModel.setEventSourcePreference(SportsEventSourcePreference.EPG) },
        )
        val eventProviders = uiState.configurations.filter { config ->
            config.enabled && SportsCapability.EVENTS in viewModel.capabilitiesFor(config)
        }.sortedWith(compareBy<SportsProviderConfig> { it.priority }.thenBy { it.instanceId })
        eventProviders.forEach { config ->
            val ordered = uiState.configurations.filter { it.typeId == config.typeId }
                .sortedBy(SportsProviderConfig::instanceId)
            val accountLabel = context.getString(
                R.string.settings_sports_provider_account_index,
                viewModel.displayNameFor(config) ?: context.localizedProviderName(config.typeId),
                ordered.indexOfFirst { it.instanceId == config.instanceId } + 1,
            )
            SportsEventSourceOption(
                label = accountLabel,
                selected = uiState.eventSourcePreference.providerInstanceId == config.instanceId,
                onClick = { viewModel.setEventSourcePreference(SportsEventSourcePreference.provider(config.instanceId)) },
            )
        }
        Text(
            text = stringResource(R.string.settings_sports_score_source),
            color = colors.primaryText,
            style = MaterialTheme.typography.titleMedium,
        )
        SportsEventSourceOption(
            label = stringResource(R.string.settings_sports_event_source_automatic),
            selected = uiState.scoreSourcePreference.mode == SportsScoreSourceMode.AUTOMATIC,
            onClick = { viewModel.setScoreSourcePreference(SportsScoreSourcePreference.AUTOMATIC) },
        )
        SportsEventSourceOption(
            label = stringResource(R.string.settings_sports_score_source_off),
            selected = uiState.scoreSourcePreference.mode == SportsScoreSourceMode.OFF,
            onClick = { viewModel.setScoreSourcePreference(SportsScoreSourcePreference.OFF) },
        )
        val scoreProviders = uiState.configurations
            .filter { config ->
                config.enabled && SportsCapability.LIVE_SCORES in viewModel.capabilitiesFor(config) &&
                    SportsCapability.EVENTS in viewModel.capabilitiesFor(config)
            }
            .sortedWith(compareBy<SportsProviderConfig> { it.priority }.thenBy { it.instanceId })
        scoreProviders.forEach { config ->
            val ordered = uiState.configurations.filter { it.typeId == config.typeId }
                .sortedBy(SportsProviderConfig::instanceId)
            val accountLabel = context.getString(
                R.string.settings_sports_provider_account_index,
                viewModel.displayNameFor(config) ?: context.localizedProviderName(config.typeId),
                ordered.indexOfFirst { it.instanceId == config.instanceId } + 1,
            )
            SportsEventSourceOption(
                label = accountLabel,
                selected = uiState.scoreSourcePreference.providerInstanceId == config.instanceId,
                onClick = { viewModel.setScoreSourcePreference(SportsScoreSourcePreference.provider(config.instanceId)) },
            )
        }
        Text(
            text = stringResource(R.string.settings_sports_standings_source),
            color = colors.primaryText,
            style = MaterialTheme.typography.titleMedium,
        )
        SportsEventSourceOption(
            label = stringResource(R.string.settings_sports_event_source_automatic),
            selected = uiState.standingsSourcePreference.mode == SportsStandingsSourceMode.AUTOMATIC,
            onClick = {
                viewModel.setStandingsSourcePreference(SportsStandingsSourcePreference.AUTOMATIC)
            },
        )
        SportsEventSourceOption(
            label = stringResource(R.string.settings_sports_score_source_off),
            selected = uiState.standingsSourcePreference.mode == SportsStandingsSourceMode.OFF,
            onClick = { viewModel.setStandingsSourcePreference(SportsStandingsSourcePreference.OFF) },
        )
        val standingsProviders = uiState.configurations.filter { config ->
            config.enabled && SportsCapability.STANDINGS in viewModel.capabilitiesFor(config)
        }.sortedWith(compareBy<SportsProviderConfig> { it.priority }.thenBy { it.instanceId })
        standingsProviders.forEach { config ->
            val ordered = uiState.configurations.filter { it.typeId == config.typeId }
                .sortedBy(SportsProviderConfig::instanceId)
            val accountLabel = context.getString(
                R.string.settings_sports_provider_account_index,
                viewModel.displayNameFor(config) ?: context.localizedProviderName(config.typeId),
                ordered.indexOfFirst { it.instanceId == config.instanceId } + 1,
            )
            SportsEventSourceOption(
                label = accountLabel,
                selected = uiState.standingsSourcePreference.providerInstanceId == config.instanceId,
                onClick = {
                    viewModel.setStandingsSourcePreference(SportsStandingsSourcePreference.provider(config.instanceId))
                },
            )
        }
        Text(
            text = stringResource(R.string.settings_sports_metadata_source),
            color = colors.primaryText,
            style = MaterialTheme.typography.titleMedium,
        )
        SportsEventSourceOption(
            label = stringResource(R.string.settings_sports_event_source_automatic),
            selected = uiState.metadataSourcePreference.mode == SportsMetadataSourceMode.AUTOMATIC,
            onClick = { viewModel.setMetadataSourcePreference(SportsMetadataSourcePreference.AUTOMATIC) },
        )
        SportsEventSourceOption(
            label = stringResource(R.string.settings_sports_score_source_off),
            selected = uiState.metadataSourcePreference.mode == SportsMetadataSourceMode.OFF,
            onClick = { viewModel.setMetadataSourcePreference(SportsMetadataSourcePreference.OFF) },
        )
        val metadataProviders = uiState.configurations.filter { config ->
            config.enabled && viewModel.capabilitiesFor(config).any {
                it == SportsCapability.TEAM_METADATA || it == SportsCapability.LEAGUE_METADATA
            }
        }.sortedWith(compareBy<SportsProviderConfig> { it.priority }.thenBy { it.instanceId })
        metadataProviders.forEach { config ->
            val ordered = uiState.configurations.filter { it.typeId == config.typeId }
                .sortedBy(SportsProviderConfig::instanceId)
            val accountLabel = context.getString(
                R.string.settings_sports_provider_account_index,
                viewModel.displayNameFor(config) ?: context.localizedProviderName(config.typeId),
                ordered.indexOfFirst { it.instanceId == config.instanceId } + 1,
            )
            SportsEventSourceOption(
                label = accountLabel,
                selected = uiState.metadataSourcePreference.providerInstanceId == config.instanceId,
                onClick = {
                    viewModel.setMetadataSourcePreference(SportsMetadataSourcePreference.provider(config.instanceId))
                },
            )
        }
        if (uiState.operationFailed) {
            Text(
                text = stringResource(R.string.settings_sports_provider_action_failed),
                color = colors.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (uiState.isLoading && uiState.configurations.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_sports_provider_loading),
                color = colors.secondaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (uiState.loadFailed) {
            Text(
                text = stringResource(R.string.settings_sports_provider_action_failed),
                color = colors.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            CompactSettingsActionChip(
                label = stringResource(R.string.settings_sports_provider_retry),
                accent = colors.accent,
                onClick = viewModel::refresh,
            )
        } else {
            Text(
                text = stringResource(R.string.settings_provider_inventory_title),
                color = colors.primaryText,
                style = MaterialTheme.typography.titleMedium,
            )
            if (uiState.configurations.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_no_providers),
                    color = colors.secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val ordered = uiState.configurations.sortedWith(
                    compareBy<SportsProviderConfig> { it.typeId }.thenBy { it.instanceId },
                )
                ordered.forEach { config ->
                    val descriptor = uiState.descriptors.firstOrNull { it.typeId == config.typeId }
                    val accountNumber = ordered.filter { it.typeId == config.typeId }
                        .indexOfFirst { it.instanceId == config.instanceId } + 1
                    SportsProviderAccountCard(
                        config = config,
                        displayName = viewModel.displayNameFor(config),
                        capabilities = viewModel.capabilitiesFor(config),
                        descriptor = descriptor,
                        accountNumber = accountNumber,
                        usageSnapshot = uiState.usageSnapshots[config.instanceId],
                        state = uiState.connectionStates[config.instanceId],
                        busy = config.instanceId in uiState.busyInstances,
                        onEnabledChange = { viewModel.setEnabled(config, it) },
                        onTest = { viewModel.testConnection(config) },
                        onEdit = if (descriptor == null) null else {
                            {
                                editingConfig = config
                                selectedDescriptor = descriptor
                            }
                        },
                        onRemove = { removingConfig = config },
                    )
                }
            }

            Text(
                text = stringResource(R.string.settings_sports_provider_available),
                color = colors.primaryText,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = SettingsDesignTokens.space8),
            )
            if (uiState.descriptors.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_sports_provider_adapters_empty),
                    color = colors.secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                uiState.descriptors.forEach { descriptor ->
                    SportsProviderDescriptorCard(
                        descriptor = descriptor,
                        onAdd = {
                            editingConfig = null
                            selectedDescriptor = descriptor
                            viewModel.clearOperationFailure()
                        },
                    )
                }
            }
        }
    }

    val descriptor = selectedDescriptor
    if (descriptor != null) {
        key(descriptor.typeId, editingConfig?.instanceId) {
            SportsProviderConfigDialog(
                descriptor = descriptor,
                existing = editingConfig,
                hasExistingCredentials = editingConfig?.instanceId?.let(uiState.credentialedInstanceIds::contains) == true,
                isSaving = uiState.isSaving,
                validationFailed = uiState.validationFailed,
                operationFailed = uiState.operationFailed,
                draftConnectionState = uiState.draftConnectionState,
                onDismiss = {
                    selectedDescriptor = null
                    editingConfig = null
                    viewModel.clearOperationFailure()
                },
                onSave = { baseUrl, username, secret, eventLeagueIds, customDefinitionJson ->
                    viewModel.saveProvider(
                        descriptor,
                        editingConfig,
                        baseUrl,
                        username,
                        secret,
                        eventLeagueIds,
                        customDefinitionJson,
                    )
                },
                onTest = { baseUrl, username, secret, eventLeagueIds, customDefinitionJson ->
                    viewModel.testDraftConnection(
                        descriptor,
                        editingConfig,
                        baseUrl,
                        username,
                        secret,
                        eventLeagueIds,
                        customDefinitionJson,
                    )
                },
                onDraftChanged = viewModel::clearOperationFailure,
            )
        }
    }

    val pendingRemoval = removingConfig
    if (pendingRemoval != null) {
        val sortedInstances = uiState.configurations.sortedWith(
            compareBy<SportsProviderConfig> { it.typeId }.thenBy { it.instanceId },
        )
        val label = context.getString(
            R.string.settings_sports_provider_account_index,
            viewModel.displayNameFor(pendingRemoval) ?: context.localizedProviderName(pendingRemoval.typeId),
            sortedInstances.filter { it.typeId == pendingRemoval.typeId }
                .indexOfFirst { it.instanceId == pendingRemoval.instanceId } + 1,
        )
        PremiumDialog(
            title = stringResource(R.string.settings_sports_provider_remove_title),
            subtitle = stringResource(R.string.settings_sports_provider_remove_message, label),
            onDismissRequest = { if (pendingRemoval.instanceId !in uiState.busyInstances) removingConfig = null },
            content = {
                if (uiState.operationFailed) {
                    Text(
                        text = stringResource(R.string.settings_sports_provider_action_failed),
                        color = colors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            footer = {
                Row(horizontalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space12)) {
                    PremiumDialogFooterButton(
                        label = stringResource(R.string.settings_cancel),
                        onClick = { removingConfig = null },
                        enabled = pendingRemoval.instanceId !in uiState.busyInstances,
                    )
                    PremiumDialogFooterButton(
                        label = stringResource(R.string.settings_delete),
                        onClick = { viewModel.remove(pendingRemoval.instanceId) },
                        enabled = pendingRemoval.instanceId !in uiState.busyInstances,
                        destructive = true,
                    )
                }
            },
        )
    }
}

@Composable
private fun SportsEventSourceOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = SettingsDesignTokens.space4),
        horizontalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            color = colors.primaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SportsProviderDescriptorCard(
    descriptor: SportsProviderDescriptor,
    onAdd: () -> Unit,
) {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.groupSurface, RoundedCornerShape(SettingsDesignTokens.groupRadius))
            .padding(SettingsDesignTokens.space16),
        verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8),
    ) {
        Text(localizedProviderName(descriptor.typeId), color = colors.primaryText, style = MaterialTheme.typography.titleSmall)
        if (descriptor.isBeta) {
            Text(
                text = stringResource(R.string.settings_sports_provider_beta),
                color = colors.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = stringResource(R.string.settings_sports_provider_capabilities) + ": " +
                capabilitySummary(descriptor.capabilities),
            color = colors.secondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        CompactSettingsActionChip(
            label = stringResource(R.string.settings_add_provider),
            accent = colors.accent,
            onClick = onAdd,
        )
    }
}

@Composable
private fun SportsProviderAccountCard(
    config: SportsProviderConfig,
    displayName: String?,
    capabilities: Set<SportsCapability>,
    descriptor: SportsProviderDescriptor?,
    accountNumber: Int,
    usageSnapshot: SportsProviderUsageSnapshot?,
    state: SportsProviderConnectionState?,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onTest: () -> Unit,
    onEdit: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    val context = androidx.compose.ui.platform.LocalContext.current
    val accountLabel = context.getString(
        R.string.settings_sports_provider_account_index,
        displayName ?: context.localizedProviderName(config.typeId),
        accountNumber,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.groupSurface, RoundedCornerShape(SettingsDesignTokens.groupRadius))
            .padding(SettingsDesignTokens.space16),
        verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8),
    ) {
        Text(accountLabel, color = colors.primaryText, style = MaterialTheme.typography.titleSmall)
        if (descriptor == null) {
            Text(
                text = stringResource(R.string.settings_sports_provider_adapter_unavailable),
                color = colors.error,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            if (descriptor.isBeta) {
                Text(
                    text = stringResource(R.string.settings_sports_provider_beta),
                    color = colors.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = stringResource(R.string.settings_sports_provider_capabilities) + ": " +
                    capabilitySummary(capabilities),
                color = colors.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        usageSnapshot?.let { usage ->
            usage.planTier?.let { tier ->
                Text(
                    text = stringResource(R.string.settings_sports_provider_plan_tier, tier),
                    color = colors.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            usage.requestsRemaining?.let { remaining ->
                Text(
                    text = stringResource(R.string.settings_sports_provider_usage_remaining, remaining),
                    color = colors.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        SwitchSettingsRow(
            label = stringResource(if (config.enabled) R.string.settings_enabled else R.string.settings_disabled),
            value = "",
            checked = config.enabled,
            onCheckedChange = onEnabledChange,
            enabled = !busy,
        )
        state?.let { connectionState ->
            val status = when (connectionState) {
                SportsProviderConnectionState.TESTING -> R.string.settings_sports_provider_testing
                SportsProviderConnectionState.SUCCESS -> R.string.settings_sports_provider_connection_success
                SportsProviderConnectionState.FAILED -> R.string.settings_sports_provider_connection_failed
                SportsProviderConnectionState.UNSUPPORTED -> R.string.settings_sports_provider_test_unsupported
                SportsProviderConnectionState.ADAPTER_UNAVAILABLE -> R.string.settings_sports_provider_adapter_unavailable
            }
            Text(
                text = stringResource(status),
                color = if (connectionState == SportsProviderConnectionState.FAILED ||
                    connectionState == SportsProviderConnectionState.ADAPTER_UNAVAILABLE) colors.error else colors.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8)) {
            CompactSettingsActionChip(
                label = stringResource(R.string.settings_sports_provider_test),
                accent = colors.accent,
                enabled = !busy && descriptor != null,
                onClick = onTest,
            )
            if (onEdit != null) {
                CompactSettingsActionChip(
                    label = stringResource(R.string.settings_edit),
                    accent = colors.accent,
                    enabled = !busy,
                    onClick = onEdit,
                )
            }
            CompactSettingsActionChip(
                label = stringResource(R.string.settings_delete),
                accent = colors.error,
                enabled = !busy,
                onClick = onRemove,
            )
        }
    }
}

@Composable
private fun SportsProviderConfigDialog(
    descriptor: SportsProviderDescriptor,
    existing: SportsProviderConfig?,
    hasExistingCredentials: Boolean,
    isSaving: Boolean,
    validationFailed: Boolean,
    operationFailed: Boolean,
    draftConnectionState: SportsProviderConnectionState?,
    onDismiss: () -> Unit,
    onSave: (
        baseUrl: String,
        username: String,
        secret: String,
        eventLeagueIds: List<String>,
        customDefinitionJson: String?,
    ) -> Unit,
    onTest: (
        baseUrl: String,
        username: String,
        secret: String,
        eventLeagueIds: List<String>,
        customDefinitionJson: String?,
    ) -> Unit,
    onDraftChanged: () -> Unit,
) {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    val context = androidx.compose.ui.platform.LocalContext.current
    var baseUrl by remember(existing?.instanceId, descriptor.typeId) {
        mutableStateOf(existing?.baseUrl ?: descriptor.defaultBaseUrl.orEmpty())
    }
    var leagueIds by remember(existing?.instanceId, descriptor.typeId) {
        mutableStateOf(existing?.eventLeagueIds.orEmpty().joinToString(", "))
    }
    var username by remember(existing?.instanceId, descriptor.typeId) { mutableStateOf("") }
    var secret by remember(existing?.instanceId, descriptor.typeId) { mutableStateOf("") }
    var customDefinitionJson by remember(existing?.instanceId, descriptor.typeId) {
        mutableStateOf(existing?.customDefinitionJson.orEmpty())
    }
    val isCustomProvider = descriptor.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID
    val credentialsAreValid = if (isCustomProvider) {
        customCredentialsValid(customDefinitionJson, hasExistingCredentials, username, secret)
    } else {
        credentialsValid(descriptor.auth, hasExistingCredentials, username, secret)
    }
    val baseUrlIsValid = isValidOptionalHttpsUrl(baseUrl) && (!isCustomProvider || baseUrl.isNotBlank())
    val parsedLeagueIds = parseEventLeagueIds(leagueIds)
    val leagueIdsAreValid = parsedLeagueIds != null &&
        when (descriptor.typeId) {
            THESPORTSDB_TYPE_ID -> parsedLeagueIds.isNotEmpty() &&
                parsedLeagueIds.all { it.matches(Regex("\\d{1,12}")) }
            ODDSPAPI_TYPE_ID -> parsedLeagueIds.size <= 5 &&
                parsedLeagueIds.all { it.matches(Regex("[1-9]\\d{0,11}")) }
            OPENLIGADB_TYPE_ID -> parsedLeagueIds.size in 1..5 && parsedLeagueIds.all(::isOpenLigaDbScope)
            else -> true
        }
    val customDefinitionIsValid = !isCustomProvider || customDefinitionJson.isNotBlank()

    PremiumDialog(
        title = stringResource(if (existing == null) R.string.settings_add_provider else R.string.settings_edit),
        subtitle = localizedProviderName(descriptor.typeId),
        onDismissRequest = { if (!isSaving) onDismiss() },
        bodyHeightFraction = if (isCustomProvider) 0.86f else 0.68f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8)) {
                descriptor.compliance?.attribution?.let { attribution ->
                    if (attribution.format == ProviderAttributionFormat.ODBL && attribution.licenseUrl != null) {
                        OpenLigaDbAttribution(attribution, colors.secondaryText)
                    } else {
                        Text(
                            text = stringResource(
                                if (attribution.format == ProviderAttributionFormat.FOOTBALL_DATA) {
                                    R.string.settings_sports_provider_football_data_attribution
                                } else R.string.settings_sports_provider_attribution,
                                attribution.sourceName,
                            ),
                            color = colors.secondaryText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (descriptor.supportsCustomBaseUrl) {
                    Text(
                        text = stringResource(R.string.settings_sports_provider_base_url),
                        color = colors.secondaryText,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    EpgSourceTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it; onDraftChanged() },
                        placeholder = descriptor.defaultBaseUrl.orEmpty(),
                        keyboardType = KeyboardType.Uri,
                    )
                    if (!baseUrlIsValid) {
                        Text(
                            text = stringResource(R.string.settings_sports_provider_invalid_base_url),
                            color = colors.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (isCustomProvider) {
                    Text(
                        text = stringResource(R.string.settings_sports_custom_definition),
                        color = colors.secondaryText,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (customDefinitionJson.isBlank()) {
                        CompactSettingsActionChip(
                            label = stringResource(R.string.settings_sports_custom_definition_example_action),
                            accent = colors.accent,
                            enabled = !isSaving,
                            onClick = {
                                customDefinitionJson = customSportsProviderExample(
                                    context.getString(R.string.settings_sports_provider_custom_json),
                                )
                                onDraftChanged()
                            },
                        )
                    }
                    EpgSourceTextField(
                        value = customDefinitionJson,
                        onValueChange = { customDefinitionJson = it; onDraftChanged() },
                        placeholder = stringResource(R.string.settings_sports_custom_definition_placeholder),
                        modifier = Modifier.heightIn(min = 112.dp, max = 200.dp),
                        singleLine = false,
                        maxLines = 8,
                    )
                    Text(
                        text = stringResource(R.string.settings_sports_custom_definition_hint),
                        color = colors.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!customDefinitionIsValid) {
                        Text(
                            text = stringResource(R.string.settings_sports_provider_invalid_input),
                            color = colors.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (SportsCapability.EVENTS in descriptor.capabilities ||
                    SportsCapability.STANDINGS in descriptor.capabilities
                ) {
                    Text(
                        text = stringResource(if (descriptor.typeId == OPENLIGADB_TYPE_ID) R.string.settings_sports_provider_openligadb_scopes else R.string.settings_sports_provider_league_ids),
                        color = colors.secondaryText,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    EpgSourceTextField(
                        value = leagueIds,
                        onValueChange = { leagueIds = it; onDraftChanged() },
                        placeholder = stringResource(R.string.settings_sports_provider_league_ids),
                    )
                    Text(
                        text = stringResource(if (descriptor.typeId == OPENLIGADB_TYPE_ID) R.string.settings_sports_provider_openligadb_scope_hint else R.string.settings_sports_provider_league_ids_hint),
                        color = colors.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!leagueIdsAreValid) {
                        Text(
                            text = stringResource(R.string.settings_sports_provider_invalid_input),
                            color = colors.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                when (descriptor.auth) {
                    SportsProviderAuth.None -> if (isCustomProvider) {
                        CustomProviderCredentialsFields(
                            username = username,
                            secret = secret,
                            existing = hasExistingCredentials,
                            typeId = descriptor.typeId,
                            onUsernameChange = { username = it; onDraftChanged() },
                            onSecretChange = { secret = it; onDraftChanged() },
                        )
                    }
                    is SportsProviderAuth.OAuth2 -> Text(
                        text = stringResource(R.string.settings_sports_provider_oauth_notice),
                        color = colors.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SportsProviderAuth.Basic -> {
                        Text(
                            text = stringResource(R.string.settings_sports_provider_username),
                            color = colors.secondaryText,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        EpgSourceTextField(
                            value = username,
                            onValueChange = { username = it; onDraftChanged() },
                            placeholder = stringResource(R.string.settings_sports_provider_username),
                        )
                        SecretField(
                            value = secret,
                            onValueChange = { secret = it; onDraftChanged() },
                            existing = hasExistingCredentials,
                            typeId = descriptor.typeId,
                        )
                    }
                    is SportsProviderAuth.ApiKeyHeader,
                    is SportsProviderAuth.ApiKeyQuery,
                    SportsProviderAuth.Bearer -> SecretField(
                        value = secret,
                        onValueChange = { secret = it; onDraftChanged() },
                        existing = hasExistingCredentials,
                        typeId = descriptor.typeId,
                    )
                }
                if (!credentialsAreValid) {
                    Text(
                        text = stringResource(R.string.settings_sports_provider_credentials_required),
                        color = colors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (validationFailed) {
                    Text(
                        text = stringResource(R.string.settings_sports_provider_invalid_input),
                        color = colors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (operationFailed) {
                    Text(
                text = stringResource(R.string.settings_sports_provider_action_failed),
                        color = colors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8)) {
                    CompactSettingsActionChip(
                        label = stringResource(
                            if (draftConnectionState == SportsProviderConnectionState.TESTING) {
                                R.string.settings_sports_provider_testing
                            } else {
                                R.string.settings_sports_provider_test
                            },
                        ),
                        accent = colors.accent,
                        enabled = !isSaving && draftConnectionState != SportsProviderConnectionState.TESTING &&
                            credentialsAreValid && baseUrlIsValid && leagueIdsAreValid && customDefinitionIsValid,
                        onClick = {
                            onTest(
                                baseUrl,
                                username,
                                secret,
                                parsedLeagueIds.orEmpty(),
                                customDefinitionJson.takeIf { isCustomProvider },
                            )
                        },
                    )
                    draftConnectionState?.takeIf { it != SportsProviderConnectionState.TESTING }?.let { status ->
                        Text(
                            text = stringResource(status.labelResource()),
                            color = if (status == SportsProviderConnectionState.FAILED ||
                                status == SportsProviderConnectionState.ADAPTER_UNAVAILABLE) colors.error
                            else colors.secondaryText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space12)) {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = onDismiss,
                    enabled = !isSaving,
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_save),
                    onClick = {
                        onSave(
                            baseUrl,
                            username,
                            secret,
                            parsedLeagueIds.orEmpty(),
                            customDefinitionJson.takeIf { isCustomProvider },
                        )
                    },
                    enabled = !isSaving && draftConnectionState != SportsProviderConnectionState.TESTING &&
                        credentialsAreValid && baseUrlIsValid && leagueIdsAreValid && customDefinitionIsValid,
                    emphasized = true,
                )
            }
        },
    )
}

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    existing: Boolean,
    typeId: String,
) {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    Text(
        text = stringResource(
            if (typeId == THESPORTSDB_TYPE_ID) R.string.settings_sports_provider_thesportsdb_api_key
            else R.string.settings_sports_provider_secret,
        ),
        color = colors.secondaryText,
        style = MaterialTheme.typography.labelSmall,
    )
    EpgSourceTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = stringResource(
            if (existing) R.string.settings_sports_provider_keep_credentials
            else R.string.settings_sports_provider_secret_hint,
        ),
        visualTransformation = PasswordVisualTransformation(),
        keyboardType = KeyboardType.Password,
    )
}

@Composable
private fun CustomProviderCredentialsFields(
    username: String,
    secret: String,
    existing: Boolean,
    typeId: String,
    onUsernameChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
) {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    Text(
        text = stringResource(R.string.settings_sports_provider_username),
        color = colors.secondaryText,
        style = MaterialTheme.typography.labelSmall,
    )
    EpgSourceTextField(
        value = username,
        onValueChange = onUsernameChange,
        placeholder = stringResource(R.string.settings_sports_provider_username),
    )
    SecretField(
        value = secret,
        onValueChange = onSecretChange,
        existing = existing,
        typeId = typeId,
    )
}

@Composable
private fun localizedProviderName(typeId: String): String = when (typeId) {
    THESPORTSDB_TYPE_ID -> stringResource(R.string.settings_sports_provider_thesportsdb_name)
    HIGHLIGHTLY_TYPE_ID -> stringResource(R.string.settings_sports_provider_highlightly_name)
    FOOTBALL_DATA_TYPE_ID -> stringResource(R.string.settings_sports_provider_football_data_name)
    ODDSPAPI_TYPE_ID -> stringResource(R.string.settings_sports_provider_oddspapi_name)
    OPENLIGADB_TYPE_ID -> stringResource(R.string.settings_sports_provider_openligadb_name)
    CUSTOM_SPORTS_PROVIDER_TYPE_ID -> stringResource(R.string.settings_sports_provider_custom_json)
    else -> typeId
}

private fun android.content.Context.localizedProviderName(typeId: String): String = when (typeId) {
    THESPORTSDB_TYPE_ID -> getString(R.string.settings_sports_provider_thesportsdb_name)
    HIGHLIGHTLY_TYPE_ID -> getString(R.string.settings_sports_provider_highlightly_name)
    FOOTBALL_DATA_TYPE_ID -> getString(R.string.settings_sports_provider_football_data_name)
    ODDSPAPI_TYPE_ID -> getString(R.string.settings_sports_provider_oddspapi_name)
    OPENLIGADB_TYPE_ID -> getString(R.string.settings_sports_provider_openligadb_name)
    CUSTOM_SPORTS_PROVIDER_TYPE_ID -> getString(R.string.settings_sports_provider_custom_json)
    else -> typeId
}

private const val THESPORTSDB_TYPE_ID = "thesportsdb"
private const val HIGHLIGHTLY_TYPE_ID = "highlightly"
private const val FOOTBALL_DATA_TYPE_ID = "football-data"
private const val ODDSPAPI_TYPE_ID = "oddspapi"
private const val OPENLIGADB_TYPE_ID = "openligadb"

@Composable
private fun capabilitySummary(capabilities: Set<SportsCapability>): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return capabilities.sortedBy { it.ordinal }
        .joinToString(" · ") { context.getString(it.labelResource()) }
}

private fun SportsCapability.labelResource(): Int = when (this) {
    SportsCapability.EVENTS -> R.string.settings_sports_capability_events
    SportsCapability.LIVE_SCORES -> R.string.settings_sports_capability_live_scores
    SportsCapability.GAME_STATUS -> R.string.settings_sports_capability_game_status
    SportsCapability.STANDINGS -> R.string.settings_sports_capability_standings
    SportsCapability.TEAM_METADATA -> R.string.settings_sports_capability_team_metadata
    SportsCapability.LEAGUE_METADATA -> R.string.settings_sports_capability_league_metadata
    SportsCapability.ARTWORK -> R.string.settings_sports_capability_artwork
    SportsCapability.BROADCASTERS -> R.string.settings_sports_capability_broadcasters
    SportsCapability.LINEUPS -> R.string.settings_sports_capability_lineups
    SportsCapability.STATISTICS -> R.string.settings_sports_capability_statistics
}

private fun SportsProviderConnectionState.labelResource(): Int = when (this) {
    SportsProviderConnectionState.TESTING -> R.string.settings_sports_provider_testing
    SportsProviderConnectionState.SUCCESS -> R.string.settings_sports_provider_connection_success
    SportsProviderConnectionState.FAILED -> R.string.settings_sports_provider_connection_failed
    SportsProviderConnectionState.UNSUPPORTED -> R.string.settings_sports_provider_test_unsupported
    SportsProviderConnectionState.ADAPTER_UNAVAILABLE -> R.string.settings_sports_provider_adapter_unavailable
}

private fun credentialsValid(
    auth: SportsProviderAuth,
    existing: Boolean,
    username: String,
    secret: String,
): Boolean = when (auth) {
    SportsProviderAuth.None, is SportsProviderAuth.OAuth2 -> true
    SportsProviderAuth.Basic -> (existing && username.isBlank() && secret.isBlank()) ||
        (username.isNotBlank() && secret.isNotBlank())
    is SportsProviderAuth.ApiKeyHeader,
    is SportsProviderAuth.ApiKeyQuery,
    SportsProviderAuth.Bearer -> existing || secret.isNotBlank()
}

private fun customSportsProviderExample(localizedName: String): String =
    """
        {
          "name": ${JSONObject.quote(localizedName)},
          "auth": { "mode": "api_key_header", "parameterName": "X-API-Key" },
          "events": {
            "endpoint": "/fixtures?from={fromDate}&to={toDate}&leagueId={leagueId}",
            "items": "data.events",
            "id": "id",
            "sport": "sport",
            "leagueName": "league.name",
            "leagueId": "league.id",
            "title": "title",
            "start": "start",
            "startFormat": "ISO_OFFSET",
            "homeName": "home.name",
            "awayName": "away.name",
            "status": "status"
          },
          "scores": {
            "endpoint": "/scores?ids={eventIds}",
            "items": "data.scores",
            "eventId": "id",
            "homeName": "home.name",
            "homeScore": "home.score",
            "awayName": "away.name",
            "awayScore": "away.score",
            "status": "status",
            "updatedAt": "updatedAt",
            "updatedAtFormat": "ISO_OFFSET"
          }
        }
    """.trimIndent()

private fun customCredentialsValid(
    definition: String,
    existing: Boolean,
    username: String,
    secret: String,
): Boolean {
    val mode = runCatching {
        val root = JSONObject(definition)
        val auth = if (root.has("auth")) {
            root.optJSONObject("auth") ?: throw IllegalArgumentException()
        } else {
            null
        }
        auth?.optString("mode", "none") ?: "none"
    }.getOrNull() ?: return false
    return when (mode) {
        "none" -> true
        "basic" -> existing || (username.isNotBlank() && secret.isNotBlank())
        "api_key_header", "api_key_query", "bearer" -> existing || secret.isNotBlank()
        else -> false
    }
}

@Composable
private fun OpenLigaDbAttribution(
    attribution: com.streamvault.domain.sports.provider.ProviderAttribution,
    color: androidx.compose.ui.graphics.Color,
) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.settings_sports_provider_attribution, attribution.sourceName),
            modifier = Modifier.clickable { uriHandler.openUri(attribution.sourceUrl) },
            color = color,
            textDecoration = TextDecoration.Underline,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.settings_sports_provider_openligadb_license_name),
            modifier = Modifier.clickable { attribution.licenseUrl?.let(uriHandler::openUri) },
            color = color,
            textDecoration = TextDecoration.Underline,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun isOpenLigaDbScope(value: String): Boolean =
    value.matches(Regex("[A-Za-z0-9._-]{1,40}:(?:19\\d{2}|20\\d{2}|2100)"))
private fun parseEventLeagueIds(input: String): List<String>? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return emptyList()
    val ids = trimmed.split(Regex("[,\\s]+")).filter(String::isNotEmpty)
    if (ids.size > 50 || ids.any { !it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) }) return null
    if (ids.distinct().size != ids.size) return null
    return ids
}

private fun isValidOptionalHttpsUrl(value: String): Boolean {
    if (value.isBlank()) return true
    return runCatching {
        val uri = URI(value.trim())
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)
}
