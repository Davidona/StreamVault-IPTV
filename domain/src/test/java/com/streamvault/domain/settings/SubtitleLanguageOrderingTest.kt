package com.streamvault.domain.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleLanguageOrderingTest {

    @Test
    fun `normalize trims lowercases deduplicates and preserves order`() {
        assertThat(
            SubtitleLanguageOrdering.normalizePinned(
                listOf(" RU ", "en", "ru", "", "  ", "Pt-BR")
            )
        ).containsExactly("ru", "en", "pt-br").inOrder()
    }

    @Test
    fun `primary code maps iso 639 2 and regional tags to base codes`() {
        assertThat(SubtitleLanguageOrdering.primaryCode("eng")).isEqualTo("en")
        assertThat(SubtitleLanguageOrdering.primaryCode("EN")).isEqualTo("en")
        assertThat(SubtitleLanguageOrdering.primaryCode("RUS")).isEqualTo("ru")
        assertThat(SubtitleLanguageOrdering.primaryCode("por")).isEqualTo("pt")
        assertThat(SubtitleLanguageOrdering.primaryCode("pt-BR")).isEqualTo("pt")
        assertThat(SubtitleLanguageOrdering.primaryCode("zh-cn")).isEqualTo("zh")
        assertThat(SubtitleLanguageOrdering.primaryCode("und")).isNull()
        assertThat(SubtitleLanguageOrdering.primaryCode("auto")).isNull()
        assertThat(SubtitleLanguageOrdering.primaryCode(null)).isNull()
        assertThat(SubtitleLanguageOrdering.primaryCode("  ")).isNull()
    }

    @Test
    fun `pinned languages move to the top in pin order and the rest keep their order`() {
        val items = listOf("fr", "en", "ru", "und", "de")

        assertThat(SubtitleLanguageOrdering.sortByPinned(listOf("ru", "en"), items) { it })
            .containsExactly("ru", "en", "fr", "und", "de").inOrder()
    }

    @Test
    fun `provider iso 639 2 tags match pinned two letter codes`() {
        val items = listOf("fre", "eng", "rus")

        assertThat(SubtitleLanguageOrdering.sortByPinned(listOf("ru"), items) { it })
            .containsExactly("rus", "fre", "eng").inOrder()
    }

    @Test
    fun `missing pinned language leaves the remaining order unchanged`() {
        val items = listOf("fr", "en", "de")

        assertThat(SubtitleLanguageOrdering.sortByPinned(listOf("ru"), items) { it })
            .containsExactly("fr", "en", "de").inOrder()
        assertThat(SubtitleLanguageOrdering.sortByPinned(emptyList(), items) { it })
            .containsExactly("fr", "en", "de").inOrder()
    }

    @Test
    fun `exact regional match wins over base language match`() {
        val items = listOf("en", "pt", "pt-br")

        assertThat(SubtitleLanguageOrdering.sortByPinned(listOf("pt-br", "en"), items) { it })
            .containsExactly("pt-br", "pt", "en").inOrder()
    }
}
