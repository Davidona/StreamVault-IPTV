package com.streamvault.feature.playback.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.streamvault.core.ui.design.AppSpacing
import com.streamvault.core.ui.design.FocusSafeLazyColumn
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.feature.playback.R
import com.streamvault.player.PlayerChapter

/**
 * Chapter list in the shared panel frame. Focus starts on the current chapter (the first one when none is current),
 * which is composed before it is focused. Choosing a chapter seeks to it and closes the frame.
 */
@Composable
internal fun VodChapterSheet(
    chapters: List<PlayerChapter>,
    selectedChapterIndex: Int?,
    onSelectChapter: (PlayerChapter) -> Unit,
    onDismiss: () -> Unit,
) {
    val targetPosition = chapters.indexOfFirst { it.index == selectedChapterIndex }.coerceAtLeast(0)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = vodSettingsFirstVisibleIndex(targetPosition),
    )
    val targetFocus = remember { FocusRequester() }
    val startKey = startSideKey()
    LaunchedEffect(Unit) {
        // Fallback for when the window had not taken its own initial focus yet at the first placement.
        withFrameNanos { }
        targetFocus.requestFocusSafely("VodChapterSheet", "Current chapter row")
    }

    VodPanelFrame(
        title = stringResource(R.string.player_chapters),
        icon = Icons.Default.MenuBook,
        onDismiss = onDismiss,
    ) {
        FocusSafeLazyColumn(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                // Every row is full width, so the start key has nowhere to go: swallow it instead of letting the
                // focus search leave the frame.
                .onKeyEvent { event -> event.key == startKey },
            state = listState,
            verticalArrangement = Arrangement.spacedBy(AppSpacing().xs),
        ) {
            itemsIndexed(chapters, key = { _, chapter -> chapter.index }) { position, chapter ->
                val selected = chapter.index == selectedChapterIndex
                val description = if (selected) {
                    stringResource(R.string.player_chapter_selected, chapter.index)
                } else {
                    stringResource(R.string.player_chapter_label, chapter.index)
                }
                VodPanelRow(
                    title = chapter.title.ifBlank { stringResource(R.string.player_chapter_label, chapter.index) },
                    modifier = Modifier
                        .then(
                            if (position == targetPosition) {
                                rememberPlacementFocus(targetFocus, "VodChapterSheet")
                            } else {
                                Modifier
                            }
                        )
                        .semantics { contentDescription = description },
                    detail = formatVodDuration(chapter.startTimeMs),
                    selected = selected,
                    onClick = { onSelectChapter(chapter) },
                )
            }
        }
    }
}

internal fun formatVodDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
