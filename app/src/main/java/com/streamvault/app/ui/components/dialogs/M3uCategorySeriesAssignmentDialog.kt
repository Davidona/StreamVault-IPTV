package com.streamvault.app.ui.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.streamvault.domain.repository.M3uCategoryItem
import com.streamvault.domain.repository.M3uSeriesAssignment

@Composable
fun M3uCategorySeriesAssignmentDialog(
    categoryName: String,
    items: List<M3uCategoryItem>,
    onDismiss: () -> Unit,
    onConfirm: (Map<Long, M3uSeriesAssignment>) -> Unit
) {
    var drafts by remember(items) {
        mutableStateOf(
            items.associate { item ->
                item.channelId to M3uAssignmentDraft(
                    seriesName = item.suggestedAssignment.seriesName,
                    seasonNumber = item.suggestedAssignment.seasonNumber.toString(),
                    episodeNumber = (item.suggestedAssignment.episodeNumber ?: 0).toString()
                )
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .fillMaxHeight(0.86f),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.m3u_series_review_title, categoryName),
                    style = MaterialTheme.typography.headlineSmall,
                    color = OnSurface
                )
                Text(
                    text = stringResource(R.string.m3u_series_review_subtitle),
                    color = OnSurface.copy(alpha = 0.75f)
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(
                        items = items,
                        key = { it.channelId },
                        contentType = { "series_assignment" }
                    ) { item ->
                        val draft = drafts[item.channelId] ?: return@items
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.title, color = OnSurface)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AssignmentField(
                                    value = draft.seriesName,
                                    label = stringResource(R.string.m3u_series_name),
                                    onValueChange = { value ->
                                        drafts = drafts + (item.channelId to draft.copy(seriesName = value))
                                    },
                                    modifier = Modifier.weight(1.8f)
                                )
                                AssignmentField(
                                    value = draft.seasonNumber,
                                    label = stringResource(R.string.m3u_season_number),
                                    onValueChange = { value ->
                                        drafts = drafts + (item.channelId to draft.copy(seasonNumber = value.filter(Char::isDigit)))
                                    },
                                    modifier = Modifier.weight(0.7f)
                                )
                                AssignmentField(
                                    value = draft.episodeNumber,
                                    label = stringResource(R.string.m3u_episode_number),
                                    onValueChange = { value ->
                                        drafts = drafts + (item.channelId to draft.copy(episodeNumber = value.filter(Char::isDigit)))
                                    },
                                    modifier = Modifier.weight(0.7f)
                                )
                            }
                            if (draft.episodeNumber.toIntOrNull() == 0) {
                                Text(
                                    text = stringResource(R.string.m3u_episode_unresolved),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            onConfirm(
                                items.associate { item ->
                                    val draft = drafts.getValue(item.channelId)
                                    item.channelId to M3uSeriesAssignment(
                                        seriesName = draft.seriesName.trim(),
                                        seasonNumber = draft.seasonNumber.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                        episodeNumber = draft.episodeNumber.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                                        episodeTitle = item.title
                                    )
                                }
                            )
                        },
                        enabled = items.isNotEmpty() && drafts.values.all { it.seriesName.isNotBlank() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.Black
                        )
                    ) { Text(stringResource(R.string.m3u_classify)) }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            contentColor = OnSurface
                        )
                    ) { Text(stringResource(R.string.category_options_cancel)) }
                }
            }
        }
    }
}

private data class M3uAssignmentDraft(
    val seriesName: String,
    val seasonNumber: String,
    val episodeNumber: String
)

@Composable
private fun AssignmentField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
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
