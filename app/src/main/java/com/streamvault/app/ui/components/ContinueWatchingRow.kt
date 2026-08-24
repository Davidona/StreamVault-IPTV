package com.streamvault.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.app.R
import com.streamvault.core.ui.image.rememberCrossfadeImageModel
import com.streamvault.core.ui.theme.AccentCyan
import com.streamvault.core.ui.theme.GradientOverlayBottom
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.TextPrimary
import com.streamvault.core.ui.theme.TextSecondary
import com.streamvault.core.ui.theme.TextTertiary
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContinueWatchingRow(
    title: String? = null,
    items: List<PlaybackHistory>,
    onItemClick: (PlaybackHistory) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth().suppressParentVerticalScroll()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title ?: stringResource(R.string.continue_watching_title),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
        }

        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = items,
                key = { it.id },
                contentType = { it.contentType }
            ) { history ->
                ContinueWatchingTile(history = history, onClick = { onItemClick(history) })
            }
        }
    }
}

@Composable
private fun ContinueWatchingTile(
    history: PlaybackHistory,
    onClick: () -> Unit
) {
    val progress = if (history.totalDurationMs > 0) {
        (history.resumePositionMs.toFloat() / history.totalDurationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    FocusableCard(
        onClick = onClick,
        width = 208.dp,
        height = 117.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            // Fallback always visible; covered by AsyncImage on successful load
            Text(
                text = when (history.contentType) {
                    ContentType.LIVE -> stringResource(R.string.card_live_badge)
                    ContentType.MOVIE,
                    ContentType.VOD -> stringResource(R.string.nav_vod)
                    ContentType.SERIES, ContentType.SERIES_EPISODE -> stringResource(R.string.nav_series)
                },
                style = MaterialTheme.typography.titleLarge,
                color = TextSecondary
            )
            if (!history.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = rememberCrossfadeImageModel(history.posterUrl),
                    contentDescription = history.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, GradientOverlayBottom)))
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = if (progress > 0f) 10.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = history.title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (
                history.contentType == ContentType.SERIES_EPISODE &&
                history.seasonNumber != null &&
                history.episodeNumber != null
            ) {
                Text(
                    text = stringResource(
                        R.string.continue_watching_season_episode,
                        history.seasonNumber!!,
                        history.episodeNumber!!
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextTertiary,
                    maxLines = 1
                )
            }
        }

        if (progress > 0f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp),
                color = AccentCyan,
                trackColor = Color.Transparent
            )
        }
    }
}
