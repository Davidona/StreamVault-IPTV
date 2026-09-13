package com.streamvault.feature.playback.player.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.feature.playback.R
import com.streamvault.feature.playback.player.SkippableChapterType

@Composable
fun SkipChapterOverlay(
    chapterType: SkippableChapterType,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val label = when (chapterType) {
        SkippableChapterType.INTRO -> stringResource(R.string.player_skip_intro)
        SkippableChapterType.OPENING -> stringResource(R.string.player_skip_opening)
        SkippableChapterType.RECAP -> stringResource(R.string.player_skip_recap)
        SkippableChapterType.OUTRO -> stringResource(R.string.player_skip_outro)
    }

    LaunchedEffect(chapterType) {
        focusRequester.requestFocusSafely(
            tag = "SkipChapterOverlay",
            target = "Skip chapter button"
        )
    }

    Box(
        modifier = modifier
            .widthIn(max = 280.dp)
            .background(Color.Black.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        TvClickableSurface(
            onClick = onSkip,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.12f),
                focusedContainerColor = Color.White.copy(alpha = 0.24f)
            ),
            modifier = Modifier.focusRequester(focusRequester)
        ) {
            Text(
                text = label,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }
}
