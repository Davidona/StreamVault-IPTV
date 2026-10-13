package com.streamvault.data.sync

import androidx.work.Constraints
import androidx.work.NetworkType
import com.streamvault.domain.sync.AutomaticSyncSettings

internal fun automaticSyncConstraints(policy: AutomaticSyncSettings): Constraints = Constraints.Builder()
    .setRequiredNetworkType(if (policy.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
    .setRequiresBatteryNotLow(true)
    .setRequiresCharging(policy.chargingOnly)
    .setRequiresDeviceIdle(policy.deviceIdleOnly)
    .build()
