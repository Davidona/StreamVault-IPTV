package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary

@Composable
public fun SettingsProviderManagementDialogs(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    providerState: SettingsProviderSectionState
) {
    val pendingSyncProvider = providerState.pendingSyncProviderId?.let { providerId ->
        uiState.providers.firstOrNull { it.id == providerId }
    }

    if (providerState.showCreateCombinedDialog) {
        var isCreating by remember { mutableStateOf(false) }
        CreateCombinedM3uDialog(
            providers = uiState.availableM3uProviders,
            isSubmitting = isCreating,
            onDismiss = { providerState.showCreateCombinedDialog = false },
            onCreate = { name, providerIds ->
                isCreating = true
                viewModel.createCombinedProfile(name, providerIds,
                    onSuccess = { providerState.showCreateCombinedDialog = false },
                    onError = { isCreating = false }
                )
            }
        )
    }

    if (providerState.showRenameCombinedDialog) {
        val selectedProfile = uiState.combinedProfiles.firstOrNull { it.id == providerState.selectedCombinedProfileId }
        if (selectedProfile != null) {
            var isRenaming by remember(selectedProfile.id) { mutableStateOf(false) }
            RenameCombinedM3uDialog(
                profile = selectedProfile,
                isSubmitting = isRenaming,
                onDismiss = { providerState.showRenameCombinedDialog = false },
                onRename = { name ->
                    isRenaming = true
                    viewModel.renameCombinedProfile(selectedProfile.id, name,
                        onSuccess = { providerState.showRenameCombinedDialog = false },
                        onError = { isRenaming = false }
                    )
                }
            )
        }
    }

    if (providerState.showAddCombinedMemberDialog) {
        val selectedProfile = uiState.combinedProfiles.firstOrNull { it.id == providerState.selectedCombinedProfileId }
        if (selectedProfile != null) {
            var isAddingMember by remember(selectedProfile.id) { mutableStateOf(false) }
            AddCombinedProviderDialog(
                profile = selectedProfile,
                availableProviders = uiState.availableM3uProviders,
                isSubmitting = isAddingMember,
                onDismiss = { providerState.showAddCombinedMemberDialog = false },
                onAddProvider = { providerId ->
                    isAddingMember = true
                    viewModel.addProviderToCombinedProfile(selectedProfile.id, providerId,
                        onSuccess = { providerState.showAddCombinedMemberDialog = false },
                        onError = { isAddingMember = false }
                    )
                }
            )
        }
    }

    if (providerState.showProviderSyncDialog && pendingSyncProvider != null) {
        ProviderSyncOptionsDialog(
            provider = pendingSyncProvider,
            onDismiss = {
                providerState.showProviderSyncDialog = false
                providerState.pendingSyncProviderId = null
            },
            onSelect = { selection ->
                providerState.showProviderSyncDialog = false
                if (selection == null) {
                    providerState.showCustomProviderSyncDialog = true
                } else {
                    viewModel.syncProviderSection(pendingSyncProvider.id, selection)
                    providerState.pendingSyncProviderId = null
                }
            }
        )
    }

    if (providerState.showCustomProviderSyncDialog && pendingSyncProvider != null) {
        ProviderCustomSyncDialog(
            provider = pendingSyncProvider,
            selected = providerState.customSyncSelections,
            onToggle = { option ->
                providerState.customSyncSelections =
                    if (option in providerState.customSyncSelections) {
                        providerState.customSyncSelections - option
                    } else {
                        providerState.customSyncSelections + option
                    }
            },
            onDismiss = {
                providerState.showCustomProviderSyncDialog = false
                providerState.pendingSyncProviderId = null
            },
            onConfirm = {
                providerState.showCustomProviderSyncDialog = false
                viewModel.syncProviderCustom(pendingSyncProvider.id, providerState.customSyncSelections)
                providerState.pendingSyncProviderId = null
            }
        )
    }

    val pendingDeleteProviderId = providerState.pendingDeleteProviderId
    if (pendingDeleteProviderId != null) {
        val providerToDelete = uiState.providers.firstOrNull { it.id == pendingDeleteProviderId }
        LaunchedEffect(pendingDeleteProviderId, providerToDelete, uiState.isDeletingProvider) {
            if (providerToDelete == null && !uiState.isDeletingProvider) {
                providerState.pendingDeleteProviderId = null
            }
        }
        val providerName = providerToDelete?.name ?: "this provider"
        PremiumDialog(
            title = stringResource(R.string.settings_provider_delete_title),
            subtitle = stringResource(R.string.settings_provider_delete_description, providerName),
            onDismissRequest = { if (!uiState.isDeletingProvider) providerState.pendingDeleteProviderId = null },
            widthFraction = 0.48f,
            heightFraction = null,
            content = {
                if (uiState.isDeletingProvider) {
                    val fraction = uiState.deleteProviderProgressFraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            color = Primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                    } else {
                        LinearProgressIndicator(
                            color = Primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                    }
                    Text(
                        text = uiState.deleteProviderProgressMessage ?: "Deleting provider...",
                        color = OnSurfaceDim
                    )
                }
            },
            footer = {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = { providerState.pendingDeleteProviderId = null },
                    enabled = !uiState.isDeletingProvider
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_delete),
                    onClick = {
                        viewModel.deleteProvider(pendingDeleteProviderId,
                            onSuccess = { providerState.pendingDeleteProviderId = null }
                        )
                    },
                    enabled = !uiState.isDeletingProvider,
                    destructive = true
                )
            }
        )
    }
}
