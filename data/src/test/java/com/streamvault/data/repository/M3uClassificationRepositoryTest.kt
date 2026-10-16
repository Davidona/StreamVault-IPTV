package com.streamvault.data.repository

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.PlaybackHistoryEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.M3uClassificationTarget
import com.streamvault.domain.repository.M3uSeriesAssignment
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class M3uClassificationRepositoryTest {

    private lateinit var database: StreamVaultDatabase
    private lateinit var repository: M3uClassificationRepositoryImpl

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            StreamVaultDatabase::class.java
        ).allowMainThreadQueries().build()
        runBlocking {
            database.providerDao().insert(
                ProviderEntity(
                    id = PROVIDER_ID,
                    name = "M3U fixture",
                    type = ProviderType.M3U
                )
            )
        }
        repository = M3uClassificationRepositoryImpl(
            providerDao = database.providerDao(),
            channelDao = database.channelDao(),
            movieDao = database.movieDao(),
            seriesDao = database.seriesDao(),
            episodeDao = database.episodeDao(),
            favoriteDao = database.favoriteDao(),
            playbackHistoryDao = database.playbackHistoryDao(),
            categoryDao = database.categoryDao(),
            classificationDao = database.m3uClassificationDao(),
            transactionRunner = com.streamvault.data.local.RoomDatabaseTransactionRunner(database)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `movie move is atomic and migrates history and favorite back to live`() = runTest {
        insertLiveCategory()
        val channelId = database.channelDao().insert(channel(501L, "Movie source"))
        database.favoriteDao().insert(
            FavoriteEntity(
                providerId = PROVIDER_ID,
                contentId = channelId,
                contentType = ContentType.LIVE
            )
        )
        database.playbackHistoryDao().insertOrUpdate(
            PlaybackHistoryEntity(
                contentId = channelId,
                contentType = ContentType.LIVE,
                providerId = PROVIDER_ID,
                title = "Movie source",
                streamUrl = "https://cdn.example/movie.m3u8",
                resumePositionMs = 12_000L,
                totalDurationMs = 60_000L
            )
        )

        assertThat(repository.classifyChannel(PROVIDER_ID, channelId, M3uClassificationTarget.MOVIE).isSuccess)
            .isTrue()
        assertThat(database.channelDao().getByProviderSync(PROVIDER_ID)).isEmpty()
        val movie = checkNotNull(database.movieDao().getByStreamId(PROVIDER_ID, 501L))
        assertThat(database.playbackHistoryDao().get(movie.id, ContentType.MOVIE.name, PROVIDER_ID)).isNotNull()
        assertThat(database.favoriteDao().get(PROVIDER_ID, movie.id, ContentType.MOVIE.name, null)).isNotNull()
        assertThat(database.m3uClassificationDao().getByStreamId(PROVIDER_ID, 501L)?.targetType)
            .isEqualTo(M3uClassificationTarget.MOVIE.name)

        assertThat(repository.moveMovieBackToLive(PROVIDER_ID, movie.id).isSuccess).isTrue()
        val restored = database.channelDao().getByProviderSync(PROVIDER_ID).single()
        assertThat(restored.streamId).isEqualTo(501L)
        assertThat(database.movieDao().getByStreamId(PROVIDER_ID, 501L)).isNull()
        assertThat(database.playbackHistoryDao().get(restored.id, ContentType.LIVE.name, PROVIDER_ID)).isNotNull()
        assertThat(database.favoriteDao().get(PROVIDER_ID, restored.id, ContentType.LIVE.name, null)).isNotNull()
        assertThat(database.m3uClassificationDao().getByStreamId(PROVIDER_ID, 501L)?.targetType)
            .isEqualTo(M3uClassificationTarget.LIVE.name)
    }

    @Test
    fun `keeping an empty category live restores its existing movie projections`() = runTest {
        insertLiveCategory()
        database.channelDao().insert(channel(502L, "Category movie"))

        assertThat(
            repository.classifyCategory(PROVIDER_ID, LIVE_CATEGORY_ID, M3uClassificationTarget.MOVIE)
                .getOrNull()
        ).isEqualTo(1)
        assertThat(database.channelDao().getByProviderSync(PROVIDER_ID)).isEmpty()
        assertThat(database.movieDao().getByStreamId(PROVIDER_ID, 502L)).isNotNull()

        assertThat(
            repository.classifyCategory(PROVIDER_ID, LIVE_CATEGORY_ID, M3uClassificationTarget.LIVE)
                .getOrNull()
        ).isEqualTo(1)
        assertThat(database.movieDao().getByStreamId(PROVIDER_ID, 502L)).isNull()
        assertThat(database.channelDao().getByProviderSync(PROVIDER_ID).single().streamId)
            .isEqualTo(502L)
    }

    @Test
    fun `series category creates separate seasons and episodes and moves the parent back atomically`() = runTest {
        insertLiveCategory()
        database.channelDao().insert(channel(601L, "Example Show S01E01"))
        database.channelDao().insert(channel(602L, "Example Show S02E03"))

        val suggestions = repository.getCategoryItems(PROVIDER_ID, LIVE_CATEGORY_ID).getOrNull()
        assertThat(suggestions).hasSize(2)
        assertThat(suggestions!!.map { it.suggestedAssignment.episodeNumber })
            .containsExactly(1, 3)

        assertThat(
            repository.classifyCategory(PROVIDER_ID, LIVE_CATEGORY_ID, M3uClassificationTarget.SERIES)
                .getOrNull()
        ).isEqualTo(2)

        val series = database.seriesDao().getByProviderSync(PROVIDER_ID).single()
        val episodes = database.episodeDao().getEntitiesBySeriesSync(series.id)
        assertThat(series.name).isEqualTo("Example Show")
        assertThat(episodes.map { it.seasonNumber to it.episodeNumber })
            .containsExactly(1 to 1, 2 to 3)
            .inOrder()
        assertThat(database.channelDao().getByProviderSync(PROVIDER_ID)).isEmpty()

        assertThat(repository.moveSeriesBackToLive(PROVIDER_ID, series.id).isSuccess).isTrue()
        assertThat(database.seriesDao().getByProviderSync(PROVIDER_ID)).isEmpty()
        assertThat(database.episodeDao().getEntitiesBySeriesSync(series.id)).isEmpty()
        assertThat(database.channelDao().getByProviderSync(PROVIDER_ID).map { it.streamId })
            .containsExactly(601L, 602L)
    }

    @Test
    fun `unresolved episode title is surfaced without inventing an episode number`() = runTest {
        insertLiveCategory()
        database.channelDao().insert(channel(603L, "Mystery Show Tập Cuối"))

        val suggestion = repository.getCategoryItems(PROVIDER_ID, LIVE_CATEGORY_ID)
            .getOrNull()!!
            .single()

        assertThat(suggestion.suggestedAssignment.seriesName).isEqualTo("Mystery Show")
        assertThat(suggestion.suggestedAssignment.episodeNumber).isEqualTo(0)
        assertThat(suggestion.suggestedAssignment.episodeTitle).isEqualTo("Mystery Show Tập Cuối")
    }

    @Test
    fun `category review assignments can create multiple manually named series`() = runTest {
        insertLiveCategory()
        val firstId = database.channelDao().insert(channel(604L, "Part one"))
        val secondId = database.channelDao().insert(channel(605L, "Part two"))

        assertThat(
            repository.classifyCategory(
                providerId = PROVIDER_ID,
                categoryId = LIVE_CATEGORY_ID,
                target = M3uClassificationTarget.SERIES,
                seriesAssignments = mapOf(
                    firstId to M3uSeriesAssignment("First series", seasonNumber = 1, episodeNumber = 7),
                    secondId to M3uSeriesAssignment("Second series", seasonNumber = 3, episodeNumber = 2)
                )
            ).getOrNull()
        ).isEqualTo(2)

        val seriesByName = database.seriesDao().getByProviderSync(PROVIDER_ID).associateBy { it.name }
        assertThat(seriesByName.keys)
            .containsExactly("First series", "Second series")
        assertThat(database.episodeDao().getEntitiesBySeriesSync(seriesByName.getValue("First series").id).single().episodeNumber)
            .isEqualTo(7)
        assertThat(database.episodeDao().getEntitiesBySeriesSync(seriesByName.getValue("Second series").id).single().seasonNumber)
            .isEqualTo(3)
    }

    @Test
    fun `reclassifying a stream into another series moves its episode`() = runTest {
        insertLiveCategory()
        val firstId = database.channelDao().insert(channel(606L, "Show S01E01"))
        repository.classifyChannel(PROVIDER_ID, firstId, M3uClassificationTarget.SERIES, M3uSeriesAssignment("Show", seasonNumber = 1, episodeNumber = 1))
        // A later playlist sync re-creates the channel and it is classified under a different name.
        val secondId = database.channelDao().insert(channel(606L, "Show S01E01"))
        repository.classifyChannel(PROVIDER_ID, secondId, M3uClassificationTarget.SERIES, M3uSeriesAssignment("Show (2020)", seasonNumber = 1, episodeNumber = 1))

        val episodes = database.episodeDao().getByProviderSync(PROVIDER_ID).filter { it.episodeId == 606L }
        val target = database.seriesDao().getByProviderSync(PROVIDER_ID).single { it.name == "Show (2020)" }
        assertThat(episodes.map { it.seriesId }).containsExactly(target.id)
    }

    private suspend fun insertLiveCategory() {
        database.categoryDao().insertAll(
            listOf(
                CategoryEntity(
                    categoryId = LIVE_CATEGORY_ID,
                    name = "Fixture Group",
                    type = ContentType.LIVE,
                    providerId = PROVIDER_ID
                )
            )
        )
    }

    private fun channel(streamId: Long, name: String) = ChannelEntity(
        streamId = streamId,
        name = name,
        groupTitle = "Fixture Group",
        categoryId = LIVE_CATEGORY_ID,
        categoryName = "Fixture Group",
        streamUrl = "https://cdn.example/$streamId.m3u8",
        providerId = PROVIDER_ID
    )

    private companion object {
        const val PROVIDER_ID = 7L
        const val LIVE_CATEGORY_ID = 70L
    }
}
