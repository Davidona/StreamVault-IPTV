package com.streamvault.player.tracks

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlayerTrackControllerSubtitleOrderingTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val controller = PlayerTrackController(context)

    private fun textFormat(language: String) = Format.Builder()
        .setSampleMimeType("application/x-subrip")
        .setLanguage(language)
        .build()

    private val tracks = Tracks(
        listOf(
            Tracks.Group(
                TrackGroup("subs", textFormat("fra"), textFormat("eng"), textFormat("rus")),
                /* adaptiveSupported= */ false,
                /* trackSupport= */ IntArray(3) { C.FORMAT_HANDLED },
                /* trackSelected= */ booleanArrayOf(false, false, false)
            )
        )
    )

    @Test
    fun `subtitle tracks follow the pinned order after tracks change`() {
        controller.setPinnedSubtitleLanguages(listOf("ru", "en"))

        controller.onTracksChanged(tracks)

        assertThat(controller.availableSubtitleTracks.value.map { it.language })
            .containsExactly("ru", "en", "fr").inOrder()
    }

    @Test
    fun `pinned languages re-sort already available tracks`() {
        controller.onTracksChanged(tracks)
        assertThat(controller.availableSubtitleTracks.value.map { it.language })
            .containsExactly("fr", "en", "ru").inOrder()

        controller.setPinnedSubtitleLanguages(listOf("ru"))

        assertThat(controller.availableSubtitleTracks.value.map { it.language })
            .containsExactly("ru", "fr", "en").inOrder()
    }

    @Test
    fun `missing pinned language keeps the provider order`() {
        controller.setPinnedSubtitleLanguages(listOf("de"))

        controller.onTracksChanged(tracks)

        assertThat(controller.availableSubtitleTracks.value.map { it.language })
            .containsExactly("fr", "en", "ru").inOrder()
    }

    @Test
    fun `reset selections keeps the pinned preference`() {
        controller.setPinnedSubtitleLanguages(listOf("ru"))
        controller.resetSelections()

        controller.onTracksChanged(tracks)

        assertThat(controller.availableSubtitleTracks.value.map { it.language })
            .containsExactly("ru", "fr", "en").inOrder()
    }
}
