package com.streamvault.feature.live.presentation.epg

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

data class LiveGuideSearchOverlayLabels(
    val title: String,
    val apply: String,
    val clearAndClose: String,
    val searchPlaceholder: String,
    val clear: String
)

@Composable
fun LiveGuideSearchOverlay(
    query: String,
    labels: LiveGuideSearchOverlayLabels,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val searchFocusRequester = remember { FocusRequester() }
    val applySearchAndClose = remember(onQueryChange, onDismiss) {
        { submittedQuery: String ->
            onQueryChange(submittedQuery)
            onDismiss()
        }
    }
    val clearAndClose = remember(onClear, onDismiss) {
        {
            onClear()
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        searchFocusRequester.requestFocus()
    }

    LiveGuideModalSearchDialog(
        onDismiss = onDismiss,
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .padding(top = 32.dp)
                .focusGroup(),
            colors = SurfaceDefaults.colors(containerColor = com.streamvault.core.ui.theme.SurfaceElevated),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = labels.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = com.streamvault.core.ui.theme.OnSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiveGuideShortcutChip(
                            label = labels.apply,
                            onClick = { applySearchAndClose(query.trim()) }
                        )
                        LiveGuideShortcutChip(
                            label = labels.clearAndClose,
                            onClick = clearAndClose
                        )
                    }
                }
                LiveGuideProgramSearchRow(
                    query = query,
                    onQueryChange = onQueryChange,
                    onClear = onClear,
                    searchLabel = labels.title,
                    searchPlaceholder = labels.searchPlaceholder,
                    clearLabel = labels.clear,
                    onSearch = applySearchAndClose,
                    focusRequester = searchFocusRequester,
                    autoRequestFocus = true,
                    contentPadding = PaddingValues(0.dp),
                    showLabel = false
                )
            }
        }
    }
}

@Composable
private fun LiveGuideModalSearchDialog(
    onDismiss: () -> Unit,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = contentAlignment
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.68f))
                    .clickable(
                        onClick = onDismiss,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    )
            )
            content()
        }
    }
}
