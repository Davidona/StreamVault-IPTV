package com.streamvault.app.ui.screens.provider

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.extractProgressFraction
import com.streamvault.core.ui.components.shell.StatusPill
import com.streamvault.core.ui.theme.*

@Composable
fun SyncProgressDialog(
    message: String,
    quickConnectCode: String? = null,
    serverUrl: String? = null,
    onCancel: (() -> Unit)? = null
) {
    if (quickConnectCode != null && serverUrl != null) {
        val qrCodeBitmap = remember(quickConnectCode, serverUrl) {
            generateJellyfinQuickConnectQrCode(serverUrl, quickConnectCode)
        }
        PremiumDialog(
            title = "Quick Connect",
            subtitle = "Enter this code on your Jellyfin server",
            onDismissRequest = {},
            widthFraction = 0.38f,
            heightFraction = null,
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (qrCodeBitmap != null) {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrCodeBitmap.asImageBitmap(),
                                contentDescription = "Quick Connect QR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.setup_jellyfin_quick_connect_code, quickConnectCode),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    if (onCancel != null) {
                        TextButton(onClick = onCancel) {
                            Text("Cancel", color = Color.White)
                        }
                    }
                }
            }
        )
    } else {
        val fraction = extractProgressFraction(message)
        val animatedFraction by animateFloatAsState(
            targetValue = fraction ?: 0f,
            animationSpec = tween(durationMillis = 400),
            label = "syncFraction"
        )
        PremiumDialog(
            title = androidx.compose.ui.res.stringResource(R.string.settings_syncing_title),
            subtitle = androidx.compose.ui.res.stringResource(R.string.settings_syncing_subtitle),
            onDismissRequest = {},
            widthFraction = 0.32f,
            heightFraction = null,
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = Primary)
                    StatusPill(
                        label = androidx.compose.ui.res.stringResource(R.string.settings_syncing_btn),
                        containerColor = PrimaryGlow
                    )
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { animatedFraction },
                            color = Primary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(
                            color = Primary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnBackground,
                        textAlign = TextAlign.Center
                    )
                }
            }
        )
    }
}

// ??? ActionButton ?????????????????????????????????????????????????????????????
