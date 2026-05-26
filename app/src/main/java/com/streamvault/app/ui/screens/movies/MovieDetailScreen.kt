package com.streamvault.app.ui.screens.movies

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.app.R
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.components.rememberCrossfadeImageModel
import com.streamvault.app.util.formatPositionMs
import com.streamvault.app.ui.components.shell.ContentMetadataStrip
import com.streamvault.app.ui.components.shell.ExternalRatingsStrip
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.requestFocusSafely
import com.streamvault.app.ui.model.formatVodRatingLabel
import com.streamvault.domain.model.ExternalRatings
import com.streamvault.domain.model.Movie
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvIconButton
import com.streamvault.app.download.OfflineDownloadItem
import com.streamvault.app.download.OfflineDownloadStatus

@Composable
fun MovieDetailScreen(
    onPlay: (Movie) -> Unit,
    onBack: () -> Unit,
    viewModel: MovieDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val movie = uiState.movie

    when {
        uiState.isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.Canvas),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.movie_loading_details), color = AppColors.TextSecondary)
            }
        }

        movie == null -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.Canvas),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = uiState.error ?: stringResource(R.string.movie_not_found),
                    color = AppColors.Live
                )
            }
        }

        else -> {
            MovieDetailContent(
                movie = movie,
                hasResume = uiState.hasResume,
                resumePositionMs = uiState.resumePositionMs,
                externalRatings = uiState.externalRatings,
                isLoadingExternalRatings = uiState.isLoadingExternalRatings,
                relatedContent = uiState.relatedContent,
                isDownloadQueued = uiState.isDownloadQueued,
                downloadItem = uiState.downloadItem,
                downloadStatus = uiState.downloadStatus,
                downloadMessage = uiState.downloadMessage,
                onPlay = { onPlay(movie) },
                onDownload = viewModel::downloadMovie,
                onToggleFavorite = viewModel::toggleFavorite,
                onRelatedClick = onPlay,
                onBack = onBack
            )
        }
    }
}

