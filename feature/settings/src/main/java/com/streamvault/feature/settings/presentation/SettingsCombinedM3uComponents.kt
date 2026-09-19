package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.design.FocusSpec
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.ErrorColor
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType

@Composable
public fun CombinedM3uProfilesCard(
    profiles: List<CombinedM3uProfile>,
    availableProviders: List<Provider>,
    selectedProfileId: Long?,
    activeLiveSource: ActiveLiveSource?,
    onSelectProfile: (Long) -> Unit,
    onCreateProfile: () -> Unit,
    onActivateProfile: (Long) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onRenameProfile: (Long) -> Unit,
    onAddProvider: (Long) -> Unit,
    onRemoveProvider: (Long, Long) -> Unit,
    onToggleProviderEnabled: (Long, Long, Boolean) -> Unit,
    onMoveProvider: (Long, Long, Boolean) -> Unit,
    targetItemId: String? = null,
    targetFocusModifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_combined_section_title), style = MaterialTheme.typography.titleMedium, color = OnSurface)
                    Text(
                    stringResource(R.string.settings_combined_section_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
                CompactSettingsActionChip(
                label = stringResource(R.string.settings_combined_create),
                    accent = Primary,
                    onClick = onCreateProfile
                )
            }

            if (profiles.isEmpty()) {
            Text(stringResource(R.string.settings_combined_none), style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(profiles, key = { it.id }) { profile ->
                        val isActive = (activeLiveSource as? ActiveLiveSource.CombinedM3uSource)?.profileId == profile.id
                        ProviderChip(
                            title = profile.name,
                            subtitle = buildList {
                                add(
                                    pluralStringResource(
                                        R.plurals.settings_combined_playlist_count,
                                        profile.members.size,
                                        profile.members.count { it.enabled },
                                        profile.members.size,
                                    )
                                )
                                if (isActive) add(stringResource(R.string.settings_active))
                                if (profile.members.none { it.enabled }) {
                                    add(stringResource(R.string.settings_combined_empty_status))
                                }
                            }.joinToString(" \u00B7 "),
                            isSelected = selectedProfileId == profile.id,
                            isActive = isActive,
                            onClick = { onSelectProfile(profile.id) }
                        )
                    }
                }

                val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.first()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CompactSettingsActionChip(
                            label = stringResource(R.string.settings_combined_use_live),
                            accent = Primary,
                            enabled = selectedProfile.members.any { it.enabled },
                            onClick = { onActivateProfile(selectedProfile.id) },
                            modifier = if (targetItemId == "sources.combined.active") targetFocusModifier else Modifier,
                        )
                        CompactSettingsActionChip(
                            label = stringResource(R.string.settings_combined_rename),
                            accent = OnBackground,
                            onClick = { onRenameProfile(selectedProfile.id) }
                        )
                        CompactSettingsActionChip(
                            label = stringResource(R.string.settings_combined_add_playlist),
                            accent = OnBackground,
                            onClick = { onAddProvider(selectedProfile.id) },
                            modifier = if (targetItemId == "sources.combined.members") targetFocusModifier else Modifier,
                        )
                        CompactSettingsActionChip(
                            label = stringResource(R.string.settings_combined_delete),
                            accent = ErrorColor,
                            onClick = { onDeleteProfile(selectedProfile.id) },
                            modifier = if (targetItemId == "sources.combined.delete") targetFocusModifier else Modifier,
                        )
                    }

                    Text(
                        text = stringResource(
                            R.string.settings_combined_enabled_count,
                            selectedProfile.members.count { it.enabled },
                            selectedProfile.members.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )

                    if (selectedProfile.members.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_combined_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    } else if (selectedProfile.members.none { it.enabled }) {
                        Text(
                            text = stringResource(R.string.settings_combined_all_disabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }

                    selectedProfile.members
                        .sortedBy { it.priority }
                        .forEachIndexed { index, member ->
                            val providerName = member.providerName.ifBlank {
                            availableProviders.firstOrNull { it.id == member.providerId }?.name
                                ?: stringResource(R.string.settings_combined_playlist_fallback, member.providerId)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(providerName, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
                                    Text(
                                stringResource(
                                    if (member.enabled) R.string.settings_combined_member_enabled
                                    else R.string.settings_combined_member_disabled
                                ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceDim
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CompactSettingsActionChip(
                                        label = stringResource(R.string.settings_combined_move_up),
                                        accent = OnBackground,
                                        enabled = index > 0,
                                        onClick = { onMoveProvider(selectedProfile.id, member.providerId, true) }
                                    )
                                    CompactSettingsActionChip(
                                        label = stringResource(R.string.settings_combined_move_down),
                                        accent = OnBackground,
                                        enabled = index < selectedProfile.members.lastIndex,
                                        onClick = { onMoveProvider(selectedProfile.id, member.providerId, false) }
                                    )
                                    Switch(
                                        checked = member.enabled,
                                        onCheckedChange = { onToggleProviderEnabled(selectedProfile.id, member.providerId, it) }
                                    )
                                    CompactSettingsActionChip(
                                        label = stringResource(R.string.settings_combined_remove),
                                        accent = ErrorColor,
                                        onClick = { onRemoveProvider(selectedProfile.id, member.providerId) }
                                    )
                                }
                            }
                        }
                }
            }
        }
    }
}

@Composable
public fun RenameCombinedM3uDialog(
    profile: CombinedM3uProfile,
    isSubmitting: Boolean = false,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }

    PremiumDialog(
        title = stringResource(R.string.settings_combined_rename_title),
        subtitle = stringResource(R.string.settings_combined_rename_description),
        onDismissRequest = onDismiss,
        widthFraction = 0.48f,
        content = {
            EpgSourceTextField(
                value = name,
                onValueChange = { updated -> name = updated },
                placeholder = stringResource(R.string.settings_combined_name_hint)
            )
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss,
                enabled = !isSubmitting
            )
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_combined_save),
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank() && !isSubmitting,
                emphasized = true
            )
        }
    )
}

