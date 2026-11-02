package com.streamvault.feature.stremio.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.design.BrowseColors
import com.streamvault.domain.model.stremio.*
import com.streamvault.feature.stremio.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** App composition injects this shelf without coupling Catalog to Stremio. */
@Composable
fun StremioHomeShelf(onOpen: (Long, StremioPlaybackHistory) -> Unit,
    onDetails: (Long, StremioMetaPreview) -> Unit, viewModel: StremioViewingViewModel = hiltViewModel()): Boolean {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshIfStale() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAll by remember { mutableStateOf(false) }
    if (showAll) {
        PremiumDialog(title = stringResource(R.string.stremio_title), onDismissRequest = { showAll = false },
            content = { Box(Modifier.heightIn(max = 520.dp)) { StremioViewingContent(onBack = { showAll = false }, onOpen, onDetails, viewModel, initialTab = 1) } },
            footer = { PremiumDialogFooterButton(label = stringResource(R.string.stremio_cancel), onClick = { showAll = false }) })
    }
    if (state.history.isEmpty() && state.watching.isEmpty() && state.releases.isEmpty()) return false
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (state.watching.isEmpty()) R.string.stremio_recently_watched else R.string.stremio_continue_watching))
            TvButton(onClick = { showAll = true }) { Text(stringResource(R.string.stremio_recently_watched)) }
        }
        ViewingRow(state.watching.ifEmpty { state.history.take(8) }, viewModel, onOpen, onDetails)
    }
    return true
}

@Composable
internal fun StremioViewingContent(onBack: () -> Unit, onOpen: (Long, StremioPlaybackHistory) -> Unit,
    onDetails: (Long, StremioMetaPreview) -> Unit, viewModel: StremioViewingViewModel = hiltViewModel(), initialTab: Int = 0) {
    BackHandler(onBack = onBack)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshIfStale() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(initialTab) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TvButton(onClick = onBack) { Text(stringResource(R.string.stremio_browse)) } }
            items(listOf(R.string.stremio_continue_watching, R.string.stremio_recently_watched, R.string.stremio_release_calendar).withIndex().toList()) { (index,label) ->
                TvButton(onClick = { tab = index }) { Text(stringResource(label)) }
            }
            item { TvButton(onClick = viewModel::refresh) { Text(stringResource(R.string.stremio_refresh)) } }
        }
        if (state.failed) Text(stringResource(R.string.stremio_error_generic))
        if (tab == 2) {
            if (state.releases.isEmpty()) Text(stringResource(R.string.stremio_empty_catalog))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.releases, key = { "${it.addonId}:${it.meta.id}:${it.video.id}" }) { row ->
                    TvButton(onClick = { onDetails(row.addonId, row.meta) }, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(row.meta.name)
                            Text(row.video.title ?: row.video.id)
                            row.video.releaseInstant()?.atZone(ZoneId.systemDefault())?.let {
                                Text(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(it))
                            }
                        }
                    }
                }
            }
        } else {
            val rows = if (tab == 0) state.watching else state.history
            if (rows.isEmpty()) Text(stringResource(R.string.stremio_empty_catalog))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.history.contentKey }) { row -> ViewingCardContent(row, viewModel, onOpen, onDetails) }
            }
        }
    }
}

@Composable
private fun ViewingRow(rows: List<ViewingCard>, viewModel: StremioViewingViewModel,
    onOpen: (Long, StremioPlaybackHistory) -> Unit, onDetails: (Long, StremioMetaPreview) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(rows, key = { it.history.contentKey }) { row ->
            Box(Modifier.width(270.dp)) { ViewingCardContent(row, viewModel, onOpen, onDetails) }
        }
    }
}

@Composable
private fun ViewingCardContent(row: ViewingCard, viewModel: StremioViewingViewModel,
    onOpen: (Long, StremioPlaybackHistory) -> Unit, onDetails: (Long, StremioMetaPreview) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    TvButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            AsyncImage(row.preview.poster ?: row.history.posterUrl, contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(100.dp).background(BrowseColors.Surface), contentScale = ContentScale.Crop)
            Text(row.history.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .format(java.time.Instant.ofEpochMilli(row.history.lastWatchedAt).atZone(ZoneId.systemDefault())))
            if (row.upNext) Text(stringResource(R.string.stremio_up_next))
            if (row.history.watched) Text(stringResource(R.string.stremio_status_watched))
            if (row.newEpisodes > 0) Text(stringResource(R.string.stremio_new_episodes, row.newEpisodes))
            if (row.history.totalDurationMs > 0 && !row.history.watched) {
                LinearProgressIndicator(progress = { (row.history.resumePositionMs.toFloat() / row.history.totalDurationMs).coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.stremio_minutes_left, (((row.history.totalDurationMs-row.history.resumePositionMs).coerceAtLeast(0)+59_999)/60_000).toInt()))
            }
        }
    }
    if (menu) PremiumDialog(title = row.preview.name, onDismissRequest = { menu = false }, content = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(onClick = { menu = false; onOpen(row.addonId,row.history) }) { Text(stringResource(if (row.history.watched || row.upNext) R.string.stremio_item_play else R.string.stremio_resume)) }
            TvButton(onClick = { menu = false; viewModel.startOver(row) { onOpen(row.addonId,row.history) } }) { Text(stringResource(R.string.stremio_start_over)) }
            TvButton(onClick = { menu = false; onDetails(row.addonId,row.preview) }) { Text(stringResource(R.string.stremio_details)) }
            TvButton(onClick = { menu = false; viewModel.dismiss(row) }) { Text(stringResource(R.string.stremio_dismiss)) }
        }
    }, footer = { PremiumDialogFooterButton(label = stringResource(R.string.stremio_cancel), onClick = { menu = false }) })
}
