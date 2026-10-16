package com.streamvault.feature.settings.presentation

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.settings.SettingsOperations
import com.streamvault.domain.settings.SettingsSyncSection
import com.streamvault.domain.sync.ProviderSectionSyncResult
import com.streamvault.feature.settings.R
import com.streamvault.feature.settings.api.SettingsSurfaceRefreshPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Answers
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.stubbing.Answer

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSyncActionsTest {
    private val context = mock<Context>(defaultAnswer = Answer { invocation ->
        if (invocation.method.name == "getString") {
            val format = when (invocation.arguments[0] as Int) {
                R.string.settings_sync_option_tv -> "TV"
                R.string.settings_sync_option_movies -> "Movies"
                R.string.settings_sync_option_series -> "Series"
                R.string.settings_sync_option_epg -> "EPG"
                R.string.settings_sync_section_queued -> "%1\$s refresh queued"
                R.string.settings_sync_sections_success -> "Refreshed: %1\$s"
                R.string.settings_sync_sections_failed -> "Incomplete: %1\$s"
                R.string.settings_sync_sections_partial -> "Refreshed: %1\$s; warnings: %2\$s"
                R.string.settings_sync_failed -> "Sync failed: %1\$s"
                R.string.settings_sync_cancelled -> "Sync cancelled."
                R.string.settings_sync_stopped_queued -> "Refresh stopped. Already queued work will continue."
                R.string.settings_syncing_section -> "Refreshing %1\$s"
                else -> "Preparing"
            }
            String.format(format, *invocation.arguments.drop(1).toTypedArray())
        } else Answers.RETURNS_DEFAULTS.answer(invocation)
    })
    private val operations = mock<SettingsOperations>()
    private val surfaces = mock<SettingsSurfaceRefreshPort>()
    private val provider = Provider(
        id = 7L, name = "Portal", type = ProviderType.STALKER_PORTAL, serverUrl = "https://example.com"
    )
    private val state = MutableStateFlow(SettingsUiState(providers = listOf(provider)))
    private val actions = SettingsSyncActions(context, operations, surfaces, state) { _, _ ->
        error("A section refresh must not rebuild the index")
    }

    @Test
    fun `sync now includes Stalker series and queues EPG without waiting for it`() = runTest {
        whenever(operations.syncProviderSection(eq(7L), any(), any())).thenAnswer { invocation ->
            val section = invocation.getArgument<SettingsSyncSection>(1)
            Result.success(ProviderSectionSyncResult(queued = section != SettingsSyncSection.LIVE))
        }
        actions.syncProviderSection(this, 7L, ProviderSyncSelection.SYNC_NOW)
        advanceUntilIdle()

        verify(operations).syncProviderSection(eq(7L), eq(SettingsSyncSection.LIVE), any())
        verify(operations).syncProviderSection(eq(7L), eq(SettingsSyncSection.MOVIES), any())
        verify(operations).syncProviderSection(eq(7L), eq(SettingsSyncSection.SERIES), any())
        verify(operations, never()).syncProviderSection(eq(7L), eq(SettingsSyncSection.EPG), any())
        verify(operations).scheduleBackgroundEpgSync(7L)
        assertThat(state.value.userMessage).contains("Series refresh queued")
        assertThat(state.value.userMessage).contains("EPG refresh queued")
        assertThat(state.value.isSyncing).isFalse()
    }

    @Test
    fun `partial TV refresh retains warnings and updates TV integration`() = runTest {
        whenever(operations.syncProviderSection(eq(7L), eq(SettingsSyncSection.LIVE), any())).thenReturn(
            Result.success(ProviderSectionSyncResult(
                partial = true, warnings = listOf("Sports unavailable"), catalogChanged = true
            ))
        )
        actions.syncProviderSection(this, 7L, ProviderSyncSelection.TV)
        advanceUntilIdle()

        assertThat(state.value.userMessage).contains("Incomplete")
        assertThat(state.value.syncWarningsByProvider[7L]).containsExactly("TV: Sports unavailable")
        verify(surfaces).enqueueTvInputCatalogRefresh()
    }

    @Test
    fun `warning retry that only queues an index does not clear existing warnings`() = runTest {
        state.value = state.value.copy(syncWarningsByProvider = mapOf(7L to listOf("Movies: Missing rows")))
        whenever(operations.syncProviderSection(eq(7L), eq(SettingsSyncSection.MOVIES), any())).thenReturn(
            Result.success(ProviderSectionSyncResult(queued = true))
        )
        actions.retryWarningAction(this, 7L, ProviderWarningAction.MOVIES)
        advanceUntilIdle()

        assertThat(state.value.syncWarningsByProvider[7L]).containsExactly("Movies: Missing rows")
        assertThat(state.value.userMessage).contains("Movies refresh queued")
    }

    @Test
    fun `explicit EPG refresh reports warnings and clears only its own warnings after success`() = runTest {
        state.value = state.value.copy(syncWarningsByProvider = mapOf(7L to listOf("TV: Missing rows")))
        whenever(operations.syncProviderSection(eq(7L), eq(SettingsSyncSection.EPG), any())).thenReturn(
            Result.success(ProviderSectionSyncResult(partial = true, warnings = listOf("XMLTV unavailable")))
        )
        actions.retryWarningAction(this, 7L, ProviderWarningAction.EPG)
        advanceUntilIdle()
        assertThat(state.value.userMessage).contains("XMLTV unavailable")
        assertThat(state.value.syncWarningsByProvider[7L]).contains("EPG: XMLTV unavailable")

        whenever(operations.syncProviderSection(eq(7L), eq(SettingsSyncSection.EPG), any())).thenReturn(
            Result.success(ProviderSectionSyncResult())
        )
        actions.retryWarningAction(this, 7L, ProviderWarningAction.EPG)
        advanceUntilIdle()
        assertThat(state.value.syncWarningsByProvider[7L]).containsExactly("TV: Missing rows")
        verify(operations, never()).scheduleBackgroundEpgSync(any())
    }

    @Test
    fun `warning retry exception cleans up the overlay`() = runTest {
        whenever(operations.syncProviderSection(eq(7L), eq(SettingsSyncSection.EPG), any()))
            .thenThrow(IllegalStateException("Guide unavailable"))
        actions.retryWarningAction(this, 7L, ProviderWarningAction.EPG)
        advanceUntilIdle()

        assertThat(state.value.isSyncing).isFalse()
        assertThat(state.value.syncCanCancel).isFalse()
        assertThat(state.value.syncProgress).isNull()
        assertThat(state.value.userMessage).contains("Guide unavailable")
    }

    @Test
    fun `cancel blocks overlapping refresh until cleanup and allows another refresh afterward`() = runTest {
        val started = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Result<ProviderSectionSyncResult>>()
        whenever(operations.syncProviderSection(eq(7L), eq(SettingsSyncSection.EPG), any()))
            .doSuspendableAnswer { started.complete(Unit); blocked.await() }
        actions.retryWarningAction(this, 7L, ProviderWarningAction.EPG)
        runCurrent()
        assertThat(started.isCompleted).isTrue()
        actions.cancelSync()
        actions.retryWarningAction(this, 7L, ProviderWarningAction.EPG)
        advanceUntilIdle()

        verify(operations, times(1)).syncProviderSection(eq(7L), eq(SettingsSyncSection.EPG), any())
        assertThat(state.value.isSyncing).isFalse()
        assertThat(state.value.userMessage).isEqualTo("Sync cancelled.")
        whenever(operations.syncProviderSection(eq(7L), eq(SettingsSyncSection.EPG), any()))
            .thenReturn(Result.success(ProviderSectionSyncResult()))
        actions.retryWarningAction(this, 7L, ProviderWarningAction.EPG)
        advanceUntilIdle()
        verify(operations, times(2)).syncProviderSection(eq(7L), eq(SettingsSyncSection.EPG), any())
    }

    @Test
    fun `cancel after indexing handoff explains that queued work continues`() = runTest {
        whenever(operations.syncProviderSection(eq(7L), eq(SettingsSyncSection.MOVIES), any()))
            .thenReturn(Result.success(ProviderSectionSyncResult(queued = true)))
        val blocked = CompletableDeferred<Result<ProviderSectionSyncResult>>()
        whenever(operations.syncProviderSection(eq(7L), eq(SettingsSyncSection.EPG), any()))
            .doSuspendableAnswer { blocked.await() }
        actions.syncProviderCustom(this, 7L, setOf(ProviderSyncSelection.MOVIES, ProviderSyncSelection.EPG))
        runCurrent()
        actions.cancelSync()
        advanceUntilIdle()

        assertThat(state.value.userMessage).contains("queued work will continue")
        assertThat(state.value.isSyncing).isFalse()
    }

    @Test
    fun `Stalker individual and custom menus expose all supported sections`() {
        assertThat(availableSyncSelections(provider)).containsExactly(
            ProviderSyncSelection.TV, ProviderSyncSelection.MOVIES,
            ProviderSyncSelection.SERIES, ProviderSyncSelection.EPG
        ).inOrder()
    }
}
