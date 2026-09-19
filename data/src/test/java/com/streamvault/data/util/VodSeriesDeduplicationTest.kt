package com.streamvault.data.util

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.VodDuplicateConfidence
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodVariantPreferenceMode
import org.junit.Test

class VodSeriesDeduplicationTest {

    @Test
    fun `accented series title still groups with its ascii equivalent`() {
        val accented = series(id = 1L, name = "Café Society", releaseDate = "2024-01-01")
        val ascii = series(id = 2L, name = "Cafe Society", releaseDate = "2024-01-01")

        val presented = buildPresentedSeries(
            series = listOf(accented, ascii),
            settings = SeriesPresentationSettings(
                duplicateHandlingMode = VodDuplicateHandlingMode.SMART,
                preferenceMode = VodVariantPreferenceMode.BALANCED
            )
        )

        assertThat(presented).hasSize(1)
        assertThat(presented.single().duplicateConfidence).isEqualTo(VodDuplicateConfidence.STRONG)
    }

    private fun series(id: Long, name: String, releaseDate: String): Series = Series(
        id = id,
        name = name,
        releaseDate = releaseDate,
        providerId = 7L,
        lastModified = id
    )
}
