package com.streamvault.data.preferences

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.settings.VodTrackPreference
import com.streamvault.domain.settings.VodTrackPreferences
import org.junit.Test

class VodTrackPreferenceCodecTest {

    @Test
    fun `round trip preserves selected audio and subtitle metadata`() {
        val preferences = VodTrackPreferences(
            audio = VodTrackPreference(
                trackId = "audio|es=1",
                language = "es",
                label = "Español, stereo"
            ),
            subtitle = VodTrackPreference(
                trackId = "subtitle\n1",
                language = "fr",
                label = "Français",
                disabled = false
            )
        )

        assertThat(decodeVodTrackPreferences(encodeVodTrackPreferences(preferences)))
            .isEqualTo(preferences)
    }

    @Test
    fun `round trip preserves explicit subtitles disabled preference`() {
        val preferences = VodTrackPreferences(
            subtitle = VodTrackPreference(disabled = true)
        )

        assertThat(decodeVodTrackPreferences(encodeVodTrackPreferences(preferences)))
            .isEqualTo(preferences)
    }
}
