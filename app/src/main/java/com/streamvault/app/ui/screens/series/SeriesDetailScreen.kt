package com.streamvault.app.ui.screens.series

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.streamvault.app.MainActivity
import com.streamvault.app.R
import com.streamvault.app.cast.CastUiEvent
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.components.rememberCrossfadeImageModel
import com.streamvault.app.util.formatPositionMs
import com.streamvault.app.ui.components.shell.ContentMetadataStrip
import com.streamvault.app.ui.components.shell.EpisodeRowCard
import com.streamvault.app.ui.components.shell.ExternalRatingsStrip
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.model.formatVodRatingLabel
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.ExternalRatings
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.VodSeriesVariant
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvIconButton
import com.streamvault.domain.model.Result
import kotlinx.coroutines.launch

private const val EPISODE_DETAIL_PAGE_SIZE = 100

@Composable
fun SeriesDetailScreen(
    onEpisodeClick: (Episode) -> Unit,
    onResumeClick: ((Episode) -> Unit)? = null,
    onBack: () -> Unit,
    viewModel: SeriesDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val series = uiState.series
    val context = LocalContext.current
    val mainActivity = remember(context) { context.findMainActivity() }

    LaunchedEffect(viewModel, context, mainActivity) {
        viewModel.castEvents.collect { event ->
            when (event) {
                CastUiEvent.OpenRouteChooser -> mainActivity?.openCastRouteChooser()
                is CastUiEvent.ShowMessage ->
                    Toast.makeText(context, context.getString(event.messageResId), Toast.LENGTH_SHORT).show()
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
                text = uiState.error ?: stringResource(R.string.series_not_found),
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
        onToggleFavorite = viewModel::toggleFavorite,
        onSelectVariant = viewModel::selectSeriesVariant,
        onSeasonSelected = viewModel::selectSeason,
        onEpisodeClick = onEpisodeClick,
        onResumeClick = onResumeClick ?: onEpisodeClick,
        onCopyEpisodeUrl = { episode ->
            when (val result = viewModel.resolveCopyStreamUrl(episode)) {
                is Result.Success -> result.data
                is Result.Error -> null
                Result.Loading -> null
            }
        },
        onDownloadEpisode = { episode ->
            viewModel.downloadEpisode(context, episode)
        },
        onCastResumeEpisode = viewModel::castResumeEpisode,
        onCastEpisode = viewModel::castEpisode,
        onBack = onBack
    )
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
                                .clip(RoundedCornerShape(24.dp))
                                .background(AppColors.SurfaceElevated)
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
                                .clip(RoundedCornerShape(24.dp))
                                .background(AppColors.SurfaceElevated)
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

            if (series.seasons.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.series_seasons),
                            style = MaterialTheme.typography.titleLarge,
                            color = AppColors.TextPrimary
                        )
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

            selectedSeason?.let { season ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.series_episodes, season.episodes.size),
                            style = MaterialTheme.typography.titleLarge,
                            color = AppColors.TextPrimary
                        )
                        Text(
                            text = season.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextTertiary
                        )
                    }
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
                        contentColor = if (selected) Color.White else AppColors.TextPrimary
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
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TvButton(
            onClick = { onResumeClick(resumeEpisode) },
            colors = ButtonDefaults.colors(
                containerColor = AppColors.Brand,
                contentColor = Color.Black
            )
        ) {
            Text(
                text = if (hasProgress) {
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
                }
            )
        }
        TvButton(
            onClick = onCopyUrl,
            colors = ButtonDefaults.colors(
                containerColor = AppColors.SurfaceEmphasis,
                contentColor = AppColors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.stream_url_copy))
        }
        TvButton(
            onClick = onCast,
            enabled = !isCasting,
            colors = ButtonDefaults.colors(
                containerColor = AppColors.SurfaceEmphasis,
                contentColor = AppColors.TextPrimary
            )
        ) {
            Text(
                stringResource(
                    if (isCasting) R.string.cast_launching else R.string.cast_button_label
                )
            )
        }
        SeriesDetailFavoriteAction(series = series, onToggleFavorite = onToggleFavorite)
    }
}

@Composable
private fun SeriesDetailFavoriteAction(
    series: Series,
    onToggleFavorite: () -> Unit
) {
    TvIconButton(
        onClick = onToggleFavorite,
        colors = ButtonDefaults.colors(
            containerColor = if (series.isFavorite) AppColors.Brand else AppColors.SurfaceEmphasis,
            contentColor = if (series.isFavorite) Color.Black else AppColors.TextSecondary
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
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) AppColors.BrandMuted else AppColors.SurfaceElevated,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.SurfaceEmphasis,
            focusedContentColor = AppColors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) AppColors.Brand else AppColors.Outline),
                shape = RoundedCornerShape(999.dp)
            ),
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, AppColors.Focus),
                shape = RoundedCornerShape(999.dp)
            )
        )
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
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(18.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = AppColors.SurfaceElevated,
                focusedContainerColor = AppColors.SurfaceEmphasis
            ),
            modifier = Modifier.weight(1f)
        ) {
            EpisodeRowCard(
                episode = episode,
                fallbackImageUrl = fallbackImageUrl,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(
                onClick = onDownload,
                colors = ButtonDefaults.colors(
                    containerColor = AppColors.SurfaceEmphasis,
                    contentColor = AppColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.download_button_label))
            }
            TvButton(onClick = onCopyUrl) {
                Text(stringResource(R.string.stream_url_copy))
            }
            TvButton(
                onClick = onCast,
                enabled = !isCasting
            ) {
                Text(
                    stringResource(
                        if (isCasting) R.string.cast_launching else R.string.cast_button_label
                    )
                )
            }
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

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
