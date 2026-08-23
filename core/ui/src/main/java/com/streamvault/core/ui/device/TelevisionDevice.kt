package com.streamvault.core.ui.device

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

internal fun classifyTelevisionDevice(
    hasLeanback: Boolean,
    hasLeanbackOnly: Boolean,
    hasTelevision: Boolean,
    hasFireTv: Boolean,
    uiModeType: Int?,
    screenWidthDp: Int,
    hasTouchscreen: Boolean
): Boolean =
    hasLeanback ||
        hasLeanbackOnly ||
        hasTelevision ||
        hasFireTv ||
        uiModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        (!hasTouchscreen && screenWidthDp >= 900)

fun Context.isTelevisionDevice(): Boolean {
    val packageManager = packageManager

    if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature("android.software.leanback_only") ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
        packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    ) {
        return true
    }

    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
        return true
    }

    return classifyTelevisionDevice(
        hasLeanback = false,
        hasLeanbackOnly = false,
        hasTelevision = false,
        hasFireTv = false,
        uiModeType = null,
        screenWidthDp = resources.configuration.screenWidthDp,
        hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    )
}
