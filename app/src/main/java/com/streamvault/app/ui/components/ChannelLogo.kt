package com.streamvault.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.core.ui.design.AppColors
import java.util.Locale

@Composable
fun ChannelLogoBadge(
    channelName: String,
    logoUrl: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    backgroundColor: Color = AppColors.SurfaceEmphasis,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
    textColor: Color = AppColors.TextSecondary
) {
    val model = rememberCrossfadeImageModel(logoUrl?.takeIf { it.isNotBlank() })
    var showFallback by remember(model) { mutableStateOf(true) }

    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (!showFallback && model != null) {
            AsyncImage(
                model = model,
                contentDescription = channelName,
                contentScale = ContentScale.Fit,
                onLoading = { showFallback = true },
                onError = { showFallback = true },
                onSuccess = { showFallback = false },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
            )
        } else {
            Text(
                text = channelInitials(channelName),
                style = textStyle,
                color = textColor,
                fontWeight = FontWeight.Bold
            )
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = channelName,
                    contentScale = ContentScale.Fit,
                    onLoading = { showFallback = true },
                    onError = { showFallback = true },
                    onSuccess = { showFallback = false },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                )
            }
        }
    }
}

private fun channelInitials(name: String): String {
    val words = name
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    val initials = when {
        words.size >= 2 -> buildString {
            append(words[0].first())
            append(words[1].first())
        }
        words.size == 1 -> words[0].take(2)
        else -> "--"
    }

    return initials.uppercase(Locale.ROOT)
}