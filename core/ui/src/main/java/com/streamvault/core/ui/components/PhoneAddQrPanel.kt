package com.streamvault.core.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.streamvault.core.ui.interaction.TvButton

@Composable
fun PhoneAddQrPanel(
    url: String?,
    title: String,
    description: String,
    status: String? = null,
    startLabel: String,
    stopLabel: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val bitmap = remember(url) {
        url?.let { value ->
            runCatching {
                val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 320, 320)
                Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply {
                    for (y in 0 until 320) for (x in 0 until 320) {
                        setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
            }.getOrNull()
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title)
        Text(description)
        if (status != null) Text(status)
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = title,
                modifier = Modifier.size(168.dp).background(androidx.compose.ui.graphics.Color.White).padding(8.dp),
            )
            Text(url.orEmpty())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton(onClick = onStart) { Text(startLabel) }
            if (url != null) TvButton(onClick = onStop) { Text(stopLabel) }
        }
    }
}
