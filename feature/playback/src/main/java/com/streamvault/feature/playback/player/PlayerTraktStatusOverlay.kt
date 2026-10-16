package com.streamvault.feature.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.BrowseColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.feature.playback.R

@Composable
internal fun PlayerTraktStatusOverlay(
    status: PlayerTraktTrackingStatus,
    modifier: Modifier = Modifier,
) {
    if (status == PlayerTraktTrackingStatus.HIDDEN) return

    val label = when (status) {
        PlayerTraktTrackingStatus.HIDDEN -> return
        PlayerTraktTrackingStatus.CONNECT_REQUIRED -> R.string.player_trakt_connect_required
        PlayerTraktTrackingStatus.NOT_SUPPORTED -> R.string.player_trakt_not_supported
        PlayerTraktTrackingStatus.NO_MATCH -> R.string.player_trakt_no_match
        PlayerTraktTrackingStatus.STARTING -> R.string.player_trakt_starting
        PlayerTraktTrackingStatus.TRACKING -> R.string.player_trakt_tracking
        PlayerTraktTrackingStatus.PAUSED -> R.string.player_trakt_paused
        PlayerTraktTrackingStatus.FAILED -> R.string.player_trakt_failed
        PlayerTraktTrackingStatus.SENT -> R.string.player_trakt_sent
    }
    val palette = BrowseColors.current
    val accent = when (status) {
        PlayerTraktTrackingStatus.TRACKING,
        PlayerTraktTrackingStatus.SENT -> palette.success
        PlayerTraktTrackingStatus.PAUSED,
        PlayerTraktTrackingStatus.STARTING -> palette.info
        PlayerTraktTrackingStatus.FAILED,
        PlayerTraktTrackingStatus.CONNECT_REQUIRED,
        PlayerTraktTrackingStatus.NO_MATCH -> palette.warning
        PlayerTraktTrackingStatus.NOT_SUPPORTED -> palette.textSecondary
        PlayerTraktTrackingStatus.HIDDEN -> palette.surface
    }

    Row(
        modifier = modifier
            .background(palette.surfaceElevated.copy(alpha = 0.96f), BrowseShapes.Control)
            .border(1.dp, palette.outline, BrowseShapes.Control)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "TRAKT",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = BrowseColors.TextPrimary,
            maxLines = 1,
        )
    }
}
