package com.streamvault.app.device

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.streamvault.core.ui.device.isTelevisionDevice as coreIsTelevisionDevice
import java.io.File

fun Context.isTelevisionDevice(): Boolean = coreIsTelevisionDevice()

@Composable
fun rememberIsTelevisionDevice(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.isTelevisionDevice() }
}

/**
 * Amazon Fire TV / Fire Stick specifically (narrower than [isTelevisionDevice], which is true for
 * any Android TV). USB-OTG storage handling is gated on this because the behaviour is tailored to
 * Fire OS, where the SAF picker frequently fails to surface plugged-in USB drives.
 */
fun Context.isFireTvDevice(): Boolean =
    packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
        Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

/**
 * App-private writable directories that live on *removable* volumes (a USB OTG drive on a Fire
 * Stick), in the order the platform reports them. Each path is
 * `/storage/<id>/Android/data/<package>/files/`, which Android grants without any runtime
 * permission or document picker — see [android.content.Context.getExternalFilesDirs]. The primary
 * (internal) volume at index 0 is intentionally excluded.
 */
fun Context.removableAppStorageDirs(): List<File> =
    ContextCompat.getExternalFilesDirs(this, null)
        .filterNotNull()
        .filter { runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false) }
