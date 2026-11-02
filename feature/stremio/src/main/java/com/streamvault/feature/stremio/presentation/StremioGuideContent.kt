package com.streamvault.feature.stremio.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.components.dialogs.PinDialog
import kotlinx.coroutines.launch
import com.streamvault.domain.model.stremio.StremioMetaPreview
import com.streamvault.domain.model.stremio.toPreview
import com.streamvault.feature.stremio.R
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun StremioGuideContent(
    onBack: () -> Unit,
    onOpenItem: (Long, StremioMetaPreview) -> Unit,
    viewModel: StremioGuideViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingUnlock by remember { mutableStateOf<String?>(null) }
    var pinFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(onClick = onBack) { Text(stringResource(R.string.stremio_browse)) }
            TvButton(onClick = { viewModel.setDate(state.date.minusDays(1)) }) { Text(state.date.minusDays(1).toString()) }
            Text(state.date.toString())
            TvButton(onClick = { viewModel.setDate(state.date.plusDays(1)) }) { Text(state.date.plusDays(1).toString()) }
            TvButton(onClick = { viewModel.setDate(LocalDate.now()) }) { Text(stringResource(R.string.stremio_today)) }
            TvButton(onClick = viewModel::load) { Text(stringResource(R.string.stremio_refresh)) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.catalogs, key = { it.key }) { catalog ->
                TvButton(onClick = { if (catalog.locked) pendingUnlock = catalog.key else viewModel.select(catalog.key) }) {
                    Text(listOfNotNull(catalog.catalog.name ?: catalog.catalog.id, catalog.addonName).joinToString(" · "))
                }
            }
        }
        if (state.loading) CircularProgressIndicator()
        if (state.failed) Text(stringResource(R.string.stremio_error_catalog_load))
        if (!state.loading && state.channels.isEmpty()) Text(stringResource(R.string.stremio_empty_catalog))
        val addonId = state.catalogs.firstOrNull { it.key == state.selectedKey }?.addonId
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.channels, key = { "${it.type.typeName}:${it.id}" }) { channel ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TvButton(onClick = { addonId?.let { onOpenItem(it, channel.toPreview()) } }) {
                        Text(channel.name)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(channel.videos, key = { "${it.id}:${it.startTime}" }) { programme ->
                            val start = Instant.parse(programme.startTime).atZone(zone)
                            val end = Instant.parse(programme.endTime).atZone(zone)
                            TvButton(onClick = { addonId?.let { onOpenItem(it, channel.toPreview()) } }) {
                                Column(Modifier.width(240.dp)) {
                                    Text("${timeFormat.format(start)} – ${timeFormat.format(end)}")
                                    Text(programme.title ?: programme.id, maxLines = 2)
                                    programme.overview?.let { Text(it, maxLines = 3) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    pendingUnlock?.let { key ->
        PinDialog(
            title = stringResource(R.string.pin_dialog_title),
            cancelLabel = stringResource(R.string.pin_dialog_cancel),
            error = if (pinFailed) stringResource(R.string.movies_incorrect_pin) else null,
            onDismissRequest = { pendingUnlock = null; pinFailed = false },
            onPinEntered = { pin ->
                scope.launch {
                    if (viewModel.unlock(key, pin)) { pendingUnlock = null; pinFailed = false }
                    else pinFailed = true
                }
            }
        )
    }

}
