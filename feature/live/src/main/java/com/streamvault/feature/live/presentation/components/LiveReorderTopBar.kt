package com.streamvault.feature.live.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.FocusSpec
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.TextPrimary
import com.streamvault.core.ui.theme.TextSecondary

@Composable
fun LiveReorderTopBar(
    categoryName: String,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    visible: Boolean = true,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    titleFormat: @Composable (String) -> String = { "Reordering $it" },
    cancelLabel: String = "Cancel",
    saveLabel: String = "Save order"
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier.fillMaxWidth().padding(bottom = 20.dp)
    ) {
        Surface(shape = RoundedCornerShape(24.dp), colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Primary.copy(alpha = 0.08f), Color.Transparent))).padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = titleFormat(categoryName), style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    subtitle?.takeIf { it.isNotBlank() }?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = TextSecondary) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = OnSurface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContentColor = OnSurface
                        ),
                        border = ButtonDefaults.border(focusedBorder = Border(border = BorderStroke(FocusSpec.BorderWidth, FocusBorder))),
                        scale = ButtonDefaults.scale(focusedScale = FocusSpec.FocusedScale)
                    ) { Text(cancelLabel, modifier = Modifier.padding(horizontal = 8.dp)) }
                    TvButton(
                        onClick = onSave,
                        colors = ButtonDefaults.colors(
                            containerColor = Primary,
                            contentColor = Color.White,
                            focusedContainerColor = Primary.copy(alpha = 0.88f),
                            focusedContentColor = Color.White
                        ),
                        border = ButtonDefaults.border(focusedBorder = Border(border = BorderStroke(FocusSpec.BorderWidth, FocusBorder))),
                        scale = ButtonDefaults.scale(focusedScale = FocusSpec.FocusedScale)
                    ) { Text(saveLabel, modifier = Modifier.padding(horizontal = 8.dp)) }
                }
            }
        }
    }
}
