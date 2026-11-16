package com.streamvault.feature.playback.player.overlay

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.LocalAppShapes
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.core.ui.design.selectableFocusBorder
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.domain.model.Channel
import com.streamvault.domain.playback.archivePlaybackCapability
import com.streamvault.feature.playback.R
import com.streamvault.feature.playback.player.playerChannelOverlayItemKey

@Composable
fun ChannelListOverlay(
    channels: List<Channel>,
    recentChannels: List<Channel>,
    currentChannelId: Long,
    overlayFocusRequester: FocusRequester = remember { FocusRequester() },
    lastVisitedCategoryName: String? = null,
    onOpenLastGroup: () -> Unit = {},
    onOpenCategories: () -> Unit = {},
    onSelectChannel: (Long) -> Unit,
    onDismiss: () -> Unit,
    onOverlayInteracted: () -> Unit = {}
) {
    val spacing = LocalAppSpacing.current
    val listState = rememberLazyListState()
    val categoriesFocus = remember { FocusRequester() }
    val lastGroupFocus = remember { FocusRequester() }
    val currentIndex = channels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
    val recentItemCount = if (recentChannels.isEmpty()) 0 else 1
    val layoutDirection = LocalLayoutDirection.current

    LaunchedEffect(currentChannelId, channels, recentItemCount) {
        if (channels.isNotEmpty()) {
            listState.scrollToItem((currentIndex - 2).coerceAtLeast(0) + recentItemCount)
        }
        withFrameNanos { }
        if (channels.isEmpty()) categoriesFocus.requestFocus()
        else runCatching { overlayFocusRequester.requestFocus() }
    }
    BackHandler(onBack = onDismiss)

    PlayerBrowsePanel {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerBrowseAction(
                label = stringResource(R.string.label_categories),
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                tertiary = true,
                modifier = Modifier.width(spacing.channelListMinTouchTarget).focusRequester(categoriesFocus)
                    .then(if (channels.isEmpty()) Modifier.focusRequester(overlayFocusRequester) else Modifier)
                    .focusProperties {
                        if (channels.isNotEmpty()) down = overlayFocusRequester
                        if (!lastVisitedCategoryName.isNullOrBlank()) {
                            if (layoutDirection == LayoutDirection.Rtl) left = lastGroupFocus else right = lastGroupFocus
                        }
                    },
                onFocused = onOverlayInteracted,
                onClick = { onOverlayInteracted(); onOpenCategories() }
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.player_channel_list_title, channels.size),
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!lastVisitedCategoryName.isNullOrBlank()) {
                    PlayerBrowseAction(
                        label = stringResource(R.string.player_last_group_label, lastVisitedCategoryName),
                        tertiary = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(lastGroupFocus)
                            .focusProperties {
                                if (channels.isNotEmpty()) down = overlayFocusRequester
                                if (layoutDirection == LayoutDirection.Rtl) right = categoriesFocus else left = categoriesFocus
                            },
                        onFocused = onOverlayInteracted,
                        onClick = { onOverlayInteracted(); onOpenLastGroup() }
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            if (recentChannels.isNotEmpty()) {
                item(key = "recent") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Text(
                            stringResource(R.string.player_recent_channels),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        LazyRow(
                            contentPadding = PaddingValues(spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            itemsIndexed(recentChannels, key = { index, channel -> "recent:${channel.id}:$index" }) { _, channel ->
                                PlayerBrowseAction(
                                    label = channel.name,
                                    modifier = Modifier.widthIn(max = spacing.channelListOverlayWidth / 2),
                                    onFocused = onOverlayInteracted,
                                    onClick = {
                                        onOverlayInteracted()
                                        onSelectChannel(channel.id)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }
            itemsIndexed(channels, key = { _, channel -> playerChannelOverlayItemKey(channel) }) { index, channel ->
                val selected = channel.id == currentChannelId
                val hasArchive = channel.archivePlaybackCapability().canBuildReplayCandidate
                PlayerBrowseRow(
                    selected = selected,
                    onClick = {
                        onOverlayInteracted()
                        onSelectChannel(channel.id)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = spacing.channelListMinTouchTarget)
                        .onPreviewKeyEvent { event ->
                            val categoriesKey = if (layoutDirection == LayoutDirection.Rtl) {
                                KeyEvent.KEYCODE_DPAD_RIGHT
                            } else KeyEvent.KEYCODE_DPAD_LEFT
                            if (event.nativeKeyEvent.keyCode == categoriesKey) {
                                if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                                    onOverlayInteracted()
                                    onOpenCategories()
                                }
                                true
                            } else false
                        }
                        .then(if (index == currentIndex) Modifier.focusRequester(overlayFocusRequester) else Modifier)
                        .focusProperties { if (index == 0 && recentChannels.isEmpty()) up = categoriesFocus }
                        .onFocusChanged { if (it.isFocused) onOverlayInteracted() }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.xs, vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        Text(
                            (channel.number.takeIf { it > 0 } ?: index + 1).toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(spacing.xl)
                        )
                        Text(
                            channel.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = AppColors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (hasArchive) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = stringResource(R.string.player_archive_badge),
                                tint = AppColors.TextSecondary,
                                modifier = Modifier.size(spacing.md)
                            )
                        }
                        if (selected) {
                            Text(
                                stringResource(R.string.player_channel_selected),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = spacing.channelListOverlayWidth / 4)
                            )
                        }
                    }
                }
            }
        }
    }
}
