package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlayerChapter
import org.junit.Test

class VodChapterNavigationTest {

    private val chapters = listOf(
        PlayerChapter(index = 1, title = "Opening", startTimeMs = 0L, endTimeMs = 60_000L),
        PlayerChapter(index = 2, title = "The turn", startTimeMs = 60_000L, endTimeMs = 120_000L),
        PlayerChapter(index = 3, title = "Finale", startTimeMs = 120_000L, endTimeMs = null)
    )

    @Test
    fun `current chapter includes its start and excludes its end`() {
        assertThat(currentChapter(chapters, positionMs = 60_000L)?.title).isEqualTo("The turn")
        assertThat(currentChapter(chapters, positionMs = 119_999L)?.title).isEqualTo("The turn")
        assertThat(currentChapter(chapters, positionMs = 120_000L)?.title).isEqualTo("Finale")
    }

    @Test
    fun `current chapter is absent in a gap`() {
        val chaptersWithGap = listOf(
            chapters[0],
            chapters[1].copy(startTimeMs = 70_000L)
        )

        assertThat(currentChapter(chaptersWithGap, positionMs = 65_000L)).isNull()
    }

    @Test
    fun `navigation tolerates unordered input`() {
        val unordered = listOf(chapters[2], chapters[0], chapters[1])

        assertThat(currentChapter(unordered, positionMs = 80_000L)?.title).isEqualTo("The turn")
    }

    @Test
    fun `previous chapter restarts the current one after the threshold`() {
        assertThat(previousChapterTarget(chapters, positionMs = 90_000L)).isEqualTo(60_000L)
    }

    @Test
    fun `previous chapter goes back one chapter near a chapter start`() {
        assertThat(previousChapterTarget(chapters, positionMs = 61_000L)).isEqualTo(0L)
    }

    @Test
    fun `previous chapter in the first chapter seeks to the beginning`() {
        assertThat(previousChapterTarget(chapters, positionMs = 1_000L)).isEqualTo(0L)
    }

    @Test
    fun `chapter targets are absent without chapters`() {
        assertThat(previousChapterTarget(emptyList(), 5_000L)).isNull()
        assertThat(nextChapterTarget(emptyList(), 5_000L)).isNull()
    }

    @Test
    fun `next chapter seeks to the following start and is absent in the last chapter`() {
        assertThat(nextChapterTarget(chapters, positionMs = 30_000L)).isEqualTo(60_000L)
        assertThat(nextChapterTarget(chapters, positionMs = 120_000L)).isNull()
    }
}
