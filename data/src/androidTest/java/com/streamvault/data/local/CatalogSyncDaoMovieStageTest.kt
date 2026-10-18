package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.entity.MovieImportStageEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogSyncDaoMovieStageTest {
    private lateinit var db: StreamVaultDatabase
    private lateinit var providerDao: ProviderDao
    private lateinit var movieDao: MovieDao
    private lateinit var catalogSyncDao: CatalogSyncDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).build()
        providerDao = db.providerDao()
        movieDao = db.movieDao()
        catalogSyncDao = db.catalogSyncDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertMissingMoviesFromStage_setsSummaryCacheState() = runTest {
        providerDao.insert(
            ProviderEntity(
                id = 1L,
                name = "M3U fixture",
                type = ProviderType.M3U
            )
        )
        catalogSyncDao.insertMovieStages(
            listOf(
                MovieImportStageEntity(
                    sessionId = 10L,
                    providerId = 1L,
                    streamId = 701L,
                    name = "Protected movie",
                    streamUrl = "http://127.0.0.1/movie.mp4",
                    syncFingerprint = "fixture"
                )
            )
        )

        catalogSyncDao.insertMissingMoviesFromStage(providerId = 1L, sessionId = 10L)

        val inserted = movieDao.getByStreamId(providerId = 1L, streamId = 701L)
        assertThat(inserted).isNotNull()
        assertThat(inserted?.cacheState).isEqualTo("SUMMARY_ONLY")
    }
}
