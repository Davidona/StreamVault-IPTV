package com.streamvault.feature.stremio.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.stremio.StremioCatalogRef
import com.streamvault.domain.model.stremio.StremioMediaType
import org.junit.Test

class StremioCatalogParentalAccessTest {
    private val pornCatalog = StremioCatalogRef(
        type = StremioMediaType.MOVIE,
        id = "porn",
        name = "Porn"
    )

    @Test
    fun `adult named catalog follows parental level even without IPTV provider`() {
        val locked = StremioCatalogParentalState(parentalControlLevel = 1).accessFor(pornCatalog)
        val hidden = StremioCatalogParentalState(parentalControlLevel = 3).accessFor(pornCatalog)
        val unrestricted = StremioCatalogParentalState(parentalControlLevel = 0).accessFor(pornCatalog)

        assertThat(locked.locked).isTrue()
        assertThat(locked.protectedCategoryIds).isEmpty()
        assertThat(hidden.hidden).isTrue()
        assertThat(unrestricted.locked).isFalse()
        assertThat(unrestricted.hidden).isFalse()
    }

    @Test
    fun `default parental state locks adult named catalogs`() {
        val access = StremioCatalogParentalState().accessFor(pornCatalog)

        assertThat(access.locked).isTrue()
        assertThat(access.hidden).isFalse()
    }

    @Test
    fun `hidden setting for matching unified VOD category hides movie and series catalogs`() {
        val category = Category(id = 42, name = "Porn", type = ContentType.VOD, isAdult = true)
        val state = StremioCatalogParentalState(
            categories = listOf(category),
            hiddenVodCategoryIds = setOf(category.id),
            parentalControlLevel = 1
        )

        assertThat(state.accessFor(pornCatalog).hidden).isTrue()
        assertThat(
            state.accessFor(pornCatalog.copy(type = StremioMediaType.SERIES)).hidden
        ).isTrue()
    }

    @Test
    fun `protected setting maps category IDs for PIN unlock`() {
        val category = Category(id = 91, name = "Premium", type = ContentType.MOVIE, isUserProtected = true)
        val state = StremioCatalogParentalState(
            categories = listOf(category),
            parentalControlLevel = 2
        )

        val locked = state.accessFor(pornCatalog.copy(id = "premium", name = "Premium"))
        val unlocked = state.copy(unlockedCategoryIds = setOf(category.id))
            .accessFor(pornCatalog.copy(id = "premium", name = "Premium"))

        assertThat(locked.locked).isTrue()
        assertThat(locked.protectedCategoryIds).contains(category.id)
        assertThat(unlocked.locked).isFalse()
    }
}
