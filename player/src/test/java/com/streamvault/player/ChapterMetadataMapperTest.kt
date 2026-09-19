package com.streamvault.player

import androidx.media3.common.C
import androidx.media3.common.Label
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.Chapter
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChapterMetadataMapperTest {

    @Test
    fun `maps visible chapters into sorted window relative chapters`() {
        val metadata = Metadata(
            chapter(startMs = 12_000L, endMs = C.TIME_UNSET, title = "Second"),
            chapter(startMs = 2_000L, endMs = 8_000L, title = "First"),
            chapter(startMs = 20_000L, endMs = 30_000L, title = "Hidden", hidden = true)
        )

        val chapters = mapChapterMetadata(
            metadata = metadata,
            windowOffsetMs = 1_000L,
            durationMs = 25_000L
        )

        assertThat(chapters).containsExactly(
            PlayerChapter(index = 1, title = "First", startTimeMs = 1_000L, endTimeMs = 7_000L),
            PlayerChapter(index = 2, title = "Second", startTimeMs = 11_000L, endTimeMs = 25_000L)
        ).inOrder()
    }

    @Test
    fun `derives missing end from the next chapter and duration`() {
        val metadata = Metadata(
            chapter(startMs = 0L, endMs = C.TIME_UNSET, title = null),
            chapter(startMs = 10_000L, endMs = C.TIME_UNSET, title = "Final")
        )

        val chapters = mapChapterMetadata(metadata, windowOffsetMs = 0L, durationMs = 30_000L)

        assertThat(chapters).containsExactly(
            PlayerChapter(index = 1, title = "Chapter 1", startTimeMs = 0L, endTimeMs = 10_000L),
            PlayerChapter(index = 2, title = "Final", startTimeMs = 10_000L, endTimeMs = 30_000L)
        ).inOrder()
    }

    @Test
    fun `ignores invalid entries and deduplicates repeated metadata`() {
        val repeated = chapter(startMs = 5_000L, endMs = 9_000L, title = "Same")
        val metadata = Metadata(
            chapter(startMs = C.TIME_UNSET, endMs = C.TIME_UNSET, title = "Unset"),
            chapter(startMs = -2_000L, endMs = 0L, title = "Negative"),
            repeated,
            repeated
        )

        val chapters = mapChapterMetadata(metadata, windowOffsetMs = 0L, durationMs = 20_000L)

        assertThat(chapters).containsExactly(
            PlayerChapter(index = 1, title = "Same", startTimeMs = 5_000L, endTimeMs = 9_000L)
        )
    }

    @Test
    fun `returns empty list without metadata`() {
        assertThat(mapChapterMetadata(null, windowOffsetMs = 0L, durationMs = 20_000L)).isEmpty()
    }

    private fun chapter(
        startMs: Long,
        endMs: Long,
        title: String?,
        hidden: Boolean = false
    ): Chapter = Chapter.Builder()
        .setStartTimeMs(startMs)
        .setEndTimeMs(endMs)
        .setHidden(hidden)
        .apply { title?.let { setTitle(Label(null, it)) } }
        .build()
}
