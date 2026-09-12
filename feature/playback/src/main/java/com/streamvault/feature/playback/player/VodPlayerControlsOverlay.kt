package com.streamvault.feature.playback.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.feature.playback.R
import com.streamvault.feature.playback.player.overlay.PlayerBackButton

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VodPlayerControlsOverlay(
    visible: Boolean,
    title: String,
    overlayState: VodOverlayState,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    seekPreview: SeekPreviewState,
    playButtonFocusRequester: FocusRequester,
    showBackButton: Boolean = false,
    onBackToMenu: () -> Unit = {},
    onClose: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekPreviousChapter: () -> Unit,
    onSeekNextChapter: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onOpenSubtitleTracks: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenSettings: () -> Unit,
    onSeekToPosition: (Long) -> Unit,
    onSetScrubbingMode: (Boolean) -> Unit,
    onSeekPreviewPositionChanged: (Long?) -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vodControlsLabel = stringResource(R.string.player_vod_controls)
    val settingsLabel = stringResource(R.string.player_playback_settings)
    val closeLabel = stringResource(R.string.player_close_controls)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = vodControlsLabel }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.92f)
                            )
                        )
                )
            )

            if (showBackButton) {
                PlayerBackButton(
                    onClick = onBackToMenu,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(24.dp)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        overlayState.currentChapter?.let { chapter ->
                            Text(
                                text = stringResource(R.string.player_chapter_title, chapter.index, chapter.title),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = formatVodDuration(currentPositionMs),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                        Text(
                            text = "-${formatVodDuration((durationMs - currentPositionMs).coerceAtLeast(0L))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.64f)
                        )
                    }
                }

                VodTimeline(
                    chapters = overlayState.chapters,
                    currentChapter = overlayState.currentChapter,
                    currentPositionMs = currentPositionMs,
                    durationMs = durationMs,
                    onSeekToPosition = onSeekToPosition,
                    onSetScrubbingMode = onSetScrubbingMode,
                    onSeekPreviewPositionChanged = onSeekPreviewPositionChanged
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VodTransportControls(
                        isPlaying = isPlaying,
                        playButtonFocusRequester = playButtonFocusRequester,
                        canSeekPreviousChapter = overlayState.previousChapterTargetMs != null,
                        canSeekNextChapter = overlayState.nextChapterTargetMs != null,
                        onSeekPreviousChapter = onSeekPreviousChapter,
                        onSeekBackward = onSeekBackward,
                        onTogglePlayPause = onTogglePlayPause,
                        onSeekForward = onSeekForward,
                        onSeekNextChapter = onSeekNextChapter
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (overlayState.showChapterAction) {
                        VodActionButton(
                            icon = Icons.Default.MenuBook,
                            label = stringResource(R.string.player_chapters),
                            onClick = onOpenChapters
                        )
                    }
                    if (overlayState.showEpisodesAction) {
                        VodActionButton(
                            icon = Icons.Default.Tv,
                            label = stringResource(R.string.player_episodes),
                            onClick = onOpenEpisodes
                        )
                    }
                    if (overlayState.showSubtitleAction) {
                        VodActionButton(
                            icon = Icons.Default.ClosedCaption,
                            label = stringResource(R.string.player_subs),
                            onClick = onOpenSubtitleTracks
                        )
                    }
                    if (overlayState.showAudioAction) {
                        VodActionButton(
                            icon = Icons.Default.Audiotrack,
                            label = stringResource(R.string.player_audio),
                            onClick = onOpenAudioTracks
                        )
                    }
                    if (overlayState.showSettingsAction) {
                        VodControlButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.semantics { contentDescription = settingsLabel }
                        ) {
                            Icon(imageVector = Icons.Default.Settings, contentDescription = null)
                        }
                    }
                    VodControlButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { contentDescription = closeLabel }
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null)
                    }
                }
            }

            if (seekPreview.visible) {
                Text(
                    text = formatVodDuration(seekPreview.positionMs),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 162.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun VodActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    VodControlButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = label }
    ) {
        Icon(imageVector = icon, contentDescription = null)
    }
}
