package com.streamvault.app.system

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.sync.SyncProgressBus
import com.streamvault.domain.sync.Section
import com.streamvault.domain.sync.SyncProgress
import com.streamvault.feature.system.api.WelcomeDevProviderConfig
import com.streamvault.feature.system.api.WelcomeSyncProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AppSystemWelcomeAdapterTest {
    @Test
    fun progressMappingPreservesRepresentativeSnapshot() = runTest {
        val bus = SyncProgressBus()
        val adapter = AppSystemWelcomeAdapter(
            syncProgressBus = bus,
            devProviderConfig = WelcomeDevProviderConfig(
                xtreamServer = " https://xtream.example ",
                xtreamUsername = " user ",
                xtreamPassword = " pass ",
                xtreamName = " Xtream ",
                m3uUrl = " https://m3u.example/list.m3u ",
                m3uName = " M3U ",
            ),
        )
        val observed = async { adapter.syncProgress.filterNotNull().first() }
        val session = bus.begin(providerId = 7L)
        bus.emit(session, SyncProgress(Section.VOD, 2, 5, "Movies", 44))

        assertThat(observed.await()).isEqualTo(
            WelcomeSyncProgress(Section.VOD, 2, 5, "Movies", 44),
        )
    }

    @Test
    fun developmentProviderConfigPreservesAllValuesWithoutNormalization() {
        val config = WelcomeDevProviderConfig(
            xtreamServer = " https://xtream.example ",
            xtreamUsername = " user ",
            xtreamPassword = " pass ",
            xtreamName = " Xtream ",
            m3uUrl = " https://m3u.example/list.m3u ",
            m3uName = " M3U ",
        )

        assertThat(AppSystemWelcomeAdapter(SyncProgressBus(), config).devProviderConfig)
            .isEqualTo(config)
    }
}
