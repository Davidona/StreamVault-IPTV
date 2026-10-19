package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.MovieCategoryHydrationDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.entity.MovieCategoryHydrationEntity
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.repository.MovieRepositoryImpl
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Answers
import org.mockito.kotlin.*

/** Refreshing categories must allow previously completed contents to be fetched again. */
class StalkerMovieRefreshRegressionTest {
    @Test fun `scheduled on-demand refresh invalidates category checkpoints without deleting cached content`() = runTest {
        val hydrationDao: MovieCategoryHydrationDao = mock()
        val movieDao: MovieDao = mock()
        val metadata: SyncMetadataRepository = mock()
        val api: StalkerProvider = mock()
        whenever(api.getVodCategories()).thenReturn(Result.success(listOf(Category(10L, name = "Movies", type = ContentType.MOVIE))))
        whenever(movieDao.getCount(7L)).thenReturn(flowOf(100))
        val executor = StalkerCatalogSectionExecutor(
            movieCategoryHydrationDao = hydrationDao,
            seriesCategoryHydrationDao = mock(),
            xtreamContentIndexDao = mock(),
            syncCatalogStore = mock(),
            movieDao = movieDao,
            seriesDao = mock(),
            syncMetadataRepository = metadata,
            createProvider = { api },
            updateIndexJob = { _, _, _, _ -> error("Unexpected index queue") },
            emitSectionProgress = { _, _, _, _ -> },
            progress = { _, _, _ -> }
        )
        val provider = LegacyProvider(7L, "Portal", ProviderType.STALKER_PORTAL, serverUrl = "https://example.test")
        val outcome = executor.syncMovies(provider, null, indexCatalog = false)
        assertThat(outcome.partial).isFalse()
        assertThat(outcome.continuationWork).isEmpty()
        verify(metadata).updateMetadata(argThat { lastMovieSuccess > 0L })
        verify(hydrationDao).deleteByProvider(7L)

        // This private predicate is pure: no repository fields or network are involved.
        val repository: MovieRepositoryImpl = mock(defaultAnswer = Answers.CALLS_REAL_METHODS)
        val predicate = MovieRepositoryImpl::class.java.getDeclaredMethod(
            "shouldUseStalkerLazyFallback", MovieCategoryHydrationEntity::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val oldComplete = MovieCategoryHydrationEntity(
            providerId = 7L, categoryId = 10L, lastHydratedAt = 1L,
            itemCount = 100, lastSuccessfulPage = 1, isComplete = true
        )
        val willFetch = predicate.invoke(repository, oldComplete, 100, 200, false) as Boolean
        assertThat(willFetch).isTrue()
    }
}
