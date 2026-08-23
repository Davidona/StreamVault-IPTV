package com.streamvault.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.shell.AppMessageState
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim

@Composable
fun TvEmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val contentWidthFraction = if (maxWidth < 700.dp) 0.9f else 0.62f
        AppMessageState(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.fillMaxWidth(contentWidthFraction),
            containerBrush = Brush.horizontalGradient(
                listOf(
                    Color.White.copy(alpha = 0.05f),
                    Color.White.copy(alpha = 0.02f)
                )
            ),
            borderColor = Color.White.copy(alpha = 0.08f),
            titleStyle = androidx.tv.material3.MaterialTheme.typography.headlineMedium,
            subtitleStyle = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
            titleColor = OnSurface,
            subtitleColor = OnSurfaceDim,
            titleTextAlign = androidx.compose.ui.text.style.TextAlign.Center,
            subtitleTextAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
