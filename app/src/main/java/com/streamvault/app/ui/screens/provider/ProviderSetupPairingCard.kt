package com.streamvault.app.ui.screens.provider

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.pairing.ProviderQrPairingState
import com.streamvault.app.pairing.ProviderQrPairingStatus
import com.streamvault.core.ui.components.shell.StatusPill
import com.streamvault.core.ui.theme.*

@Composable
internal fun PhonePairingCard(
    pairingState: ProviderQrPairingState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val isActive = pairingState.status == ProviderQrPairingStatus.READY ||
        pairingState.status == ProviderQrPairingStatus.RECEIVING
    val message = pairingState.message ?: stringResource(R.string.setup_phone_pairing_body)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = Surface.copy(alpha = 0.72f)),
        border = Border(
            border = BorderStroke(
                1.dp,
                if (isActive) Primary.copy(alpha = 0.55f) else SurfaceHighlight
            ),
            shape = RoundedCornerShape(16.dp)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.setup_phone_pairing_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = OnBackground
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
                StatusPill(
                    label = when (pairingState.status) {
                        ProviderQrPairingStatus.IDLE -> stringResource(R.string.setup_phone_pairing_idle)
                        ProviderQrPairingStatus.READY -> stringResource(R.string.setup_phone_pairing_ready)
                        ProviderQrPairingStatus.RECEIVING -> stringResource(R.string.setup_phone_pairing_receiving)
                        ProviderQrPairingStatus.COMPLETE -> stringResource(R.string.setup_phone_pairing_complete)
                        ProviderQrPairingStatus.ERROR -> stringResource(R.string.setup_phone_pairing_error)
                    },
                    containerColor = if (pairingState.status == ProviderQrPairingStatus.ERROR) {
                        ErrorColor.copy(alpha = 0.35f)
                    } else {
                        PrimaryGlow
                    }
                )
            }

            pairingState.qrBitmap?.let { bitmap ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.setup_phone_pairing_qr_description),
                        modifier = Modifier
                            .size(156.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.setup_phone_pairing_same_wifi),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnBackground
                        )
                        pairingState.url?.let { url ->
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall,
                                color = Primary,
                                maxLines = 3
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    SmallActionButton(
                        text = if (isActive) {
                            stringResource(R.string.setup_phone_pairing_restart)
                        } else {
                            stringResource(R.string.setup_phone_pairing_start)
                        },
                        onClick = onStart
                    )
                }
                if (isActive) {
                    Box(modifier = Modifier.weight(1f)) {
                        SmallActionButton(
                            text = stringResource(R.string.setup_phone_pairing_stop),
                            onClick = onStop
                        )
                    }
                }
            }
        }
    }
}
