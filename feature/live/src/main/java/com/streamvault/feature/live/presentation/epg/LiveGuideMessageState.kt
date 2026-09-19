package com.streamvault.feature.live.presentation.epg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary

@Composable
fun LiveGuideMessageState(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, color = OnSurface)
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
            }
            if (actionLabel != null && onAction != null) {
                TvButton(
                    onClick = onAction,
                    colors = ButtonDefaults.colors(containerColor = Primary, contentColor = Color.White)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
