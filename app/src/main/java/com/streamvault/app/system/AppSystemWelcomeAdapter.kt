package com.streamvault.app.system

import com.streamvault.app.BuildConfig
import com.streamvault.data.sync.SyncProgressBus
import com.streamvault.feature.system.api.SystemWelcomePort
import com.streamvault.feature.system.api.WelcomeDevProviderConfig
import com.streamvault.feature.system.api.WelcomeSyncProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class AppSystemWelcomeAdapter private constructor(
    private val syncProgressBus: SyncProgressBus,
    override val devProviderConfig: WelcomeDevProviderConfig,
    @Suppress("UNUSED_PARAMETER") ignored: Unit,
) : SystemWelcomePort {
    @Inject
    constructor(syncProgressBus: SyncProgressBus) : this(
        syncProgressBus = syncProgressBus,
        devProviderConfig = WelcomeDevProviderConfig(
            xtreamServer = BuildConfig.XTREAM_DEV_SERVER,
            xtreamUsername = BuildConfig.XTREAM_DEV_USERNAME,
            xtreamPassword = BuildConfig.XTREAM_DEV_PASSWORD,
            xtreamName = BuildConfig.XTREAM_DEV_NAME,
            m3uUrl = BuildConfig.M3U_DEV_URL,
            m3uName = BuildConfig.M3U_DEV_NAME,
        ),
        ignored = Unit,
    )

    internal constructor(
        syncProgressBus: SyncProgressBus,
        devProviderConfig: WelcomeDevProviderConfig,
    ) : this(syncProgressBus, devProviderConfig, Unit)

    override val syncProgress: Flow<WelcomeSyncProgress?> = syncProgressBus.aggregate.map { aggregate ->
        aggregate?.representative?.progress?.let { progress ->
            WelcomeSyncProgress(
                section = progress.section,
                current = progress.current,
                total = progress.total,
                currentLabel = progress.currentLabel,
                itemsIndexed = progress.itemsIndexed,
            )
        }
    }
}
