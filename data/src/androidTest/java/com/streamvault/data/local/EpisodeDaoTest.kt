package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.entity.EpisodeEntity
import com.streamvault.data.local.entity.PlaybackHistoryEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class EpisodeDaoTest {
    private lateinit var db: StreamVaultDatabase
    private lateinit var episodeDao: EpisodeDao
    private lateinit var historyDao: PlaybackHistoryDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(
            context, StreamVaultDatabase::class.java
        ).build()
        episodeDao = db.episodeDao()
        historyDao = db.playbackHistoryDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun replaceAll_doesNotDeleteOtherSeriesEpisodesSharingSynthesizedIds() = runTest {
        // Xtream synthesizes season * 10000 + episode when a panel omits episode ids (issue #166).
        fun season1(seriesId: Long, count: Int) = (1..count).map { number ->
            EpisodeEntity(
                episodeId = 10_000L + number,
                title = "S01E$number",
                episodeNumber = number,
                seasonNumber = 1,
                seriesId = seriesId,
                providerId = 5L
            )
        }

        db.providerDao().insert(ProviderEntity(id = 5L, name = "Xtream", type = ProviderType.XTREAM_CODES))
        db.seriesDao().insert(SeriesEntity(id = 91L, seriesId = 3001L, name = "A", providerId = 5L))
        db.seriesDao().insert(SeriesEntity(id = 92L, seriesId = 3002L, name = "B", providerId = 5L))

        episodeDao.replaceAll(seriesId = 91L, providerId = 5L, episodes = season1(91L, count = 10))
        episodeDao.replaceAll(seriesId = 92L, providerId = 5L, episodes = season1(92L, count = 5))

        assertThat(episodeDao.getBySeriesSync(91L)).hasSize(10)
        assertThat(episodeDao.getBySeriesSync(92L)).hasSize(5)
    }

    @Test
    fun syncWatchProgressFromHistory_updatesEpisodeFromPlaybackHistory() = runTest {
        episodeDao.insertAll(
            listOf(
                EpisodeEntity(
                    id = 11L,
                    episodeId = 201L,
                    title = "Episode 1",
                    episodeNumber = 1,
                    seasonNumber = 1,
                    seriesId = 91L,
                    providerId = 5L,
                    watchProgress = 0L
                )
            )
        )
        historyDao.insertOrUpdate(
            PlaybackHistoryEntity(
                contentId = 11L,
                contentType = ContentType.SERIES_EPISODE,
                providerId = 5L,
                resumePositionMs = 18_000L,
                lastWatchedAt = 27_000L
            )
        )

        episodeDao.syncWatchProgressFromHistory(11L, 5L)

        val episode = episodeDao.getById(11L)
        assertThat(episode).isNotNull()
        assertThat(episode?.watchProgress).isEqualTo(18_000L)
        assertThat(episode?.lastWatchedAt).isEqualTo(27_000L)
    }

    @Test
    fun syncWatchProgressFromHistoryByProvider_updatesMatchingEpisodesAndClearsStaleRows() = runTest {
        episodeDao.insertAll(
            listOf(
                EpisodeEntity(
                    id = 11L,
                    episodeId = 201L,
                    title = "Episode 1",
                    episodeNumber = 1,
                    seasonNumber = 1,
                    seriesId = 91L,
                    providerId = 5L,
                    watchProgress = 0L,
                    lastWatchedAt = 0L
                ),
                EpisodeEntity(
                    id = 12L,
                    episodeId = 202L,
                    title = "Episode 2",
                    episodeNumber = 2,
                    seasonNumber = 1,
                    seriesId = 91L,
                    providerId = 5L,
                    watchProgress = 13_000L,
                    lastWatchedAt = 14_000L
                ),
                EpisodeEntity(
                    id = 21L,
                    episodeId = 301L,
                    title = "Other Provider Episode",
                    episodeNumber = 1,
                    seasonNumber = 1,
                    seriesId = 101L,
                    providerId = 6L,
                    watchProgress = 3_000L,
                    lastWatchedAt = 4_000L
                )
            )
        )
        historyDao.insertOrUpdate(
            PlaybackHistoryEntity(
                contentId = 11L,
                contentType = ContentType.SERIES_EPISODE,
                providerId = 5L,
                resumePositionMs = 18_000L,
                lastWatchedAt = 27_000L
            )
        )

        episodeDao.syncWatchProgressFromHistoryByProvider(5L)

        val watchedEpisode = episodeDao.getById(11L)
        val staleEpisode = episodeDao.getById(12L)
        val otherProviderEpisode = episodeDao.getById(21L)

        assertThat(watchedEpisode?.watchProgress).isEqualTo(18_000L)
        assertThat(watchedEpisode?.lastWatchedAt).isEqualTo(27_000L)
        assertThat(staleEpisode?.watchProgress).isEqualTo(0L)
        assertThat(staleEpisode?.lastWatchedAt).isEqualTo(0L)
        assertThat(otherProviderEpisode?.watchProgress).isEqualTo(3_000L)
        assertThat(otherProviderEpisode?.lastWatchedAt).isEqualTo(4_000L)
    }
}