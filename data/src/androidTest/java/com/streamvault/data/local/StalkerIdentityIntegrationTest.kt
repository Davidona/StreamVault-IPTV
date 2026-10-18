package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.StalkerRemoteIdentityEntity
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.remote.stalker.StalkerRemoteIdentityResolver
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StalkerIdentityIntegrationTest {
    @Test fun batchedIdentitiesRespectRoomConstraintsAndRollback() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).build()
        try {
            val providerId = db.providerDao().insert(ProviderEntity(name = "Stalker", type = ProviderType.STALKER_PORTAL))
            val otherId = db.providerDao().insert(ProviderEntity(name = "Other", type = ProviderType.STALKER_PORTAL))
            val dao = db.stalkerRemoteIdentityDao()
            val runner = RoomDatabaseTransactionRunner(db)
            val resolver = StalkerRemoteIdentityResolver(dao, runner, db.categoryDao())
            db.categoryDao().insertAll(listOf(CategoryEntity(providerId = providerId, categoryId = 42L, name = "Existing", type = ContentType.LIVE)))
            assertThat(resolver.resolveCategories(providerId, ContentType.LIVE, listOf("remote-category" to "EXISTING")))
                .containsEntry("remote-category", 42L)
            val ids = (1..1200).map(Int::toString) + listOf("001", "string-id")
            val initial = resolver.resolveAll(providerId, ContentType.LIVE, ids)
            assertThat(initial.values.toSet()).hasSize(ids.size)
            assertThat(resolver.resolveAll(providerId, ContentType.LIVE, ids)).isEqualTo(initial)
            initial.forEach { (rawId, id) -> assertThat(resolver.reverse(providerId, ContentType.LIVE, id)).isEqualTo(rawId) }
            assertThat(resolver.resolveAll(otherId, ContentType.LIVE, listOf("1"))).containsEntry("1", 1L)
            val known = dao.getByRawId(providerId, ContentType.LIVE.name, "1")!!
            val conflict = known.copy(rawId = "conflict")
            assertThat(dao.insertAll(listOf(known, conflict))).containsExactly(-1L, -1L)
            assertThat(dao.getByRawId(providerId, ContentType.LIVE.name, "conflict")).isNull()
            assertThat(dao.getBySurrogateId(providerId, ContentType.LIVE.name, known.surrogateId)).isEqualTo(known)
            val rolledBack = StalkerRemoteIdentityEntity(providerId, ContentType.LIVE, "rollback", 9000L, 0L, 0L)
            try {
                runner.inTransaction {
                    dao.insertAll(listOf(rolledBack))
                    error("rollback")
                }
            } catch (expected: IllegalStateException) {
                assertThat(expected.message).isEqualTo("rollback")
            }
            assertThat(dao.getByRawId(providerId, ContentType.LIVE.name, "rollback")).isNull()
        } finally { db.close() }
    }
}
