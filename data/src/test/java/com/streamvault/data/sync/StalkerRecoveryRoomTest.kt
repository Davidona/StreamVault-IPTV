package com.streamvault.data.sync

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.repository.EpgRetryAfterStore
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StalkerRecoveryRoomTest {
    @Test
    fun `deadline stores persist across reconstruction using Android preferences`() {
        val context = RuntimeEnvironment.getApplication()
        val providerId = 91_912_345L
        val url = "https://example.invalid/xmltv?password=fixture"
        val epg = EpgRetryAfterStore(context)
        val catalog = XtreamRetryDeadlineStore(context)
        try {
            epg.clearProvider(providerId)
            catalog.clear(providerId)
            epg.record(providerId, url, 1_000L, 600_000L)
            catalog.extend(providerId, 601_000L)
            assertThat(EpgRetryAfterStore(context).remainingMillis(providerId, url, 2_000L))
                .isEqualTo(599_000L)
            assertThat(XtreamRetryDeadlineStore(context).deadline(providerId)).isEqualTo(601_000L)
            assertThat(EpgRetryAfterStore(context).remainingMillis(providerId, url, 601_000L)).isEqualTo(0L)
        } finally {
            epg.clearProvider(providerId)
            catalog.clear(providerId)
        }
    }

    @Test
    fun `real Room receipts retain cooldown budgets and cursors across store recreation`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            db.providerDao().insert(ProviderEntity(id = 1L, name = "Fixture", type = ProviderType.STALKER_PORTAL))
            var store = StalkerIndexCheckpointStore(db.movieCategoryHydrationDao(), db.seriesCategoryHydrationDao())
            for (type in listOf(ContentType.MOVIE, ContentType.SERIES)) {
                store.markAttemptSucceeded(1L, type, 42L, null, 1, 1_000L, 10, 3, 10,
                    30, 3, false, false, null, "page-1")
                val previous = store.getHydrationSnapshot(1L, type, 42L)
                store.markAttemptFailed(1L, type, 42L, previous, 2, 2_000L,
                    "Portal rate limit reached", true, null, deferredUntilMs = 902_000L)
            }
            store = StalkerIndexCheckpointStore(db.movieCategoryHydrationDao(), db.seriesCategoryHydrationDao())
            for (type in listOf(ContentType.MOVIE, ContentType.SERIES)) {
                val saved = requireNotNull(store.getHydrationSnapshot(1L, type, 42L))
                assertThat(saved.lastStatus).isEqualTo("COOLDOWN")
                assertThat(saved.lastSuccessfulPage).isEqualTo(1)
                assertThat(saved.lastAttemptedPage).isEqualTo(2)
                assertThat(saved.retryBudgetRemaining).isEqualTo(3)
                assertThat(StalkerIndexPolicy.canAttempt(saved, 901_999L)).isFalse()
                assertThat(StalkerIndexPolicy.canAttempt(saved, 902_000L)).isTrue()
                assertThat(StalkerIndexPolicy.nextAttemptPage(saved)).isEqualTo(2)
            }
        } finally {
            db.close()
        }
    }
}
