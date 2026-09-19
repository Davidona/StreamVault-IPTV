package com.streamvault.player.tracks

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.settings.VodTrackPreference
import com.streamvault.player.PlayerTrack
import com.streamvault.player.TrackType
import org.junit.Test

class VodTrackPreferenceResolverTest {

    @Test
    fun `matches a series track by language and label when episode track id changes`() {
        val preference = VodTrackPreference(
            trackId = "episode-one-audio",
            language = "es",
            label = "Spanish"
        )
        val available = listOf(
            PlayerTrack(
                id = "episode-two-audio",
                name = "Spanish",
                language = "es",
                type = TrackType.AUDIO,
                isSelected = false
            )
        )

        assertThat(resolvePreferredPlayerTrack(preference, available)).isEqualTo(available.single())
    }

    @Test
    fun `falls back to the preferred language when the label also changes`() {
        val preference = VodTrackPreference(
            trackId = "old-subtitle",
            language = "fr",
            label = "French"
        )
        val available = listOf(
            PlayerTrack(
                id = "new-subtitle",
                name = "Français",
                language = "fr",
                type = TrackType.TEXT,
                isSelected = false
            )
        )

        assertThat(resolvePreferredPlayerTrack(preference, available)).isEqualTo(available.single())
    }

    @Test
    fun `does not select a track when the preferred language is unavailable`() {
        val preference = VodTrackPreference(language = "de", label = "German")
        val available = listOf(
            PlayerTrack(
                id = "english",
                name = "English",
                language = "en",
                type = TrackType.TEXT,
                isSelected = false
            )
        )

        assertThat(resolvePreferredPlayerTrack(preference, available)).isNull()
    }

    @Test
    fun `falls back to the preferred label when language metadata is missing`() {
        val preference = VodTrackPreference(label = "Commentary")
        val available = listOf(
            PlayerTrack(
                id = "commentary",
                name = "Commentary",
                language = null,
                type = TrackType.AUDIO,
                isSelected = false
            )
        )

        assertThat(resolvePreferredPlayerTrack(preference, available)).isEqualTo(available.single())
    }

    @Test
    fun `matches language families when locale regions differ between episodes`() {
        val preference = VodTrackPreference(language = "es-ES")
        val available = listOf(
            PlayerTrack(
                id = "spanish",
                name = "Spanish",
                language = "es",
                type = TrackType.AUDIO,
                isSelected = false
            )
        )

        assertThat(resolvePreferredPlayerTrack(preference, available)).isEqualTo(available.single())
    }
}
