package com.streamvault.app.ui.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.domain.repository.M3uClassificationTarget

@Composable
fun M3uCategoryOrganizerDialog(
    categoryName: String,
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
                Text(
                    stringResource(R.string.m3u_category_rule_subtitle),
                    color = OnSurface.copy(alpha = 0.75f)
                )
                Button(
                    onClick = { onTargetSelected(M3uClassificationTarget.MOVIE) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black
                    )
                ) { Text(stringResource(R.string.m3u_move_to_movies)) }
                Button(
                    onClick = { onTargetSelected(M3uClassificationTarget.SERIES) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = Color.Black
                    )
                ) { Text(stringResource(R.string.m3u_move_to_series)) }
                Button(
                    onClick = { onTargetSelected(M3uClassificationTarget.LIVE) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        contentColor = OnSurface
                    )
                ) { Text(stringResource(R.string.m3u_keep_live)) }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        contentColor = OnSurface
                    )
                ) { Text(stringResource(R.string.category_options_cancel)) }
            }
        }
    }
}
