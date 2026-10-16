package com.streamvault.feature.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import com.streamvault.domain.settings.VideoQualityIssue
import com.streamvault.feature.playback.R
import com.streamvault.player.PlaybackQualityGateState
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun PlayerQualityGateOverlay(
    state: PlaybackQualityGateState,
    hasAlternateSource: Boolean,
    onContinueOnce: (String) -> Unit,
    onTryAlternateSource: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val candidate = state.candidate
    val offeredQuality = stringResource(R.string.player_quality_gate_offer, candidate.name)
    val numberFormat = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = 1
        minimumFractionDigits = 0
    }
    val candidateHeight = candidate.videoHeight
    val heightLimit = state.limit.maxVideoHeight
    val candidateBitrate = candidate.videoBitrateBps
    val bitrateLimit = state.limit.maxVideoBitrateBps
    val heightExceeded = if (
        VideoQualityIssue.EXCEEDS_HEIGHT_LIMIT in state.issues &&
        candidateHeight != null && heightLimit != null
    ) {
        stringResource(
            R.string.player_quality_gate_height_exceeded,
            candidateHeight,
            heightLimit
        )
    } else null
    val heightUnknown = if (VideoQualityIssue.UNKNOWN_HEIGHT in state.issues) {
        heightLimit?.let { stringResource(R.string.player_quality_gate_height_unknown, it) }
    } else null
    val bitrateExceeded = if (
        VideoQualityIssue.EXCEEDS_BITRATE_LIMIT in state.issues &&
        candidateBitrate != null && bitrateLimit != null
    ) {
        stringResource(
            R.string.player_quality_gate_bitrate_exceeded,
            numberFormat.format(candidateBitrate / 1_000_000.0),
            numberFormat.format(bitrateLimit / 1_000_000.0)
        )
    } else null
    val bitrateUnknown = if (VideoQualityIssue.UNKNOWN_BITRATE in state.issues) {
        bitrateLimit?.let {
            stringResource(R.string.player_quality_gate_bitrate_unknown, numberFormat.format(it / 1_000_000.0))
        }
    } else null
    val details = listOfNotNull(heightExceeded, heightUnknown, bitrateExceeded, bitrateUnknown)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.86f))
            .focusGroup(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .background(Color(0xFF17191F), RoundedCornerShape(24.dp))
                .padding(28.dp)
                .focusGroup(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.player_quality_gate_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.player_quality_gate_message),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.86f)
            )
            Text(
                text = offeredQuality,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            details.forEach { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.78f)
                )
            }
            if (state.canContinueOnce) {
                Button(onClick = { onContinueOnce(candidate.id) }) {
                    Text(stringResource(R.string.player_quality_gate_continue_once))
                }
            }
            if (hasAlternateSource) {
                Button(onClick = onTryAlternateSource) {
                    Text(stringResource(R.string.player_try_alternate_stream))
                }
            }
            Button(onClick = onStop) {
                Text(stringResource(R.string.player_close))
            }
        }
    }
}
