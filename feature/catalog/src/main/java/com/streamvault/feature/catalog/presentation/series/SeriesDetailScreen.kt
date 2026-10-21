package com.streamvault.feature.catalog.presentation.series

import com.streamvault.core.ui.design.BrowseShapes
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.feature.catalog.R
import com.streamvault.feature.catalog.api.CatalogPlatformHost
import com.streamvault.feature.catalog.api.CatalogUiEvent
import com.streamvault.feature.catalog.presentation.catalogMessageText
import com.streamvault.feature.catalog.presentation.components.EpisodeRowCard
import com.streamvault.feature.catalog.presentation.components.DetailActionButton
import com.streamvault.feature.catalog.presentation.components.DetailSectionHeading
import com.streamvault.feature.catalog.presentation.components.ExternalRatingsStrip
import com.streamvault.feature.catalog.presentation.components.formatVodRatingLabel
import com.streamvault.feature.catalog.presentation.components.TraktWatchlistActionButton
import com.streamvault.feature.catalog.presentation.components.TraktRatingActionButton
import com.streamvault.feature.catalog.presentation.components.TraktRatingDialog
import com.streamvault.feature.catalog.presentation.components.TraktCustomListActionButton
import com.streamvault.feature.catalog.presentation.time.formatPositionMs
import com.streamvault.core.ui.device.rememberIsTelevisionDevice
import com.streamvault.core.ui.image.rememberCrossfadeImageModel
import com.streamvault.core.ui.components.shell.ContentMetadataStrip
import com.streamvault.core.ui.components.shell.StatusPill
import com.streamvault.core.ui.design.BrowseColors as AppColors
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.ExternalRatings
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.VodSeriesVariant
import com.streamvault.domain.trakt.TraktCustomListItem
import com.streamvault.domain.trakt.TraktCustomListItemIdentity
import com.streamvault.domain.trakt.TraktCustomListItemType
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvIconButton
import com.streamvault.domain.model.Result
import kotlinx.coroutines.launch

private const val EPISODE_DETAIL_PAGE_SIZE = 100

@Composable
fun SeriesDetailScreen(
    onEpisodeClick: (Episode, Series) -> Unit,
    onResumeClick: ((Episode) -> Unit)? = null,
    onBack: () -> Unit,
    platformHost: CatalogPlatformHost? = null,
    viewModel: SeriesDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val series = uiState.series
    val context = LocalContext.current
    var ratingDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel, context, platformHost) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                CatalogUiEvent.OpenCastRouteChooser -> platformHost?.openCastRouteChooser()
                is CatalogUiEvent.ShowMessage -> Toast.makeText(
                    context,
                    context.catalogMessageText(event.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    if (uiState.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Canvas),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.series_loading_details), color = AppColors.TextSecondary)
        }
        return
    }

    if (series == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Canvas),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    uiState.detailsUnavailable -> stringResource(R.string.series_episodes_load_failed)
                    else -> uiState.error ?: stringResource(R.string.series_not_found)
                },
                color = AppColors.Live
            )
        }
        return
    }

    SeriesDetailContent(
        series = series,
        selectedSeason = uiState.selectedSeason,
        resumeEpisode = uiState.resumeEpisode,
        unwatchedEpisodeCount = uiState.unwatchedEpisodeCount,
        isCasting = uiState.isCasting,
        externalRatings = uiState.externalRatings,
        isLoadingExternalRatings = uiState.isLoadingExternalRatings,
        traktWatchlistConnected = uiState.traktWatchlistConnected,
        traktWatchlistLoaded = uiState.traktWatchlistLoaded,
        traktWatchlisted = uiState.traktWatchlisted,
        traktWatchlistBusy = uiState.traktWatchlistBusy,
        traktUserRating = uiState.traktUserRating,
        traktRatingBusy = uiState.traktRatingBusy,
        onToggleTraktWatchlist = viewModel::toggleTraktWatchlist,
        onRateOnTrakt = { ratingDialogOpen = true },
        onToggleFavorite = viewModel::toggleFavorite,
        onSelectVariant = viewModel::selectSeriesVariant,
        onSeasonSelected = viewModel::selectSeason,
        onEpisodeClick = { episode -> onEpisodeClick(episode, series) },
        onResumeClick = onResumeClick ?: { episode -> onEpisodeClick(episode, series) },
        onCopyEpisodeUrl = { episode ->
            when (val result = viewModel.resolveCopyStreamUrl(episode)) {
                is Result.Success -> result.data
                is Result.Error -> null
                Result.Loading -> null
            }
        },
        onDownloadEpisode = { episode ->
            viewModel.downloadEpisode(episode)
        },
        onCastResumeEpisode = viewModel::castResumeEpisode,
        onCastEpisode = viewModel::castEpisode,
        onBack = onBack
    )
    if (ratingDialogOpen) {
        TraktRatingDialog(
            currentRating = uiState.traktUserRating,
            busy = uiState.traktRatingBusy,
            onDismiss = { ratingDialogOpen = false },
            onSelect = { rating ->
                ratingDialogOpen = false
                viewModel.rateOnTrakt(rating)
            },
        )
    }
}

