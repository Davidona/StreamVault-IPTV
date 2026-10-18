@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.streamvault.feature.stremio.presentation

import com.streamvault.core.ui.layout.AdaptiveRow

import androidx.compose.foundation.layout.FlowRow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.core.ui.components.SearchInput
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.components.dialogs.PinDialog
import com.streamvault.core.ui.design.BrowseColors as AppColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.browsePanel
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.TvIconButton
import com.streamvault.core.ui.interaction.TvPrimaryButton
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioMetaPreview
import com.streamvault.feature.stremio.R
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch

@Composable
internal fun StremioCatalogBrowseContent(
    modifier: Modifier = Modifier,
    viewModel: StremioCatalogBrowseViewModel = hiltViewModel(),
    onOpenItem: (Long, StremioMetaPreview) -> Unit = { _, _ -> },
    onManageAddons: () -> Unit = {},
    onContinueWatching: (() -> Unit)? = null
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val selected = state.rows.firstOrNull { it.key == state.selectedCatalogKey }
    val incorrectParentalPinMessage = stringResource(R.string.movies_incorrect_pin)
    var showSearch by remember { mutableStateOf(false) }
    var showGenrePicker by remember { mutableStateOf(false) }
    var showParentalPin by remember { mutableStateOf(false) }
    var parentalPinError by remember { mutableStateOf<String?>(null) }
    var pendingLockedCatalogKey by remember { mutableStateOf<StremioCatalogRowKey?>(null) }
    var pendingLockedGenre by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    var restoredFocus by remember { mutableStateOf(false) }
    var lastFocusedMetaId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastGridQueryKey by rememberSaveable { mutableStateOf<String?>(null) }
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    if (state.rows.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            StremioCatalogSelectionNoticeMessage(state.selectionNotice)
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
            StremioCatalogSelectionNoticeMessage(state.selectionNotice)
            Text(stringResource(R.string.stremio_no_supported_catalogs), color = AppColors.TextSecondary)
            Spacer(Modifier.height(12.dp))
            TvButton(onClick = onManageAddons) { Text(stringResource(R.string.stremio_manage_addons)) }
        }
        return
    }
    val gridQueryKey = "${selected.key.addonId}:${selected.key.catalogKey}:${selected.search.trim()}:${selected.genre.trim()}"
    LaunchedEffect(gridQueryKey) {
        if (lastGridQueryKey != null && lastGridQueryKey != gridQueryKey) {
            lastFocusedMetaId = null
            gridState.scrollToItem(0)
        }
        lastGridQueryKey = gridQueryKey
    }
    LaunchedEffect(selected.key, selected.metas) {
        if (restoredFocus || showSearch || showGenrePicker) return@LaunchedEffect
        val focusedId = lastFocusedMetaId ?: selected.metas.firstOrNull()?.id ?: return@LaunchedEffect
        val focusedIndex = selected.metas.indexOfFirst { it.id == focusedId }
        if (focusedIndex < 0) return@LaunchedEffect
        gridState.scrollToItem(focusedIndex)
        yield()
        itemFocusRequesters[focusedId]?.let { requester ->
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) restoredFocus = true
        }
    }
    // Only these two compact rows sit above the artwork. Search and filters are transient.
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AdaptiveRow(
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TvIconButton(onClick = onManageAddons, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.stremio_manage_addons))
            }
            Text(
                stringResource(R.string.stremio_browse),
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary
            )
            Text(
                selected.addonName,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextTertiary
            )
            if (selected.supportsSearch) {
                TvButton(
                    onClick = { showSearch = true },
                    enabled = !selected.isLocked && !selected.selectedGenreLocked,
                    modifier = Modifier.height(32.dp).width(220.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        selected.search.ifBlank { stringResource(R.string.stremio_search_catalog) },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (selected.supportsGenre) {
                TvButton(
                    onClick = { if (!selected.isLocked) showGenrePicker = true },
                    enabled = !selected.isLocked,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    if (selected.selectedGenreLocked) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = stringResource(R.string.a11y_locked),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(selected.genre.ifBlank { stringResource(R.string.stremio_all_genres) },
                        style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            onContinueWatching?.let { action ->
                TvIconButton(onClick = action, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.History, stringResource(R.string.stremio_continue_watching))
                }
            }
            TvIconButton(
                onClick = { viewModel.refresh(selected.key) },
                enabled = !selected.loading && !selected.isLocked && !selected.selectedGenreLocked,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Refresh, stringResource(R.string.stremio_refresh))
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(28.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            itemsIndexed(state.rows, key = { _, row -> "${row.key.addonId}:${row.key.catalogKey}" }) { _, row ->
                val active = row.key == selected.key
                TvButton(
                    onClick = {
                        if (row.isLocked) {
                            pendingLockedCatalogKey = row.key
                            parentalPinError = null
                            showParentalPin = true
                        } else {
                            viewModel.selectCatalog(row.key)
                        }
                    },
                    enabled = row.blockedReason == null,
                    modifier = Modifier.height(28.dp).semantics { this.selected = active; role = Role.Tab },
                    colors = ButtonDefaults.colors(
                        containerColor = if (active) AppColors.BrandMuted else AppColors.Surface,
                        contentColor = if (active) AppColors.TextPrimary else AppColors.TextSecondary,
                        focusedContainerColor = AppColors.BrandStrong,
                        focusedContentColor = AppColors.OnPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    if (row.isLocked) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = stringResource(R.string.a11y_locked),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        catalogLabel(row, state.rows.map { it.key.addonId }.distinct().size > 1),
                        maxLines = 1, style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        StremioCatalogSelectionNoticeMessage(state.selectionNotice)
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
        if (selected.isLocked || selected.selectedGenreLocked) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = stringResource(R.string.a11y_locked),
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(stringResource(R.string.a11y_locked), color = AppColors.TextSecondary)
                }
            }
        } else if (selected.loading && selected.metas.isEmpty()) {
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
                columns = GridCells.Adaptive(minSize = 132.dp),
                state = gridState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(2.dp)
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
    if (showSearch) {
        var searchDraft by remember(selected.key) { mutableStateOf(selected.search) }
        val submitFocus = remember { FocusRequester() }
        val submitSearch = {
            viewModel.setSearch(selected.key, searchDraft)
            showSearch = false
        }
        PremiumDialog(
            title = stringResource(R.string.stremio_search_catalog),
            onDismissRequest = { showSearch = false },
            heightFraction = null,
            content = {
                SearchInput(
                    value = searchDraft,
                    onValueChange = { searchDraft = it },
                    placeholder = stringResource(R.string.stremio_search_catalog),
                    onSearch = submitSearch,
                    enabled = selected.blockedReason == null,
                    modifier = Modifier.fillMaxWidth().focusProperties { down = submitFocus }
                )
            },
            footer = {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.stremio_browse),
                    onClick = submitSearch,
                    modifier = Modifier.focusRequester(submitFocus),
                    emphasized = true
                )
            }
        )
    }
    if (showGenrePicker) {
        GenrePickerDialog(
            row = selected,
            onSelect = { genre ->
                viewModel.setGenre(selected.key, genre)
                val updated = viewModel.state.value.rows.firstOrNull { it.key == selected.key }
                if (genre in updated?.lockedGenreCategoryIds.orEmpty()) {
                    pendingLockedCatalogKey = selected.key
                    pendingLockedGenre = genre
                    parentalPinError = null
                    showGenrePicker = false
                    showParentalPin = true
                } else if (selected.genreOptions.isNotEmpty()) {
                    showGenrePicker = false
                }
            },
            onSelectLocked = { genre ->
                pendingLockedCatalogKey = selected.key
                pendingLockedGenre = genre
                parentalPinError = null
                showGenrePicker = false
                showParentalPin = true
            },
            onDismiss = { showGenrePicker = false }
        )
    }
    if (showParentalPin) {
        PinDialog(
            title = stringResource(R.string.pin_dialog_title),
            cancelLabel = stringResource(R.string.pin_dialog_cancel),
            error = parentalPinError,
            onDismissRequest = {
                showParentalPin = false
                parentalPinError = null
                pendingLockedCatalogKey = null
                pendingLockedGenre = null
            },
            onPinEntered = { pin ->
                coroutineScope.launch {
                    if (viewModel.verifyParentalPin(pin)) {
                        val key = pendingLockedCatalogKey
                        val genre = pendingLockedGenre
                        if (key != null && genre != null) {
                            viewModel.unlockGenre(key, genre)
                            viewModel.setGenre(key, genre)
                        } else {
                            key?.let(viewModel::unlockCatalog)
                        }
                        showParentalPin = false
                        parentalPinError = null
                        pendingLockedCatalogKey = null
                        pendingLockedGenre = null
                    } else {
                        parentalPinError = incorrectParentalPinMessage
                    }
                }
            }
        )
    }
}

@Composable
private fun catalogLabel(row: StremioCatalogRowState, showAddon: Boolean): String {
    val type = stringResource(if (row.catalog.type == StremioMediaType.SERIES)
        R.string.stremio_media_series else R.string.stremio_media_movies)
    val readableId = row.catalog.id.takeIf { id -> id.all { it.isLetter() || it.isWhitespace() } }
    val name = row.catalog.name?.takeIf(String::isNotBlank) ?: when {
        readableId == null -> listOf(type, stringResource(R.string.stremio_all_genres)).joinToString(" \u00b7 ")
        readableId.lowercase() in setOf("movie", "movies", "series", "tv shows", "tvshows") -> type
        else -> readableId
    }
    return if (showAddon) "$name \u00b7 ${row.addonName}" else name
}

@Composable
private fun GenrePickerDialog(
    row: StremioCatalogRowState,
    onSelect: (String) -> Unit,
    onSelectLocked: (String) -> Unit,
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
                if (row.genreOptions.isEmpty()) {
                    SearchInput(
                        value = row.genre,
                        onValueChange = onSelect,
                        placeholder = stringResource(R.string.stremio_filter_genre),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                row.genreOptions.filterNot { it in row.hiddenGenreOptions }.forEach { genre ->
                    val isLocked = genre in row.lockedGenreCategoryIds
                    TvButton(
                        onClick = { if (isLocked) onSelectLocked(genre) else onSelect(genre) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLocked) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = stringResource(R.string.a11y_locked),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
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
    val label = remember(meta.name) { stremioTitleLabel(meta.name) }
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
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(BrowseShapes.Artwork).background(AppColors.SurfaceEmphasis)
        ) {
            Text(
                meta.name.take(1).uppercase(),
                modifier = Modifier.align(Alignment.Center),
                color = AppColors.BrandStrong,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            AsyncImage(
                model = meta.poster,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.94f))))
                    .padding(start = 10.dp, end = 10.dp, top = 28.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    label.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, color = Color.White
                )
                (meta.releaseInfo?.takeIf(String::isNotBlank) ?: label.year)?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
                }
            }
        }
    }
}

@Composable
private fun StremioCatalogSelectionNoticeMessage(notice: StremioCatalogSelectionNotice?) {
    val message = when (notice) {
        StremioCatalogSelectionNotice.ADDON_UNAVAILABLE -> R.string.stremio_selected_addon_unavailable
        StremioCatalogSelectionNotice.CATALOG_UNAVAILABLE -> R.string.stremio_selected_catalog_unavailable
        null -> null
    }
    message?.let { CatalogMessage(message = stringResource(it)) }
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
