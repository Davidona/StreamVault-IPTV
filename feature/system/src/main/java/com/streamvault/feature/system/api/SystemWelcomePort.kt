package com.streamvault.feature.system.api

import com.streamvault.domain.sync.Section
import kotlinx.coroutines.flow.Flow

data class WelcomeDevProviderConfig(
    val xtreamServer: String = "",
    val xtreamUsername: String = "",
    val xtreamPassword: String = "",
    val xtreamName: String = "",
    val m3uUrl: String = "",
    val m3uName: String = "",
)

data class WelcomeSyncProgress(
    val section: Section,
    val current: Int,
    val total: Int,
    val currentLabel: String,
    val itemsIndexed: Int,
)

interface SystemWelcomePort {
    val syncProgress: Flow<WelcomeSyncProgress?>
    val devProviderConfig: WelcomeDevProviderConfig
}
