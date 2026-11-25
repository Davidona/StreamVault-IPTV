package com.streamvault.feature.playback.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.feature.playback.R

/**
 * Start-side button group: [previous chapter] [-10] [play/pause] [+10] [next chapter]. The chapter
 * buttons are present only when [showChapterSkip] is set.
 */
@Composable
internal fun VodTransportControls(
    isPlaying: Boolean,
    showChapterSkip: Boolean,
    playButtonFocusRequester: FocusRequester,
    timelineFocusRequester: FocusRequester,
    onPreviousChapter: () -> Unit,
    onSeekBackward: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showChapterSkip) {
            VodRoundButton(
                icon = Icons.Default.FastRewind,
                label = stringResource(R.string.player_previous_chapter),
                onClick = onPreviousChapter
            )
        }
        VodRoundButton(
            icon = Icons.Default.Replay10,
            label = stringResource(R.string.player_rewind_10_seconds),
            onClick = onSeekBackward
        )
        VodPlayPauseButton(
            icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            label = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
            onClick = onTogglePlayPause,
            modifier = Modifier
                .focusRequester(playButtonFocusRequester)
                .focusProperties { up = timelineFocusRequester }
        )
        VodRoundButton(
            icon = Icons.Default.Forward10,
            label = stringResource(R.string.player_forward_10_seconds),
            onClick = onSeekForward
        )
        if (showChapterSkip) {
            VodRoundButton(
                icon = Icons.Default.FastForward,
                label = stringResource(R.string.player_next_chapter),
                onClick = onNextChapter
            )
        }
    }
}
