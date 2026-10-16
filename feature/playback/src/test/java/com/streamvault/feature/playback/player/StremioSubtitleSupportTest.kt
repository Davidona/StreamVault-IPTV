package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.StremioSubtitlePlaybackSource
import com.streamvault.domain.model.stremio.StremioAddonSource
import com.streamvault.domain.model.stremio.StremioAttributedResource
import com.streamvault.domain.model.stremio.StremioSubtitle
import org.junit.Test

class StremioSubtitleSupportTest {
    @Test
    fun `three letter language codes map to player language tags`() {
        assertThat(stremioSubtitleLanguageTag("eng")).isEqualTo("en")
        assertThat(stremioSubtitleLanguageTag("ger")).isEqualTo("de")
        assertThat(stremioSubtitleLanguageTag("pt_br")).isEqualTo("pt-BR")
        assertThat(stremioSubtitleLanguageTag("unknown")).isEqualTo("und")
    }

    @Test
    fun `subtitle options include inline and addon resources and deduplicate urls`() {
        val options = buildStremioPlayerSubtitleOptions(
            inlineSubtitles = listOf(
                StremioSubtitlePlaybackSource(
                    url = "https://cdn.example.test/eng.srt?sig=secret",
                    language = "eng",
                    id = "inline"
                )
            ),
            addonSubtitles = listOf(
                StremioAttributedResource(
                    source = StremioAddonSource(1L, "Subtitles", "subtitles", "addon-key"),
                    resource = StremioSubtitle(
                        url = "https://cdn.example.test/eng.srt?sig=secret",
                        lang = "en",
                        id = "resource"
                    )
                ),
                StremioAttributedResource(
                    source = StremioAddonSource(1L, "Subtitles", "subtitles", "addon-key"),
                    resource = StremioSubtitle(
                        url = "file:///private/subtitle.srt",
                        lang = "eng"
                    )
                ),
                StremioAttributedResource(
                    source = StremioAddonSource(1L, "Subtitles", "subtitles", "addon-key"),
                    resource = StremioSubtitle(
                        url = "https://cdn.example.test/de.srt",
                        lang = "deu",
                        id = "german"
                    )
                )
            )
        )

        assertThat(options).hasSize(2)
        assertThat(options[0].languageTag).isEqualTo("en")
        assertThat(options[0].label).contains("Stream")
        assertThat(options[1].languageTag).isEqualTo("de")
        assertThat(options[1].label).contains("Subtitles")
    }
}
