package com.streamvault.feature.live.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.AppDialog
import com.streamvault.core.ui.components.dialogs.PinDialog
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.browse.OnSurface
import com.streamvault.core.ui.design.browse.SurfaceElevated
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.feature.live.R

@Composable
internal fun PhoneOrderingDialog(providerId: Long, categoryId: Long?, onDismiss: () -> Unit,
    viewModel: PhoneOrderingViewModel = hiltViewModel(key = "phone-order:$providerId")) {
    PhoneOrderingDialogContent(providerId, null, categoryId, onDismiss, viewModel)
}

@Composable
internal fun CombinedPhoneOrderingDialog(profileId: Long, categoryId: Long?, onDismiss: () -> Unit,
    viewModel: PhoneOrderingViewModel = hiltViewModel(key = "phone-order:combined:$profileId")) {
    PhoneOrderingDialogContent(null, profileId, categoryId, onDismiss, viewModel)
}

@Composable
private fun PhoneOrderingDialogContent(providerId: Long?, profileId: Long?, categoryId: Long?, onDismiss: () -> Unit,
    viewModel: PhoneOrderingViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(providerId, profileId, categoryId) {
        if (profileId != null) viewModel.openCombined(profileId, categoryId)
        else viewModel.open(checkNotNull(providerId), categoryId)
    }
    DisposableEffect(viewModel, providerId, profileId) { onDispose { viewModel.stop() } }
    if (state.pinRequired) {
        PinDialog(onDismissRequest = onDismiss, onPinEntered = viewModel::unlock,
            title = stringResource(R.string.pin_dialog_title), cancelLabel = stringResource(R.string.pin_dialog_cancel),
            error = if (state.incorrectPin) stringResource(R.string.home_incorrect_pin) else null)
    } else AppDialog(onDismissRequest = onDismiss) {
        Surface(shape = BrowseShapes.Panel, colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.live_phone_scan), color = OnSurface)
                state.qr?.let { Image(it.asImageBitmap(), contentDescription = stringResource(R.string.live_phone_scan), modifier = Modifier.size(240.dp)) }
                if (state.failed) Text(stringResource(R.string.live_phone_error), color = OnSurface)
                if (state.loading) androidx.compose.material3.CircularProgressIndicator()
                TvButton(onClick = onDismiss) { Text(stringResource(R.string.live_phone_stop)) }
            }
        }
    }
}
