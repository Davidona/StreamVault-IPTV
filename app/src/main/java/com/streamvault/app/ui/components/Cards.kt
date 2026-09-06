package com.streamvault.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import com.streamvault.app.ui.design.LocalAppShapes
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.app.R
import com.streamvault.app.ui.components.rememberCrossfadeImageModel
import com.streamvault.app.ui.components.shell.MoviePosterCard
import com.streamvault.app.ui.components.shell.SeriesPosterCard
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.app.ui.model.formatVodRatingLabel
import com.streamvault.app.ui.model.archivePlaybackCapability
import com.streamvault.app.ui.theme.AccentAmber
import com.streamvault.app.ui.theme.AccentCyan
import com.streamvault.app.ui.theme.AccentRed
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.theme.GradientOverlayBottom
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.Surface
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.SurfaceHighlight
import com.streamvault.app.ui.theme.TextPrimary
import com.streamvault.app.ui.theme.TextSecondary
import com.streamvault.app.ui.theme.TextTertiary
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.interaction.mouseClickable
import com.streamvault.app.ui.interaction.rememberTvInteractionSounds

private object ChannelProgressTicker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val nowMs = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000L)
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 30_000L),
        initialValue = System.currentTimeMillis()
    )
}

@Composable
fun FocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    width: Dp = 160.dp,
    height: Dp = 240.dp,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false,
    semanticsDescription: String? = null,
    semanticsStateDescription: String? = null,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val sounds = rememberTvInteractionSounds()
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) {
            if (isReorderMode && !isDragging) 1f else FocusSpec.FocusedScale
        } else {
            if (isDragging) FocusSpec.FocusedScale else 1f
        },
        animationSpec = tween(durationMillis = 160),
        label = "cardScale"
    )

    Surface(
        onClick = {
            sounds.playSelect()
            onClick()
        },
        onLongClick = onLongClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .width(width)
            .height(height)
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
                semanticsDescription?.let { contentDescription = it }
                semanticsStateDescription?.let { stateDescription = it }
            }
            .onFocusChanged {
                if (it.isFocused && !isFocused) {
                    sounds.playNavigate()
                }
                isFocused = it.isFocused
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = FocusSpec.PressedScale
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Surface,
            focusedContainerColor = SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(
                    width = if (isDragging) 4.dp else FocusSpec.CardBorderWidth,
                    color = if (isDragging) AccentAmber else FocusBorder
                ),
                shape = RoundedCornerShape(12.dp)
            )
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content(isFocused)
        }
    }
}

@Composable
fun ChannelCard(
    channel: Channel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isLocked: Boolean = false,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false,
    isRecording: Boolean = false,
    isScheduledRecording: Boolean = false
) {
    val nowMs by ChannelProgressTicker.nowMs.collectAsStateWithLifecycle()
    val channelCardShape = LocalAppShapes.current.small
    val hasUsableArchive = channel.archivePlaybackCapability().canBuildReplayCandidate
    val channelDescription = buildString {
        append(
            channel.number.takeIf { it > 0 }?.let {
                stringResource(R.string.a11y_channel_with_number, it, channel.name)
            } ?: channel.name
        )
        if (!isLocked) {
            channel.currentProgram?.title?.takeIf { it.isNotBlank() }?.let {
                append(". ")
                append(stringResource(R.string.a11y_now_playing, it))
            }
            if (channel.isFavorite) {
                append(". ")
                append(stringResource(R.string.a11y_favorite))
            }
            if (hasUsableArchive) {
                append(". ")
                append(stringResource(R.string.a11y_catch_up_available))
            }
        }
    }
    FocusableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        width = 220.dp,
        height = 124.dp,
        isReorderMode = isReorderMode,
        isDragging = isDragging,
        semanticsDescription = channelDescription,
        semanticsStateDescription = if (isLocked) stringResource(R.string.a11y_locked) else null
    ) { isFocused ->
        if (!isLocked) {
            ChannelLogoBadge(
                channelName = channel.name,
                logoUrl = channel.logoUrl,
                shape = channelCardShape,
                textStyle = MaterialTheme.typography.titleMedium,
                textColor = TextSecondary,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, GradientOverlayBottom)
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = if (isLocked) stringResource(R.string.card_locked_channel) else channel.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (isFocused) TextPrimary else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!isLocked) {
                channel.currentProgram?.let { program ->
                    Text(
                        text = program.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val totalDuration = program.endTime - program.startTime
                    val elapsed = nowMs - program.startTime
                    val progress = if (totalDuration > 0) elapsed.toFloat() / totalDuration else 0f

                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp)),
                        color = AccentCyan,
                        trackColor = SurfaceHighlight
                    )
                }
            }
        }

        if (!isLocked) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (channel.isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = AccentAmber,
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (channel.errorCount > 0) {
                    StatusPill(label = stringResource(R.string.badge_error), containerColor = AccentRed, cornerRadius = 4.dp, horizontalPadding = 6.dp, verticalPadding = 2.dp)
                }
                if (hasUsableArchive) {
                    StatusPill(label = stringResource(R.string.badge_catch_up), containerColor = Primary, contentColor = Color.Black, cornerRadius = 4.dp, horizontalPadding = 6.dp, verticalPadding = 2.dp)
                }
                if (isRecording) {
                    StatusPill(label = stringResource(R.string.badge_recording), containerColor = AccentRed, cornerRadius = 4.dp, horizontalPadding = 6.dp, verticalPadding = 2.dp)
                } else if (isScheduledRecording) {
                    StatusPill(label = stringResource(R.string.badge_scheduled), containerColor = AccentAmber, cornerRadius = 4.dp, horizontalPadding = 6.dp, verticalPadding = 2.dp)
                }
                StatusPill(
                    label = stringResource(R.string.card_live_badge),
                    containerColor = AccentRed,
                    cornerRadius = 4.dp,
                    horizontalPadding = 6.dp,
                    verticalPadding = 2.dp
                )
            }
        }

        if (isLocked) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StatusPill(label = stringResource(R.string.home_locked_short), containerColor = SurfaceHighlight, cornerRadius = 4.dp, horizontalPadding = 6.dp, verticalPadding = 2.dp)
            }
        }
    }
}

