package com.streamvault.data.sync

import androidx.work.ExistingWorkPolicy

private const val PROVIDER_WORK_UNIQUE_PREFIX = "provider-workflow-"
private const val PROVIDER_EPG_WORK_UNIQUE_PREFIX = "provider-epg-"

internal fun providerWorkUniqueName(providerId: Long): String {
    require(providerId > 0L) { "Provider work requires a positive provider ID." }
    return PROVIDER_WORK_UNIQUE_PREFIX + providerId
}

internal fun providerEpgWorkUniqueName(providerId: Long): String {
    require(providerId > 0L) { "Provider EPG work requires a positive provider ID." }
    return PROVIDER_EPG_WORK_UNIQUE_PREFIX + providerId
}

internal fun providerWorkExistingPolicy(supersede: Boolean): ExistingWorkPolicy =
    if (supersede) {
        ExistingWorkPolicy.REPLACE
    } else {
        ExistingWorkPolicy.APPEND_OR_REPLACE
    }