@Composable
public fun CreateCombinedM3uDialog(
    providers: List<Provider>,
    isSubmitting: Boolean = false,
    onDismiss: () -> Unit,
    onCreate: (String, List<Long>) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var selectedProviderIds by rememberSaveable { mutableStateOf(setOf<Long>()) }
    val m3uProviders = remember(providers) { providers.filter { it.type == ProviderType.M3U } }
    val effectiveName = remember(name, selectedProviderIds, m3uProviders) {
        val manualName = name.trim()
        if (manualName.isNotBlank()) {
            manualName
        } else {
            val selectedProviders = m3uProviders.filter { it.id in selectedProviderIds }
            when {
                selectedProviders.isEmpty() -> ""
                selectedProviders.size == 1 -> "${selectedProviders.first().name} Mix"
                selectedProviders.size == 2 -> "${selectedProviders[0].name} + ${selectedProviders[1].name}"
                else -> "${selectedProviders.first().name} + ${selectedProviders.size - 1} More"
            }
        }
    }

    PremiumDialog(
        title = stringResource(R.string.settings_combined_create_title),
        subtitle = stringResource(R.string.settings_combined_create_description),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            EpgSourceTextField(
                value = name,
                onValueChange = { updated -> name = updated },
            placeholder = effectiveName.ifBlank { stringResource(R.string.settings_combined_name_hint) }
            )

            if (m3uProviders.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_combined_no_playlists),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    m3uProviders.forEach { provider ->
                        val isSelected = provider.id in selectedProviderIds
                        TvClickableSurface(
                            onClick = {
                                selectedProviderIds = if (isSelected) {
                                    selectedProviderIds - provider.id
                                } else {
                                    selectedProviderIds + provider.id
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isSelected) Primary.copy(alpha = 0.16f) else com.streamvault.core.ui.theme.SurfaceElevated,
                                focusedContainerColor = Primary.copy(alpha = 0.24f)
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = provider.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnSurface
                                    )
                                    Text(
                                    text = stringResource(
                                        if (isSelected) R.string.settings_combined_member_included
                                        else R.string.settings_combined_member_include_action
                                    ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceDim
                                    )
                                }
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = null
                                )
                            }
                        }
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss,
                enabled = !isSubmitting
            )
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_combined_create),
                onClick = { onCreate(effectiveName, selectedProviderIds.toList()) },
                enabled = selectedProviderIds.isNotEmpty() && effectiveName.isNotBlank() && !isSubmitting,
                emphasized = true
            )
        }
    )
}

@Composable
public fun AddCombinedProviderDialog(
    profile: CombinedM3uProfile,
    availableProviders: List<Provider>,
    isSubmitting: Boolean = false,
    onDismiss: () -> Unit,
    onAddProvider: (Long) -> Unit
) {
    val candidateProviders = remember(profile, availableProviders) {
        availableProviders.filter { provider -> profile.members.none { it.providerId == provider.id } }
    }
    var selectedProviderId by rememberSaveable(profile.id) { mutableStateOf(candidateProviders.firstOrNull()?.id) }
    PremiumDialog(
        title = stringResource(R.string.settings_combined_add_title, profile.name),
        subtitle = stringResource(R.string.settings_combined_add_description),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            if (candidateProviders.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_combined_all_added),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    candidateProviders.forEach { provider ->
                        val isSelected = selectedProviderId == provider.id
                        TvClickableSurface(
                            onClick = { selectedProviderId = provider.id },
                            modifier = Modifier.fillMaxWidth(),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isSelected) Primary.copy(alpha = 0.16f) else com.streamvault.core.ui.theme.SurfaceElevated,
                                focusedContainerColor = Primary.copy(alpha = 0.22f)
                            ),
                            border = ClickableSurfaceDefaults.border(
                                border = Border(
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) Primary.copy(alpha = 0.4f) else com.streamvault.core.ui.design.AppColors.Divider
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                                focusedBorder = Border(
                                    border = BorderStroke(FocusSpec.BorderWidth, com.streamvault.core.ui.theme.FocusBorder),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = provider.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = OnSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedProviderId = provider.id }
                                )
                            }
                        }
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss,
                enabled = !isSubmitting
            )
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_combined_add),
                onClick = { selectedProviderId?.let(onAddProvider) },
                enabled = selectedProviderId != null && candidateProviders.isNotEmpty() && !isSubmitting,
                emphasized = true
            )
        }
    )
}

@Composable
private fun ProviderChip(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    isActive: Boolean,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.16f) else Color.Transparent,
            focusedContainerColor = Primary.copy(alpha = 0.24f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = OnSurface)
            Text(
                if (isActive) "$subtitle • Active" else subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isActive) Primary else OnSurfaceDim
            )
        }
    }
}
