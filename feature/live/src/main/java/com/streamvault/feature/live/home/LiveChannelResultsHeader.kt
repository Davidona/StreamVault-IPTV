package com.streamvault.feature.live.home

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.SearchInput
import com.streamvault.core.ui.components.shell.ContentMetadataStrip
import com.streamvault.core.ui.interaction.TvIconButton
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurfaceDim

@Composable
fun LiveChannelResultsHeader(
    heading: String,
    resultCountLabel: String,
    metadataValues: List<String>,
    isDenseMode: Boolean,
    hasSplitChannels: Boolean,
    slotCount: Int,
    slotLimit: Int,
    onOpenSplit: () -> Unit,
    channelSearchQuery: String,
    onChannelSearchQueryChanged: (String) -> Unit,
    searchPlaceholder: String,
    channelSearchFocusRequester: FocusRequester,
    channelSearchWidth: Dp,
    isReorderMode: Boolean,
    showCategoriesButton: Boolean = false,
    onShowCategories: () -> Unit = {},
    showCategoriesContentDescription: String = "",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 2.dp, bottom = if (isDenseMode) 4.dp else 6.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (isDenseMode) 2.dp else 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = heading,
                style = if (isDenseMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                color = OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                    .basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 900,
                        repeatDelayMillis = 1200,
                        velocity = 24.dp
                    )
            )
            if (hasSplitChannels) {
                CompactSplitLauncherButton(
                    slotCount = slotCount,
                    slotLimit = slotLimit,
                    onClick = onOpenSplit,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
        if (isDenseMode) {
            Text(
                text = resultCountLabel,
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceDim,
                maxLines = 1
            )
        } else {
            ContentMetadataStrip(values = metadataValues)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedVisibility(
                visible = showCategoriesButton,
                enter = fadeIn(tween(150)) + expandHorizontally(),
                exit = fadeOut(tween(100)) + shrinkHorizontally()
            ) {
                TvIconButton(
                    onClick = onShowCategories,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = showCategoriesContentDescription
                    )
                }
            }
            SearchInput(
                value = channelSearchQuery,
                onValueChange = { if (!isReorderMode) onChannelSearchQueryChanged(it) },
                placeholder = searchPlaceholder,
                onSearch = {},
                focusRequester = channelSearchFocusRequester,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(min = 0.dp, max = channelSearchWidth),
                enabled = !isReorderMode
            )
        }
    }
}
