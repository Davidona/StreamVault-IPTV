package com.streamvault.feature.stremio.presentation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.streamvault.feature.stremio.R
import com.streamvault.domain.model.stremio.StremioMetaPreview

@Composable
internal fun StremioCatalogBrowseContent(
    modifier: Modifier = Modifier,
    viewModel: StremioCatalogBrowseViewModel = hiltViewModel(),
    onOpenItem: (Long, StremioMetaPreview) -> Unit = { _, _ -> }
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    when {
        state.loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.rows.isEmpty() -> Box(
            modifier = modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.stremio_browse_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(items = state.rows, key = { "${it.key.addonId}:${it.key.catalogKey}" }) { row ->
                CatalogRow(row, viewModel, onOpenItem)
            }
        }
    }
}

@Composable
private fun CatalogRow(
    row: StremioCatalogRowState,
    viewModel: StremioCatalogBrowseViewModel,
    onOpenItem: (Long, StremioMetaPreview) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(
                text = row.catalog.name?.takeIf { it.isNotBlank() } ?: row.catalog.id,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${row.addonName} · ${row.catalog.type.typeName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (row.supportsSearch) {
            OutlinedTextField(
                value = row.search,
                onValueChange = { viewModel.setSearch(row.key, it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.stremio_search_catalog)) },
                singleLine = true,
                enabled = row.blockedReason == null
            )
        }

        if (row.supportsGenre) {
            if (row.genreOptions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!row.genreRequired) {
                        item(key = "all") {
                            FilterChip(
                                selected = row.genre.isBlank(),
                                onClick = { viewModel.setGenre(row.key, "") },
                                label = { Text(stringResource(R.string.stremio_all_genres)) }
                            )
                        }
                    }
                    items(row.genreOptions, key = { it }) { genre ->
                        FilterChip(
                            selected = row.genre == genre,
                            onClick = { viewModel.setGenre(row.key, genre) },
                            label = { Text(genre) }
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = row.genre,
                    onValueChange = { viewModel.setGenre(row.key, it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.stremio_filter_genre)) },
                    singleLine = true,
                    enabled = row.blockedReason == null
                )
            }
        }

        row.blockedReason?.let { reason ->
            Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        row.waitingForInput?.let {
            val waiting = when {
                row.searchRequired && row.search.isBlank() -> stringResource(R.string.stremio_catalog_waiting_search)
                row.genreRequired && row.genre.isBlank() -> stringResource(R.string.stremio_catalog_waiting_genre)
                else -> it
            }
            Text(waiting, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        row.error?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { viewModel.retry(row.key) }) {
                    Text(stringResource(R.string.stremio_retry))
                }
            }
        }

        if (row.loading && row.metas.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (row.metas.isEmpty() && row.blockedReason == null &&
            row.waitingForInput == null && row.error == null
        ) {
            Text(
                text = stringResource(R.string.stremio_empty_catalog),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (row.metas.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(
                    items = row.metas,
                    key = { "${it.type.typeName}:${it.id}" }
                ) { meta ->
                    CatalogMetaCard(meta) { onOpenItem(row.key.addonId, meta) }
                }
            }
        }

        if (row.canLoadMore || row.loadingMore) {
            Button(
                onClick = { viewModel.loadMore(row.key) },
                enabled = !row.loadingMore && !row.loading
            ) {
                if (row.loadingMore) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.stremio_load_more))
            }
        }
    }
}

@Composable
private fun CatalogMetaCard(meta: StremioMetaPreview, onClick: () -> Unit) {
    var isFocused by remember(meta.type, meta.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val description = stringResource(
        R.string.stremio_catalog_item_a11y,
        meta.name,
        meta.releaseInfo.orEmpty()
    )
    Card(
        modifier = Modifier
            .width(144.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .semantics { contentDescription = description },
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) colors.surfaceVariant else colors.surface
        ),
        border = if (isFocused) BorderStroke(2.dp, colors.primary) else null
    ) {
        Column {
            AsyncImage(
                model = meta.poster,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(204.dp),
                contentScale = ContentScale.Crop
            )
            Text(
                text = meta.name,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
