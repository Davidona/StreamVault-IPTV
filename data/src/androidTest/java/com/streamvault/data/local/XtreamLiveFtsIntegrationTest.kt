package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.sync.SyncCatalogStore
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises trigger-maintained search through fresh and migrated production Room databases. */
@RunWith(AndroidJUnit4::class)
class XtreamLiveFtsIntegrationTest {
    @get:Rule
    val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), StreamVaultDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun freshAndMigratedDatabasesMaintainLiveSearchThroughPromotion() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in listOf<Int?>(null, 9, 78)) {
            val name = "xtream-live-fts-${version ?: "fresh"}"
            context.deleteDatabase(name)
            if (version != null) migrations.createDatabase(name, version).close()
            val db = Room.databaseBuilder(context, StreamVaultDatabase::class.java, name)
                .addMigrations(*StreamVaultDatabaseMigrationRegistry.all.toTypedArray()).build()
            try {
                val providerId = db.providerDao().insert(ProviderEntity(name = "Live", type = ProviderType.XTREAM_CODES))
                val otherId = db.providerDao().insert(ProviderEntity(name = "Other", type = ProviderType.XTREAM_CODES))
                val store = SyncCatalogStore(db.channelDao(), db.movieDao(), db.seriesDao(),
                    db.categoryDao(), db.catalogSyncDao(), db.tmdbIdentityDao(), RoomDatabaseTransactionRunner(db))
                suspend fun promote(provider: Long, rows: List<ChannelEntity>, partial: Boolean = false) {
                    val session = store.newSessionId()
                    store.stageAdmittedLiveBatch(provider, session, rows, rows.size)
                    if (partial) store.applyStagedLiveCatalogUpsertOnly(provider, session, null)
                    else store.applyStagedLiveCatalog(provider, session, null)
                }
                fun matches(term: String): Int = db.openHelper.writableDatabase.query(
                    "SELECT COUNT(*) FROM channels_fts WHERE channels_fts MATCH ?", arrayOf(term)
                ).use { it.moveToFirst(); it.getInt(0) }
                val first = ChannelEntity(streamId = 1, name = "Originaltoken", providerId = providerId)
                promote(providerId, listOf(first))
                promote(otherId, listOf(first.copy(providerId = otherId, name = "Othertoken")))
                val originalId = db.channelDao().getByProviderSync(providerId).single().id
                db.favoriteDao().insert(FavoriteEntity(providerId = providerId, contentId = originalId, contentType = ContentType.LIVE))
                db.openHelper.writableDatabase.execSQL("UPDATE channels SET is_user_protected = 1 WHERE id = ?", arrayOf(originalId))
                assertThat(matches("originaltoken")).isEqualTo(1)
                promote(providerId, listOf(first)) // unchanged fingerprint
                assertThat(matches("originaltoken")).isEqualTo(1)
                promote(providerId, listOf(first.copy(name = "Renamedtoken")))
                assertThat(matches("originaltoken")).isEqualTo(0)
                assertThat(matches("renamedtoken")).isEqualTo(1)
                assertThat(db.channelDao().getByProviderSync(providerId).single().id).isEqualTo(originalId)
                assertThat(db.channelDao().getByProviderSync(providerId).single().isUserProtected).isTrue()
                assertThat(db.favoriteDao().getAllGlobal(providerId).first().single().contentId).isEqualTo(originalId)
                val failedSession = store.newSessionId()
                store.stageAdmittedLiveBatch(providerId, failedSession, listOf(first.copy(name = "Rollbacktoken")), 1)
                val failure = runCatching {
                    store.applyStagedLiveCatalog(providerId, failedSession, null) { error("rollback") }
                }.exceptionOrNull()
                assertThat(failure).isNotNull()
                assertThat(matches("rollbacktoken")).isEqualTo(0)
                assertThat(matches("renamedtoken")).isEqualTo(1)
                promote(providerId, listOf(first.copy(streamId = 2, name = "Partialtoken")), partial = true)
                assertThat(matches("renamedtoken")).isEqualTo(1)
                assertThat(matches("partialtoken")).isEqualTo(1)
                promote(providerId, listOf(first.copy(streamId = 2, name = "Partialtoken")))
                assertThat(matches("renamedtoken")).isEqualTo(0)
                assertThat(matches("partialtoken")).isEqualTo(1)
                assertThat(matches("othertoken")).isEqualTo(1)
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
    }
}
