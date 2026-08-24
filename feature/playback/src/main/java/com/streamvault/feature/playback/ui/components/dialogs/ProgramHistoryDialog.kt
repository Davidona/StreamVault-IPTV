package com.streamvault.feature.playback.ui.components.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.streamvault.core.ui.device.rememberIsTelevisionDevice
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.streamvault.core.ui.time.LocalUiTimeFormat
import com.streamvault.core.ui.time.createTimeFormatter
import com.streamvault.feature.playback.R
import com.streamvault.domain.model.Program
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvIconButton

@Composable
fun ProgramHistoryDialog(
    programs: List<Program>,
    onDismiss: () -> Unit,
    onProgramSelect: (Program) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    var canInteract by remember { mutableStateOf(false) }
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val locale = Locale.getDefault()
    val zoneId = remember { ZoneId.systemDefault() }
    val appTimeFormat = LocalUiTimeFormat.current
    val timeFormat = remember(appTimeFormat, locale) { appTimeFormat.createTimeFormatter(locale) }
    val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE, MMM d", locale) }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        delay(500)
        canInteract = true
    }

    Dialog(onDismissRequest = { if (canInteract) onDismiss() }) {
        val dialogContent: @Composable (Modifier) -> Unit = { resolvedModifier ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = resolvedModifier
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.player_archive_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    TvIconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.add_group_close_cd)
                        )
                    }
                }

                if (programs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.player_no_archive),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val unfocusedBorderColor = Color.White.copy(alpha = 0.12f)
                    val focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = programs,
                            key = { "${it.channelId}:${it.startTime}:${it.endTime}:${it.id}" },
                            contentType = { "program" }
                        ) { program ->
                            var isFocused by remember { mutableStateOf(false) }

                            TvClickableSurface(
                                onClick = { if (canInteract) onProgramSelect(program) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { isFocused = it.isFocused }
                                    .then(
                                        if (program == programs.first())
                                            Modifier.focusRequester(focusRequester)
                                        else Modifier
                                    )
                                    .border(
                                        width = if (isFocused) 2.dp else 1.dp,
                                        color = if (isFocused) {
                                            Color.White
                                        } else {
                                            unfocusedBorderColor
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = focusedContainerColor
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = program.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.program_date_time_range,
                                                Instant.ofEpochMilli(program.startTime).atZone(zoneId).format(dayFormat),
                                                Instant.ofEpochMilli(program.startTime).atZone(zoneId).format(timeFormat),
                                                Instant.ofEpochMilli(program.endTime).atZone(zoneId).format(timeFormat)
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (program.hasArchive) {
                                        Text(
                                            text = stringResource(R.string.player_archive_badge),
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }

        if (isTelevisionDevice) {
            dialogContent(
                Modifier
                    .width(450.dp)
                    .heightIn(max = 600.dp)
                    .padding(16.dp)
            )
        } else {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val dialogModifier = when {
                    maxWidth < 700.dp -> Modifier
                        .fillMaxWidth(0.9f)
                        .heightIn(max = 600.dp)
                        .padding(16.dp)
                    maxWidth < 1280.dp -> Modifier
                        .fillMaxWidth(0.58f)
                        .heightIn(max = 650.dp)
                        .padding(16.dp)
                    else -> Modifier
                        .width(450.dp)
                        .heightIn(max = 600.dp)
                        .padding(16.dp)
                }
                dialogContent(dialogModifier)
            }
        }
    }
}
