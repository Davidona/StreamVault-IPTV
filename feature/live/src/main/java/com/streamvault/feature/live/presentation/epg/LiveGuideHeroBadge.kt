package com.streamvault.feature.live.presentation.epg

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceHighlight

@Composable
fun LiveGuideHeroBadge(
    text: String,
    highlight: Boolean = false,
    accentColor: Color = Primary
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        colors = SurfaceDefaults.colors(
            containerColor = if (highlight) accentColor.copy(alpha = 0.18f) else SurfaceHighlight
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (highlight) accentColor else OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
