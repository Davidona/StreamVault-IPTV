package com.streamvault.data.sync

import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.StalkerDeviceIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class ProviderContinuationSchedulerTest {
    @Test
    fun `full catalog continuation schedules ordinary provider resume`() = runTest {
        val workScheduler = mock<ProviderSyncWorkScheduler>()
        val snapshot = ProviderSnapshot(
            provider = Provider(id = 7L, name = "Portal", type = ProviderType.STALKER_PORTAL),
            configuration = StalkerConfig(
                portalUrl = "https://portal.example.com",
                device = StalkerDeviceIdentity(macAddress = "00:11:22:33:44:55")
            ),
            configurationGeneration = 1L
        )

        ProviderContinuationScheduler(workScheduler).schedule(
            snapshot,
            listOf(
                SyncContinuation(
                    operation = SyncContinuationOperation.FULL_CATALOG,
                    reason = "test",
                    force = true
                )
            )
        )

        verify(workScheduler).scheduleProviderResume(7L)
    }
}
