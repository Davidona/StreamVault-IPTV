package com.streamvault.feature.live.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.browse.OnSurface
import com.streamvault.core.ui.design.browse.SurfaceElevated
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.LiveChannelOrderMode
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
    androidx.compose.runtime.LaunchedEffect(providerId, category.id) {
        viewModel.observe(providerId, category)
    }
    DisposableEffect(viewModel) { onDispose { viewModel.reset() } }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(category.name, style = MaterialTheme.typography.titleMedium, color = OnSurface)
            when {
                state.loading -> Text(stringResource(R.string.home_loading_channels), color = OnSurface)
                state.message != null -> state.message?.let { message ->
                    Text(stringResource(message), color = OnSurface)
                }
                else -> Row {
                    Text(stringResource(R.string.live_order_mode), color = OnSurface)
                    Spacer(Modifier.width(8.dp))
                    Text(liveChannelOrderModeLabel(state.mode), color = OnSurface)
                    if (state.mode == LiveChannelOrderMode.USE_DEFAULT) {
                        Spacer(Modifier.width(8.dp))
                        Text("→", color = OnSurface)
                        Spacer(Modifier.width(8.dp))
                        Text(liveChannelOrderModeLabel(state.defaultMode), color = OnSurface)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(onClick = onOpenChannelOrder) {
                    Text(stringResource(R.string.live_order_mode))
                }
                TvButton(onClick = onReorderChannels) {
                    Text(stringResource(R.string.home_reorder_channels))
                }
            }
        }
    }
}
