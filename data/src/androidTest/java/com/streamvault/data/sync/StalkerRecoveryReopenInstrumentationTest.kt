package com.streamvault.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StalkerRecoveryReopenInstrumentationTest {
    @Test fun cooldownAndCursorSurviveDatabaseReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "stalker-recovery-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, StreamVaultDatabase::class.java, name)
            .allowMainThreadQueries().build()
        var db = open()
        try {
            val providerId = db.providerDao().insert(ProviderEntity(name = "Recovery fixture", type = ProviderType.STALKER_PORTAL))
            var store = StalkerIndexCheckpointStore(db.movieCategoryHydrationDao(), db.seriesCategoryHydrationDao())
            for (type in listOf(ContentType.MOVIE, ContentType.SERIES)) {
                store.markAttemptSucceeded(providerId, type, 42L, null, 1, 1_000L, 10, 3, 10,
                    30, 3, false, false, null, "page-1")
                store.markAttemptFailed(providerId, type, 42L, store.getHydrationSnapshot(providerId, type, 42L),
                    2, 2_000L, "Portal rate limit reached", true, null, deferredUntilMs = 902_000L)
            }
            db.close()
            db = open()
            store = StalkerIndexCheckpointStore(db.movieCategoryHydrationDao(), db.seriesCategoryHydrationDao())
            for (type in listOf(ContentType.MOVIE, ContentType.SERIES)) {
                val saved = requireNotNull(store.getHydrationSnapshot(providerId, type, 42L))
                assertThat(saved.lastSuccessfulPage).isEqualTo(1)
                assertThat(saved.lastAttemptedPage).isEqualTo(2)
                assertThat(saved.retryBudgetRemaining).isEqualTo(3)
                assertThat(StalkerIndexPolicy.canAttempt(saved, 901_999L)).isFalse()
                assertThat(StalkerIndexPolicy.canAttempt(saved, 902_000L)).isTrue()
                assertThat(StalkerIndexPolicy.nextAttemptPage(saved)).isEqualTo(2)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