@Composable
private fun SeriesDetailContent(
    series: Series,
    selectedSeason: Season?,
    resumeEpisode: Episode?,
    unwatchedEpisodeCount: Int,
    isCasting: Boolean,
    externalRatings: ExternalRatings,
    isLoadingExternalRatings: Boolean,
    traktWatchlistConnected: Boolean,
    traktWatchlistLoaded: Boolean,
    traktWatchlisted: Boolean,
    traktWatchlistBusy: Boolean,
    traktUserRating: Int?,
    traktRatingBusy: Boolean,
    onToggleTraktWatchlist: () -> Unit,
    onRateOnTrakt: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectVariant: (Long) -> Unit,
    onSeasonSelected: (Season) -> Unit,
    onEpisodeClick: (Episode) -> Unit,
    onResumeClick: (Episode) -> Unit,
    onCopyEpisodeUrl: suspend (Episode) -> String?,
    onDownloadEpisode: (Episode) -> Unit,
    onCastResumeEpisode: () -> Unit,
    onCastEpisode: (Episode) -> Unit,
    onBack: () -> Unit
) {
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val copyEpisodeUrl: (Episode) -> Unit = { episode ->
        coroutineScope.launch {
            copyStreamUrlToClipboard(context, onCopyEpisodeUrl(episode))
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas)
    ) {
        val compactLayout = !isTelevisionDevice && maxWidth < 900.dp
        val heroHeight = when {
            maxWidth < 700.dp -> 220.dp
            !isTelevisionDevice && maxWidth < 900.dp -> 280.dp
            else -> 420.dp
        }
        val contentPadding = if (compactLayout) {
            PaddingValues(horizontal = 16.dp, vertical = 20.dp)
        } else {
            PaddingValues(horizontal = 56.dp, vertical = 36.dp)
        }
        val posterWidth = if (compactLayout) 132.dp else 220.dp
        var visibleEpisodeLimit by remember(selectedSeason?.seasonNumber) {
            mutableStateOf(EPISODE_DETAIL_PAGE_SIZE)
        }
        val visibleEpisodes = selectedSeason?.episodes.orEmpty().take(visibleEpisodeLimit)

        AsyncImage(
            model = rememberCrossfadeImageModel(series.backdropUrl ?: series.posterUrl),
            contentDescription = series.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .align(Alignment.TopCenter),
            contentScale = ContentScale.Crop
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            AppColors.HeroTop,
                            AppColors.HeroBottom
                        )
                    )
                )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                TvButton(
                    onClick = onBack,
                    colors = ButtonDefaults.colors(
                        containerColor = AppColors.Surface.copy(alpha = 0.72f),
                        contentColor = AppColors.TextPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Outline))
                    )
                ) {
                    Text(stringResource(R.string.series_detail_back))
                }
            }

            item {
                if (compactLayout) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(
                            modifier = Modifier
                                .width(posterWidth)
                                .aspectRatio(2f / 3f)
                                .clip(BrowseShapes.Panel)
                                .background(AppColors.SurfaceElevated)
                                .border(1.dp, AppColors.Outline, BrowseShapes.Panel)
                        ) {
                            AsyncImage(
                                model = rememberCrossfadeImageModel(series.posterUrl ?: series.backdropUrl),
                                contentDescription = series.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusPill(label = stringResource(R.string.nav_series), containerColor = AppColors.BrandMuted)
                                series.rating.takeIf { it > 0f }?.let {
                                    StatusPill(
                                        label = formatVodRatingLabel(it),
                                        containerColor = AppColors.Warning,
                                        contentColor = Color.Black
                                    )
                                }
                                if (unwatchedEpisodeCount > 0) {
                                    StatusPill(
                                        label = stringResource(R.string.series_unwatched_badge, unwatchedEpisodeCount),
                                        containerColor = AppColors.SurfaceEmphasis
                                    )
                                }
                            }
                            Text(
                                text = series.name,
                                style = MaterialTheme.typography.displayMedium,
                                color = AppColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            ContentMetadataStrip(
                                values = listOf(
                                    series.releaseDate.orEmpty(),
                                    series.genre.orEmpty(),
                                    selectedSeason?.name.orEmpty()
                                )
                            )
                            ExternalRatingsStrip(
                                ratings = externalRatings,
                                isLoading = isLoadingExternalRatings
                            )
                            SeriesVersionSelector(
                                variants = series.variants,
                                selectedVariantId = series.selectedVariantId ?: series.id,
                                onSelectVariant = onSelectVariant
                            )
                            Text(
                                text = series.plot ?: stringResource(R.string.series_plot_fallback),
                                style = MaterialTheme.typography.bodyLarge,
                                color = AppColors.TextSecondary,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                            resumeEpisode?.let { ep ->
                                val hasProgress = ep.watchProgress > 5000L
                                SeriesDetailActions(
                                    series = series,
                                    resumeEpisode = ep,
                                     hasProgress = hasProgress,
                                     isCasting = isCasting,
                                     onResumeClick = onResumeClick,
                                     onCopyUrl = { copyEpisodeUrl(ep) },
                                     onCast = onCastResumeEpisode,
                                     onToggleFavorite = onToggleFavorite
                                 )
                            }
                            if (resumeEpisode == null) {
                                SeriesDetailFavoriteAction(series = series, onToggleFavorite = onToggleFavorite)
                            }
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Box(
                            modifier = Modifier
                                .width(posterWidth)
                                .aspectRatio(2f / 3f)
                                .clip(BrowseShapes.Panel)
                                .background(AppColors.SurfaceElevated)
                                .border(1.dp, AppColors.Outline, BrowseShapes.Panel)
                        ) {
                            AsyncImage(
                                model = rememberCrossfadeImageModel(series.posterUrl ?: series.backdropUrl),
                                contentDescription = series.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusPill(label = stringResource(R.string.nav_series), containerColor = AppColors.BrandMuted)
                                series.rating.takeIf { it > 0f }?.let {
                                    StatusPill(
                                        label = formatVodRatingLabel(it),
                                        containerColor = AppColors.Warning,
                                        contentColor = Color.Black
                                    )
                                }
                                if (unwatchedEpisodeCount > 0) {
                                    StatusPill(
                                        label = stringResource(R.string.series_unwatched_badge, unwatchedEpisodeCount),
                                        containerColor = AppColors.SurfaceEmphasis
                                    )
                                }
                            }
                            Text(
                                text = series.name,
                                style = MaterialTheme.typography.displayMedium,
                                color = AppColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            ContentMetadataStrip(
                                values = listOf(
                                    series.releaseDate.orEmpty(),
                                    series.genre.orEmpty(),
                                    selectedSeason?.name.orEmpty()
                                )
                            )
                            ExternalRatingsStrip(
                                ratings = externalRatings,
                                isLoading = isLoadingExternalRatings
                            )
                            SeriesVersionSelector(
                                variants = series.variants,
                                selectedVariantId = series.selectedVariantId ?: series.id,
                                onSelectVariant = onSelectVariant
                            )
                            Text(
                                text = series.plot ?: stringResource(R.string.series_plot_fallback),
                                style = MaterialTheme.typography.bodyLarge,
                                color = AppColors.TextSecondary,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                            resumeEpisode?.let { ep ->
                                val hasProgress = ep.watchProgress > 5000L
                                SeriesDetailActions(
                                    series = series,
                                    resumeEpisode = ep,
                                     hasProgress = hasProgress,
                                     isCasting = isCasting,
                                     onResumeClick = onResumeClick,
                                     onCopyUrl = { copyEpisodeUrl(ep) },
                                     onCast = onCastResumeEpisode,
                                     onToggleFavorite = onToggleFavorite
                                 )
                            }
                            if (resumeEpisode == null) {
                                SeriesDetailFavoriteAction(series = series, onToggleFavorite = onToggleFavorite)
                            }
                        }
                    }
                }
            }

            if (series.tmdbId?.let { it > 0L } == true) {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item {
                            TraktWatchlistActionButton(
                                hasIdentity = true,
                                connected = traktWatchlistConnected,
                                loaded = traktWatchlistLoaded,
                                watchlisted = traktWatchlisted,
                                busy = traktWatchlistBusy,
                                onClick = onToggleTraktWatchlist,
                            )
                        }
                        item {
                            TraktRatingActionButton(
                                hasIdentity = true,
                                connected = traktWatchlistConnected,
                                busy = traktRatingBusy,
                                rating = traktUserRating,
                                onClick = onRateOnTrakt,
                            )
                        }
                        item {
                            TraktCustomListActionButton(
                                item = series.traktShowListItem(),
                                additionalItems = series.traktNestedListItems(),
                            )
                        }
                    }
                }
            }

            if (series.seasons.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DetailSectionHeading(stringResource(R.string.series_seasons))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(vertical = 2.dp)
                        ) {
                            items(series.seasons, key = { it.seasonNumber }) { season ->
                                SeasonChip(
                                    season = season,
                                    isSelected = season == selectedSeason,
                                    onClick = { onSeasonSelected(season) }
                                )
                            }
                        }
                    }
                }
            }

            if (series.seasons.none { season -> season.episodes.isNotEmpty() }) {
                item {
                    Text(
                        text = stringResource(R.string.series_episodes_empty),
                        color = AppColors.TextSecondary
                    )
                }
            } else {
                selectedSeason?.let { season ->
                    item {
                        DetailSectionHeading(
                            stringResource(R.string.series_episodes, season.episodes.size),
                            season.name
                        )
                    }
                    val fallbackCover = series?.let {
                            it.posterUrl ?: it.backdropUrl
                        }
                    items(visibleEpisodes, key = { it.id }) { episode ->
                        EpisodeItem(
                            episode = episode,
                            fallbackImageUrl = fallbackCover,
                            onClick = { onEpisodeClick(episode) },
                            onCopyUrl = { copyEpisodeUrl(episode) },
                            onDownload = { onDownloadEpisode(episode) },
                            onCast = { onCastEpisode(episode) },
                            isCasting = isCasting
                        )
                    }
                    if (visibleEpisodes.size < season.episodes.size) {
                        item {
                            TvButton(
                                onClick = {
                                    visibleEpisodeLimit = (visibleEpisodeLimit + EPISODE_DETAIL_PAGE_SIZE)
                                        .coerceAtMost(season.episodes.size)
                                }
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.library_load_more,
                                        visibleEpisodes.size,
                                        season.episodes.size
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Series.traktShowListItem(): TraktCustomListItem? {
    val showTmdbId = tmdbId?.takeIf { it > 0L } ?: return null
    return TraktCustomListItem(
        identity = TraktCustomListItemIdentity(TraktCustomListItemType.SHOW, showTmdbId),
        title = name,
        year = releaseDate?.take(4)?.toIntOrNull(),
    )
}

@Composable
private fun Series.traktNestedListItems(): List<TraktCustomListItem> {
    val showTmdbId = tmdbId?.takeIf { it > 0L } ?: return emptyList()
    val items = mutableListOf<TraktCustomListItem>()
    seasons.forEach { season ->
        items += TraktCustomListItem(
            identity = TraktCustomListItemIdentity(
                type = TraktCustomListItemType.SEASON,
                tmdbId = showTmdbId,
                seasonNumber = season.seasonNumber,
            ),
            title = "$name · ${season.name}",
        )
        season.episodes.forEach { episode ->
            if (episode.episodeNumber > 0) {
                val episodeNumber = stringResource(
                    R.string.continue_watching_season_episode,
                    episode.seasonNumber,
                    episode.episodeNumber,
                )
                items += TraktCustomListItem(
                    identity = TraktCustomListItemIdentity(
                        type = TraktCustomListItemType.EPISODE,
                        tmdbId = showTmdbId,
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber,
                    ),
                    title = "$name · $episodeNumber · ${episode.title}",
                )
            }
        }
    }
    return items
}

@Composable
private fun SeriesVersionSelector(
    variants: List<VodSeriesVariant>,
    selectedVariantId: Long,
    onSelectVariant: (Long) -> Unit
) {
    if (variants.size <= 1) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.series_detail_versions),
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.TextPrimary
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(variants, key = { it.rawSeriesId }) { variant ->
                val selected = variant.rawSeriesId == selectedVariantId
                TvButton(
                    onClick = { onSelectVariant(variant.rawSeriesId) },
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) AppColors.Brand else AppColors.SurfaceEmphasis,
                        contentColor = if (selected) AppColors.OnPrimary else AppColors.TextPrimary
                    )
                ) {
                    Text(
                        text = variant.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesDetailActions(
    series: Series,
    resumeEpisode: Episode,
    hasProgress: Boolean,
    isCasting: Boolean,
    onResumeClick: (Episode) -> Unit,
    onCopyUrl: () -> Unit,
    onCast: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        item { DetailActionButton(
            label = if (hasProgress) {
                stringResource(
                    R.string.series_detail_resume,
                    resumeEpisode.seasonNumber,
                    resumeEpisode.episodeNumber,
                    formatPositionMs(resumeEpisode.watchProgress)
                )
            } else {
                stringResource(
                    R.string.series_detail_play_episode,
                    resumeEpisode.seasonNumber,
                    resumeEpisode.episodeNumber
                )
            },
            onClick = { onResumeClick(resumeEpisode) },
            primary = true
        ) }
        item { DetailActionButton(stringResource(R.string.stream_url_copy), onCopyUrl) }
        item { DetailActionButton(
            stringResource(if (isCasting) R.string.cast_launching else R.string.cast_button_label),
            onCast, enabled = !isCasting
        ) }
        item { SeriesDetailFavoriteAction(series = series, onToggleFavorite = onToggleFavorite) }
    }
}

@Composable
private fun SeriesDetailFavoriteAction(
    series: Series,
    onToggleFavorite: () -> Unit
) {
    TvIconButton(
        onClick = onToggleFavorite,
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape = CircleShape),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(1.dp, AppColors.Outline), shape = CircleShape),
            focusedBorder = Border(BorderStroke(2.dp, AppColors.Focus), shape = CircleShape)
        ),
        colors = ButtonDefaults.colors(
            containerColor = if (series.isFavorite) AppColors.Brand else AppColors.SurfaceEmphasis,
            focusedContainerColor = if (series.isFavorite) AppColors.Brand else AppColors.SurfaceEmphasis,
            contentColor = if (series.isFavorite) AppColors.OnPrimary else AppColors.TextSecondary,
            focusedContentColor = if (series.isFavorite) AppColors.OnPrimary else AppColors.TextSecondary
        )
    ) {
        Icon(
            imageVector = if (series.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = stringResource(
                if (series.isFavorite) R.string.favorites_remove else R.string.favorites_add
            )
        )
    }
}

@Composable
fun SeasonChip(
    season: Season,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = BrowseShapes.Control),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) AppColors.BrandMuted else AppColors.SurfaceElevated,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = if (isSelected) AppColors.BrandMuted else AppColors.SurfaceElevated,
            focusedContentColor = AppColors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) AppColors.Brand else AppColors.Outline),
                shape = BrowseShapes.Control
            ),
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, AppColors.Focus),
                shape = BrowseShapes.Control
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = season.name,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun EpisodeItem(
    episode: Episode,
    fallbackImageUrl: String? = null,
    onClick: () -> Unit,
    onCopyUrl: () -> Unit,
    onDownload: () -> Unit,
    onCast: () -> Unit,
    isCasting: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TvClickableSurface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(shape = BrowseShapes.Panel),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = AppColors.SurfaceElevated,
                focusedContainerColor = AppColors.SurfaceElevated
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, AppColors.Outline), shape = BrowseShapes.Panel),
                focusedBorder = Border(BorderStroke(2.dp, AppColors.Focus), shape = BrowseShapes.Panel)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.weight(1f)
        ) {
            EpisodeRowCard(
                episode = episode,
                fallbackImageUrl = fallbackImageUrl,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Column(
            modifier = Modifier.width(140.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            DetailActionButton(stringResource(R.string.download_button_label), onDownload,
                modifier = Modifier.fillMaxWidth(), compact = true)
            DetailActionButton(stringResource(R.string.stream_url_copy), onCopyUrl,
                modifier = Modifier.fillMaxWidth(), compact = true)
            DetailActionButton(
                stringResource(if (isCasting) R.string.cast_launching else R.string.cast_button_label),
                onCast, modifier = Modifier.fillMaxWidth(), enabled = !isCasting, compact = true)
        }
    }
}

private fun copyStreamUrlToClipboard(context: android.content.Context, url: String?) {
    if (url.isNullOrBlank()) {
        Toast.makeText(context, context.getString(R.string.stream_url_copy_failed), Toast.LENGTH_SHORT).show()
        return
    }
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.stream_url_clip_label), url))
    Toast.makeText(context, context.getString(R.string.stream_url_copied), Toast.LENGTH_SHORT).show()
}

