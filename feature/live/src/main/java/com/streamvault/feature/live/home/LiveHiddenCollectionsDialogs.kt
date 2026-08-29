package com.streamvault.feature.live.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.PrimaryLight
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel

@Composable
fun LiveHiddenCategoriesDialog(
    hiddenCategories: List<Category>,
    title: String,
    subtitle: String,
    unhideAllLabel: String,
    closeLabel: String,
    onUnhide: (Category) -> Unit,
    onUnhideAll: () -> Unit,
    onDismiss: () -> Unit
) {
    PremiumDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        widthFraction = 0.55f,
        heightFraction = 0.95f,
        bodyHeightFraction = 0.85f,
        content = {
            TvClickableSurface(
                onClick = onUnhideAll,
                enabled = hiddenCategories.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = SurfaceElevated,
                    focusedContainerColor = SurfaceHighlight
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, PrimaryLight),
                        shape = RoundedCornerShape(10.dp)
                    )
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = unhideAllLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary
                    )
                }
            }
            hiddenCategories.forEach { category ->
                key(category.id) {
                    LiveHiddenCategoryRow(
                        category = category,
                        onUnhide = { onUnhide(category) }
                    )
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(label = closeLabel, onClick = onDismiss)
        }
    )
}

@Composable
private fun LiveHiddenCategoryRow(
    category: Category,
    onUnhide: () -> Unit
) {
    TvClickableSurface(
        onClick = onUnhide,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated.copy(alpha = 0.4f),
            focusedContainerColor = SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, PrimaryLight),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface,
                modifier = Modifier.weight(1f).padding(end = 12.dp)
            )
            if (category.count > 0) {
                Text(
                    text = category.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

@Composable
fun LiveHiddenChannelsDialog(
    hiddenChannels: List<Channel>,
    title: String,
    subtitle: String,
    unhideAllLabel: String,
    closeLabel: String,
    onUnhide: (Channel) -> Unit,
    onUnhideAll: () -> Unit,
    onDismiss: () -> Unit
) {
    PremiumDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        widthFraction = 0.55f,
        heightFraction = 0.95f,
        bodyHeightFraction = 0.85f,
        content = {
            TvClickableSurface(
                onClick = onUnhideAll,
                enabled = hiddenChannels.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = SurfaceElevated,
                    focusedContainerColor = SurfaceHighlight
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, PrimaryLight),
                        shape = RoundedCornerShape(10.dp)
                    )
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = unhideAllLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary
                    )
                }
            }
            hiddenChannels.forEach { channel ->
                key(channel.id) {
                    LiveHiddenChannelRow(
                        channel = channel,
                        onUnhide = { onUnhide(channel) }
                    )
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(label = closeLabel, onClick = onDismiss)
        }
    )
}

@Composable
private fun LiveHiddenChannelRow(
    channel: Channel,
    onUnhide: () -> Unit
) {
    TvClickableSurface(
        onClick = onUnhide,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated.copy(alpha = 0.4f),
            focusedContainerColor = SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, PrimaryLight),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface,
                modifier = Modifier.weight(1f).padding(end = 12.dp)
            )
        }
    }
}
