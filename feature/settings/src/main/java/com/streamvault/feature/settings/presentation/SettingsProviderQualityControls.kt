package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.domain.settings.PlaybackQualityLimit
import com.streamvault.domain.settings.QualityLimitOverride
import com.streamvault.domain.settings.ScopedPlaybackQualityPreference
import com.streamvault.feature.settings.R

@Composable
internal fun SettingsProviderQualityControls(
    providerId: Long,
    preference: ScopedPlaybackQualityPreference?,
    globalProfileLimit: PlaybackQualityLimit,
    onChange: (ScopedPlaybackQualityPreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val current = preference ?: ScopedPlaybackQualityPreference()
    val heightOptions = buildList {
        (current.maxVideoHeight as? QualityLimitOverride.Maximum)?.let { add(it.value) }
        addAll(listOf(480, 720, 1080, 2160))
    }.distinct().sorted()
    val bitrateOptions = buildList {
        (current.maxVideoBitrateBps as? QualityLimitOverride.Maximum)?.let { add(it.value) }
        addAll(listOf(2_000_000L, 4_000_000L, 8_000_000L))
    }.distinct().sorted()
    var showHeightDialog by rememberSaveable(providerId) { mutableStateOf(false) }
    var showBitrateDialog by rememberSaveable(providerId) { mutableStateOf(false) }

    if (showHeightDialog) {
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_quality_custom_height),
            onDismiss = { showHeightDialog = false }
        ) {
            LevelOption(
                level = 0,
                text = context.getString(
                    R.string.settings_remote_selection_global_default,
                    formatQualityCapLabel(
                        globalProfileLimit.maxVideoHeight,
                        context.getString(R.string.settings_quality_cap_auto)
                    )
                ),
                currentLevel = heightOverrideIndex(current.maxVideoHeight, heightOptions),
                onSelect = {
                    onChange(current.copy(maxVideoHeight = QualityLimitOverride.Inherit))
                    showHeightDialog = false
                }
            )
            LevelOption(
                level = 1,
                text = stringResource(R.string.settings_quality_cap_no_additional_limit),
                currentLevel = heightOverrideIndex(current.maxVideoHeight, heightOptions),
                onSelect = {
                    onChange(current.copy(maxVideoHeight = QualityLimitOverride.NoAdditionalLimit))
                    showHeightDialog = false
                }
            )
            heightOptions.forEachIndexed { index, height ->
                LevelOption(
                    level = index + 2,
                    text = formatQualityCapLabel(
                        height,
                        context.getString(R.string.settings_quality_cap_auto)
                    ),
                    currentLevel = heightOverrideIndex(current.maxVideoHeight, heightOptions),
                    onSelect = {
                        onChange(current.copy(maxVideoHeight = QualityLimitOverride.Maximum(height)))
                        showHeightDialog = false
                    }
                )
            }
        }
    }

    if (showBitrateDialog) {
        PremiumSelectionDialog(
            title = stringResource(R.string.settings_quality_custom_bitrate),
            onDismiss = { showBitrateDialog = false }
        ) {
            LevelOption(
                level = 0,
                text = context.getString(
                    R.string.settings_remote_selection_global_default,
                    globalProfileLimit.maxVideoBitrateBps?.let {
                        context.getString(R.string.settings_quality_cap_mbps, (it / 1_000_000L).toInt())
                    } ?: context.getString(R.string.settings_quality_cap_no_limit)
                ),
                currentLevel = bitrateOverrideIndex(current.maxVideoBitrateBps, bitrateOptions),
                onSelect = {
                    onChange(current.copy(maxVideoBitrateBps = QualityLimitOverride.Inherit))
                    showBitrateDialog = false
                }
            )
            LevelOption(
                level = 1,
                text = stringResource(R.string.settings_quality_cap_no_additional_limit),
                currentLevel = bitrateOverrideIndex(current.maxVideoBitrateBps, bitrateOptions),
                onSelect = {
                    onChange(current.copy(maxVideoBitrateBps = QualityLimitOverride.NoAdditionalLimit))
                    showBitrateDialog = false
                }
            )
            bitrateOptions.forEachIndexed { index, bitrate ->
                LevelOption(
                    level = index + 2,
                    text = context.getString(
                        R.string.settings_quality_cap_mbps,
                        (bitrate / 1_000_000L).toInt()
                    ),
                    currentLevel = bitrateOverrideIndex(current.maxVideoBitrateBps, bitrateOptions),
                    onSelect = {
                        onChange(current.copy(maxVideoBitrateBps = QualityLimitOverride.Maximum(bitrate)))
                        showBitrateDialog = false
                    }
                )
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.settings_quality_profile_title),
            style = MaterialTheme.typography.titleMedium,
            color = OnBackground,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_quality_custom_height),
            value = heightOverrideLabel(
                current.maxVideoHeight,
                globalProfileLimit,
                context.getString(R.string.settings_quality_cap_auto),
                context.getString(R.string.settings_remote_selection_global_default),
                context.getString(R.string.settings_quality_cap_no_additional_limit)
            ),
            onClick = { showHeightDialog = true }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_quality_custom_bitrate),
            value = bitrateOverrideLabel(
                current.maxVideoBitrateBps,
                globalProfileLimit,
                context.getString(R.string.settings_remote_selection_global_default),
                context.getString(R.string.settings_quality_cap_no_additional_limit),
                context.getString(R.string.settings_quality_cap_no_limit),
                context.getString(R.string.settings_quality_cap_mbps)
            ),
            onClick = { showBitrateDialog = true }
        )
    }
}

