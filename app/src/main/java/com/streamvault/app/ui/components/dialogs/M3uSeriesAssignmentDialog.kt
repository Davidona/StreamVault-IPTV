package com.streamvault.app.ui.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.domain.repository.M3uSeriesAssignment

@Composable
fun M3uSeriesAssignmentDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (M3uSeriesAssignment) -> Unit
) {
    var seriesName by remember { mutableStateOf(initialTitle.trim()) }
    var season by remember { mutableStateOf("1") }
    var episode by remember { mutableStateOf("1") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.55f),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.m3u_move_to_series),
                    style = MaterialTheme.typography.headlineSmall,
                    color = OnSurface
                )
                Text(
                    text = stringResource(R.string.m3u_series_name),
                    color = OnSurface
                )
                M3uTextField(seriesName, { seriesName = it }, stringResource(R.string.m3u_series_name))
                M3uTextField(season, { season = it.filter(Char::isDigit) }, stringResource(R.string.m3u_season_number))
                M3uTextField(episode, { episode = it.filter(Char::isDigit) }, stringResource(R.string.m3u_episode_number))
                Button(
                    onClick = {
                        if (seriesName.isNotBlank()) {
                            onConfirm(
                                M3uSeriesAssignment(
                                    seriesName = seriesName.trim(),
                                    seasonNumber = season.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                    episodeNumber = episode.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                )
                            )
                        }
                    },
                    enabled = seriesName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black
                    )
                ) {
                    Text(stringResource(R.string.m3u_classify))
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        contentColor = OnSurface
                    )
                ) {
                    Text(stringResource(R.string.category_options_cancel))
                }
            }
        }
    }
}

@Composable
private fun M3uTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = OnSurface,
            unfocusedTextColor = OnSurface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = OnSurface.copy(alpha = 0.4f),
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = OnSurface.copy(alpha = 0.7f)
        )
    )
}
