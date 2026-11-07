package com.streamvault.data.preferences

import androidx.datastore.preferences.core.*
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.*
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiveCategoryOrderStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `combined identity copy preserves category sections and supports retries`() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "identity-copy.preferences_pb") }
        val store = LiveCategoryOrderStore(data)
        val original = LiveCategoryOrder(sortMode = CategorySortMode.MANUAL, normalIds = listOf(4, 2),
            pinnedIds = listOf(4), personalIds = listOf(-999), pinnedCategoryIds = setOf(4))
        store.saveCombined("legacy-combined-1", 0, original)
        val newIdentity = store.prepareCombinedIdentityMigration("legacy-combined-1")
        assertThat(store.prepareCombinedIdentityMigration("legacy-combined-1")).isEqualTo(newIdentity)
        assertThat(store.observeCombined(newIdentity).first()).isEqualTo(store.observeCombined("legacy-combined-1").first())
        val current = store.observeCombined("legacy-combined-1").first()
        store.saveCombined("legacy-combined-1", current.revision, current.copy(normalIds = listOf(2, 4)))
        assertThat(store.prepareCombinedIdentityMigration("legacy-combined-1")).isEqualTo(newIdentity)
        assertThat(store.observeCombined(newIdentity).first().normalIds).containsExactly(2L, 4L).inOrder()
    }

    @Test fun `duplicate category copy preserves shared data and stays independent on retry`() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "duplicate-category.preferences_pb") }
        val store = LiveCategoryOrderStore(data)
        val original = LiveCategoryOrder(normalIds = listOf(2, 1), pinnedCategoryIds = setOf(2))
        store.saveCombined("legacy-combined-1", 0, original)
        val copy = store.prepareDuplicateCombinedIdentityMigration("legacy-combined-1", 1)
        val saved = store.observeCombined(copy).first()
        assertThat(saved).isEqualTo(store.observeCombined("legacy-combined-1").first())
        store.saveCombined(copy, saved.revision, saved.copy(normalIds = listOf(1, 2)))
        assertThat(store.prepareDuplicateCombinedIdentityMigration("legacy-combined-1", 1)).isEqualTo(copy)
        assertThat(store.observeCombined(copy).first().normalIds).containsExactly(1L, 2L).inOrder()
        assertThat(store.observeCombined("legacy-combined-1").first().normalIds).containsExactly(2L, 1L).inOrder()
    }

    @Test fun `order commits with existing category sort and reads existing pin membership`() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "category.preferences_pb") }
        data.edit { it[stringPreferencesKey("pinned_categories_7_LIVE")] = "2,3" }
        val store = LiveCategoryOrderStore(data)
        val order = LiveCategoryOrder(CategorySortMode.MANUAL, normalIds = listOf(3, 1, 2), pinnedIds = listOf(3, 2), personalIds = listOf(-999, -998))
        assertThat(store.save(7, 0, order)).isInstanceOf(SaveLiveCategoryOrderResult.Saved::class.java)
        val saved = LiveCategoryOrderStore(data).observe(7).first()
        assertThat(saved.normalIds).containsExactly(3L, 1L, 2L).inOrder()
        assertThat(saved.pinnedIds).containsExactly(3L, 2L).inOrder()
        assertThat(saved.pinnedCategoryIds).containsExactly(2L, 3L)
        assertThat(data.data.first()[stringPreferencesKey("category_sort_7_LIVE")]).isEqualTo("MANUAL")
        assertThat(store.observe(8).first()).isEqualTo(LiveCategoryOrder())
    }

    @Test fun `stale editor cannot overwrite a later predefined sort choice`() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "sort.preferences_pb") }
        val store = LiveCategoryOrderStore(data)
        store.save(7, 0, LiveCategoryOrder(CategorySortMode.MANUAL, normalIds = listOf(3, 1, 2)))
        data.edit { it[LiveCategoryOrderStore.modeKey(7)] = "TITLE_ASC"; LiveCategoryOrderStore.bumpRevision(it, 7) }
        assertThat(store.save(7, 1, LiveCategoryOrder(normalIds = listOf(1, 2, 3)))).isInstanceOf(SaveLiveCategoryOrderResult.Conflict::class.java)
        val saved = store.observe(7).first()
        assertThat(saved.sortMode).isEqualTo(CategorySortMode.TITLE_ASC)
        assertThat(saved.normalIds).containsExactly(3L, 1L, 2L).inOrder()
    }

    @Test fun `concurrent drafts produce one save and one conflict`() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "race.preferences_pb") }
        val store = LiveCategoryOrderStore(data)
        val a = async { store.save(7, 0, LiveCategoryOrder(normalIds = listOf(1, 2))) }
        val b = async { store.save(7, 0, LiveCategoryOrder(normalIds = listOf(2, 1))) }
        val results = listOf(a.await(), b.await())
        assertThat(results.filterIsInstance<SaveLiveCategoryOrderResult.Saved>()).hasSize(1)
        assertThat(results.filterIsInstance<SaveLiveCategoryOrderResult.Conflict>()).hasSize(1)
    }

    @Test fun `pin membership and order save atomically and reject stale drafts`() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "pins.preferences_pb") }
        val store = LiveCategoryOrderStore(data)
        val order = LiveCategoryOrder(normalIds = listOf(1, 2, 3), pinnedIds = listOf(3), pinnedCategoryIds = setOf(3))
        assertThat(store.save(7, 0, order, includePins = true)).isInstanceOf(SaveLiveCategoryOrderResult.Saved::class.java)
        val saved = store.observe(7).first()
        assertThat(saved.pinnedCategoryIds).containsExactly(3L)
        assertThat(saved.normalIds).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(saved.revision).isEqualTo(1)
        assertThat(store.save(7, 0, order.copy(pinnedCategoryIds = setOf(2)), includePins = true)).isInstanceOf(SaveLiveCategoryOrderResult.Conflict::class.java)
        assertThat(store.observe(7).first()).isEqualTo(saved)
    }

    @Test fun `combined category orders use isolated portable profile identities and atomic revisions`() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "combined.preferences_pb") }
        val store = LiveCategoryOrderStore(data)
        val firstIdentity = "profile:portable-uuid-1"
        val secondIdentity = "profile:portable-uuid-2"
        val first = LiveCategoryOrder(
            sortMode = CategorySortMode.MANUAL,
            normalIds = listOf(101, 102),
            pinnedIds = listOf(102),
            pinnedCategoryIds = setOf(102)
        )

        assertThat(store.saveCombined(firstIdentity, 0, first)).isInstanceOf(SaveLiveCategoryOrderResult.Saved::class.java)
        val saved = store.observeCombined(firstIdentity).first()
        assertThat(saved).isEqualTo(first.copy(revision = 1))
        assertThat(store.observeCombined(secondIdentity).first()).isEqualTo(LiveCategoryOrder())
        assertThat(store.saveCombined(firstIdentity, 0, first.copy(normalIds = listOf(102, 101))))
            .isInstanceOf(SaveLiveCategoryOrderResult.Conflict::class.java)
        assertThat(data.data.first().asMap().keys.map { it.name }.none { firstIdentity in it }).isTrue()
    }

    @Test fun `concurrent combined category drafts produce one save and one conflict`() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "combined-race.preferences_pb") }
        val store = LiveCategoryOrderStore(data)
        val first = async { store.saveCombined("profile:race", 0, LiveCategoryOrder(normalIds = listOf(1, 2))) }
        val second = async { store.saveCombined("profile:race", 0, LiveCategoryOrder(normalIds = listOf(2, 1))) }

        val results = listOf(first.await(), second.await())
        assertThat(results.filterIsInstance<SaveLiveCategoryOrderResult.Saved>()).hasSize(1)
        assertThat(results.filterIsInstance<SaveLiveCategoryOrderResult.Conflict>()).hasSize(1)
    }
}
