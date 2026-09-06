package com.streamvault.data.settings

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.dao.XtreamLiveOnboardingDao
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.data.local.entity.XtreamLiveOnboardingStateEntity
import com.streamvault.data.sync.ProviderSyncCommands
import com.streamvault.data.sync.SyncRepairSection
import com.streamvault.data.sync.XtreamLiveSyncReason
import com.streamvault.domain.model.Result
import com.streamvault.domain.settings.SettingsSyncSection
import com.streamvault.domain.settings.SettingsXtreamIndexJob
import com.streamvault.domain.settings.SettingsXtreamLiveOnboarding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SettingsOperationsImplTest {
    private val programDao = mock<ProgramDao>()
    private val xtreamIndexJobDao = mock<XtreamIndexJobDao>()
    private val xtreamLiveOnboardingDao = mock<XtreamLiveOnboardingDao>()
    private val syncCommands = mock<ProviderSyncCommands>()
    private val operations = SettingsOperationsImpl(
        programDao = programDao,
        xtreamIndexJobDao = xtreamIndexJobDao,
        xtreamLiveOnboardingDao = xtreamLiveOnboardingDao,
        syncCommands = syncCommands
    )

    @Test
    fun `observe program count forwards provider count`() = runTest {
        whenever(programDao.observeCountByProvider(7L)).thenReturn(flowOf(18))

        assertThat(operations.observeProgramCount(7L).first()).isEqualTo(18)
    }

    @Test
    fun `observe index jobs maps every settings-facing field`() = runTest {
        whenever(xtreamIndexJobDao.observeAll()).thenReturn(
            flowOf(listOf(XtreamIndexJobEntity(7L, "MOVIE", "PARTIAL", indexedRows = 18, lastError = "bad row")))
        )

        assertThat(operations.observeXtreamIndexJobs().first()).containsExactly(
            SettingsXtreamIndexJob(7L, "MOVIE", "PARTIAL", 18, "bad row")
        )
    }

    @Test
    fun `observe incomplete onboarding maps every settings-facing field`() = runTest {
        whenever(xtreamLiveOnboardingDao.observeIncomplete()).thenReturn(
            flowOf(
                listOf(
                    XtreamLiveOnboardingStateEntity(
                        providerId = 7L,
                        phase = "INDEXING",
                        importStrategy = "STREAMING",
                        acceptedRowCount = 18,
                        stagedFlushCount = 2,
                        syncProfileTier = "LOW",
                        syncProfileBatchSize = 16,
                        syncProfileStrategy = "SEQUENTIAL",
                        syncProfileLowMemory = true,
                        syncProfileMemoryClassMb = 384,
                        syncProfileAvailableMemMb = 192L,
                        lastError = "bad row",
                        updatedAt = 99L
                    )
                )
            )
        )

        assertThat(operations.observeIncompleteXtreamLiveOnboarding().first()).containsExactly(
            SettingsXtreamLiveOnboarding(
                providerId = 7L,
                phase = "INDEXING",
                importStrategy = "STREAMING",
                acceptedRowCount = 18,
                stagedFlushCount = 2,
                syncProfileTier = "LOW",
                syncProfileBatchSize = 16,
                syncProfileStrategy = "SEQUENTIAL",
                syncProfileLowMemory = true,
                syncProfileMemoryClassMb = 384,
                syncProfileAvailableMemMb = 192L,
                lastError = "bad row",
                updatedAt = 99L
            )
        )
    }

    @Test
    fun `sync provider delegates force unchanged`() = runTest {
        whenever(syncCommands.sync(7L, force = true)).thenReturn(Result.success(Unit))

        assertThat(operations.syncProvider(7L, force = true)).isEqualTo(Result.success(Unit))
        verify(syncCommands).sync(7L, force = true)
    }

    @Test
    fun `retry section translates settings section and preserves progress callback`() = runTest {
        whenever(
            syncCommands.retrySection(
                eq(7L),
                eq(SyncRepairSection.MOVIES),
                isNull(),
                eq(XtreamLiveSyncReason.MANUAL_SETTINGS),
                any()
            )
        )
            .thenAnswer { invocation ->
                invocation.getArgument<((String) -> Unit)?>(4)?.invoke("Movies")
                Result.success(Unit)
            }
        var progress: String? = null

        assertThat(
            operations.retryProviderSection(7L, SettingsSyncSection.MOVIES) { progress = it }
        ).isEqualTo(Result.success(Unit))
        assertThat(progress).isEqualTo("Movies")
        verify(syncCommands).retrySection(
            eq(7L),
            eq(SyncRepairSection.MOVIES),
            isNull(),
            eq(XtreamLiveSyncReason.MANUAL_SETTINGS),
            any()
        )
    }

    @Test
    fun `rebuild index preserves progress callback`() = runTest {
        whenever(syncCommands.rebuildXtreamIndex(eq(7L), any())).thenAnswer { invocation ->
            invocation.getArgument<((String) -> Unit)?>(1)?.invoke("Indexing")
            Result.success(Unit)
        }
        var progress: String? = null

        assertThat(operations.rebuildXtreamIndex(7L) { progress = it }).isEqualTo(Result.success(Unit))
        assertThat(progress).isEqualTo("Indexing")
    }

    @Test
    fun `schedule background epg forwards provider id`() {
        operations.scheduleBackgroundEpgSync(7L)

        verify(syncCommands).scheduleBackgroundEpgSync(7L)
    }
}
