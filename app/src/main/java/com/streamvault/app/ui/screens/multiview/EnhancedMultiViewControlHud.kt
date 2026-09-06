package com.streamvault.app.ui.screens.multiview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvIconButton

@Composable
internal fun EnhancedMultiViewControlHud(
    focused: MultiViewSlot?,
    uiState: MultiViewUiState,
    firstControlFocusRequester: FocusRequester,
    onShowReplacementPicker: () -> Unit,
    onRemoveFocusedSlot: () -> Unit,
    onClearPinnedAudio: () -> Unit,
    onPinAudioToFocusedSlot: () -> Unit,
    onLoadPreset: (Int) -> Unit,
    onSavePreset: (Int) -> Unit
) {
    val telemetryTone = if (uiState.telemetry.standbySlots > 0 || uiState.telemetry.bufferingSlots > 0) {
        Color(0xFFFFD166)
    } else {
        Primary
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .background(AppColors.CanvasElevated.copy(alpha = 0.94f), RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(horizontal = 22.dp, vertical = 18.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Split Screen Controls",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(
                    R.string.multiview_policy_summary,
                    uiState.performancePolicy.tier.name.lowercase().replaceFirstChar { it.uppercase() },
                    uiState.telemetry.activeSlotLimit
                ),
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (focused != null && focused.title.isNotBlank()) {
            Text(
                text = stringResource(R.string.multiview_focused_prefix, focused.title),
                color = Primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                colors = androidx.tv.material3.SurfaceDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.08f)
                )
            ) {
                Text(
                    text = stringResource(
                        R.string.multiview_telemetry_snapshot,
                        uiState.telemetry.activeSlots,
                        uiState.telemetry.standbySlots,
                        uiState.telemetry.bufferingSlots,
                        uiState.telemetry.errorSlots
                    ),
                    color = telemetryTone,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            if (!uiState.telemetry.throttledReason.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    colors = androidx.tv.material3.SurfaceDefaults.colors(
                        containerColor = Color(0xFFFFC107).copy(alpha = 0.16f)
                    )
                ) {
                    Text(
                        text = "Standby active",
                        color = Color(0xFFFFD166),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(
                onClick = onShowReplacementPicker,
                modifier = Modifier.focusRequester(firstControlFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = Primary.copy(alpha = 0.94f),
                    contentColor = Color.Black,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black
                )
            ) {
                Text(stringResource(R.string.multiview_replace_slot))
            }
            TvButton(
                onClick = onRemoveFocusedSlot,
                enabled = focused != null && !focused.isEmpty,
                colors = ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.10f),
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black
                )
            ) {
                Text(stringResource(R.string.multiview_remove_slot))
            }
            if (uiState.pinnedAudioSlotIndex == uiState.focusedSlotIndex) {
                TvButton(
                    onClick = onClearPinnedAudio,
                    colors = ButtonDefaults.colors(
                        containerColor = AppColors.SurfaceAccent,
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black
                    )
                ) {
                    Text(stringResource(R.string.multiview_audio_follow_focus))
                }
            } else {
                TvButton(
                    onClick = onPinAudioToFocusedSlot,
                    enabled = focused != null && !focused.isEmpty && focused.performanceBlockedReason == null,
                    colors = ButtonDefaults.colors(
                        containerColor = AppColors.SurfaceAccent,
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black
                    )
                ) {
                    Text(stringResource(R.string.multiview_pin_audio))
                }
            }
        }

        if (uiState.presets.isNotEmpty()) {
            Text(
                text = "Presets",
                color = Color.White.copy(alpha = 0.84f),
                style = MaterialTheme.typography.labelLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                uiState.presets.forEach { preset ->
                    val presetLabel = stringResource(R.string.multiview_preset_label, preset.index + 1)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TvButton(
                            onClick = { onLoadPreset(preset.index) },
                            colors = ButtonDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.10f),
                                contentColor = Color.White,
                                focusedContainerColor = Color.White,
                                focusedContentColor = Color.Black
                            )
                        ) {
                            Text(
                                text = if (preset.isPopulated) {
                                    "$presetLabel (${preset.channelCount})"
                                } else {
                                    presetLabel
                                }
                            )
                        }
                        TvButton(
                            onClick = { onSavePreset(preset.index) },
                            colors = ButtonDefaults.colors(
                                containerColor = AppColors.SurfaceElevated,
                                contentColor = Color.White,
                                focusedContainerColor = Color.White,
                                focusedContentColor = Color.Black
                            )
                        ) {
                            Text(text = stringResource(R.string.multiview_preset_save, preset.index + 1))
                        }
                    }
                }
            }
        }

        if (!uiState.telemetry.throttledReason.isNullOrBlank()) {
            Text(
                text = uiState.telemetry.throttledReason.orEmpty(),
                color = Color(0xFFFFE7A3),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}
