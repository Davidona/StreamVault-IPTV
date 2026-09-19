package com.streamvault.feature.live.presentation.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

@Composable
fun LiveFocusedMarqueeText(
    text: String,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
) {
    val ambientLayoutDirection = LocalLayoutDirection.current
    val marqueeLayoutDirection = remember(text, ambientLayoutDirection) {
        when {
            hasMixedDirectionalText(text) -> LayoutDirection.Ltr
            hasStrongRtlText(text) -> LayoutDirection.Rtl
            else -> ambientLayoutDirection
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides marqueeLayoutDirection) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = if (isFocused) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = modifier.then(
                if (isFocused) {
                    Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 900,
                        repeatDelayMillis = 1200,
                        velocity = 24.dp,
                    )
                } else {
                    Modifier
                }
            ),
        )
    }
}

private fun hasMixedDirectionalText(text: String): Boolean {
    var hasStrongLtr = false
    var hasStrongRtl = false
    text.forEach { char ->
        when (Character.getDirectionality(char)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> hasStrongLtr = true
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> hasStrongRtl = true
        }
        if (hasStrongLtr && hasStrongRtl) return true
    }
    return false
}

private fun hasStrongRtlText(text: String): Boolean {
    var hasStrongRtl = false
    text.forEach { char ->
        when (Character.getDirectionality(char)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> hasStrongRtl = true
        }
    }
    return hasStrongRtl
}
