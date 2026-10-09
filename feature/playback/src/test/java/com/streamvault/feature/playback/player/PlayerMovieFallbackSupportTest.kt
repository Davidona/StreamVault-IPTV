package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.model.VodMovieVariant
import org.junit.Test

class PlayerMovieFallbackSupportTest {

    @Test
    fun `selects the best lower AVC variant for the same title`() {
        val current = movie(id = 10L, name = "Example Film (2021) 1080p HEVC")
        val lower = variant(id = 12L, name = "Example Film (2021) 720p AVC")
        val higher = variant(id = 11L, name = "Example Film (2021) 4K AVC")
        val unrelated = variant(id = 13L, name = "Different Film (2021) 480p AVC")

        val selected = selectAvcMovieFallbackVariant(current, listOf(higher, unrelated, lower))

        assertThat(selected).isEqualTo(lower)
    }

    @Test
    fun `rejects AVC candidates with unknown quality`() {
        val current = movie(id = 10L, name = "Example Film (2021) 4K HEVC")
        val unknown = variant(id = 11L, name = "Example Film (2021) AVC")

        assertThat(selectAvcMovieFallbackVariant(current, listOf(unknown))).isNull()
    }

    @Test
    fun `does not use a provider variant fallback for a non HEVC source`() {
        val current = movie(id = 10L, name = "Example Film (2021) 1080p AVC")
        val lower = variant(id = 11L, name = "Example Film (2021) 720p AVC")

        assertThat(selectAvcMovieFallbackVariant(current, listOf(lower))).isNull()
    }

    @Test
    fun `allows same resolution when AVC is a codec compatible fallback`() {
        val current = movie(id = 10L, name = "Example Film (2021) 4K HEVC")
        val compatible = variant(id = 11L, name = "Example Film (2021) 4K AVC")

        assertThat(selectAvcMovieFallbackVariant(current, listOf(compatible))).isEqualTo(compatible)
    }

    @Test
    fun `recognizes dotted codec labels when title only provides the quality`() {
        val current = movie(id = 10L, name = "Example Film (2021) 1080p H.265")
        val compatible = variant(
            id = 11L,
            name = "Example Film (2021) 720p",
            label = "720p H.264"
        )

        assertThat(selectAvcMovieFallbackVariant(current, listOf(compatible))).isEqualTo(compatible)
    }

    @Test
    fun `does not switch adaptive sources to a provider variant`() {
        val current = movie(id = 10L, name = "Example Film (2021) 4K HEVC")
        val lower = variant(id = 11L, name = "Example Film (2021) 1080p AVC")

        assertThat(
            selectAvcMovieFallbackVariant(
                currentMovie = current,
                variants = listOf(lower),
                currentStreamType = StreamType.HLS
            )
        ).isNull()
    }

    @Test
    fun `preserves an explicit provider variant choice`() {
        val current = movie(id = 10L, name = "Example Film (2021) 4K HEVC")
        val lower = variant(id = 11L, name = "Example Film (2021) 1080p AVC")

        assertThat(
            selectAvcMovieFallbackVariant(
                currentMovie = current,
                variants = listOf(lower),
                hasExplicitVariantChoice = true
            )
        ).isNull()
    }

    @Test
    fun `preserves a manual video track choice`() {
        val current = movie(id = 10L, name = "Example Film (2021) 4K HEVC")
        val lower = variant(id = 11L, name = "Example Film (2021) 1080p AVC")

        assertThat(
            selectAvcMovieFallbackVariant(
                currentMovie = current,
                variants = listOf(lower),
                hasManualVideoTrackChoice = true
            )
        ).isNull()
    }

    private fun movie(id: Long, name: String) = Movie(
        id = id,
        name = name,
        providerId = 1L,
        streamUrl = "https://provider.invalid/movie/$id.mkv"
    )

    private fun variant(id: Long, name: String, label: String = name.substringAfterLast(' ')) = VodMovieVariant(
        rawMovieId = id,
        name = name,
        streamUrl = "https://provider.invalid/movie/$id.mkv",
        streamId = id,
        containerExtension = "mkv",
        releaseDate = "2021-01-01",
        year = "2021",
        durationSeconds = 5_400,
        rating = 7.0f,
        addedAt = id,
        qualityScore = 0,
        recencyScore = 0L,
        reliabilityScore = 0,
        label = label
    )
}
