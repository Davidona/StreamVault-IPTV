package com.streamvault.player.tracks

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.settings.VodTrackPreference
import com.streamvault.domain.settings.VodTrackPreferences
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreferredTextTrackPolicyTest {

    @Test
    fun `no VOD preference keeps text disabled`() {
        assertThat(resolvePreferredTextTrackPolicy(null)).isEqualTo(
            PreferredTextTrackPolicy(
                preferredLanguageTag = null,
                selectTextByDefault = false,
                textDisabled = true
            )
        )
    }

    @Test
    fun `saved subtitle language enables Media3 automatic text selection`() {
        val preferences = VodTrackPreferences(
            subtitle = VodTrackPreference(language = "en-US", label = "English")
        )

        assertThat(resolvePreferredTextTrackPolicy(preferences)).isEqualTo(
            PreferredTextTrackPolicy(
                preferredLanguageTag = "en-US",
                selectTextByDefault = true,
                textDisabled = false
            )
        )
    }

    @Test
    fun `explicitly disabled subtitles stay disabled`() {
        val preferences = VodTrackPreferences(
            subtitle = VodTrackPreference(language = "en", disabled = true)
        )

        assertThat(resolvePreferredTextTrackPolicy(preferences)).isEqualTo(
            PreferredTextTrackPolicy(
                preferredLanguageTag = null,
                selectTextByDefault = false,
                textDisabled = true
            )
        )
    }

    @Test
    fun `label-only preference waits for the app-owned exact-track override`() {
        val preferences = VodTrackPreferences(
            subtitle = VodTrackPreference(label = "Commentary")
        )

        assertThat(resolvePreferredTextTrackPolicy(preferences)).isEqualTo(
            PreferredTextTrackPolicy(
                preferredLanguageTag = null,
                selectTextByDefault = false,
                textDisabled = true
            )
        )
    }

    @Test
    fun `media3 parameters enable the preferred text language`() {
        val parameters = TrackSelectionParameters.Builder()
            .applyPreferredTextTrackPolicy(
                PreferredTextTrackPolicy(
                    preferredLanguageTag = "en-US",
                    selectTextByDefault = true,
                    textDisabled = false
                )
            )
            .build()

        assertThat(parameters.preferredTextLanguages).containsExactly("en-us")
        assertThat(parameters.selectTextByDefault).isTrue()
        assertThat(parameters.disabledTrackTypes).doesNotContain(C.TRACK_TYPE_TEXT)
    }

    @Test
    fun `media3 parameters disable text when no automatic language is available`() {
        val parameters = TrackSelectionParameters.Builder()
            .applyPreferredTextTrackPolicy(
                PreferredTextTrackPolicy(
                    preferredLanguageTag = null,
                    selectTextByDefault = false,
                    textDisabled = true
                )
            )
            .build()

        assertThat(parameters.preferredTextLanguages).isEmpty()
        assertThat(parameters.selectTextByDefault).isFalse()
        assertThat(parameters.disabledTrackTypes).contains(C.TRACK_TYPE_TEXT)
    }
}
