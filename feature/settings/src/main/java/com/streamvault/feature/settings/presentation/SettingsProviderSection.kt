package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.components.TvEmptyState
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType

public fun LazyListScope.providerSection(
    uiState: SettingsUiState,
    onAddProvider: () -> Unit,
    onEditProvider: (Provider) -> Unit,
    onNavigateToParentalControl: (Long) -> Unit,
    viewModel: SettingsViewModel,
    providerState: SettingsProviderSectionState,
    targetItemId: String? = null,
    targetFocusModifier: Modifier = Modifier,
) {
    item {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_provider_inventory_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.settings_provider_inventory_count,
                        uiState.providers.size,
                        uiState.providers.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                )
            }
            CompactSettingsActionChip(
                label = stringResource(R.string.settings_add_provider),
                accent = Primary,
                onClick = onAddProvider,
                modifier = if (targetItemId == "sources.add") targetFocusModifier else Modifier,
            )
        }
    }
    if (uiState.providers.isEmpty()) {
        item {
            TvEmptyState(
                title = stringResource(R.string.settings_no_providers),
                subtitle = stringResource(R.string.settings_no_providers_subtitle),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 8.dp)
            )
        }
    } else {
        item {
            var selectedProviderId by rememberSaveable(uiState.providers, uiState.activeProviderId) {
                mutableStateOf(uiState.activeProviderId ?: uiState.providers.first().id)
            }
            LaunchedEffect(uiState.providers, uiState.activeProviderId) {
                val availableIds = uiState.providers.map { it.id }.toSet()
                if (selectedProviderId !in availableIds) {
                    selectedProviderId = uiState.activeProviderId ?: uiState.providers.first().id
                }
            }
            val selectedProvider = uiState.providers.firstOrNull { it.id == selectedProviderId }
                ?: uiState.providers.first()

            if (uiState.providers.size > 1 || targetItemId == "sources.active_provider") {
                Text(
                    text = stringResource(R.string.settings_provider_selector_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 14.dp)
                ) {
                    items(uiState.providers, key = { it.id }) { provider ->
                        ProviderSelectorTab(
                            provider = provider,
                            isSelected = provider.id == selectedProvider.id,
                            isActive = provider.id == uiState.activeProviderId,
                            showType = uiState.providers.count { it.name == provider.name } > 1,
                            onClick = { selectedProviderId = provider.id },
                            modifier = if (
                                targetItemId == "sources.active_provider" && provider.id == selectedProvider.id
                            ) targetFocusModifier else Modifier,
                        )
                    }
                }
            }
            ProviderSettingsCard(
                provider = selectedProvider,
                isActive = selectedProvider.id == uiState.activeProviderId,
                isSyncing = uiState.isSyncing,
                appTimeFormat = uiState.appTimeFormat,
                xtreamLiveOnboardingPhase = uiState.xtreamLiveOnboardingPhaseByProvider[selectedProvider.id],
                xtreamLiveOnboarding = uiState.xtreamLiveOnboardingByProvider[selectedProvider.id],
                xtreamIndexSectionStatuses = uiState.xtreamIndexSectionStatusByProvider[selectedProvider.id].orEmpty(),
                diagnostics = uiState.diagnosticsByProvider[selectedProvider.id],
                databaseMaintenance = uiState.databaseMaintenance,
                syncWarnings = uiState.syncWarningsByProvider[selectedProvider.id].orEmpty(),
                onRetryWarningAction = { action -> viewModel.retryWarningAction(selectedProvider.id, action) },
                onConnect = { viewModel.setActiveProvider(selectedProvider.id) },
                onRefresh = {
                    providerState.pendingSyncProviderId = selectedProvider.id
                    providerState.customSyncSelections = availableSyncSelections(selectedProvider).toSet()
                    providerState.showProviderSyncDialog = true
                },
                onDelete = { providerState.pendingDeleteProviderId = selectedProvider.id },
                onEdit = { onEditProvider(selectedProvider) },
                onParentalControl = { onNavigateToParentalControl(selectedProvider.id) },
                onToggleM3uVodClassification = { enabled ->
                    viewModel.setM3uVodClassificationEnabled(selectedProvider.id, enabled)
                },
                onRefreshM3uClassification = {
                    viewModel.refreshProviderClassification(selectedProvider.id)
                },
                targetItemId = targetItemId,
                targetFocusModifier = targetFocusModifier,
            )

            Spacer(modifier = Modifier.height(18.dp))
            AutomaticSyncSettingsCard(
                viewModel = viewModel,
                modifier = if (targetItemId == "sources.automatic_sync") targetFocusModifier else Modifier,
            )
            Spacer(modifier = Modifier.height(18.dp))
            AutomaticSyncSettingsCard(viewModel = viewModel, provider = selectedProvider)
            Spacer(modifier = Modifier.height(18.dp))
            if (uiState.availableM3uProviders.size > 1 || uiState.combinedProfiles.isNotEmpty()) {
                CombinedM3uProfilesCard(
                    profiles = uiState.combinedProfiles,
                    availableProviders = uiState.availableM3uProviders,
                    selectedProfileId = providerState.selectedCombinedProfileId,
                    activeLiveSource = uiState.activeLiveSource,
                    onSelectProfile = { providerState.selectedCombinedProfileId = it },
                    onCreateProfile = { providerState.showCreateCombinedDialog = true },
                    onActivateProfile = { profileId -> viewModel.setActiveCombinedProfile(profileId) },
                    onDeleteProfile = { profileId ->
                        if (providerState.selectedCombinedProfileId == profileId) {
                            providerState.selectedCombinedProfileId = null
                        }
                        viewModel.deleteCombinedProfile(profileId)
                    },
                    onRenameProfile = { profileId ->
                        providerState.selectedCombinedProfileId = profileId
                        providerState.showRenameCombinedDialog = true
                    },
                    onAddProvider = { profileId ->
                        providerState.selectedCombinedProfileId = profileId
                        providerState.showAddCombinedMemberDialog = true
                    },
                    onRemoveProvider = { profileId, providerId ->
                        viewModel.removeProviderFromCombinedProfile(profileId, providerId)
                    },
                    onToggleProviderEnabled = { profileId, providerId, enabled ->
                        viewModel.setCombinedProviderEnabled(profileId, providerId, enabled)
                    },
                    onMoveProvider = { profileId, providerId, moveUp ->
                        viewModel.moveCombinedProvider(profileId, providerId, moveUp)
                    },
                    targetItemId = targetItemId,
                    targetFocusModifier = targetFocusModifier,
                )
            }
        }
    }

}
