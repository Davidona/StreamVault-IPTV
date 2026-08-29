package com.streamvault.feature.live.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.domain.repository.M3uClassificationTarget

data class LiveM3uCategoryOrganizerLabels(
    val subtitle: String,
    val moveToMovies: String,
    val moveToSeries: String,
    val keepLive: String,
    val cancel: String
)

@Composable
fun LiveM3uCategoryOrganizerDialog(
    categoryName: String,
    labels: LiveM3uCategoryOrganizerLabels,
    onDismiss: () -> Unit,
    onTargetSelected: (M3uClassificationTarget) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.55f),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(categoryName, style = MaterialTheme.typography.headlineSmall, color = OnSurface)
                Text(labels.subtitle, color = OnSurface.copy(alpha = 0.75f))
                LiveM3uOrganizerAction(
                    label = labels.moveToMovies,
                    containerColor = MaterialTheme.colorScheme.primary,
                    onClick = { onTargetSelected(M3uClassificationTarget.MOVIE) }
                )
                LiveM3uOrganizerAction(
                    label = labels.moveToSeries,
                    containerColor = MaterialTheme.colorScheme.secondary,
                    onClick = { onTargetSelected(M3uClassificationTarget.SERIES) }
                )
                LiveM3uOrganizerAction(
                    label = labels.keepLive,
                    containerColor = Color.White.copy(alpha = 0.08f),
                    contentColor = OnSurface,
                    onClick = { onTargetSelected(M3uClassificationTarget.LIVE) }
                )
                LiveM3uOrganizerAction(
                    label = labels.cancel,
                    containerColor = Color.White.copy(alpha = 0.08f),
                    contentColor = OnSurface,
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun LiveM3uOrganizerAction(
    label: String,
    containerColor: Color,
    contentColor: Color = Color.Black,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Text(label)
    }
}
