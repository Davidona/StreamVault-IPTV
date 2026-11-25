package com.streamvault.feature.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import coil3.compose.AsyncImage
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.AppSpacing
import com.streamvault.core.ui.design.FocusSafeLazyColumn
import com.streamvault.core.ui.design.FocusSafeLazyRow
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Season
import com.streamvault.feature.playback.R

private val ThumbnailWidth = 96.dp
private val ThumbnailHeight = 54.dp

/**
 * Episode list in the shared panel frame: season chips scrolling horizontally on top when the series has several
 * seasons, and the episodes of the chosen season below. Focus starts on the playing episode. Choosing a season swaps
 * the list and keeps focus on its chip. Choosing an episode plays it and closes the frame; Back closes it. The
 * start-side key (Left, mirrored in RTL) never leaves the frame from the list.
 */
@Composable
internal fun VodEpisodeSheet(
    seasons: List<Season>,
    currentEpisodeId: Long,
    currentSeasonNumber: Int?,
    onSelectEpisode: (Episode) -> Unit,
    onDismiss: () -> Unit,
) {
    val availableSeasons = remember(seasons) { vodEpisodeSeasons(seasons) }
    var selectedSeasonNumber by remember(availableSeasons, currentSeasonNumber) {
        mutableStateOf(vodEpisodeInitialSeason(availableSeasons, currentSeasonNumber))
    }
    val seasonNumber = selectedSeasonNumber ?: return
    val episodes = availableSeasons.first { it.seasonNumber == seasonNumber }.episodes
    // Only the first opening moves focus to the playing episode: after a season is chosen, focus stays on its chip.
    var seasonChosen by remember { mutableStateOf(false) }
    val episodeFocus = remember { FocusRequester() }
    val selectedChipFocus = remember { FocusRequester() }
    val startKey = startSideKey()

    LaunchedEffect(Unit) {
        // Fallback for when the window had not taken its own initial focus yet at the first placement.
        withFrameNanos { }
        episodeFocus.requestFocusSafely("VodEpisodeSheet", "Playing episode row")
    }

    VodPanelFrame(
        title = stringResource(R.string.player_episodes),
        icon = Icons.Default.VideoLibrary,
        onDismiss = onDismiss,
    ) {
        if (availableSeasons.size > 1) {
            SeasonChips(
                seasons = availableSeasons,
                selectedSeasonNumber = seasonNumber,
                selectedChipFocus = selectedChipFocus,
                onSelect = {
                    seasonChosen = true
                    selectedSeasonNumber = it
                },
            )
        }
        // Own list state per season, started at its target row, so a season never opens scrolled by the previous one.
        key(seasonNumber) {
            val targetIndex = remember { if (seasonChosen) 0 else vodEpisodeTargetIndex(episodes, currentEpisodeId) }
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = vodSettingsFirstVisibleIndex(targetIndex),
            )
            FocusSafeLazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    // Every row is full width, so the start key has nowhere to go: swallow it instead of letting the
                    // focus search leave the frame.
                    .onKeyEvent { event -> event.key == startKey },
                state = listState,
                verticalArrangement = Arrangement.spacedBy(AppSpacing().xs),
            ) {
                items(episodes, key = { it.id }) { episode ->
                    VodPanelRow(
                        title = episode.title.ifBlank {
                            stringResource(R.string.player_episode_label, episode.episodeNumber)
                        },
                        modifier = if (!seasonChosen && episode.id == episodes[targetIndex].id) {
                            rememberPlacementFocus(episodeFocus, "VodEpisodeSheet")
                        } else {
                            Modifier
                        },
                        detail = episodeDetail(episode),
                        leading = { EpisodeThumbnail(episode) },
                        selected = episode.id == currentEpisodeId,
                        onClick = { onSelectEpisode(episode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonChips(
    seasons: List<Season>,
    selectedSeasonNumber: Int,
    selectedChipFocus: FocusRequester,
    onSelect: (Int) -> Unit,
) {
    val selectedIndex = seasons.indexOfFirst { it.seasonNumber == selectedSeasonNumber }
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 1).coerceAtLeast(0))
    FocusSafeLazyRow(
        modifier = Modifier
            .fillMaxWidth()
            // Coming back up from the episodes lands on the last chip used, the chosen season at first.
            .focusRestorer(selectedChipFocus),
        state = rowState,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing().sm),
    ) {
        items(seasons.size, key = { seasons[it].seasonNumber }) { index ->
            val season = seasons[index]
            val selected = season.seasonNumber == selectedSeasonNumber
            VodPanelChip(
                label = stringResource(R.string.player_season_label, season.seasonNumber),
                selected = selected,
                modifier = Modifier
                    .then(if (selected) Modifier.focusRequester(selectedChipFocus) else Modifier)
                    // The row ends at the frame edge: Left and Right at its ends must not search beyond it.
                    .focusProperties {
                        if (index == 0) left = FocusRequester.Cancel
                        if (index == seasons.lastIndex) right = FocusRequester.Cancel
                    },
                onClick = { onSelect(season.seasonNumber) },
            )
        }
    }
}

@Composable
private fun EpisodeThumbnail(episode: Episode) {
    Box(
        modifier = Modifier
            .size(width = ThumbnailWidth, height = ThumbnailHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.current.controlContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (episode.coverUrl.isNullOrBlank()) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
        } else {
            AsyncImage(
                model = episode.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun episodeDetail(episode: Episode): String {
    val label = stringResource(R.string.player_episode_label, episode.episodeNumber)
    val duration = episode.duration?.takeIf { it.isNotBlank() } ?: return label
    return "$label · $duration"
}
