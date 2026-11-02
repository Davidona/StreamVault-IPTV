package com.streamvault.app.stremio

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkerParameters
import com.streamvault.domain.model.stremio.*
import com.streamvault.domain.repository.*
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.feature.stremio.presentation.NoopStremioCatalogParentalAccess
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[33])
class StremioBackgroundWorkerTest {
    @Test fun deliveredEpisodesAreDeduplicatedAndUpcomingEpisodesAreNotNotified()=runBlocking {
        val context:Application=RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        context.getSharedPreferences("stremio_release_delivery",Context.MODE_PRIVATE).edit().clear().commit()
        val account=mock<StremioAccount>();whenever(account.sync()).thenReturn(true)
        val addons=mock<StremioAddonRepository>();val histories=mock<StremioPlaybackHistoryRepository>()
        val preferences=mock<SettingsPreferences>();whenever(preferences.hideStremioAddons).thenReturn(flowOf(false))
        val addon=StremioAddon(1,"https://fixture.test/manifest.json","fixture","Fixture","1",catalogSettings=mapOf(
            stremioLibraryKey(StremioMediaType.SERIES,"series") to true,stremioFollowedKey(StremioMediaType.SERIES,"series",1) to true))
        whenever(addons.observeAddons()).thenReturn(MutableStateFlow(listOf(addon)))
        whenever(histories.observeHistory()).thenReturn(flowOf(emptyList()))
        whenever(addons.getAddon(1)).thenReturn(addon)
        val meta=StremioMetaDetail("series",StremioMediaType.SERIES,"Fixture Series",videos=listOf(
            StremioVideo("released",released=Instant.now().minusSeconds(3600).toString()),
            StremioVideo("future",released=Instant.now().plusSeconds(3600).toString())))
        whenever(addons.resolveMeta(any(),any(),any())).thenReturn(StremioMetaResolution(
            StremioAttributedResource(StremioAddonSource(1,"Fixture","fixture",addon.installFingerprint()),meta)))
        val worker=StremioBackgroundWorker(context,mock<WorkerParameters>(),account,addons,histories,preferences,NoopStremioCatalogParentalAccess)
        worker.doWork()
        val manager=context.getSystemService(NotificationManager::class.java)
        val first=manager.activeNotifications.single().notification
        assertEquals("Fixture Series",first.extras.getString("android.title"))
        val ledger=context.getSharedPreferences("stremio_release_delivery",Context.MODE_PRIVATE)
        assertTrue(ledger.getBoolean(StremioBackgroundWorker.deliveryKey(addon.installFingerprint(),"series","released"),false))
        assertFalse(ledger.getBoolean(StremioBackgroundWorker.deliveryKey(addon.installFingerprint(),"series","future"),false))
        worker.doWork()
        assertSame(first,manager.activeNotifications.single().notification)
    }
}
