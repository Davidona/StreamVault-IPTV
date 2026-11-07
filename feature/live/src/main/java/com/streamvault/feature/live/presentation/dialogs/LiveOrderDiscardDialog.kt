package com.streamvault.feature.live.presentation.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.AppDialog
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.browse.OnSurface
import com.streamvault.core.ui.design.browse.SurfaceElevated
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.feature.live.R

@Composable
internal fun LiveOrderDiscardDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AppDialog(onDismissRequest = onKeepEditing) {
        Surface(shape = BrowseShapes.Panel, colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.live_order_discard_title), color = OnSurface)
                TvButton(onClick = onKeepEditing) { Text(stringResource(R.string.action_cancel)) }
                TvButton(onClick = onDiscard) { Text(stringResource(R.string.live_order_discard)) }
            }
        }
    }
}
