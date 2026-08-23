@file:Suppress("UseKtx")

package com.streamvault.app.ui.screens.provider

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.streamvault.app.R
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.theme.AccentCyan
import com.streamvault.core.ui.theme.TextPrimary
import com.streamvault.core.ui.theme.TextSecondary
import com.streamvault.core.ui.theme.TextTertiary

@Composable
internal fun JellyfinProviderForm(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    quickConnectCode: String,
    onQuickConnectRequest: () -> Unit,
    isEditing: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = stringResource(R.string.setup_tab_jellyfin),
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )

        // Step 1: Server URL
        ProviderTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            placeholder = stringResource(R.string.setup_server_url)
        )

        // Quick Connect button + QR code display
        if (!isEditing) {
            TvButton(
                onClick = onQuickConnectRequest,
                colors = ButtonDefaults.colors(containerColor = AccentCyan, contentColor = Color.Black)
            ) {
                Text(stringResource(R.string.setup_jellyfin_quick_connect))
            }
        }

        // QR code and code text (shown after quick connect is initiated)
        if (quickConnectCode.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            val qrCodeBitmap = remember(quickConnectCode, serverUrl) {
                generateJellyfinQuickConnectQrCode(serverUrl, quickConnectCode)
            }
            if (qrCodeBitmap != null) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
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
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = stringResource(R.string.setup_jellyfin_quick_connect_code, quickConnectCode),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Text(
                text = stringResource(R.string.setup_jellyfin_quick_connect_code_instructions),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Step 2: Manual credentials (for password auth or existing provider edit)
        ProviderTextField(
            value = username,
            onValueChange = onUsernameChange,
            placeholder = stringResource(R.string.setup_username)
        )
        ProviderTextField(
            value = password,
            onValueChange = onPasswordChange,
            placeholder = stringResource(R.string.setup_password),
            isPassword = true
        )
        ProviderTextField(
            value = name,
            onValueChange = onNameChange,
            placeholder = stringResource(R.string.setup_name_placeholder)
        )
    }
}

internal fun generateJellyfinQuickConnectQrCode(serverUrl: String, code: String): Bitmap? {
    return try {
        val qrUrl = "${serverUrl.trimEnd('/')}/web/index.html#!/quickconnect.html?code=$code"
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(qrUrl, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0 until 512) {
            for (y in 0 until 512) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
