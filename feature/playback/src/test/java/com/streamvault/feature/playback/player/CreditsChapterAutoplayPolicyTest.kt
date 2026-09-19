package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.player.PlayerChapter
import org.junit.Test

class CreditsChapterAutoplayPolicyTest {

    private val chapters = listOf(
        PlayerChapter(index = 1, title = "Story", startTimeMs = 0L, endTimeMs = 100_000L),
        PlayerChapter(index = 2, title = "End Credits", startTimeMs = 100_000L, endTimeMs = null)
    )

    @Test
    fun `finds credits chapter only while position is inside its range`() {
        assertThat(findCreditsChapter(chapters, positionMs = 100_000L)?.index).isEqualTo(2)
        assertThat(findCreditsChapter(chapters, positionMs = 99_999L)).isNull()
    }

    @Test
    fun `recognizes credit title variants without matching unrelated titles`() {
        assertThat(isCreditsChapterTitle("Closing-Credits")).isTrue()
        assertThat(isCreditsChapterTitle("Post credits scene")).isFalse()
        assertThat(isCreditsChapterTitle("Credit sequence")).isTrue()
        assertThat(isCreditsChapterTitle("Finale")).isFalse()
    }

    @Test
    fun `starts credits autoplay only once for an enabled series with a next episode`() {
        val creditsChapter = chapters.last()

        assertThat(
            shouldStartCreditsAutoPlay(
                currentChapter = creditsChapter,
                lastTriggeredChapterStartMs = null,
                hasNextEpisode = true,
                autoPlayEnabled = true,
                contentType = ContentType.SERIES_EPISODE
            )
        ).isTrue()
        assertThat(
            shouldStartCreditsAutoPlay(
                currentChapter = creditsChapter,
                lastTriggeredChapterStartMs = creditsChapter.startTimeMs,
                hasNextEpisode = true,
                autoPlayEnabled = true,
                contentType = ContentType.SERIES_EPISODE
            )
        ).isFalse()
        assertThat(
            shouldStartCreditsAutoPlay(
                currentChapter = creditsChapter,
                lastTriggeredChapterStartMs = null,
                hasNextEpisode = false,
                autoPlayEnabled = true,
                contentType = ContentType.SERIES_EPISODE
            )
        ).isFalse()
        assertThat(
            shouldStartCreditsAutoPlay(
                currentChapter = creditsChapter,
                lastTriggeredChapterStartMs = null,
                hasNextEpisode = true,
                autoPlayEnabled = true,
                contentType = ContentType.MOVIE
            )
        ).isFalse()
    }

    @Test
    fun `classifies skippable chapter names using streaming segment conventions`() {
        assertThat(skippableChapterType("Intro")).isEqualTo(SkippableChapterType.INTRO)
        assertThat(skippableChapterType("Opening Credits")).isEqualTo(SkippableChapterType.OPENING)
        assertThat(skippableChapterType("Previously On")).isEqualTo(SkippableChapterType.RECAP)
        assertThat(skippableChapterType("Outro")).isEqualTo(SkippableChapterType.OUTRO)
        assertThat(isCreditsChapterTitle("Opening Credits")).isFalse()
        assertThat(isCreditsChapterTitle("Post Credits Scene")).isFalse()
        assertThat(skippableChapterType("Story")).isNull()
    }

    @Test
    fun `finds a skip action only while a named chapter has a valid end`() {
        val action = findSkippableChapterAction(
            chapters = listOf(
                PlayerChapter(index = 1, title = "Intro", startTimeMs = 0L, endTimeMs = 90_000L),
                PlayerChapter(index = 2, title = "Story", startTimeMs = 90_000L, endTimeMs = null)
            ),
            positionMs = 45_000L
        )

        assertThat(action?.type).isEqualTo(SkippableChapterType.INTRO)
        assertThat(action?.targetPositionMs).isEqualTo(90_000L)
        assertThat(
            findSkippableChapterAction(
                chapters = listOf(
                    PlayerChapter(index = 1, title = "Intro", startTimeMs = 0L, endTimeMs = 90_000L)
                ),
                positionMs = 90_000L
            )
        ).isNull()
        assertThat(
            findSkippableChapterAction(
                chapters = listOf(
                    PlayerChapter(index = 1, title = "Intro", startTimeMs = 0L, endTimeMs = null)
                ),
                positionMs = 45_000L
            )
        ).isNull()
    }

    @Test
    fun `allows skip chapters only for playable vod content`() {
        assertThat(ContentType.VOD.isSkippableChapterContent()).isTrue()
        assertThat(ContentType.MOVIE.isSkippableChapterContent()).isTrue()
        assertThat(ContentType.SERIES_EPISODE.isSkippableChapterContent()).isTrue()
        assertThat(ContentType.LIVE.isSkippableChapterContent()).isFalse()
    }

    @Test
    fun `requires generic credits to begin near the end of the media`() {
        val chapters = listOf(
            PlayerChapter(index = 1, title = "Credits", startTimeMs = 100_000L, endTimeMs = 160_000L),
            PlayerChapter(index = 2, title = "Story", startTimeMs = 160_000L, endTimeMs = null)
        )

        assertThat(
            findCreditsChapter(chapters, positionMs = 150_000L, durationMs = 300_000L)
        ).isNull()
        assertThat(
            findCreditsChapter(chapters, positionMs = 120_000L, durationMs = 160_000L)?.title
        ).isEqualTo("Credits")
        assertThat(
            findCreditsChapter(
                chapters = listOf(
                    PlayerChapter(index = 1, title = "End Credits", startTimeMs = 20_000L, endTimeMs = null)
                ),
                positionMs = 25_000L,
                durationMs = 300_000L
            )?.title
        ).isEqualTo("End Credits")
    }
}
