package com.streamvault.app.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.theme.Primary

internal fun formatPlaybackTimerMinutesLabel(minutes: Int, context: Context): String {
    return if (minutes <= 0) {
        context.getString(R.string.settings_timer_off)
    } else {
        context.resources.getQuantityString(R.plurals.settings_timer_minutes, minutes, minutes)
    }
}

@Composable
internal fun PlaybackTimerPresetDialog(
    title: String,
    selectedMinutes: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    val context = LocalContext.current
    val options = remember { listOf(0, 15, 30, 45, 60, 90, 120) }

    PremiumSelectionDialog(
        title = title,
        onDismiss = onDismiss
    ) {
        options.forEachIndexed { index, minutes ->
            LevelOption(
                level = index,
                text = formatPlaybackTimerMinutesLabel(minutes, context),
                currentLevel = if (selectedMinutes == minutes) index else -1,
                onSelect = { onSelect(minutes) }
            )
        }
    }
}

internal fun formatAudioVideoOffsetLabel(offsetMs: Int): String = when {
    offsetMs > 0 -> "+$offsetMs ms"
    offsetMs < 0 -> "$offsetMs ms"
    else -> "0 ms"
}

@Composable
internal fun AudioVideoOffsetValueDialog(
    title: String,
    subtitle: String,
    initialValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue.coerceIn(-2_000, 2_000)) }

    PremiumDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        widthFraction = 0.42f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = formatAudioVideoOffsetLabel(value),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PremiumDialogFooterButton(
                        label = "-50",
                        onClick = { value = (value - 50).coerceAtLeast(-2_000) }
                    )
                    PremiumDialogFooterButton(
                        label = "+50",
                        onClick = { value = (value + 50).coerceAtMost(2_000) }
                    )
                    PremiumDialogFooterButton(
                        label = stringResource(R.string.settings_reset),
                        onClick = { value = 0 }
                    )
                }
            }
        },
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = onDismiss
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_save),
                    onClick = { onConfirm(value) }
                )
            }
        }
    )
}

@Composable
internal fun SimpleTextValueDialog(
    title: String,
    subtitle: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    PremiumDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        widthFraction = 0.58f,
        content = {
            EpgSourceTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = initialValue
            )
        },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = onDismiss
                )
                PremiumDialogActionButton(
                    label = stringResource(R.string.settings_save),
                    onClick = { onConfirm(value.trim()) }
                )
            }
        }
    )
}
