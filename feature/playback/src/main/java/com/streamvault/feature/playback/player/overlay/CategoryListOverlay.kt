package com.streamvault.feature.playback.player.overlay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.domain.model.Category
import com.streamvault.feature.playback.R
import com.streamvault.feature.playback.player.playerCategoryOverlayItemKey

@Composable
fun CategoryListOverlay(
    categories: List<Category>,
    currentCategoryId: Long,
    overlayFocusRequester: FocusRequester = remember { FocusRequester() },
    isCategoryLocked: (Category) -> Boolean = { false },
    onSelectCategory: (Category) -> Unit,
    onDismiss: () -> Unit,
    onOverlayInteracted: () -> Unit = {}
) {
    val spacing = LocalAppSpacing.current
    val listState = rememberLazyListState()
    val backFocus = remember { FocusRequester() }
    val currentIndex = categories.indexOfFirst { it.id == currentCategoryId }.coerceAtLeast(0)
    LaunchedEffect(categories, currentIndex) {
        if (categories.isNotEmpty()) listState.scrollToItem((currentIndex - 2).coerceAtLeast(0))
        withFrameNanos { }
        if (categories.isEmpty()) backFocus.requestFocus()
        else runCatching { overlayFocusRequester.requestFocus() }
    }
    BackHandler(onBack = onDismiss)

    PlayerBrowsePanel {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            PlayerBrowseAction(
                label = stringResource(R.string.player_close),
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                tertiary = true,
                modifier = Modifier.width(spacing.channelListMinTouchTarget).focusRequester(backFocus)
                    .then(if (categories.isEmpty()) Modifier.focusRequester(overlayFocusRequester) else Modifier)
                    .focusProperties { if (categories.isNotEmpty()) down = overlayFocusRequester },
                onFocused = onOverlayInteracted,
                onClick = { onOverlayInteracted(); onDismiss() }
            )
            Text(
                stringResource(R.string.label_categories),
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            itemsIndexed(categories, key = { _, category -> playerCategoryOverlayItemKey(category) }) { index, category ->
                val selected = category.id == currentCategoryId
                PlayerBrowseRow(
                    selected = selected,
                    onClick = { onOverlayInteracted(); onSelectCategory(category) },
                    modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }
                        .then(if (index == currentIndex) Modifier.focusRequester(overlayFocusRequester) else Modifier)
                        .focusProperties { if (index == 0) up = backFocus }
                        .onFocusChanged { if (it.isFocused) onOverlayInteracted() }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            category.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = AppColors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (isCategoryLocked(category)) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = stringResource(R.string.home_locked_short),
                                tint = AppColors.TextSecondary,
                                modifier = Modifier.size(spacing.md)
                            )
                        }
                        Text(
                            category.count.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = AppColors.TextPrimary,
                                modifier = Modifier.size(spacing.md)
                            )
                        }
                    }
                }
            }
        }
    }
}
