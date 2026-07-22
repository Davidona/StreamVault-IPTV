package com.streamvault.data.util

import android.content.Context
import android.os.Build
import android.os.UserManager

/** Returns true only when credential-protected storage is safe to use. */
fun Context.isUserUnlockedForWork(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true

    val userManager = getSystemService(UserManager::class.java)
    return userManager?.isUserUnlocked == true &&
        !applicationContext.isDeviceProtectedStorage
}