@Composable
fun MovieCard(
    movie: Movie,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isLocked: Boolean = false,
    showTypeBadge: Boolean = false,
    watchProgress: Float = 0f,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false,
    width: Dp = 136.dp,
    height: Dp = 204.dp
) {
    val movieDescription = buildString {
        append(movie.name)
        movie.year?.takeIf { it.isNotBlank() }?.let {
            append(". ")
            append(it)
        }
        if (movie.isFavorite && !isLocked) {
            append(". ")
            append(stringResource(R.string.a11y_favorite))
        }
    }
    FocusableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        width = width,
        height = height,
        isReorderMode = isReorderMode,
        isDragging = isDragging,
        semanticsDescription = movieDescription,
        semanticsStateDescription = if (isLocked) stringResource(R.string.a11y_locked) else null
    ) {
        if (!isLocked) {
            MoviePosterCard(
                movie = movie,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                StatusPill(
                    label = if (isLocked) stringResource(R.string.home_locked_short) else stringResource(R.string.badge_movie),
                    containerColor = SurfaceHighlight,
                    cornerRadius = 4.dp,
                    horizontalPadding = 6.dp,
                    verticalPadding = 2.dp
                )
            }
        }

        if (watchProgress > 0f && !isLocked) {
            LinearProgressIndicator(
                progress = { watchProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp),
                color = Primary,
                trackColor = Color.Transparent
            )
        }

        if (!isLocked) {
            if (showTypeBadge) {
                StatusPill(
                    label = stringResource(R.string.badge_movie),
                    containerColor = Color.Black.copy(alpha = 0.72f),
                    cornerRadius = 4.dp,
                    horizontalPadding = 6.dp,
                    verticalPadding = 2.dp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 8.dp)
                )
            }

            if (movie.rating > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = formatVodRatingLabel(movie.rating),
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentAmber
                    )
                }
            }

            if (movie.isFavorite) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = AccentAmber,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SeriesCard(
    series: Series,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isLocked: Boolean = false,
    showTypeBadge: Boolean = false,
    watchProgress: Float = 0f,
    subtitle: String? = null,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false,
    width: Dp = 136.dp,
    height: Dp = 204.dp
) {
    val seriesDescription = buildString {
        append(series.name)
        subtitle?.takeIf { it.isNotBlank() }?.let {
            append(". ")
            append(it)
        } ?: series.genre?.takeIf { it.isNotBlank() }?.let {
            append(". ")
            append(it)
        }
        if (series.isFavorite && !isLocked) {
            append(". ")
            append(stringResource(R.string.a11y_favorite))
        }
    }
    FocusableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        width = width,
        height = height,
        isReorderMode = isReorderMode,
        isDragging = isDragging,
        semanticsDescription = seriesDescription,
        semanticsStateDescription = if (isLocked) stringResource(R.string.a11y_locked) else null
    ) {
        if (!isLocked) {
            SeriesPosterCard(
                series = series,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                StatusPill(
                    label = if (isLocked) stringResource(R.string.home_locked_short) else stringResource(R.string.badge_series),
                    containerColor = SurfaceHighlight,
                    cornerRadius = 4.dp,
                    horizontalPadding = 6.dp,
                    verticalPadding = 2.dp
                )
            }
        }

        if (watchProgress > 0f && !isLocked) {
            LinearProgressIndicator(
                progress = { watchProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp),
                color = Primary,
                trackColor = Color.Transparent
            )
        }

        if (!isLocked) {
            if (showTypeBadge) {
                StatusPill(
                    label = stringResource(R.string.badge_series),
                    containerColor = Color.Black.copy(alpha = 0.72f),
                    cornerRadius = 4.dp,
                    horizontalPadding = 6.dp,
                    verticalPadding = 2.dp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 8.dp)
                )
            }

            if (series.rating > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = formatVodRatingLabel(series.rating),
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentAmber
                    )
                }
            }

            if (series.isFavorite) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = AccentAmber,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}
