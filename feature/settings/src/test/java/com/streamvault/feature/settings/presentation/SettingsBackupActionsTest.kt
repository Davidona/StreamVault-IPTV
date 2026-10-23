package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupImportResult
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.manager.BackupRestoreOutcome
import com.streamvault.domain.manager.BackupProviderReference
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.usecase.ExportBackup
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.ImportBackupResult
import com.streamvault.domain.usecase.InspectBackupResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsBackupActionsTest {

    private val exportBackup: ExportBackup = mock()
    private val importBackup: ImportBackup = mock()

    private val provider = LegacyProvider(
        id = 18, name = "Test", type = ProviderType.XTREAM_CODES,
        serverUrl = "https://example.com", username = "user"
    )
    private val reference = BackupProviderReference("https://example.com", "user", providerType = ProviderType.XTREAM_CODES)

    private fun preview(providerCount: Int = 1): BackupPreview {
        val preview: BackupPreview = mock()
        whenever(preview.providerCount).thenReturn(providerCount)
        return preview
    }

    @Test
    fun missingProvider_neverOffersSyncWhetherProviderSectionWasExcludedOrEmpty() = runTest {
        for (includeProviders in listOf(false, true)) {
            val ui = MutableStateFlow(SettingsUiState(
                pendingBackupUri = "content://backup",
                backupPreview = preview(),
                backupImportPlan = BackupImportPlan(importProviders = includeProviders),
                // Stale UI data must not make a deleted provider eligible.
                providers = listOf(provider)
            ))
            whenever(importBackup.confirm(org.mockito.kotlin.any())).thenReturn(ImportBackupResult.Success(
                BackupImportResult(outcome = BackupRestoreOutcome.WAITING_FOR_SYNC,
                    restoreJobId = "pending-job", affectedProviders = listOf(reference), unresolvedCount = 1)
            ))
            SettingsBackupActions(exportBackup, importBackup, ui, loadRestoreProviders = { emptyList() })
                .confirmBackupImport(this)
            advanceUntilIdle()
            assertThat(ui.value.pendingRestoreProviders).isEmpty()
            assertThat(ui.value.pendingRestoreJobId).isEqualTo("pending-job")
            assertThat(ui.value.selectedRestoreProviderIndices).isEmpty()
            assertThat(ui.value.isSyncing).isFalse()
        }
    }

    @Test
    fun noProviderReferences_doesNotOfferSyncEvenWhenDeviceHasProviders() = runTest {
        val ui = MutableStateFlow(SettingsUiState(pendingBackupUri = "content://backup", providers = listOf(provider)))
        whenever(importBackup.confirm(org.mockito.kotlin.any())).thenReturn(ImportBackupResult.Success(
            BackupImportResult(outcome = BackupRestoreOutcome.COMPLETE)
        ))
        SettingsBackupActions(exportBackup, importBackup, ui, loadRestoreProviders = { error("Unnecessary lookup") })
            .confirmBackupImport(this)
        advanceUntilIdle()
        assertThat(ui.value.pendingRestoreProviders).isEmpty()
    }

    @Test
    fun newlyImportedProvider_canBeOfferedBeforeUiObserverUpdates() = runTest {
        val ui = MutableStateFlow(SettingsUiState(pendingBackupUri = "content://backup", backupPreview = preview()))
        whenever(importBackup.confirm(org.mockito.kotlin.any())).thenReturn(ImportBackupResult.Success(
            BackupImportResult(outcome = BackupRestoreOutcome.WAITING_FOR_SYNC, affectedProviders = listOf(reference))
        ))
        SettingsBackupActions(exportBackup, importBackup, ui, loadRestoreProviders = { listOf(provider) })
            .confirmBackupImport(this)
        advanceUntilIdle()
        assertThat(ui.value.providers).isEmpty()
        assertThat(ui.value.pendingRestoreProviders).containsExactly(reference)
    }

    @Test
    fun mixedReferences_onlyOfferConfiguredUnambiguousProvidersWhenProviderImportIsSelected() = runTest {
        val ui = MutableStateFlow(SettingsUiState(pendingBackupUri = "content://backup",
            backupPreview = preview(), backupImportPlan = BackupImportPlan(importProviders = true)))
        val missing = reference.copy(serverUrl = "https://missing.example")
        val ambiguous = reference.copy(username = "duplicate")
        whenever(importBackup.confirm(org.mockito.kotlin.any())).thenReturn(ImportBackupResult.Success(
            BackupImportResult(outcome = BackupRestoreOutcome.WAITING_FOR_SYNC,
                affectedProviders = listOf(reference, missing, ambiguous, reference))
        ))
        SettingsBackupActions(exportBackup, importBackup, ui, loadRestoreProviders = {
            listOf(provider, provider.copy(id = 19, username = "duplicate"), provider.copy(id = 20, username = "duplicate"))
        }).confirmBackupImport(this)
        advanceUntilIdle()
        assertThat(ui.value.pendingRestoreProviders).containsExactly(reference)
    }

    @Test
    fun excludedOrEmptyProviderSection_neverOffersSyncForExistingLocalProviders() = runTest {
        for ((selected, count) in listOf(false to 1, false to 0, true to 0)) {
            val ui = MutableStateFlow(SettingsUiState(
                pendingBackupUri = "content://backup", backupPreview = preview(count),
                backupImportPlan = BackupImportPlan(importProviders = selected), providers = listOf(provider)
            ))
            whenever(importBackup.confirm(org.mockito.kotlin.any())).thenReturn(ImportBackupResult.Success(
                BackupImportResult(outcome = BackupRestoreOutcome.WAITING_FOR_SYNC,
                    restoreJobId = "pending-job", affectedProviders = listOf(reference))
            ))
            SettingsBackupActions(exportBackup, importBackup, ui,
                loadRestoreProviders = { error("Excluded or empty Providers must not trigger sync lookup") })
                .confirmBackupImport(this)
            advanceUntilIdle()
            assertThat(ui.value.pendingRestoreProviders).isEmpty()
            assertThat(ui.value.pendingRestoreJobId).isEqualTo("pending-job")
        }
    }

    @Test
    fun syncEligibility_checksAccountTypeAndMacAndAcceptsLegacyUntypedReferences() {
        val providers = listOf(provider)
        assertThat(providers.findRestoreSyncProvider(reference.copy(serverUrl = " HTTPS://EXAMPLE.COM/ ", username = " user "))).isEqualTo(provider)
        assertThat(providers.findRestoreSyncProvider(reference.copy(providerType = null))).isEqualTo(provider)
        assertThat(providers.findRestoreSyncProvider(reference.copy(username = "other"))).isNull()
        assertThat(providers.findRestoreSyncProvider(reference.copy(providerType = ProviderType.STALKER_PORTAL))).isNull()
        assertThat(providers.findRestoreSyncProvider(reference.copy(stalkerMacAddress = "00:11:22:33:44:55"))).isNull()
        assertThat(listOf(provider, provider.copy(id = 19)).findRestoreSyncProvider(reference)).isNull()
    }

    @Test
    fun importPlanSectionInventory_requiresUpdatingTheCombinationMatrixWhenSectionsChange() {
        val sections = BackupImportPlan::class.java.declaredFields.filter { it.name.startsWith("import") }.map { it.name }
        assertThat(sections).containsExactly(
            "importPreferences", "importProviders", "importSavedLibrary", "importPlaybackHistory",
            "importMultiViewPresets", "importRecordingSchedules", "importStremioAddons"
        )
    }

    @Test
    fun importError_preservesRetrySourcePlanAndCredentialsWithoutFollowUp() = runTest(StandardTestDispatcher()) {
        val plan = BackupImportPlan(importProviders = false)
        val credentials = listOf(com.streamvault.domain.manager.ProviderCredentials("https://example.com", "user", "secret"))
        val ui = MutableStateFlow(SettingsUiState(
            pendingBackupUri = "content://backup", backupImportPlan = plan, pendingDriveCredentials = credentials
        ))
        whenever(importBackup.confirm(org.mockito.kotlin.any())).thenReturn(ImportBackupResult.Error("unavailable"))
        var followedUp = false
        SettingsBackupActions(exportBackup, importBackup, ui).confirmBackupImport(this) { followedUp = true }
        advanceUntilIdle()
        assertThat(ui.value.pendingBackupUri).isEqualTo("content://backup")
        assertThat(ui.value.backupImportPlan).isEqualTo(plan)
        assertThat(ui.value.pendingDriveCredentials).isEqualTo(credentials)
        assertThat(ui.value.isImportingBackup).isFalse()
        assertThat(ui.value.isSyncing).isFalse()
        assertThat(ui.value.userMessage).contains("unavailable")
        assertThat(followedUp).isFalse()
    }

    @Test
    fun noSource_doesNotStartAnImport() = runTest(StandardTestDispatcher()) {
        val ui = MutableStateFlow(SettingsUiState())
        SettingsBackupActions(exportBackup, importBackup, ui).confirmBackupImport(this)
        advanceUntilIdle()
        org.mockito.kotlin.verifyNoInteractions(importBackup)
        assertThat(ui.value.isImportingBackup).isFalse()
    }

    @Test
    fun exportError_resetsBusyStateAndReportsFailureToCaller() = runTest(StandardTestDispatcher()) {
        val ui = MutableStateFlow(SettingsUiState())
        whenever(exportBackup(org.mockito.kotlin.any())).thenReturn(
            com.streamvault.domain.usecase.ExportBackupResult.Error("storage unavailable")
        )
        var succeeded = false
        var finished: Boolean? = null
        SettingsBackupActions(exportBackup, importBackup, ui).exportConfig(
            this, "content://export", onSuccess = { succeeded = true }, onFinished = { finished = it }
        )
        advanceUntilIdle()
        assertThat(ui.value.isSyncing).isFalse()
        assertThat(ui.value.userMessage).contains("storage unavailable")
        assertThat(succeeded).isFalse()
        assertThat(finished).isFalse()
    }

    @Test
    fun dismissPreview_discardsSourcePlanAndDriveCredentials() {
        val ui = MutableStateFlow(SettingsUiState(
            pendingBackupUri = "content://backup", backupImportPlan = BackupImportPlan(importProviders = false),
            pendingDriveCredentials = listOf(com.streamvault.domain.manager.ProviderCredentials("https://example.com", "user", "secret"))
        ))
        SettingsBackupActions(exportBackup, importBackup, ui).dismissBackupPreview()
        assertThat(ui.value.pendingBackupUri).isNull()
        assertThat(ui.value.backupPreview).isNull()
        assertThat(ui.value.pendingDriveCredentials).isNull()
        assertThat(ui.value.backupImportPlan).isEqualTo(BackupImportPlan())
    }

    @Test
    fun partialImport_retainsSourceAndPlanForCheckpointRetry() = runTest(StandardTestDispatcher()) {
        val plan = BackupImportPlan(importPreferences = true, importProviders = false)
        val uiState = MutableStateFlow(
            SettingsUiState(
                pendingBackupUri = "content://backup",
                backupImportPlan = plan
            )
        )
        val actions = SettingsBackupActions(exportBackup, importBackup, uiState)
        whenever(importBackup.confirm(org.mockito.kotlin.any())).thenReturn(
            ImportBackupResult.Success(
                BackupImportResult(
                    outcome = BackupRestoreOutcome.PARTIAL,
                    importedSections = listOf("Providers"),
                    failedSections = listOf("Preferences: unavailable")
                )
            )
        )
        var onSuccessCalled = false

        actions.confirmBackupImport(this, onSuccess = { onSuccessCalled = true })
        advanceUntilIdle()

        assertThat(uiState.value.pendingBackupUri).isEqualTo("content://backup")
        assertThat(uiState.value.backupImportPlan).isEqualTo(plan)
        assertThat(uiState.value.isImportingBackup).isFalse()
        assertThat(onSuccessCalled).isFalse()
    }

    @Test
    fun completeImport_clearsSourceAndRunsFollowUp() = runTest(StandardTestDispatcher()) {
        val uiState = MutableStateFlow(
            SettingsUiState(pendingBackupUri = "content://backup")
        )
        val actions = SettingsBackupActions(exportBackup, importBackup, uiState)
        whenever(importBackup.confirm(org.mockito.kotlin.any())).thenReturn(
            ImportBackupResult.Success(BackupImportResult(outcome = BackupRestoreOutcome.COMPLETE))
        )
        var onSuccessCalled = false

        actions.confirmBackupImport(this, onSuccess = { onSuccessCalled = true })
        advanceUntilIdle()

        assertThat(uiState.value.pendingBackupUri).isNull()
        assertThat(uiState.value.backupImportPlan).isEqualTo(BackupImportPlan())
        assertThat(onSuccessCalled).isTrue()
    }

    @Test
    fun normalInspect_clearsCredentialsPendingFromAnEarlierDrivePull() = runTest(StandardTestDispatcher()) {
        val uiState = MutableStateFlow(
            SettingsUiState(
                pendingDriveCredentials = listOf(
                    com.streamvault.domain.manager.ProviderCredentials(
                        serverUrl = "https://example.com",
                        username = "alice",
                        password = "secret"
                    )
                )
            )
        )
        val actions = SettingsBackupActions(exportBackup, importBackup, uiState)
        whenever(importBackup.inspect(org.mockito.kotlin.any())).thenReturn(
            InspectBackupResult.Success(
                uriString = "content://local-backup",
                preview = BackupPreview(
                    version = 12,
                    providerCount = 0,
                    favoriteCount = 0,
                    groupCount = 0,
                    playbackHistoryCount = 0,
                    multiViewPresetCount = 0,
                    preferenceCount = 0,
                    protectedCategoryCount = 0,
                    scheduledRecordingCount = 0,
                    providerConflicts = 0,
                    favoriteConflicts = 0,
                    groupConflicts = 0,
                    historyConflicts = 0,
                    protectedCategoryConflicts = 0,
                    recordingConflicts = 0
                )
            )
        )

        actions.inspectBackup(this, "content://local-backup")
        advanceUntilIdle()

        assertThat(uiState.value.pendingDriveCredentials).isNull()
    }
}
