package com.streamvault.feature.stremio.presentation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.streamvault.core.ui.components.shell.AppSectionHeader
import com.streamvault.core.ui.components.SearchInput
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.design.BrowseColors as AppColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.browsePanel
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.TvPrimaryButton
import com.streamvault.feature.stremio.R
import com.streamvault.domain.model.stremio.StremioMetaPreview
import kotlinx.coroutines.yield

@Composable
internal fun StremioCatalogBrowseContent(
    modifier: Modifier = Modifier,
    viewModel: StremioCatalogBrowseViewModel = hiltViewModel(),
    onOpenItem: (Long, StremioMetaPreview) -> Unit = { _, _ -> },
    onManageAddons: () -> Unit = {}
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val selected = state.rows.firstOrNull { it.key == state.selectedCatalogKey }
    var showCatalogPicker by remember { mutableStateOf(false) }
    var showGenrePicker by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    var lastFocusedMetaId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastGridQueryKey by rememberSaveable { mutableStateOf<String?>(null) }
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    if (state.rows.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val message = when (state.emptyReason) {
                StremioBrowseEmptyReason.NO_INSTALLED_ADDONS -> R.string.stremio_browse_no_installed_addons
                StremioBrowseEmptyReason.NO_ENABLED_ADDONS -> R.string.stremio_browse_no_enabled_addons
                StremioBrowseEmptyReason.NO_ENABLED_CATALOGS -> R.string.stremio_browse_no_enabled_catalogs
                null -> R.string.stremio_browse_empty
            }
            Text(stringResource(message), style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
            Spacer(Modifier.height(12.dp))
            TvButton(onClick = onManageAddons) { Text(stringResource(R.string.stremio_manage_addons)) }
        }
        return
    }
    if (selected == null) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.stremio_no_supported_catalogs), color = AppColors.TextSecondary)
            Spacer(Modifier.height(12.dp))
            TvButton(onClick = onManageAddons) { Text(stringResource(R.string.stremio_manage_addons)) }
        }
        return
    }

    val gridQueryKey = "${selected.key.addonId}:${selected.key.catalogKey}:${selected.search.trim()}:${selected.genre.trim()}"
    LaunchedEffect(gridQueryKey) {
        if (lastGridQueryKey != null && lastGridQueryKey != gridQueryKey) gridState.scrollToItem(0)
        lastGridQueryKey = gridQueryKey
    }
    LaunchedEffect(selected.key, lastFocusedMetaId, selected.metas) {
        val focusedId = lastFocusedMetaId ?: return@LaunchedEffect
        val focusedIndex = selected.metas.indexOfFirst { it.id == focusedId }
        if (focusedIndex < 0) return@LaunchedEffect
        gridState.scrollToItem(focusedIndex)
        yield()
        itemFocusRequesters[focusedId]?.let { requester -> runCatching { requester.requestFocus() } }
    }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AppSectionHeader(
                    title = selected.catalog.name?.takeIf(String::isNotBlank) ?: selected.catalog.id,
                    subtitle = "${selected.addonName} · ${selected.catalog.type.typeName}"
                )
            }
            TvButton(onClick = { showCatalogPicker = true }) {
                Text(stringResource(R.string.stremio_choose_catalog))
            }
            if (selected.supportsGenre && selected.genreOptions.isNotEmpty()) {
                TvButton(onClick = { showGenrePicker = true }) {
                    Text(selected.genre.ifBlank { stringResource(R.string.stremio_all_genres) })
                }
            }
            if (selected.pageCount > 0 || selected.error != null) {
                TvButton(onClick = { viewModel.refresh(selected.key) }, enabled = !selected.loading) {
                    Text(stringResource(R.string.stremio_refresh))
                }
            }
        }

        if (selected.supportsSearch) {
            SearchInput(
                value = selected.search,
                onValueChange = { viewModel.setSearch(selected.key, it) },
                placeholder = stringResource(R.string.stremio_search_catalog),
                enabled = selected.blockedReason == null,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (selected.supportsGenre && selected.genreOptions.isEmpty()) {
            SearchInput(
                value = selected.genre,
                onValueChange = { viewModel.setGenre(selected.key, it) },
                placeholder = stringResource(R.string.stremio_filter_genre),
                enabled = selected.blockedReason == null,
                modifier = Modifier.fillMaxWidth()
            )
        }

        selected.blockedReason?.let { CatalogMessage(message = it, error = true) }
        selected.waitingForInput?.let {
            val waiting = when {
                selected.searchRequired && selected.search.isBlank() -> stringResource(R.string.stremio_catalog_waiting_search)
                selected.genreRequired && selected.genre.isBlank() -> stringResource(R.string.stremio_catalog_waiting_genre)
                else -> it
            }
            CatalogMessage(message = waiting)
        }
        selected.error?.let { error ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(error, modifier = Modifier.weight(1f), color = AppColors.Live, style = MaterialTheme.typography.bodySmall)
                TvButton(onClick = { viewModel.retry(selected.key) }) { Text(stringResource(R.string.stremio_retry)) }
            }
        }
        val showingPreviousResults = selected.metas.isNotEmpty() &&
            (selected.loadedSearch != selected.search.trim() || selected.loadedGenre != selected.genre.trim())
        if (showingPreviousResults) {
            CatalogMessage(
                message = stringResource(
                    if (selected.loading) R.string.stremio_catalog_updating_previous_results
                    else R.string.stremio_catalog_previous_results_label
                )
            )
        } else if (selected.loading && selected.metas.isNotEmpty()) {
            CatalogMessage(message = stringResource(R.string.stremio_catalog_refreshing_previous_results))
        }
        if (selected.loading && selected.metas.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.Brand)
            }
        } else if (selected.metas.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (selected.blockedReason == null && selected.waitingForInput == null && selected.error == null) {
                    Text(stringResource(R.string.stremio_empty_catalog), color = AppColors.TextSecondary)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                state = gridState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)
            ) {
                items(selected.metas, key = { "${it.type.typeName}:${it.id}" }) { meta ->
                    val focusRequester = itemFocusRequesters.getOrPut(meta.id) { FocusRequester() }
                    CatalogMetaCard(
                        meta = meta,
                        focusRequester = focusRequester,
                        onFocused = { lastFocusedMetaId = meta.id },
                        onClick = { onOpenItem(selected.key.addonId, meta) }
                    )
                }
                if ((selected.canLoadMore || selected.loadingMore) && selected.failedPageSkip == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        TvPrimaryButton(
                            onClick = { viewModel.loadMore(selected.key) },
                            enabled = !selected.loadingMore && !selected.loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (selected.loadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.OnPrimary)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.stremio_load_more))
                        }
                    }
                }
            }
        }
    }

    if (showCatalogPicker) {
        CatalogPickerDialog(
            rows = state.rows,
            selectedKey = selected.key,
            onSelect = { viewModel.selectCatalog(it); showCatalogPicker = false },
            onDismiss = { showCatalogPicker = false }
        )
    }
    if (showGenrePicker) {
        GenrePickerDialog(
            row = selected,
            onSelect = { viewModel.setGenre(selected.key, it); showGenrePicker = false },
            onDismiss = { showGenrePicker = false }
        )
    }
}
@Composable
private fun CatalogPickerDialog(
    rows: List<StremioCatalogRowState>,
    selectedKey: StremioCatalogRowKey,
    onSelect: (StremioCatalogRowKey) -> Unit,
    onDismiss: () -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.stremio_choose_catalog),
        onDismissRequest = onDismiss,
        heightFraction = null,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    TvButton(
                        onClick = { onSelect(row.key) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = row.blockedReason == null
                    ) {
                        Text(
                            text = listOf(
                                row.catalog.name?.takeIf(String::isNotBlank) ?: row.catalog.id,
                                row.addonName,
                                row.catalog.type.typeName,
                                if (row.key == selectedKey) "✓" else null
                            ).filterNotNull().joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(label = stringResource(R.string.stremio_cancel), onClick = onDismiss)
        }
    )
}
@Composable
private fun GenrePickerDialog(
    row: StremioCatalogRowState,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.stremio_filter_genre),
        onDismissRequest = onDismiss,
        heightFraction = null,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!row.genreRequired) {
                    TvButton(onClick = { onSelect("") }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.stremio_all_genres))
                    }
                }
                row.genreOptions.forEach { genre ->
                    TvButton(onClick = { onSelect(genre) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (genre == row.genre) "$genre ✓" else genre)
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(label = stringResource(R.string.stremio_cancel), onClick = onDismiss)
        }
    )
}

@Composable
private fun CatalogMetaCard(
    meta: StremioMetaPreview,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val description = stringResource(
        R.string.stremio_catalog_item_a11y,
        meta.name,
        meta.releaseInfo.orEmpty()
    )
    TvClickableSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(BrowseShapes.Artwork)
    ) {
        Column(modifier = Modifier.fillMaxWidth().clip(BrowseShapes.Artwork)) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(BrowseShapes.Artwork)
                    .background(AppColors.SurfaceEmphasis),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = meta.name.take(1).uppercase(),
                    color = AppColors.BrandStrong,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                meta.poster?.takeIf(String::isNotBlank)?.let { poster ->
                    AsyncImage(
                        model = poster,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = meta.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.TextPrimary
                )
                meta.releaseInfo?.takeIf(String::isNotBlank)?.let { releaseInfo ->
                    Text(
                        releaseInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogMessage(message: String, error: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().browsePanel(BrowseShapes.Control).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            message,
            color = if (error) AppColors.Live else AppColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
