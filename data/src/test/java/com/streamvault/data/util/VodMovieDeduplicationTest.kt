package com.streamvault.data.util

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.VodDuplicateConfidence
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodVariantPreferenceMode
import org.junit.Test

class VodMovieDeduplicationTest {

    @Test
    fun `force latest prefers newest version over older higher quality`() {
        val older4k = movie(
            id = 1L,
            name = "Space Run 4K HDR",
            year = "2021",
            streamUrl = "http://example.test/movie/1.mkv"
        )
        val newerHd = movie(
            id = 2L,
            name = "Space Run HD",
            year = "2024",
            streamUrl = "http://example.test/movie/2.mp4"
        )

        val selected = selectPreferredMovieVariant(
            movies = listOf(older4k, newerHd),
            preferenceMode = VodVariantPreferenceMode.FORCE_LATEST
        )

        assertThat(selected?.id).isEqualTo(2L)
    }

    @Test
    fun `best quality prefers older higher quality version over latest hd`() {
        val older4k = movie(
            id = 1L,
            name = "Space Run 4K HDR Remux",
            year = "2021",
            streamUrl = "http://example.test/movie/1.mkv"
        )
        val newerHd = movie(
            id = 2L,
            name = "Space Run HD",
            year = "2024",
            streamUrl = "http://example.test/movie/2.mp4"
        )

        val selected = selectPreferredMovieVariant(
            movies = listOf(older4k, newerHd),
            preferenceMode = VodVariantPreferenceMode.BEST_QUALITY
        )

        assertThat(selected?.id).isEqualTo(1L)
    }

    @Test
    fun `smart grouping keeps title matches with different years separate`() {
        val original = movie(id = 1L, name = "The Escape", year = "1999")
        val remake = movie(id = 2L, name = "The Escape", year = "2024")

        val presented = buildPresentedMovies(
            movies = listOf(original, remake),
            settings = MoviePresentationSettings(
                duplicateHandlingMode = VodDuplicateHandlingMode.SMART,
                preferenceMode = VodVariantPreferenceMode.BALANCED
            )
        )

        assertThat(presented).hasSize(2)
        assertThat(movieDuplicateConfidence(listOf(original, remake))).isEqualTo(VodDuplicateConfidence.WEAK)
    }

    @Test
    fun `smart grouping collapses same title same year into grouped representative`() {
        val sd = movie(id = 1L, name = "Space Run SD", year = "2024")
        val hd = movie(id = 2L, name = "Space Run 1080p", year = "2024")

        val presented = buildPresentedMovies(
            movies = listOf(sd, hd),
            settings = MoviePresentationSettings(
                duplicateHandlingMode = VodDuplicateHandlingMode.SMART,
                preferenceMode = VodVariantPreferenceMode.BEST_QUALITY
            )
        )

        assertThat(presented).hasSize(1)
        assertThat(presented.single().selectedVariantId).isEqualTo(2L)
        assertThat(presented.single().variants.map { it.rawMovieId }).containsExactly(2L, 1L).inOrder()
        assertThat(presented.single().duplicateConfidence).isEqualTo(VodDuplicateConfidence.STRONG)
    }

    @Test
    fun `accented title still groups with its ascii equivalent`() {
        val accented = movie(id = 1L, name = "Café Society", year = "2024")
        val ascii = movie(id = 2L, name = "Cafe Society", year = "2024")

        val presented = buildPresentedMovies(
            movies = listOf(accented, ascii),
            settings = MoviePresentationSettings(
                duplicateHandlingMode = VodDuplicateHandlingMode.SMART,
                preferenceMode = VodVariantPreferenceMode.BALANCED
            )
        )

        assertThat(presented).hasSize(1)
        assertThat(presented.single().duplicateConfidence).isEqualTo(VodDuplicateConfidence.STRONG)
    }

    private fun movie(
        id: Long,
        name: String,
        year: String? = null,
        streamUrl: String = "http://example.test/movie/$id.mp4"
    ): Movie = Movie(
        id = id,
        streamId = id,
        name = name,
        year = year,
        streamUrl = streamUrl,
        providerId = 7L,
        containerExtension = streamUrl.substringAfterLast('.', "mp4"),
        addedAt = id
    )
}
