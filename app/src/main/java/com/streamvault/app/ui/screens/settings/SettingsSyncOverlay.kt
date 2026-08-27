package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.remember
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.core.ui.progress.extractProgressFraction
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary

@Composable
internal fun SyncingOverlay(
    isSyncing: Boolean,
    providerName: String? = null,
    progress: String? = null,
    sectionLabel: String? = null,
    startedAt: Long = 0L,
    onCancel: (() -> Unit)? = null
) {
    if (!isSyncing) return

    // Dialog creates a separate window — on Android TV this traps all focus
    // within the dialog, preventing D-pad from reaching UI behind it.
    Dialog(
        onDismissRequest = { /* block dismiss — sync in progress */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        val cancelFocusRequester = remember { FocusRequester() }
        LaunchedEffect(onCancel) {
            if (onCancel != null) {
                cancelFocusRequester.requestFocus()
            }
        }

        // Elapsed time counter
        val elapsedSnapshot = remember { mutableStateOf(0L) }
        LaunchedEffect(startedAt) {
            elapsedSnapshot.value = 0L
            if (startedAt > 0L) {
                while (true) {
                    kotlinx.coroutines.delay(1000L)
                    elapsedSnapshot.value = (System.currentTimeMillis() - startedAt) / 1000L
                }
            }
        }
        val elapsedText = remember(elapsedSnapshot.value) {
            val seconds = elapsedSnapshot.value
            val mins = seconds / 60L
            val secs = seconds % 60L
            if (mins > 0L) "${mins}m ${secs}s" else "${secs}s"
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            val fraction = progress?.let { extractProgressFraction(it) }
            val animatedFraction by animateFloatAsState(
                targetValue = fraction ?: 0f,
                animationSpec = tween(durationMillis = 400),
                label = "syncFraction"
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.widthIn(min = 360.dp, max = 520.dp)
            ) {
                CircularProgressIndicator(color = Primary)

                // Section label
                Text(
                    text = sectionLabel ?: stringResource(R.string.settings_syncing_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface
                )

                // Provider name
                if (providerName != null) {
                    Text(
                        text = providerName,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }

                // Detail progress message
                progress?.let { message ->
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { animatedFraction },
                            color = Primary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(
                            color = Primary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }

                // Elapsed time
                if (startedAt > 0L) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_sync_elapsed, elapsedText),
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceDim
                        )
                    }
                }

                // Cancel button — the only interactive element
                if (onCancel != null) {
                    OutlinedButton(
                        onClick = {
                            cancelFocusRequester.requestFocus()
                            onCancel()
                        },
                        modifier = Modifier.focusRequester(cancelFocusRequester)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_cancel),
                            color = Primary
                        )
                    }
                }
            }
        }
    }
}
