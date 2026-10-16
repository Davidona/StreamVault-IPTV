package com.streamvault.feature.playback.multiview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.BrowseColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.feature.playback.R
import androidx.compose.ui.res.stringResource

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
        BrowseColors.Warning
    } else {
        BrowseColors.Success
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .background(
                Brush.verticalGradient(
                    listOf(
                        BrowseColors.SurfaceEmphasis.copy(alpha = 0.98f),
                        BrowseColors.Surface.copy(alpha = 0.98f)
                    )
                ),
                BrowseShapes.Hero
            )
            .border(1.dp, BrowseColors.Outline, BrowseShapes.Hero)
            .padding(horizontal = 22.dp, vertical = 18.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = "Split Screen Controls",
                color = BrowseColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(
                    R.string.multiview_policy_summary,
                    uiState.performancePolicy.tier.name.lowercase().replaceFirstChar { it.uppercase() },
                    uiState.telemetry.activeSlotLimit
                ),
                color = BrowseColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (focused != null && focused.title.isNotBlank()) {
            Text(
                text = stringResource(R.string.multiview_focused_prefix, focused.title),
                color = BrowseColors.BrandStrong,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = BrowseShapes.Control,
                colors = SurfaceDefaults.colors(containerColor = BrowseColors.SurfaceAccent)
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
                    shape = BrowseShapes.Control,
                    colors = SurfaceDefaults.colors(
                        containerColor = BrowseColors.Warning.copy(alpha = 0.14f)
                    )
                ) {
                    Text(
                        text = "Standby active",
                        color = BrowseColors.Warning,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SplitControlButton(
                onClick = onShowReplacementPicker,
                modifier = Modifier.focusRequester(firstControlFocusRequester),
                primary = true
            ) {
                Text(stringResource(R.string.multiview_replace_slot))
            }
            SplitControlButton(
                onClick = onRemoveFocusedSlot,
                enabled = focused != null && !focused.isEmpty
            ) {
                Text(stringResource(R.string.multiview_remove_slot))
            }
            if (uiState.pinnedAudioSlotIndex == uiState.focusedSlotIndex) {
                SplitControlButton(onClick = onClearPinnedAudio) {
                    Text(stringResource(R.string.multiview_audio_follow_focus))
                }
            } else {
                SplitControlButton(
                    onClick = onPinAudioToFocusedSlot,
                    enabled = focused != null && !focused.isEmpty && focused.performanceBlockedReason == null
                ) {
                    Text(stringResource(R.string.multiview_pin_audio))
                }
            }
        }

        if (uiState.presets.isNotEmpty()) {
            Text(
                text = "Presets",
                color = BrowseColors.TextSecondary,
                style = MaterialTheme.typography.labelLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                uiState.presets.forEach { preset ->
                    val presetLabel = stringResource(R.string.multiview_preset_label, preset.index + 1)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SplitControlButton(onClick = { onLoadPreset(preset.index) }) {
                            Text(
                                text = if (preset.isPopulated) {
                                    "$presetLabel (${preset.channelCount})"
                                } else {
                                    presetLabel
                                }
                            )
                        }
                        SplitControlButton(onClick = { onSavePreset(preset.index) }) {
                            Text(text = stringResource(R.string.multiview_preset_save, preset.index + 1))
                        }
                    }
                }
            }
        }

        if (!uiState.telemetry.throttledReason.isNullOrBlank()) {
            Text(
                text = uiState.telemetry.throttledReason.orEmpty(),
                color = BrowseColors.Warning,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SplitControlButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val shape = BrowseShapes.Control
    val containerColor = if (primary) BrowseColors.Brand else BrowseColors.SurfaceAccent
    val contentColor = if (primary) BrowseColors.OnPrimary else BrowseColors.TextPrimary
    TvButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = containerColor,
            focusedContentColor = contentColor,
            disabledContainerColor = BrowseColors.Surface.copy(alpha = 0.72f),
            disabledContentColor = BrowseColors.TextDisabled
        ),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(1.dp, BrowseColors.Outline), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, BrowseColors.Focus), shape = shape)
        ),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        content = content
    )
}
