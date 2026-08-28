package com.streamvault.feature.live.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurfaceDim

@Composable
fun LiveChannelContentHost(
    isLoading: Boolean,
    errorMessage: String?,
    hasChannels: Boolean,
    isBlockedCategorySearch: Boolean,
    loadingLabel: String,
    lockedLabel: String,
    noChannelsLabel: String,
    noChannelsSubtitle: String,
    additionalEmptyHint: String? = null,
    modifier: Modifier = Modifier,
    channelContent: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = loadingLabel,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            errorMessage != null -> {
                Text(
                    text = errorMessage,
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleMedium,
                    color = OnBackground
                )
            }

            !hasChannels -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isBlockedCategorySearch) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = OnBackground,
                            modifier = Modifier.size(34.dp)
                        )
                        Text(
                            text = lockedLabel,
                            style = MaterialTheme.typography.titleLarge,
                            color = OnBackground
                        )
                    } else {
                        Text(
                            text = noChannelsLabel,
                            style = MaterialTheme.typography.titleLarge,
                            color = OnBackground
                        )
                    }
                    Text(
                        text = noChannelsSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                    if (!isBlockedCategorySearch) {
                        additionalEmptyHint?.let { hint ->
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
            }

            else -> channelContent()
        }
    }
}
