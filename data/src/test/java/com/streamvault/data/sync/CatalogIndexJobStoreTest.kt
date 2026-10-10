package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.StalkerIndexJobDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.StalkerIndexJobEntity
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerIndexState
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CatalogIndexJobStoreTest {
    @Test
    fun `permanent rejection with no previous success does not restart automatically`() {
        assertThat(store.shouldRunSummary(
            XtreamIndexJobEntity(1L, "MOVIE", state = "FAILED_PERMANENT", lastSuccessAt = 0L)
        )).isFalse()
        assertThat(store.shouldRunSummary(
            XtreamIndexJobEntity(1L, "MOVIE", state = "FAILED_RETRYABLE", lastSuccessAt = 0L)
        )).isTrue()
    }
    private val providerDao = mock<ProviderDao>()
    private val xtreamDao = mock<XtreamIndexJobDao>()
    private val stalkerDao = mock<StalkerIndexJobDao>()
    private val store = CatalogIndexJobStore(
        providerDao = providerDao,
        xtreamIndexJobDao = xtreamDao,
        stalkerIndexJobStore = StalkerIndexJobStore(stalkerDao)
    )

    @Test
    fun `xtream upsert patches existing durable job and clears priority when requested`() = runTest {
        whenever(providerDao.getById(7L)).thenReturn(ProviderEntity(7L, "Xtream", ProviderType.XTREAM_CODES))
        whenever(xtreamDao.get(7L, ContentType.MOVIE.name)).thenReturn(
            XtreamIndexJobEntity(
                providerId = 7L,
                section = ContentType.MOVIE.name,
                state = "RUNNING",
                totalCategories = 12,
                completedCategories = 4,
                priorityCategoryId = 44L,
                priorityRequestedAt = 91L,
                lastSuccessAt = 82L
            )
        )

        store.upsert(
            CatalogIndexJobUpdate(
                providerId = 7L,
                section = ContentType.MOVIE.name,
                state = "SUCCESS",
                now = 100L,
                completedCategories = 12,
                clearPriority = true,
                lastSuccessAt = 100L
            )
        )

        val captured = argumentCaptor<XtreamIndexJobEntity>()
        verify(xtreamDao).upsert(captured.capture())
        assertThat(captured.firstValue.state).isEqualTo("SUCCESS")
        assertThat(captured.firstValue.totalCategories).isEqualTo(12)
        assertThat(captured.firstValue.completedCategories).isEqualTo(12)
        assertThat(captured.firstValue.priorityCategoryId).isNull()
        assertThat(captured.firstValue.priorityRequestedAt).isEqualTo(0L)
        assertThat(captured.firstValue.lastSuccessAt).isEqualTo(100L)
    }

    @Test
    fun `stalker movie updates route to the typed stalker job store`() = runTest {
        whenever(providerDao.getById(8L)).thenReturn(ProviderEntity(8L, "Portal", ProviderType.STALKER_PORTAL))
        whenever(stalkerDao.get(8L, ContentType.MOVIE.name)).thenReturn(null)

        store.upsert(
            CatalogIndexJobUpdate(
                providerId = 8L,
                section = ContentType.MOVIE.name,
                state = "FAILED_RETRYABLE",
                now = 200L,
                totalCategories = 3,
                failedCategories = 1,
                lastError = "temporary"
            )
        )

        verify(xtreamDao, never()).get(any(), any())
        verify(xtreamDao, never()).upsert(any())
        val captured = argumentCaptor<StalkerIndexJobEntity>()
        verify(stalkerDao).upsert(captured.capture())
        assertThat(captured.firstValue.section).isEqualTo(ContentType.MOVIE)
        assertThat(captured.firstValue.state).isEqualTo(StalkerIndexState.RETRY_WAIT)
        assertThat(captured.firstValue.totalCategories).isEqualTo(3)
        assertThat(captured.firstValue.failedCategories).isEqualTo(1)
    }

    @Test
    fun `summary refresh policy runs missing and pending jobs but not fresh success`() {
        assertThat(store.shouldRunSummary(null)).isTrue()
        assertThat(store.shouldRunSummary(XtreamIndexJobEntity(1L, "MOVIE", state = "QUEUED"))).isTrue()
        assertThat(
            store.shouldRunSummary(
                XtreamIndexJobEntity(
                    1L,
                    "MOVIE",
                    state = "SUCCESS",
                    lastSuccessAt = System.currentTimeMillis() - 1L
                )
            )
        ).isFalse()
    }

    @Test
    fun `every persisted xtream index state has an explicit process death policy`() {
        val recoverableAfterProcessDeath = setOf(
            "QUEUED",
            "RUNNING",
            "PARTIAL",
            "STALE",
            "FAILED_RETRYABLE"
        )
        val terminalStates = setOf(
            "SUCCESS",
            "FAILED_PERMANENT",
            "TRUNCATED",
            "DISABLED",
            "IDLE"
        )

        recoverableAfterProcessDeath.forEach { state ->
            assertThat(
                store.shouldRunSummary(
                    XtreamIndexJobEntity(1L, "MOVIE", state = state, lastSuccessAt = Long.MAX_VALUE)
                )
            ).isTrue()
        }
        terminalStates.forEach { state ->
            assertThat(
                store.shouldRunSummary(
                    XtreamIndexJobEntity(
                        1L,
                        "MOVIE",
                        state = state,
                        lastSuccessAt = System.currentTimeMillis()
                    )
                )
            ).isFalse()
        }
    }

    @Test
    fun `every persisted stalker index state round trips and keeps recovery semantics`() = runTest {
        val stalkerStore = StalkerIndexJobStore(stalkerDao)
        val expectedLegacyStates = mapOf(
            StalkerIndexState.DISABLED to "DISABLED",
            StalkerIndexState.QUEUED to "QUEUED",
            StalkerIndexState.RUNNING to "RUNNING",
            StalkerIndexState.RETRY_WAIT to "FAILED_RETRYABLE",
            StalkerIndexState.PARTIAL to "PARTIAL",
            StalkerIndexState.COMPLETE to "SUCCESS",
            StalkerIndexState.TRUNCATED to "TRUNCATED",
            StalkerIndexState.FAILED to "FAILED_PERMANENT"
        )

        expectedLegacyStates.forEach { (state, legacy) ->
            whenever(stalkerDao.get(8L, ContentType.MOVIE.name)).thenReturn(null)
            stalkerStore.upsertLegacy(
                providerId = 8L,
                section = ContentType.MOVIE,
                state = legacy,
                now = 200L,
                lastSuccessAt = if (state in setOf(
                        StalkerIndexState.COMPLETE,
                        StalkerIndexState.TRUNCATED,
                        StalkerIndexState.FAILED,
                        StalkerIndexState.DISABLED
                    )
                ) System.currentTimeMillis() else 0L
            )

            val captured = argumentCaptor<StalkerIndexJobEntity>()
            verify(stalkerDao, org.mockito.kotlin.atLeastOnce()).upsert(captured.capture())
            assertThat(captured.allValues.last().state).isEqualTo(state)
            assertThat(stalkerStore.toLegacyState(state)).isEqualTo(legacy)
            assertThat(
                stalkerStore.shouldRunSummary(
                    captured.allValues.last().copy(lastSuccessAt = System.currentTimeMillis())
                )
            ).isEqualTo(
                state in setOf(
                    StalkerIndexState.QUEUED,
                    StalkerIndexState.RUNNING,
                    StalkerIndexState.RETRY_WAIT,
                    StalkerIndexState.PARTIAL
                )
            )
        }
    }
}
