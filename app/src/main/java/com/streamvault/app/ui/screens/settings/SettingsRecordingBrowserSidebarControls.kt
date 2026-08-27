package com.streamvault.app.ui.screens.settings

import com.streamvault.feature.settings.presentation.CompactRecordingActionChip
import com.streamvault.feature.settings.presentation.recordingStatusAccent
import com.streamvault.feature.settings.presentation.recordingStatusLabel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.RecordingStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingBrowserSidebarControls(
    filteredCount: Int,
    totalCount: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    statusFilter: RecordingStatus?,
    onStatusFilterChange: (RecordingStatus?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.settings_recording_title),
                style = MaterialTheme.typography.titleSmall,
                color = Primary
            )
            Text(
                text = "$filteredCount of $totalCount items",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactRecordingActionChip(
                label = "All",
                accent = if (statusFilter == null) Primary else OnSurfaceDim,
                onClick = { onStatusFilterChange(null) }
            )
            RecordingStatus.entries.forEach { status ->
                CompactRecordingActionChip(
                    label = recordingStatusLabel(status),
                    accent = if (statusFilter == status) recordingStatusAccent(status) else OnSurfaceDim,
                    onClick = {
                        onStatusFilterChange(if (statusFilter == status) null else status)
                    }
                )
            }
        }
        RecordingBrowserSearchField(
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange
        )
    }
}

@Composable
private fun RecordingBrowserSearchField(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit
) {
    val isTelevisionDevice = com.streamvault.app.device.rememberIsTelevisionDevice()
    val searchFocusRequester = remember { FocusRequester() }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var acceptsInput by remember(isTelevisionDevice) { mutableStateOf(!isTelevisionDevice) }

    TvClickableSurface(
        onClick = {
            acceptsInput = true
            inputFocusRequester.requestFocus()
            keyboardController?.show()
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(searchFocusRequester),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, OnSurfaceDim.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(8.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (searchQuery.isEmpty()) {
                Text(
                    text = "Search recordings...",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = OnBackground),
                singleLine = true,
                cursorBrush = SolidColor(Primary),
                readOnly = isTelevisionDevice && !acceptsInput,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    acceptsInput = false
                    keyboardController?.hide()
                    searchFocusRequester.requestFocus()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inputFocusRequester)
                    .onFocusChanged {
                        if (!it.isFocused && isTelevisionDevice) {
                            acceptsInput = false
                            keyboardController?.hide()
                        }
                    }
            )
        }
    }
}
