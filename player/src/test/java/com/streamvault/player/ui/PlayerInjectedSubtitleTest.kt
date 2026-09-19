package com.streamvault.player.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerInjectedSubtitleTest {
    @Test
    fun `non-blank text maps to exactly one cue`() {
        val cues = buildInjectedSubtitleCues("Translated line")

        assertThat(cues).hasSize(1)
        assertThat(cues.single().text.toString()).isEqualTo("Translated line")
    }

    @Test
    fun `null and blank text clear cues`() {
        assertThat(buildInjectedSubtitleCues(null)).isEmpty()
        assertThat(buildInjectedSubtitleCues("   ")).isEmpty()
    }
}