@Composable
private fun MovieDetailContent(
    movie: Movie,
    hasResume: Boolean,
    resumePositionMs: Long,
    externalRatings: ExternalRatings,
    isLoadingExternalRatings: Boolean,
    relatedContent: List<Movie>,
    isDownloadQueued: Boolean,
    downloadItem: OfflineDownloadItem?,
    downloadStatus: OfflineDownloadStatus?,
    downloadMessage: String?,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRelatedClick: (Movie) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val playButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(movie.id) {
        playButtonFocusRequester.requestFocusSafely(
            tag = "MovieDetailScreen",
            target = "Play button"
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas)
    ) {
        val compactLayout = !isTelevisionDevice && maxWidth < 900.dp
        val heroHeight = when {
            maxWidth < 700.dp -> 240.dp
            !isTelevisionDevice && maxWidth < 900.dp -> 300.dp
            else -> 440.dp
        }
        val contentPadding = if (compactLayout) {
            PaddingValues(horizontal = 16.dp, vertical = 20.dp)
        } else {
            PaddingValues(horizontal = 56.dp, vertical = 36.dp)
        }
        val posterWidth = if (compactLayout) 148.dp else 240.dp

        AsyncImage(
            model = rememberCrossfadeImageModel(movie.backdropUrl ?: movie.posterUrl),
            contentDescription = movie.name,
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
                        colors = listOf(Color.Transparent, AppColors.HeroTop, AppColors.HeroBottom)
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
                    Text(stringResource(R.string.movie_detail_back))
                }
            }

            item {
                if (compactLayout) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        MoviePoster(movie = movie, posterWidth = posterWidth)
                        MovieDetailHeroText(
                            movie = movie,
                            hasResume = hasResume,
                            resumePositionMs = resumePositionMs,
                            externalRatings = externalRatings,
                            isLoadingExternalRatings = isLoadingExternalRatings,
                            onPlay = onPlay,
                            onDownload = onDownload,
                            onToggleFavorite = onToggleFavorite,
                            playButtonFocusRequester = playButtonFocusRequester,
                            isDownloadQueued = isDownloadQueued,
                            downloadItem = downloadItem,
                            downloadStatus = downloadStatus,
                            downloadMessage = downloadMessage,
                            onPlayTrailer = {
                                resolveTrailerUrl(movie.youtubeTrailer)?.let { trailerUrl ->
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trailerUrl)))
                                    }
                                }
                            }
                        )
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        MoviePoster(movie = movie, posterWidth = posterWidth)
                        MovieDetailHeroText(
                            movie = movie,
                            hasResume = hasResume,
                            resumePositionMs = resumePositionMs,
                            externalRatings = externalRatings,
                            isLoadingExternalRatings = isLoadingExternalRatings,
                            onPlay = onPlay,
                            onDownload = onDownload,
                            onToggleFavorite = onToggleFavorite,
                            playButtonFocusRequester = playButtonFocusRequester,
                            isDownloadQueued = isDownloadQueued,
                            downloadItem = downloadItem,
                            downloadStatus = downloadStatus,
                            downloadMessage = downloadMessage,
                            onPlayTrailer = {
                                resolveTrailerUrl(movie.youtubeTrailer)?.let { trailerUrl ->
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trailerUrl)))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            if (relatedContent.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.movie_detail_related),
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary
                    )
                }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(relatedContent, key = { it.id }) { related ->
                            TvClickableSurface(
                                onClick = { onRelatedClick(related) },
                                modifier = Modifier.width(120.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(2f / 3f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(AppColors.SurfaceElevated)
                                    ) {
                                        AsyncImage(
                                            model = rememberCrossfadeImageModel(related.posterUrl ?: related.backdropUrl),
                                            contentDescription = related.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    Text(
                                        text = related.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = AppColors.TextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
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

@Composable
private fun MoviePoster(
    movie: Movie,
    posterWidth: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .width(posterWidth)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(24.dp))
            .background(AppColors.SurfaceElevated)
    ) {
        AsyncImage(
            model = rememberCrossfadeImageModel(movie.posterUrl ?: movie.backdropUrl),
            contentDescription = movie.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun MovieDetailHeroText(
    movie: Movie,
    hasResume: Boolean,
    resumePositionMs: Long,
    externalRatings: ExternalRatings,
    isLoadingExternalRatings: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onToggleFavorite: () -> Unit,
    playButtonFocusRequester: FocusRequester,
    isDownloadQueued: Boolean,
    downloadItem: OfflineDownloadItem?,
    downloadStatus: OfflineDownloadStatus?,
    downloadMessage: String?,
    onPlayTrailer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasTrailer = !movie.youtubeTrailer.isNullOrBlank()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(label = stringResource(R.string.nav_movies), containerColor = AppColors.BrandMuted)
            movie.rating.takeIf { it > 0f }?.let {
                StatusPill(
                    label = formatVodRatingLabel(it),
                    containerColor = AppColors.Warning,
                    contentColor = Color.Black
                )
            }
            movie.year?.takeIf { it.isNotBlank() }?.let {
                StatusPill(label = it, containerColor = AppColors.SurfaceEmphasis)
            }
        }

        Text(
            text = movie.name,
            style = MaterialTheme.typography.displayMedium,
            color = AppColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        ContentMetadataStrip(
            values = listOf(
                movie.releaseDate.orEmpty(),
                movie.duration.orEmpty(),
                movie.genre.orEmpty()
            )
        )

        ExternalRatingsStrip(
            ratings = externalRatings,
            isLoading = isLoadingExternalRatings
        )

        MovieFactGrid(movie = movie)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TvButton(
                onClick = onPlay,
                modifier = Modifier.focusRequester(playButtonFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = AppColors.Brand,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = if (hasResume) {
                        stringResource(R.string.movie_detail_resume_from, formatPositionMs(resumePositionMs))
                    } else {
                        stringResource(R.string.movie_detail_play)
                    }
                )
            }
            if (hasTrailer) {
                TvButton(
                    onClick = onPlayTrailer,
                    colors = ButtonDefaults.colors(
                        containerColor = AppColors.SurfaceEmphasis,
                        contentColor = AppColors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.movie_detail_trailer))
                }
            }
            TvButton(
                onClick = onDownload,
                enabled = !isDownloadQueued && (downloadStatus == null || downloadStatus == OfflineDownloadStatus.PAUSED),
                colors = ButtonDefaults.colors(
                    containerColor = AppColors.SurfaceEmphasis,
                    contentColor = AppColors.TextPrimary,
                    disabledContainerColor = AppColors.SurfaceElevated,
                    disabledContentColor = AppColors.TextTertiary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isDownloadQueued) {
                        stringResource(R.string.vod_download_preparing)
                    } else if (downloadStatus != null) {
                        downloadStatus.buttonLabel(downloadItem)
                    } else {
                        stringResource(R.string.vod_download)
                    }
                )
            }
            TvIconButton(
                onClick = onToggleFavorite,
                colors = ButtonDefaults.colors(
                    containerColor = if (movie.isFavorite) AppColors.Brand else AppColors.SurfaceEmphasis,
                    contentColor = if (movie.isFavorite) Color.White else AppColors.TextSecondary
                )
            ) {
                Icon(
                    imageVector = if (movie.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(
                        if (movie.isFavorite) R.string.favorites_remove else R.string.favorites_add
                    )
                )
            }
        }

        downloadMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = movie.plot?.ifBlank { stringResource(R.string.movie_plot_fallback) }
                ?: stringResource(R.string.movie_plot_fallback),
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextSecondary,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OfflineDownloadStatus.buttonLabel(item: OfflineDownloadItem?): String {
    val percent = item?.progressPercent
    return when (this) {
        OfflineDownloadStatus.PENDING,
        OfflineDownloadStatus.RUNNING -> percent?.let {
            stringResource(R.string.vod_download_downloading_progress, it)
        } ?: stringResource(R.string.vod_download_downloading)
        OfflineDownloadStatus.PAUSED -> percent?.let {
            stringResource(R.string.vod_download_resume_progress, it)
        } ?: stringResource(R.string.vod_download_resume)
        OfflineDownloadStatus.SUCCESSFUL -> stringResource(R.string.vod_download_downloaded)
        OfflineDownloadStatus.FAILED,
        OfflineDownloadStatus.UNKNOWN -> stringResource(R.string.vod_download)
    }
}

private fun resolveTrailerUrl(rawTrailer: String?): String? {
    val trailer = rawTrailer?.trim().orEmpty()
    if (trailer.isBlank()) return null
    return when {
        trailer.startsWith("http://", ignoreCase = true) || trailer.startsWith("https://", ignoreCase = true) -> trailer
        trailer.startsWith("youtu.be/", ignoreCase = true) -> "https://$trailer"
        trailer.startsWith("www.youtube.com/", ignoreCase = true) || trailer.startsWith("youtube.com/", ignoreCase = true) -> "https://$trailer"
        else -> "https://www.youtube.com/watch?v=$trailer"
    }
}

@Composable
private fun MovieFactGrid(movie: Movie) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MovieFactRow(label = stringResource(R.string.movie_detail_director), value = movie.director)
        MovieFactRow(label = stringResource(R.string.movie_detail_release_date), value = movie.releaseDate)
        MovieFactRow(label = stringResource(R.string.movie_detail_duration), value = movie.duration)
        MovieFactRow(label = stringResource(R.string.movie_detail_genre), value = movie.genre)
        MovieFactRow(label = stringResource(R.string.movie_detail_cast), value = movie.cast)
    }
}

@Composable
private fun MovieFactRow(
    label: String,
    value: String?
) {
    val resolvedValue = value?.takeIf { it.isNotBlank() } ?: stringResource(R.string.movie_detail_unknown)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.TextPrimary,
            modifier = Modifier.width(180.dp)
        )
        Text(
            text = resolvedValue,
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
