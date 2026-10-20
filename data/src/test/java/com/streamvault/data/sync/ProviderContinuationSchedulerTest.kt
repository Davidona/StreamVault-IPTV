package com.streamvault.data.sync

import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.StalkerDeviceIdentity
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.inOrder
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

    @Test
    fun `Stalker bootstrap continuation schedules the one-hop catalog resume`() = runTest {
        val workScheduler = mock<ProviderSyncWorkScheduler>()
        val snapshot = ProviderSnapshot(
            provider = Provider(id = 8L, name = "Portal", type = ProviderType.STALKER_PORTAL),
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
                    operation = SyncContinuationOperation.STALKER_BOOTSTRAP_RESUME,
                    reason = "test"
                )
            )
        )

        verify(workScheduler).scheduleStalkerBootstrapResume(8L)
        verify(workScheduler, org.mockito.kotlin.never()).scheduleProviderResume(8L)
    }

    @Test
    fun `Xtream movie and series indexes are queued before delayed guide work`() = runTest {
        val workScheduler = mock<ProviderSyncWorkScheduler>()
        val snapshot = ProviderSnapshot(
            provider = Provider(id = 9L, name = "Xtream", type = ProviderType.XTREAM_CODES),
            configuration = XtreamConfig(
                serverUrl = "https://example.com",
                username = "user",
                password = "password"
            ),
            configurationGeneration = 1L
        )

        ProviderContinuationScheduler(workScheduler).schedule(
            snapshot,
            listOf(
                SyncContinuation(
                    operation = SyncContinuationOperation.REFRESH_GUIDE,
                    reason = "guide follows catalog availability"
                ),
                SyncContinuation(
                    operation = SyncContinuationOperation.INDEX_CATALOG,
                    section = com.streamvault.domain.model.ContentType.MOVIE,
                    reason = "movie summary"
                ),
                SyncContinuation(
                    operation = SyncContinuationOperation.INDEX_CATALOG,
                    section = com.streamvault.domain.model.ContentType.SERIES,
                    reason = "series summary"
                )
            )
        )

        inOrder(workScheduler) {
            verify(workScheduler).scheduleXtreamIndex(9L, com.streamvault.domain.model.ContentType.MOVIE, false)
            verify(workScheduler).scheduleXtreamIndex(9L, com.streamvault.domain.model.ContentType.SERIES, false)
            verify(workScheduler).scheduleBackgroundEpg(9L)
        }
    }
    @Test
    fun `automatic index provenance is retained by durable continuations`() = runTest {
        val scheduler = mock<ProviderSyncWorkScheduler>()
        val snapshot = ProviderSnapshot(Provider(9L, "Xtream", ProviderType.XTREAM_CODES),
            XtreamConfig("https://example.test", "user", "password"), 1L)
        withContext(AutomaticSyncExecution.automatic.asContextElement(true)) {
            ProviderContinuationScheduler(scheduler).schedule(snapshot, listOf(
                SyncContinuation(SyncContinuationOperation.INDEX_CATALOG, section = com.streamvault.domain.model.ContentType.MOVIE, reason = "automatic refresh")
            ))
        }
        verify(scheduler).scheduleXtreamIndex(9L, com.streamvault.domain.model.ContentType.MOVIE, false, automatic = true)
    }

}
