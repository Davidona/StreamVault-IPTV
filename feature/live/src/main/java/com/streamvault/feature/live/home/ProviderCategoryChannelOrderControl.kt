package com.streamvault.feature.live.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.domain.model.Category
import com.streamvault.feature.live.R

@Composable
fun ProviderCategoryChannelOrderControl(
    providerId: Long,
    category: Category,
    onOpenChannelOrder: () -> Unit,
    onReorderChannels: () -> Unit,
    viewModel: ProviderChannelOrderSummaryViewModel = hiltViewModel(
        key = "channel-order-summary:$providerId:${category.id}"
    )
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(providerId, category.id) { viewModel.observe(providerId, category) }
    DisposableEffect(viewModel) { onDispose { viewModel.reset() } }
    val spacing = LocalAppSpacing.current
    val shape = RoundedCornerShape(spacing.sm)
    var focused by remember { mutableStateOf(false) }
    TvClickableSurface(
        onClick = onReorderChannels,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) AppColors.Focus else Color.Transparent, shape),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Surface,
            focusedContainerColor = AppColors.SurfaceAccent
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(category.name, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = when {
                    state.loading -> stringResource(R.string.home_loading_channels)
                    state.message != null -> stringResource(checkNotNull(state.message))
                    else -> liveChannelOrderModeLabel(state.mode)
                }
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AppColors.TextPrimary)
        }
    }
}