private fun heightOverrideIndex(
    override: QualityLimitOverride<Int>,
    options: List<Int>
): Int = when (override) {
    QualityLimitOverride.Inherit -> 0
    QualityLimitOverride.NoAdditionalLimit -> 1
    is QualityLimitOverride.Maximum -> options.indexOf(override.value).takeIf { it >= 0 }?.plus(2) ?: 0
}

private fun bitrateOverrideIndex(
    override: QualityLimitOverride<Long>,
    options: List<Long>
): Int = when (override) {
    QualityLimitOverride.Inherit -> 0
    QualityLimitOverride.NoAdditionalLimit -> 1
    is QualityLimitOverride.Maximum -> options.indexOf(override.value).takeIf { it >= 0 }?.plus(2) ?: 0
}

private fun heightOverrideLabel(
    override: QualityLimitOverride<Int>,
    globalProfileLimit: PlaybackQualityLimit,
    autoLabel: String,
    globalDefaultFormat: String,
    noAdditionalLimitLabel: String
): String = when (override) {
    QualityLimitOverride.Inherit -> inheritLabel(
        globalDefaultFormat,
        formatQualityCapLabel(globalProfileLimit.maxVideoHeight, autoLabel)
    )
    QualityLimitOverride.NoAdditionalLimit -> noAdditionalLimitLabel
    is QualityLimitOverride.Maximum -> formatQualityCapLabel(override.value, autoLabel)
}

private fun bitrateOverrideLabel(
    override: QualityLimitOverride<Long>,
    globalProfileLimit: PlaybackQualityLimit,
    globalDefaultFormat: String,
    noAdditionalLimitLabel: String,
    noLimitLabel: String,
    bitrateFormat: String
): String = when (override) {
    QualityLimitOverride.Inherit -> inheritLabel(
        globalDefaultFormat,
        globalProfileLimit.maxVideoBitrateBps?.let { bitrateLabel(bitrateFormat, it) } ?: noLimitLabel
    )
    QualityLimitOverride.NoAdditionalLimit -> noAdditionalLimitLabel
    is QualityLimitOverride.Maximum -> bitrateLabel(bitrateFormat, override.value)
}

private fun inheritLabel(format: String, value: String): String =
    String.format(java.util.Locale.getDefault(), format, value)

private fun bitrateLabel(format: String, bitrateBps: Long): String =
    String.format(java.util.Locale.getDefault(), format, (bitrateBps / 1_000_000L).toInt())
