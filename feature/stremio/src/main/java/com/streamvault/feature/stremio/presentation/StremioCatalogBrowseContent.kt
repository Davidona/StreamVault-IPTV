package com.streamvault.feature.stremio.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.streamvault.core.ui.components.shell.AppSectionHeader
import com.streamvault.core.ui.design.BrowseColors as AppColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.browsePanel
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.TvPrimaryButton
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
        state.loading && state.rows.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AppColors.Brand)
        }
        state.rows.isEmpty() -> Box(
            modifier = modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.stremio_browse_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.TextSecondary
            )
        }
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)
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
    Column(
        modifier = Modifier.fillMaxWidth().browsePanel(BrowseShapes.Panel).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppSectionHeader(
            title = row.catalog.name?.takeIf { it.isNotBlank() } ?: row.catalog.id,
            subtitle = "${row.addonName} · ${row.catalog.type.typeName}"
        )

        if (row.supportsSearch) {
            OutlinedTextField(
                value = row.search,
                onValueChange = { viewModel.setSearch(row.key, it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.stremio_search_catalog)) },
                singleLine = true,
                enabled = row.blockedReason == null,
                colors = browseTextFieldColors()
            )
        }

        if (row.supportsGenre) {
            if (row.genreOptions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!row.genreRequired) {
                        item(key = "all") {
                            CatalogFilterChip(
                                label = stringResource(R.string.stremio_all_genres),
                                selected = row.genre.isBlank(),
                                onClick = { viewModel.setGenre(row.key, "") }
                            )
                        }
                    }
                    items(row.genreOptions, key = { it }) { genre ->
                        CatalogFilterChip(
                            label = genre,
                            selected = row.genre == genre,
                            onClick = { viewModel.setGenre(row.key, genre) }
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
                    enabled = row.blockedReason == null,
                    colors = browseTextFieldColors()
                )
            }
        }

        row.blockedReason?.let { reason ->
            CatalogMessage(message = reason, error = true)
        }
        row.waitingForInput?.let {
            val waiting = when {
                row.searchRequired && row.search.isBlank() -> stringResource(R.string.stremio_catalog_waiting_search)
                row.genreRequired && row.genre.isBlank() -> stringResource(R.string.stremio_catalog_waiting_genre)
                else -> it
            }
            CatalogMessage(message = waiting)
        }
        row.error?.let { error ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    error,
                    modifier = Modifier.weight(1f),
                    color = AppColors.Live,
                    style = MaterialTheme.typography.bodySmall
                )
                TvButton(onClick = { viewModel.retry(row.key) }) {
                    Text(stringResource(R.string.stremio_retry))
                }
            }
        }

        if (row.loading && row.metas.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(156.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.Brand)
            }
        } else if (row.metas.isEmpty() && row.blockedReason == null &&
            row.waitingForInput == null && row.error == null
        ) {
            Text(
                text = stringResource(R.string.stremio_empty_catalog),
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
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
            TvPrimaryButton(
                onClick = { viewModel.loadMore(row.key) },
                enabled = !row.loadingMore && !row.loading
            ) {
                if (row.loadingMore) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.OnPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.stremio_load_more))
            }
        }
    }
}

@Composable
private fun CatalogMetaCard(meta: StremioMetaPreview, onClick: () -> Unit) {
    val description = stringResource(
        R.string.stremio_catalog_item_a11y,
        meta.name,
        meta.releaseInfo.orEmpty()
    )
    TvClickableSurface(
        onClick = onClick,
        modifier = Modifier
            .width(152.dp)
            .semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(BrowseShapes.Artwork)
    ) {
        Column(modifier = Modifier.fillMaxWidth().clip(BrowseShapes.Artwork)) {
            Box(
                modifier = Modifier.fillMaxWidth().height(210.dp).clip(BrowseShapes.Artwork)
                    .background(AppColors.SurfaceEmphasis),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = meta.name.take(1).uppercase(),
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
private fun CatalogFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = BrowseShapes.Control
    TvButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp),
        shape = ButtonDefaults.shape(shape),
        border = ButtonDefaults.border(
            border = if (selected) Border(BorderStroke(1.dp, AppColors.Brand.copy(alpha = 0.7f)), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, AppColors.Focus), shape = shape)
        ),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) AppColors.BrandMuted else AppColors.Surface,
            contentColor = if (selected) AppColors.TextPrimary else AppColors.TextSecondary,
            focusedContainerColor = if (selected) AppColors.BrandMuted else AppColors.SurfaceEmphasis,
            focusedContentColor = AppColors.TextPrimary
        )
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 6.dp))
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

@Composable
private fun browseTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AppColors.TextPrimary,
    unfocusedTextColor = AppColors.TextPrimary,
    disabledTextColor = AppColors.TextSecondary,
    focusedBorderColor = AppColors.Brand,
    unfocusedBorderColor = AppColors.Outline,
    disabledBorderColor = AppColors.Outline.copy(alpha = 0.4f),
    focusedLabelColor = AppColors.Brand,
    unfocusedLabelColor = AppColors.TextSecondary,
    disabledLabelColor = AppColors.TextTertiary,
    cursorColor = AppColors.Brand
)
