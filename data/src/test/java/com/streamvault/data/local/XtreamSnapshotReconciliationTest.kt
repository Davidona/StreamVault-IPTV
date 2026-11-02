package com.streamvault.data.local

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.XtreamContentIndexEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class XtreamSnapshotReconciliationTest {
    @Test fun `successful snapshot removes missing movies and series but retains active indexes and other providers`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StreamVaultDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            for (providerId in listOf(1L, 2L)) {
                db.providerDao().insert(ProviderEntity(id = providerId, name = "Fixture", type = ProviderType.XTREAM_CODES))
                db.movieDao().insertAll(listOf(MovieEntity(providerId = providerId, streamId = 10, name = "Gone"),
                    MovieEntity(providerId = providerId, streamId = 20, name = "Retained")))
                db.seriesDao().insertAll(listOf(SeriesEntity(providerId = providerId, seriesId = 10, name = "Gone"),
                    SeriesEntity(providerId = providerId, seriesId = 20, name = "Retained")))
                for (type in listOf(ContentType.MOVIE, ContentType.SERIES)) {
                    db.xtreamContentIndexDao().upsertAll(listOf(
                        XtreamContentIndexEntity(providerId, type, "10", name = "Gone"),
                        XtreamContentIndexEntity(providerId, type, "20", name = "Retained", indexedAt = 100)))
                }
            }
            val index = db.xtreamContentIndexDao()
            assertThat(db.movieDao().getByStreamId(1, 10)).isNotNull()
            for (type in listOf(ContentType.MOVIE, ContentType.SERIES)) {
                index.beginSnapshot(1, type.name)
                assertThat(index.countActiveRows(1, type.name)).isEqualTo(2)
                // An incomplete import has not removed any cached content.
                index.upsert(XtreamContentIndexEntity(1, type, "20", name = "Retained", indexedAt = 100))
                assertThat(index.pruneCompletedSnapshot(1, type.name)).isEqualTo(1)
                assertThat(index.getByRemoteId(1, type.name, "10")).isNull()
                assertThat(index.getByRemoteId(1, type.name, "20")).isNotNull()
                assertThat(index.getByRemoteId(2, type.name, "10")).isNotNull()
            }
            assertThat(db.movieDao().getByStreamId(1, 10)).isNull()
            assertThat(db.movieDao().getByStreamId(1, 20)).isNotNull()
            assertThat(db.movieDao().getByStreamId(2, 10)).isNotNull()
        } finally { db.close() }
    }
}
