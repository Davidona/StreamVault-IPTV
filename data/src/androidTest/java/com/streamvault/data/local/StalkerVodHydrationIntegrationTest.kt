package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.PlaybackHistoryEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.StalkerIndexJobEntity
import com.streamvault.data.local.entity.VodCategoryHydrationEntity
import com.streamvault.data.local.entity.VodCatalogEntryEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerIndexState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StalkerVodHydrationIntegrationTest {
    @Test
    fun persistedPageAndIndexContinuationSurviveReopenAndCascadeOnProviderDeletion() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "stalker-vod-restart-integration.db"
        context.deleteDatabase(databaseName)
        var db = Room.databaseBuilder(context, StreamVaultDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            val providerId = db.providerDao().insert(
                ProviderEntity(name = "VOD restart fixture", type = ProviderType.STALKER_PORTAL)
            )
            val categoryId = 73L
            db.movieDao().upsertCategoryPage(
                providerId,
                listOf(
                    MovieEntity(
                        streamId = 7301L,
                        name = "Committed page one",
                        categoryId = categoryId,
                        providerId = providerId
                    )
                )
            )
            db.vodCatalogEntryDao().replacePage(
                providerId,
                categoryId,
                1,
                listOf(
                    VodCatalogEntryEntity(
                        providerId = providerId,
                        categoryId = categoryId,
                        rawItemId = "7301",
                        itemType = ContentType.MOVIE,
                        targetId = 7301L,
                        rawPage = 1,
                        rawIndex = 0
                    )
                )
            )
            db.vodCategoryHydrationDao().upsert(
                VodCategoryHydrationEntity(
                    providerId = providerId,
                    categoryId = categoryId,
                    lastLoadedPage = 1,
                    lastAttemptedPage = 2,
                    lastSuccessfulPage = 1,
                    totalPages = 3,
                    advertisedTotalPages = 3,
                    itemCount = 1,
                    lastStatus = "FAILED_RETRYABLE",
                    lastError = "temporary page two failure"
                )
            )
            db.stalkerIndexJobDao().upsert(
                StalkerIndexJobEntity(
                    providerId = providerId,
                    section = ContentType.MOVIE,
                    state = StalkerIndexState.PARTIAL,
                    totalCategories = 4,
                    completedCategories = 1,
                    nextCategoryIndex = 1,
                    indexedRows = 1
                )
            )

            db.close()
            db = Room.databaseBuilder(context, StreamVaultDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()

            val resumedHydration = db.vodCategoryHydrationDao().get(providerId, categoryId)
            assertThat(resumedHydration?.lastSuccessfulPage).isEqualTo(1)
            assertThat(resumedHydration?.lastAttemptedPage).isEqualTo(2)
            assertThat(resumedHydration?.lastStatus).isEqualTo("FAILED_RETRYABLE")
            assertThat(db.vodCatalogEntryDao().countByCategory(providerId, categoryId)).isEqualTo(1)
            assertThat(db.movieDao().getByProviderSync(providerId).single().name)
                .isEqualTo("Committed page one")
            val resumedJob = db.stalkerIndexJobDao().get(providerId, ContentType.MOVIE.name)
            assertThat(resumedJob?.state).isEqualTo(StalkerIndexState.PARTIAL)
            assertThat(resumedJob?.nextCategoryIndex).isEqualTo(1)

            db.providerDao().delete(providerId)

            assertThat(db.providerDao().getById(providerId)).isNull()
            assertThat(db.vodCategoryHydrationDao().get(providerId, categoryId)).isNull()
            assertThat(db.vodCatalogEntryDao().countByCategory(providerId, categoryId)).isEqualTo(0)
            assertThat(db.movieDao().getByProviderSync(providerId)).isEmpty()
            assertThat(db.stalkerIndexJobDao().get(providerId, ContentType.MOVIE.name)).isNull()
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun roomPageCommitKeepsProtectionIdentityFavoritesProgressAndCatalogOrder() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).build()
        try {
            val providerId = db.providerDao().insert(
                ProviderEntity(name = "VOD fixture", type = ProviderType.STALKER_PORTAL)
            )
            val categoryId = 42L
            db.categoryDao().insertAll(
                listOf(
                    CategoryEntity(
                        providerId = providerId,
                        categoryId = categoryId,
                        name = "Protected catalog",
                        type = ContentType.VOD,
                        isUserProtected = true
                    )
                )
            )
            val stableMovieId = db.movieDao().insert(
                MovieEntity(
                    id = 500L,
                    streamId = 101L,
                    name = "Before refresh",
                    categoryId = categoryId,
                    providerId = providerId
                )
            )
            db.favoriteDao().insert(
                FavoriteEntity(
                    providerId = providerId,
                    contentId = stableMovieId,
                    contentType = ContentType.MOVIE
                )
            )
            db.playbackHistoryDao().insertOrUpdate(
                PlaybackHistoryEntity(
                    contentId = stableMovieId,
                    contentType = ContentType.MOVIE,
                    providerId = providerId,
                    resumePositionMs = 31_500L,
                    lastWatchedAt = 100L
                )
            )

            val pageItems = listOf(
                VodCatalogEntryEntity(providerId, categoryId, "101", ContentType.MOVIE, 101L, 1, 0),
                VodCatalogEntryEntity(providerId, categoryId, "201", ContentType.SERIES, 201L, 1, 1),
                VodCatalogEntryEntity(providerId, categoryId, "102", ContentType.MOVIE, 102L, 1, 2)
            )
            db.withTransaction {
                val protected = db.categoryDao().getByProviderTypeAndCategoryId(
                    providerId,
                    ContentType.VOD.name,
                    categoryId
                )?.isUserProtected == true
                db.movieDao().upsertCategoryPage(
                    providerId,
                    listOf(
                        MovieEntity(
                            streamId = 101L,
                            name = "Updated movie",
                            categoryId = categoryId,
                            categoryName = "Protected catalog",
                            providerId = providerId,
                            isUserProtected = protected
                        ),
                        MovieEntity(
                            streamId = 102L,
                            name = "Second movie",
                            categoryId = categoryId,
                            categoryName = "Protected catalog",
                            providerId = providerId,
                            isUserProtected = protected
                        )
                    )
                )
                db.seriesDao().upsertCategoryPage(
                    providerId,
                    listOf(
                        SeriesEntity(
                            seriesId = 201L,
                            providerSeriesId = "201",
                            name = "Derived series",
                            categoryId = categoryId,
                            categoryName = "Protected catalog",
                            providerId = providerId,
                            isUserProtected = protected,
                            catalogOrigin = com.streamvault.domain.model.SeriesCatalogOrigin.VOD_DERIVED
                        )
                    )
                )
                db.vodCatalogEntryDao().replacePage(providerId, categoryId, 1, pageItems)
                db.vodCategoryHydrationDao().upsert(
                    VodCategoryHydrationEntity(
                        providerId = providerId,
                        categoryId = categoryId,
                        lastLoadedPage = 1,
                        lastAttemptedPage = 1,
                        lastSuccessfulPage = 1,
                        totalPages = 2,
                        advertisedTotalPages = 2,
                        pageSize = 3,
                        itemCount = 3,
                        hasMovies = true,
                        hasSeries = true,
                        lastStatus = "SUCCESS"
                    )
                )
            }

            // The second empty terminal page advances the durable coverage receipt without
            // deleting the previously committed page or replacing the category's order.
            db.withTransaction {
                db.categoryDao().getByProviderTypeAndCategoryId(
                    providerId,
                    ContentType.VOD.name,
                    categoryId
                )
                db.vodCatalogEntryDao().replacePage(providerId, categoryId, 2, emptyList())
                db.vodCategoryHydrationDao().upsert(
                    requireNotNull(db.vodCategoryHydrationDao().get(providerId, categoryId)).copy(
                        lastLoadedPage = 2,
                        lastAttemptedPage = 2,
                        lastSuccessfulPage = 2,
                        isComplete = true
                    )
                )
            }

            val movies = db.movieDao().getByProviderSync(providerId).associateBy { it.streamId }
            assertThat(movies.keys).containsExactly(101L, 102L)
            val refreshedMovie = movies.getValue(101L)
            assertThat(refreshedMovie.id).isEqualTo(stableMovieId)
            assertThat(refreshedMovie.name).isEqualTo("Updated movie")
            assertThat(refreshedMovie.isUserProtected).isTrue()
            assertThat(refreshedMovie.watchProgress).isEqualTo(31_500L)
            assertThat(db.movieDao().search(providerId, "updated", 10).first().map { it.name })
                .containsExactly("Updated movie")
            assertThat(db.movieDao().search(providerId, "before", 10).first()).isEmpty()
            assertThat(
                db.favoriteDao().getGlobalByType(providerId, ContentType.MOVIE.name).first()
                    .map(FavoriteEntity::contentId)
            ).containsExactly(stableMovieId)

            val series = db.seriesDao().getByProviderSync(providerId).single()
            assertThat(series.name).isEqualTo("Derived series")
            assertThat(series.isUserProtected).isTrue()
            val entries = db.vodCatalogEntryDao().observeByCategory(providerId, categoryId).first()
            assertThat(entries.map { it.rawItemId }).containsExactly("101", "201", "102").inOrder()
            assertThat(entries.map { it.rawIndex }).containsExactly(0, 1, 2).inOrder()
            val hydration = db.vodCategoryHydrationDao().get(providerId, categoryId)
            assertThat(hydration?.lastSuccessfulPage).isEqualTo(2)
            assertThat(hydration?.totalPages).isEqualTo(2)
            assertThat(hydration?.isComplete).isTrue()

            val failedTransaction = runCatching {
                db.withTransaction {
                    db.movieDao().upsertCategoryPage(
                        providerId,
                        listOf(
                            MovieEntity(
                                streamId = 101L,
                                name = "Rolled back title",
                                categoryId = categoryId,
                                providerId = providerId
                            )
                        )
                    )
                    db.vodCategoryHydrationDao().upsert(
                        requireNotNull(db.vodCategoryHydrationDao().get(providerId, categoryId)).copy(
                            lastSuccessfulPage = 3,
                            isComplete = false
                        )
                    )
                    error("abort test transaction")
                }
            }
            assertThat(failedTransaction.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(db.movieDao().getByProviderSync(providerId).single { it.streamId == 101L }.name)
                .isEqualTo("Updated movie")
            val durableHydration = db.vodCategoryHydrationDao().get(providerId, categoryId)
            assertThat(durableHydration?.lastSuccessfulPage).isEqualTo(2)
            assertThat(durableHydration?.isComplete).isTrue()
        } finally {
            db.close()
        }
    }
}
