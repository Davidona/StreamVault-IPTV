package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.domain.settings.SubtitleLanguageOrdering
import com.streamvault.feature.settings.R

@Composable
fun PinnedSubtitleLanguagesDialog(
    currentLanguages: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val options = remember { subtitleLanguageOptions() }
    var draft by remember(currentLanguages) {
        mutableStateOf(SubtitleLanguageOrdering.normalizePinned(currentLanguages))
    }
    val displayOptions = remember(draft, options) {
        val pinned = draft.map { tag ->
            options.firstOrNull { it.tag == tag } ?: AppLanguageOption(tag, subtitleLanguageLabel(tag))
        }
        pinned + options.filterNot { it.tag in draft }
    }

    PremiumDialog(
        title = stringResource(R.string.settings_pinned_subtitle_languages),
        subtitle = stringResource(R.string.settings_pinned_subtitle_languages_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.58f,
        bodyHeightFraction = 0.62f,
        content = {
            Text(
                text = stringResource(R.string.settings_pinned_subtitle_languages_note),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            displayOptions.forEach { option ->
                val pinnedIndex = draft.indexOf(option.tag)
                PinnedSubtitleLanguageRow(
                    option = option,
                    pinnedIndex = pinnedIndex,
                    pinnedCount = draft.size,
                    onToggle = { pin ->
                        draft = if (pin) {
                            SubtitleLanguageOrdering.normalizePinned(draft + option.tag)
                        } else {
                            draft - option.tag
                        }
                    },
                    onMoveUp = { draft = draft.movedBy(option.tag, -1) },
                    onMoveDown = { draft = draft.movedBy(option.tag, 1) }
                )
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
            PremiumDialogFooterButton(
                label = stringResource(R.string.action_save_order),
                emphasized = true,
                onClick = { onSave(draft) }
            )
        }
    )
}

@Composable
private fun PinnedSubtitleLanguageRow(
    option: AppLanguageOption,
    pinnedIndex: Int,
    pinnedCount: Int,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val pinned = pinnedIndex >= 0
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceElevated, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface
                )
                Text(
                    text = if (pinned) {
                        stringResource(R.string.settings_pinned_subtitle_languages_position, pinnedIndex + 1)
                    } else {
                        stringResource(R.string.settings_pinned_subtitle_languages_not_pinned)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pinned) Primary else OnSurfaceDim
                )
            }
            TvButton(onClick = { onToggle(!pinned) }) {
                Text(
                    stringResource(
                        if (pinned) {
                            R.string.settings_pinned_subtitle_languages_unpin
                        } else {
                            R.string.settings_pinned_subtitle_languages_pin
                        }
                    )
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(
                    enabled = pinned && pinnedIndex > 0,
                    onClick = onMoveUp
                ) {
                    Text(stringResource(R.string.settings_top_navigation_move_up))
                }
                TvButton(
                    enabled = pinned && pinnedIndex < pinnedCount - 1,
                    onClick = onMoveDown
                ) {
                    Text(stringResource(R.string.settings_top_navigation_move_down))
                }
            }
        }
    }
}

private fun List<String>.movedBy(tag: String, delta: Int): List<String> {
    val index = indexOf(tag)
    val target = index + delta
    if (index < 0 || target < 0 || target > lastIndex) return this
    return toMutableList().also { list ->
        list[index] = list[target]
        list[target] = tag
    }
}
