package com.streamvault.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.core.ui.design.FocusSpec
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.interaction.mouseClickable

@Composable
fun SavedCategoryContextCard(
    categoryName: String,
    itemCount: Int,
    onManageClick: () -> Unit,
    onBrowseAllClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = SurfaceDefaults.colors(
            containerColor = SurfaceElevated
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Primary.copy(alpha = 0.10f),
                            Primary.copy(alpha = 0.02f)
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.library_saved_active_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary
                )
                Text(
                    text = categoryName,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.library_saved_active_summary, itemCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.library_saved_manage_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onManageClick,
                    modifier = Modifier.mouseClickable(onClick = onManageClick),
                    colors = ButtonDefaults.colors(
                        containerColor = Primary.copy(alpha = 0.92f),
                        focusedContainerColor = Primary,
                        focusedContentColor = androidx.compose.ui.graphics.Color.White
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(FocusSpec.BorderWidth, FocusBorder)
                        )
                    ),
                    scale = ButtonDefaults.scale(focusedScale = FocusSpec.FocusedScale)
                ) {
                    Text(stringResource(R.string.library_saved_manage_action))
                }
                Button(
                    onClick = onBrowseAllClick,
                    modifier = Modifier.mouseClickable(onClick = onBrowseAllClick),
                    colors = ButtonDefaults.colors(
                        containerColor = SurfaceElevated.copy(alpha = 0.78f),
                        contentColor = OnSurface,
                        focusedContainerColor = SurfaceElevated,
                        focusedContentColor = OnSurface
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(FocusSpec.BorderWidth, FocusBorder)
                        )
                    ),
                    scale = ButtonDefaults.scale(focusedScale = FocusSpec.FocusedScale)
                ) {
                    Text(stringResource(R.string.library_browse_all))
                }
            }
        }
    }
}
