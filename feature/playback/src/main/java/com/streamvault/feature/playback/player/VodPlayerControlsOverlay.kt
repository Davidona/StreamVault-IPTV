package com.streamvault.feature.playback.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.feature.playback.R

@Composable
internal fun VodPlayerControlsOverlay(
    visible: Boolean,
    title: String,
    overlayState: VodOverlayState,
    episodeHeader: VodEpisodeHeader?,
    badges: List<String>,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    seekPreview: SeekPreviewState,
    playButtonFocusRequester: FocusRequester,
    onTogglePlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onPlayPreviousEpisode: () -> Unit = {},
    onPlayNextEpisode: () -> Unit = {},
    onOpenSubtitleTracks: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenSettings: () -> Unit,
    onSeekToPosition: (Long) -> Unit,
    onSetScrubbingMode: (Boolean) -> Unit,
    onSeekPreviewPositionChanged: (Long?) -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalAppSpacing.current
    val timelineFocusRequester = remember { FocusRequester() }
    val vodControlsLabel = stringResource(R.string.player_vod_controls)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                        onUserInteraction()
                    }
                    false
                }
                .semantics { contentDescription = vodControlsLabel }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VodScrimBrush)
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xl),
                verticalAlignment = Alignment.Top
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    VodHeader(
                        title = title,
                        episode = episodeHeader,
                        currentChapter = overlayState.currentChapter
                    )
                }
                VodBadges(
                    badges = badges,
                    modifier = Modifier.padding(top = VOD_OVERLAY_VERTICAL_PADDING, end = VOD_OVERLAY_HORIZONTAL_PADDING)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = VOD_OVERLAY_HORIZONTAL_PADDING, vertical = VOD_OVERLAY_VERTICAL_PADDING),
                verticalArrangement = Arrangement.spacedBy(VOD_OVERLAY_ROW_GAP)
            ) {
                VodEndsAtRow(positionMs = currentPositionMs, durationMs = durationMs, playbackSpeed = playbackSpeed)
                VodTimeline(
                    chapters = overlayState.chapters,
                    currentChapter = overlayState.currentChapter,
                    currentPositionMs = currentPositionMs,
                    durationMs = durationMs,
                    timelineFocusRequester = timelineFocusRequester,
                    playButtonFocusRequester = playButtonFocusRequester,
                    onSeekToPosition = onSeekToPosition,
                    onSetScrubbingMode = onSetScrubbingMode,
                    onSeekPreviewPositionChanged = onSeekPreviewPositionChanged,
                    onUserInteraction = onUserInteraction
                )
                VodTimeRow(positionMs = currentPositionMs, durationMs = durationMs)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(VOD_BUTTON_ROW_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VodTransportControls(
                        isPlaying = isPlaying,
                        showChapterSkip = overlayState.showChapterSkipActions,
                        playButtonFocusRequester = playButtonFocusRequester,
                        timelineFocusRequester = timelineFocusRequester,
                        onPreviousChapter = onPreviousChapter,
                        onSeekBackward = onSeekBackward,
                        onTogglePlayPause = onTogglePlayPause,
                        onSeekForward = onSeekForward,
                        onNextChapter = onNextChapter
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    VodActionButtons(
                        overlayState = overlayState,
                        onPlayPreviousEpisode = onPlayPreviousEpisode,
                        onPlayNextEpisode = onPlayNextEpisode,
                        onOpenAudioTracks = onOpenAudioTracks,
                        onOpenSubtitleTracks = onOpenSubtitleTracks,
                        onOpenChapters = onOpenChapters,
                        onOpenEpisodes = onOpenEpisodes,
                        onOpenSettings = onOpenSettings
                    )
                }
            }

            if (seekPreview.visible) {
                Text(
                    text = formatVodDuration(seekPreview.positionMs),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = VOD_TIMELINE_BOTTOM_OFFSET + VOD_TIMELINE_HEIGHT + spacing.xs)
                        .clip(RoundedCornerShape(spacing.sm))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = spacing.md, vertical = spacing.xs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/** End-side group: episode skips, audio, subtitles, chapter list, episode list, settings. */
@Composable
private fun VodActionButtons(
    overlayState: VodOverlayState,
    onPlayPreviousEpisode: () -> Unit,
    onPlayNextEpisode: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenSubtitleTracks: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (overlayState.showPreviousEpisodeAction) {
            VodRoundButton(
                icon = Icons.Default.SkipPrevious,
                label = stringResource(R.string.player_previous_episode),
                onClick = onPlayPreviousEpisode
            )
        }
        if (overlayState.showNextEpisodeAction) {
            VodRoundButton(
                icon = Icons.Default.SkipNext,
                label = stringResource(R.string.player_next_episode),
                onClick = onPlayNextEpisode
            )
        }
        if (overlayState.showAudioAction) {
            VodRoundButton(
                icon = Icons.Default.Audiotrack,
                label = stringResource(R.string.player_audio),
                onClick = onOpenAudioTracks
            )
        }
        if (overlayState.showSubtitleAction) {
            VodRoundButton(
                icon = Icons.Default.ClosedCaption,
                label = stringResource(R.string.player_subs),
                onClick = onOpenSubtitleTracks
            )
        }
        if (overlayState.showChapterAction) {
            VodRoundButton(
                icon = Icons.Default.MenuBook,
                label = stringResource(R.string.player_chapters),
                onClick = onOpenChapters
            )
        }
        if (overlayState.showEpisodesAction) {
            VodRoundButton(
                icon = Icons.Default.VideoLibrary,
                label = stringResource(R.string.player_episodes),
                onClick = onOpenEpisodes
            )
        }
        if (overlayState.showSettingsAction) {
            VodRoundButton(
                icon = Icons.Default.Settings,
                label = stringResource(R.string.player_playback_settings),
                onClick = onOpenSettings
            )
        }
    }
}
