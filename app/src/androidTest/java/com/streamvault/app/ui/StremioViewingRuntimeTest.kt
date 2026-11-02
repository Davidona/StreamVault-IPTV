package com.streamvault.app.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.streamvault.app.tv.WatchNextManager
import com.streamvault.domain.model.stremio.StremioVideo
import com.streamvault.domain.model.stremio.releaseInstant
import dagger.hilt.internal.GeneratedComponentManager
import java.time.Instant
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks against the explicitly seeded, isolated Stremio compatibility AVD. */
@RunWith(AndroidJUnit4::class)
class StremioViewingRuntimeTest {
    @Test fun offsetReleaseTimesWorkWithTheBundledAndroidTimeLibrary() {
        assertThat(StremioVideo("fixture", released="2026-11-01T23:00:00.123456+02:00").releaseInstant())
            .isEqualTo(Instant.parse("2026-11-01T21:00:00.123456Z"))
    }

    @Test fun seededProgressIsPublishedToWatchNextWithAnExactReplayLink() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("stremioViewingFixture") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val component = (app as GeneratedComponentManager<*>).generatedComponent()
        val manager = (component.javaClass.getDeclaredField("watchNextManagerProvider").apply { isAccessible=true }
            .get(component) as Provider<*>).get() as WatchNextManager
        val parental = (component.javaClass.getDeclaredField("stremioCatalogParentalAccessManagerProvider").apply { isAccessible=true }
            .get(component) as Provider<*>).get() as com.streamvault.feature.stremio.presentation.StremioCatalogParentalAccess
        withTimeout(10_000) { parental.state.first { it.isReady } }
        manager.refreshWatchNext()
        app.contentResolver.query(Uri.parse("content://android.media.tv/watch_next_program"),
            arrayOf("title","internal_provider_id","last_playback_position_millis","duration_millis","intent_uri"),null,null,null)!!.use { cursor ->
            val rows = mutableListOf<List<String>>()
            while(cursor.moveToNext()) rows += (0 until cursor.columnCount).map { cursor.getString(it).orEmpty() }
            val fixture = rows.single { it[0] == "Compatibility Movie" }
            assertThat(fixture[1]).startsWith("stremio:")
            assertThat(fixture[2]).isEqualTo("15000")
            assertThat(fixture[3]).isEqualTo("60000")
            val intent = android.content.Intent.parseUri(fixture[4], android.content.Intent.URI_INTENT_SCHEME)
            assertThat(intent.getStringExtra("com.streamvault.app.extra.STREMIO_ITEM"))
                .contains("replayVideoId=movie%3A1%3Avideo")
        }
    }
}
