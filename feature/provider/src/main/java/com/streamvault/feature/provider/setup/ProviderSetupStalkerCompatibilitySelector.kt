package com.streamvault.feature.provider.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.theme.AccentAmber
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.domain.model.StalkerCompatibilityRegistry
import com.streamvault.domain.model.StalkerCompatibilityProfileIds
import com.streamvault.domain.model.StalkerProfileVerification
import com.streamvault.domain.model.StalkerProtocolPreference

@Composable
internal fun StalkerCompatibilitySelector(
    protocol: StalkerProtocolPreference,
    profileId: String,
    onProtocolSelected: (StalkerProtocolPreference) -> Unit,
    onProfileSelected: (String) -> Unit
) {
    var showProtocols by rememberSaveable { mutableStateOf(false) }
    var showProfiles by rememberSaveable { mutableStateOf(false) }
    val selectedProfile = StalkerCompatibilityRegistry.find(profileId)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Compatibility", style = MaterialTheme.typography.titleSmall, color = OnBackground)
        SmallActionButton(
            text = "Protocol: ${protocol.name.replace('_', ' ')}",
            onClick = { showProtocols = !showProtocols }
        )
        AnimatedVisibility(showProtocols) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StalkerProtocolPreference.entries
                    .filterNot { it == StalkerProtocolPreference.MINISTRA_API_V3 }
                    .forEach { option ->
                        SmallActionButton(
                            text = if (option == protocol) "✓ ${option.name.replace('_', ' ')}" else option.name.replace('_', ' '),
                            onClick = {
                                onProtocolSelected(option)
                                onProfileSelected(StalkerCompatibilityProfileIds.AUTO)
                                showProtocols = false
                            }
                        )
                    }
            }
        }
        SmallActionButton(
            text = "Model: ${selectedProfile?.displayName ?: "Automatic"}",
            onClick = { showProfiles = !showProfiles }
        )
        AnimatedVisibility(showProfiles) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallActionButton(
                    text = if (profileId == StalkerCompatibilityProfileIds.AUTO) "✓ Automatic" else "Automatic",
                    onClick = {
                        onProfileSelected(StalkerCompatibilityProfileIds.AUTO)
                        showProfiles = false
                    }
                )
                val family = if (protocol == StalkerProtocolPreference.MINISTRA_API_V3) {
                    com.streamvault.domain.model.StalkerProtocolFamily.MINISTRA_API_V3
                } else {
                    com.streamvault.domain.model.StalkerProtocolFamily.CLASSIC_MAG
                }
                StalkerCompatibilityRegistry.profiles
                    .filter { it.protocolFamily == family }
                    .groupBy { it.generation }
                    .forEach { (generation, profiles) ->
                        Text(generation, style = MaterialTheme.typography.labelMedium, color = OnBackground.copy(alpha = 0.72f))
                        profiles.forEach { option ->
                            val experimental = option.verification == StalkerProfileVerification.EXPERIMENTAL
                            SmallActionButton(
                                text = buildString {
                                    if (option.id == profileId) append("✓ ")
                                    append(option.displayName)
                                    if (experimental) append(" — EXPERIMENTAL")
                                },
                                onClick = {
                                    onProfileSelected(option.id)
                                    showProfiles = false
                                }
                            )
                        }
                    }
                if (family == com.streamvault.domain.model.StalkerProtocolFamily.CLASSIC_MAG) {
                    Text(
                        "Experimental profiles require captured/manual identity fields and are excluded from automatic discovery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentAmber
                    )
                    SmallActionButton(
                        text = if (profileId == StalkerCompatibilityProfileIds.CUSTOM) "✓ Custom profile" else "Custom profile",
                        onClick = {
                            onProfileSelected(StalkerCompatibilityProfileIds.CUSTOM)
                            showProfiles = false
                        }
                    )
                }
            }
        }
    }
}
