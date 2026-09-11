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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvIconButton

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
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "VOD playback controls" }
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

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 42.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        overlayState.currentChapter?.let { chapter ->
                            Text(
                                text = "Chapter ${chapter.index} · ${chapter.title}",
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
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
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (overlayState.showChapterAction) {
                        VodActionButton(
                            icon = Icons.Default.MenuBook,
                            label = "Chapters",
                            onClick = onOpenChapters
                        )
                    }
                    if (overlayState.showEpisodesAction) {
                        VodActionButton(
                            icon = Icons.Default.Tv,
                            label = "Episodes",
                            onClick = onOpenEpisodes
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (overlayState.showSubtitleAction) {
                        VodActionButton(
                            icon = Icons.Default.ClosedCaption,
                            label = "Subtitles",
                            onClick = onOpenSubtitleTracks
                        )
                    }
                    if (overlayState.showAudioAction) {
                        VodActionButton(
                            icon = Icons.Default.Audiotrack,
                            label = "Audio",
                            onClick = onOpenAudioTracks
                        )
                    }
                    if (overlayState.showSettingsAction) {
                        TvIconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.semantics { contentDescription = "Settings" }
                        ) {
                            Icon(imageVector = Icons.Default.Settings, contentDescription = null)
                        }
                    }
                    TvIconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { contentDescription = "Close controls" }
                    ) {
                        Text(text = "×", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    }
                }
            }

            if (seekPreview.visible) {
                Text(
                    text = formatVodDuration(seekPreview.positionMs),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 190.dp)
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
    TvButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = label }
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label)
    }
}
