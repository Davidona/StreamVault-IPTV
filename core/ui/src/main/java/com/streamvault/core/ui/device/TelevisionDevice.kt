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
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return classifyTelevisionDevice(
        hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
        hasLeanbackOnly = packageManager.hasSystemFeature("android.software.leanback_only"),
        hasTelevision = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION),
        hasFireTv = packageManager.hasSystemFeature("amazon.hardware.fire_tv"),
        uiModeType = uiModeManager?.currentModeType,
        screenWidthDp = resources.configuration.screenWidthDp,
        hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    )
}
