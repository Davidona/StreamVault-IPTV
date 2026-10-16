package com.streamvault.feature.catalog.presentation.components

import com.streamvault.core.ui.design.BrowseShapes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import com.streamvault.domain.util.isPlaybackComplete
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.feature.catalog.R
import com.streamvault.core.ui.image.ChannelLogoBadge
import com.streamvault.core.ui.accessibility.rememberReducedMotionEnabled
import com.streamvault.feature.catalog.presentation.components.channelProgressFraction
import com.streamvault.core.ui.image.rememberCrossfadeImageModel
import com.streamvault.core.ui.design.BrowseColors as AppColors
import com.streamvault.core.ui.design.AppMotion
import com.streamvault.core.ui.design.BrowseFocusSpec as FocusSpec
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.interaction.rememberTvInteractionSounds
import com.streamvault.core.ui.components.shell.ContentMetadataStrip
import com.streamvault.core.ui.components.shell.StatusPill
import com.streamvault.domain.playback.archivePlaybackCapability
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series

@Composable
fun LiveChannelRowCard(
    highlighted: Boolean = false,
    channel: Channel,
    nowMs: Long,
    sourceBadgeLabel: String? = null,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 68.dp
) {
    val isUltraCompact = rowHeight <= 60.dp
    val isDense = rowHeight <= 56.dp
    val contentPadding = if (isUltraCompact) 5.dp else 6.dp
    val horizontalPadding = if (isUltraCompact) 8.dp else 10.dp
    val logoWidth = if (isDense) 42.dp else if (isUltraCompact) 46.dp else 52.dp
    val logoPadding = if (isDense) 5.dp else if (isUltraCompact) 6.dp else 8.dp
    val contentSpacing = if (isUltraCompact) 8.dp else 10.dp
    val badgeSpacing = if (isUltraCompact) 3.dp else 4.dp
    val hasUsableArchive = channel.archivePlaybackCapability().canBuildReplayCandidate

    Box(
        modifier = modifier
            .clip(BrowseShapes.Panel)
            .background(if (highlighted) AppColors.SurfaceEmphasis else AppColors.SurfaceElevated)
            .fillMaxWidth()
            .height(rowHeight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = contentPadding),
            horizontalArrangement = Arrangement.spacedBy(contentSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(logoWidth)
                    .fillMaxHeight()
                    .clip(BrowseShapes.Artwork)
            ) {
                ChannelLogoBadge(
                    channelName = channel.name,
                    logoUrl = channel.logoUrl,
                    backgroundColor = AppColors.SurfaceEmphasis,
                    contentPadding = PaddingValues(logoPadding),
                    textStyle = MaterialTheme.typography.titleLarge,
                    textColor = AppColors.TextSecondary,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (!isDense) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(badgeSpacing),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(label = stringResource(R.string.card_live_badge), containerColor = AppColors.Live)
                        sourceBadgeLabel?.takeIf { it.isNotBlank() }?.let { label ->
                            StatusPill(
                                label = label,
                                containerColor = AppColors.SurfaceEmphasis,
                                contentColor = AppColors.TextPrimary
                            )
                        }
                        if (channel.isFavorite) {
                            StatusPill(label = stringResource(R.string.badge_saved), containerColor = AppColors.Warning, contentColor = Color.Black)
                        }
                        if (hasUsableArchive) {
                            StatusPill(label = stringResource(R.string.badge_catch_up), containerColor = AppColors.Brand)
                        }
                    }
                }
                Text(
                    text = buildString {
                        val numberLabel = channel.number.takeIf { it > 0 }?.toString()?.padStart(2, '0')
                        if (numberLabel != null) {
                            append(numberLabel)
                            append("  ")
                        } else if (channel.number == 0) {
                            append("--  ")
                        }
                        append(channel.name)
                    },
                    style = if (isDense) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleSmall,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val program = channel.currentProgram
                if (program != null) {
                    Text(
                        text = program.title,
                        style = if (isDense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!isDense) {
                        LinearProgressIndicator(
                            progress = {
                                channelProgressFraction(
                                    nowMs = nowMs,
                                    startTimeMs = program.startTime,
                                    endTimeMs = program.endTime
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .clip(BrowseShapes.Control),
                            color = AppColors.Info,
                            trackColor = AppColors.SurfaceEmphasis
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.label_no_schedule),
                        style = if (isDense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                        color = AppColors.TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
fun LiveChannelRowSurface(
    channel: Channel,
    nowMs: Long,
    onClick: () -> Unit,
    sourceBadgeLabel: String? = null,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isLocked: Boolean = false,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false,
    rowHeight: Dp = 68.dp
) {
    var isFocused by remember { mutableStateOf(false) }
    val sounds = rememberTvInteractionSounds()
    val focusRequester = remember { FocusRequester() }
    val favoriteLabel = stringResource(R.string.a11y_favorite)
    val catchUpLabel = stringResource(R.string.a11y_catch_up_available)
    val lockedLabel = stringResource(R.string.a11y_locked)
    val hasUsableArchive = channel.archivePlaybackCapability().canBuildReplayCandidate
    val channelDescription = buildString {
        append(
            channel.number.takeIf { it > 0 }?.let {
                stringResource(R.string.a11y_channel_with_number, it, channel.name)
            } ?: channel.name
        )
        channel.currentProgram?.title?.takeIf { it.isNotBlank() }?.let {
            append(". ")
            append(stringResource(R.string.a11y_now_playing, it))
        }
        if (channel.isFavorite) {
            append(". ")
            append(favoriteLabel)
        }
        if (hasUsableArchive) {
            append(". ")
            append(catchUpLabel)
        }
    }
    val reducedMotionEnabled = rememberReducedMotionEnabled()
    val scale by animateFloatAsState(
        targetValue = if (isDragging) FocusSpec.FocusedScale else 1f,
        animationSpec = if (reducedMotionEnabled) snap() else AppMotion.FocusSpec,
        label = "liveRowScale"
    )

    Surface(
        onClick = {
            sounds.playSelect()
            onClick()
        },
        onLongClick = onLongClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .fillMaxWidth()
            .mouseClickable(
                focusRequester = focusRequester,
                onLongClick = onLongClick,
                onClick = {
                    sounds.playSelect()
                    onClick()
                }
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics(mergeDescendants = true) {
                contentDescription = channelDescription
                if (isLocked) {
                    stateDescription = lockedLabel
                }
            }
            .onFocusChanged {
                if (it.isFocused && !isFocused) {
                    sounds.playNavigate()
                }
                isFocused = it.isFocused
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(BrowseShapes.Control),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.SurfaceElevated,
            focusedContainerColor = AppColors.SurfaceEmphasis
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, AppColors.Outline), shape = BrowseShapes.Control),
            focusedBorder = Border(
                border = BorderStroke(
                    width = if (isDragging) 4.dp else FocusSpec.BorderWidth,
                    color = if (isDragging) AppColors.Warning else AppColors.Focus
                ),
                shape = BrowseShapes.Control
            )
        )
    ) {
        Box {
            LiveChannelRowCard(
                highlighted = isFocused,
                channel = channel,
                nowMs = nowMs,
                sourceBadgeLabel = sourceBadgeLabel,
                modifier = Modifier.fillMaxWidth(),
                rowHeight = rowHeight
            )
            if (isLocked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AppColors.HeroBottom.copy(alpha = 0.82f)),
                    contentAlignment = Alignment.Center
                ) {
                    StatusPill(
                        label = stringResource(R.string.home_locked_short),
                        containerColor = AppColors.SurfaceEmphasis,
                        contentColor = AppColors.TextPrimary
                    )
                }
            }
            if (isReorderMode && isDragging) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    StatusPill(
                        label = stringResource(R.string.badge_moving),
                        containerColor = AppColors.Warning,
                        contentColor = Color.Black
                    )
                }
            }
            if (!isLocked && !isReorderMode && channel.isFavorite) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = AppColors.MediaWarning,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MoviePosterCard(movie: Movie, modifier: Modifier = Modifier) {
    PosterCard(
        imageUrl = movie.posterUrl,
        title = movie.name,
        subtitle = movie.year,
        modifier = modifier
    )
}

@Composable
fun SeriesPosterCard(series: Series, modifier: Modifier = Modifier) {
    PosterCard(
        imageUrl = series.posterUrl,
        title = series.name,
        subtitle = series.releaseDate ?: series.genre,
        modifier = modifier
    )
}

@Composable
fun EpisodeRowCard(
    episode: Episode,
    modifier: Modifier = Modifier,
    fallbackImageUrl: String? = null
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val previewWidth = if (screenWidth < 700.dp) 116.dp else 154.dp
    val durationMs = episode.durationSeconds.toLong() * 1000L
    val showProgress = episode.watchProgress > 5000L && durationMs > 0L &&
        !isPlaybackComplete(episode.watchProgress, durationMs)
    val displayUrl = episode.coverUrl.takeIf { !it.isNullOrBlank() } ?: fallbackImageUrl
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(BrowseShapes.Panel)
            .background(
                Brush.horizontalGradient(
                    listOf(AppColors.SurfaceElevated, AppColors.Surface)
                )
            )
            .border(1.dp, AppColors.Outline, BrowseShapes.Panel)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(previewWidth).aspectRatio(16f / 9f)
                    .clip(BrowseShapes.Artwork)
                    .background(AppColors.SurfaceEmphasis)
                    .border(1.dp, AppColors.Outline, BrowseShapes.Artwork),
                contentAlignment = Alignment.Center
            ) {
                if (displayUrl != null) {
                    AsyncImage(
                        model = rememberCrossfadeImageModel(displayUrl),
                        contentDescription = episode.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Text(
                    text = stringResource(R.string.label_episode_full, episode.episodeNumber),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(7.dp)
                        .background(AppColors.HeroBottom.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                if (showProgress) {
                    LinearProgressIndicator(
                        progress = { (episode.watchProgress.toFloat() / durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                        color = AppColors.Brand,
                        trackColor = AppColors.SurfaceEmphasis
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                episode.duration?.takeIf { it.isNotBlank() }?.let { duration ->
                    Text(
                        duration,
                        style = MaterialTheme.typography.labelMedium,
                        color = AppColors.Brand
                    )
                }
                episode.plot?.takeIf { it.isNotBlank() }?.let { plot ->
                    Text(
                        text = plot,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterCard(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier
) {
    val posterShape = BrowseShapes.Artwork
    var imageLoaded by remember(imageUrl) { mutableStateOf(false) }
    var imageFailed by remember(imageUrl) { mutableStateOf(false) }
    val showFallback = imageUrl.isNullOrBlank() || imageFailed || !imageLoaded

    Box(
        modifier = modifier
            .clip(posterShape)
            .background(Brush.linearGradient(listOf(AppColors.SurfaceEmphasis, AppColors.Surface, AppColors.Brand.copy(alpha = 0.18f))))
    ) {
        // Fallback letter: only shown while no URL, still loading, or load failed
        if (showFallback) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title.take(1).uppercase(),
                    style = MaterialTheme.typography.displayLarge,
                    color = AppColors.TextSecondary
                )
            }
        }
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = rememberCrossfadeImageModel(imageUrl),
                contentDescription = title,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(posterShape),
                contentScale = ContentScale.Crop,
                onSuccess = { imageLoaded = true },
                onError = { imageFailed = true }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.52f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, AppColors.HeroBottom)
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

