package com.streamvault.feature.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.LiveClockFont
import com.streamvault.domain.model.LiveClockPosition
import com.streamvault.domain.model.LiveClockSize
import kotlinx.coroutines.delay

internal fun shouldShowLiveClock(
    contentType: String,
    isCatchUpPlayback: Boolean,
    isInPictureInPictureMode: Boolean,
    enabled: Boolean
): Boolean = enabled &&
    contentType == "LIVE" &&
    !isCatchUpPlayback &&
    !isInPictureInPictureMode

@Composable
internal fun BoxScope.LiveClockOverlay(
    timeFormat: AppTimeFormat,
    position: LiveClockPosition,
    size: LiveClockSize,
    font: LiveClockFont,
    modifier: Modifier = Modifier
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Text(
        text = formatLiveClock(nowMs, timeFormat),
        style = TextStyle(
            color = Color.White,
            fontSize = size.textSize,
            fontFamily = font.fontFamily,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            shadow = Shadow(color = Color.Black, blurRadius = 4f)
        ),
        modifier = modifier
            .align(position.alignment)
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(9.dp))
            .background(Color.Black.copy(alpha = 0.56f), RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

private val LiveClockPosition.alignment: Alignment
    get() = when (this) {
        LiveClockPosition.TOP_START -> Alignment.TopStart
        LiveClockPosition.TOP_END -> Alignment.TopEnd
        LiveClockPosition.BOTTOM_START -> Alignment.BottomStart
        LiveClockPosition.BOTTOM_END -> Alignment.BottomEnd
    }

private val LiveClockSize.textSize
    get() = when (this) {
        LiveClockSize.SMALL -> 18.sp
        LiveClockSize.MEDIUM -> 26.sp
        LiveClockSize.LARGE -> 34.sp
    }

private val LiveClockFont.fontFamily
    get() = when (this) {
        LiveClockFont.CLEAN -> FontFamily.SansSerif
        LiveClockFont.DIGITAL_MONO -> FontFamily.Monospace
        LiveClockFont.CLASSIC_SERIF -> FontFamily.Serif
    }
