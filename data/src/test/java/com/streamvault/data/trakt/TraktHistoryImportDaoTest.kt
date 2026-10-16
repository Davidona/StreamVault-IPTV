package com.streamvault.data.trakt

import androidx.room.Room
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.RoomDatabaseTransactionRunner
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.repository.PlaybackHistoryRepositoryImpl
import com.streamvault.domain.model.*
import com.streamvault.domain.trakt.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TraktHistoryImportDaoTest {
    private lateinit var database: StreamVaultDatabase
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StreamVaultDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { database.close() }
    private fun repository(): PlaybackHistoryRepositoryImpl {
        val preferences = mock<PreferencesRepository>()
        whenever(preferences.isIncognitoMode).thenReturn(flowOf(false))
        return PlaybackHistoryRepositoryImpl(database.playbackHistoryDao(), preferences,
            database.movieDao(), database.episodeDao(), RoomDatabaseTransactionRunner(database))
    }
    private suspend fun provider() {
        database.providerDao().insert(ProviderEntity(id = 1, name = "Synthetic", type = ProviderType.M3U,
            serverUrl = "https://example.org"))
    }
    @Test fun completedImportPersistsOnceAndUpdatesMovieProjection() = runBlocking {
        provider()
        database.movieDao().insertAll(listOf(Movie(id = 10, providerId = 1, name = "Movie", tmdbId = 603,
            durationSeconds = 100).toEntity()))
        val repository = repository()
        val base = PlaybackHistory(contentId = 10, contentType = ContentType.MOVIE, providerId = 1,
            title = "Movie", streamUrl = "", totalDurationMs = 100000)
        val identity = TraktMediaIdentity.MovieId(603)
        val match = TraktHistoryMatch(identity, 5000, 3)
        assertEquals(true, repository.importTraktHistory(base, identity, match).getOrNull())
        assertEquals(false, repository.importTraktHistory(base, identity, match).getOrNull())
        val row = requireNotNull(database.playbackHistoryDao().get(10, ContentType.MOVIE.name, 1))
        assertEquals("COMPLETED_AUTO", row.watchedStatus)
        assertEquals(5000L, row.lastWatchedAt)
        assertEquals(3, row.watchCount)
        assertEquals(100000L, row.resumePositionMs)
        assertEquals(1, database.playbackHistoryDao().getAllSync().size)
        assertEquals(100000L, database.movieDao().getByProviderAndTmdbIdSync(1, 603).single().watchProgress)
    }
    @Test fun unknownDurationCompletedEpisodeIsExcludedFromUnwatchedCount() = runBlocking {
        provider()
        database.seriesDao().insertAll(listOf(Series(id = 42, providerId = 1, name = "Show", tmdbId = 1396).toEntity()))
        database.episodeDao().insertAll(listOf(
            Episode(id = 20, title = "Watched", providerId = 1, seriesId = 42, seasonNumber = 1, episodeNumber = 1).toEntity(),
            Episode(id = 21, title = "Unwatched", providerId = 1, seriesId = 42, seasonNumber = 1, episodeNumber = 2).toEntity()))
        val base = PlaybackHistory(contentId = 20, contentType = ContentType.SERIES_EPISODE, providerId = 1,
            title = "Watched", streamUrl = "", seriesId = 42, seasonNumber = 1, episodeNumber = 1)
        val identity = TraktMediaIdentity.EpisodeId(1396, 1, 1)
        assertEquals(true, repository().importTraktHistory(base, identity, TraktHistoryMatch(identity, 5000, 1)).getOrNull())
        assertEquals(1, database.episodeDao().getUnwatchedCount(1, 42, .95f).first())
    }
}
