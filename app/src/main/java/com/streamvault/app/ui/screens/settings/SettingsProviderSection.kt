package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.core.ui.components.TvEmptyState
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType

internal fun LazyListScope.providerSection(
    uiState: SettingsUiState,
    onAddProvider: () -> Unit,
    onEditProvider: (Provider) -> Unit,
    onNavigateToParentalControl: (Long) -> Unit,
    viewModel: SettingsViewModel,
    providerState: SettingsProviderSectionState
) {
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
                        onClick = { selectedProviderId = provider.id }
                    )
                }
            }
            ProviderSettingsCard(
                provider = selectedProvider,
                isActive = selectedProvider.id == uiState.activeProviderId,
                isSyncing = uiState.isSyncing,
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
                    providerState.customSyncSelections = buildSet {
                        add(ProviderSyncSelection.TV)
                        add(ProviderSyncSelection.MOVIES)
                        add(ProviderSyncSelection.EPG)
                        if (selectedProvider.type == ProviderType.XTREAM_CODES) {
                            add(ProviderSyncSelection.SERIES)
                        }
                    }
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
                }
            )

            Spacer(modifier = Modifier.height(18.dp))
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
                }
            )
        }
    }

    item {
        TvClickableSurface(
            onClick = onAddProvider,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Primary.copy(alpha = 0.15f),
                focusedContainerColor = Primary.copy(alpha = 0.3f)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_add_provider),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Primary
                )
            }
        }
    }
}
