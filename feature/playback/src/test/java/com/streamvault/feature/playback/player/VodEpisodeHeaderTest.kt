package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Episode
import org.junit.Test

class VodEpisodeHeaderTest {

    private fun episode(title: String) = Episode(id = 1, title = title, episodeNumber = 3, seasonNumber = 2)

    @Test
    fun `episode name equal to the title is dropped`() {
        val header = buildVodEpisodeHeader(episode("Show - S02E03 - Pilot"), "Show - S02E03 - Pilot")
        assertThat(header.episodeTitle).isEmpty()
        assertThat(header.seasonNumber).isEqualTo(2)
        assertThat(header.episodeNumber).isEqualTo(3)
    }

    @Test
    fun `episode name contained in the title is dropped`() {
        assertThat(buildVodEpisodeHeader(episode("Pilot"), "Show - S02E03 - Pilot").episodeTitle).isEmpty()
    }

    @Test
    fun `title contained in the episode name is dropped`() {
        assertThat(buildVodEpisodeHeader(episode("Show - S02E03 - Pilot"), "show").episodeTitle).isEmpty()
    }

    @Test
    fun `distinct episode name is kept`() {
        assertThat(buildVodEpisodeHeader(episode("The Pilot"), "Some Show").episodeTitle).isEqualTo("The Pilot")
    }

    @Test
    fun `blank display title keeps the episode name`() {
        assertThat(buildVodEpisodeHeader(episode("The Pilot"), "  ").episodeTitle).isEqualTo("The Pilot")
    }

    @Test
    fun `matching episode marker is stripped with its separator`() {
        val title = "AR-ANM-S - The Witch (2026) (JP) - S01E01 - I Tried Using"
        assertThat(stripEpisodeMarker(title, 1, 1)).isEqualTo("AR-ANM-S - The Witch (2026) (JP) - I Tried Using")
    }

    @Test
    fun `marker is matched with or without zero padding and case`() {
        assertThat(stripEpisodeMarker("Show s2e3", 2, 3)).isEqualTo("Show")
        assertThat(stripEpisodeMarker("Show - S02 E03", 2, 3)).isEqualTo("Show")
    }

    @Test
    fun `marker with other numbers is left alone`() {
        assertThat(stripEpisodeMarker("Show - S10E01", 1, 1)).isEqualTo("Show - S10E01")
        assertThat(stripEpisodeMarker("Show - S01E02", 1, 1)).isEqualTo("Show - S01E02")
    }

    @Test
    fun `title without a marker is unchanged`() {
        assertThat(stripEpisodeMarker("Some Movie (2024)", 1, 1)).isEqualTo("Some Movie (2024)")
    }
}
