package com.streamvault.feature.playback.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Icon
import com.streamvault.feature.playback.R

@Composable
internal fun VodTransportControls(
    isPlaying: Boolean,
    playButtonFocusRequester: FocusRequester,
    canSeekPreviousChapter: Boolean,
    canSeekNextChapter: Boolean,
    onSeekPreviousChapter: () -> Unit,
    onSeekBackward: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekNextChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VodTransportButton(
            icon = Icons.Default.SkipPrevious,
            label = stringResource(R.string.player_previous_chapter),
            enabled = canSeekPreviousChapter,
            onClick = onSeekPreviousChapter
        )
        VodTransportButton(
            icon = Icons.Default.Replay10,
            label = stringResource(R.string.player_rewind_10_seconds),
            onClick = onSeekBackward
        )
        VodControlButton(
            onClick = onTogglePlayPause,
            modifier = Modifier
                .focusRequester(playButtonFocusRequester)
                .semantics { contentDescription = if (isPlaying) "Pause" else "Play" }
                .padding(2.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = androidx.tv.material3.LocalContentColor.current
            )
        }
        VodTransportButton(
            icon = Icons.Default.Forward10,
            label = stringResource(R.string.player_forward_10_seconds),
            onClick = onSeekForward
        )
        VodTransportButton(
            icon = Icons.Default.SkipNext,
            label = stringResource(R.string.player_next_chapter),
            enabled = canSeekNextChapter,
            onClick = onSeekNextChapter
        )
    }
}

@Composable
private fun VodTransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    VodControlButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = label }
    ) {
        Icon(imageVector = icon, contentDescription = null)
    }
}
