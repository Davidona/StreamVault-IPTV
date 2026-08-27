package com.streamvault.app.ui.screens.provider

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.pairing.ProviderQrPairingManager
import com.streamvault.app.pairing.ProviderQrPairingState
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerTransportChallenge
import com.streamvault.domain.model.StalkerTransportChallengeReason
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.StalkerTransportOrigin
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupImportResult
import com.streamvault.domain.manager.BackupRestoreOutcome
import com.streamvault.domain.manager.DriveAuthState
import com.streamvault.domain.manager.DriveBackupSnapshot
import com.streamvault.domain.manager.DriveBackupSyncManager
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.model.Result as DomainResult
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.ImportBackupResult
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSetupViewModelTest {

    private val providerRepository: ProviderRepository = mock()
    private val combinedM3uRepository: CombinedM3uRepository = mock()
    private val validateAndAddProvider: ValidateAndAddProvider = mock()
    private val importBackup: ImportBackup = mock()
    private val driveBackupSyncManager: DriveBackupSyncManager = mock()
    private val providerQrPairingManager: ProviderQrPairingManager = mock()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(null))
        whenever(providerRepository.getProviders()).thenReturn(flowOf(emptyList()))
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(flowOf(null))
        whenever(driveBackupSyncManager.authState).thenReturn(flowOf(DriveAuthState.SignedOut))
        whenever(providerQrPairingManager.state).thenReturn(MutableStateFlow(ProviderQrPairingState()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `adding m3u while combined source is active prepares attach prompt with names`() = runTest {
        val createdProvider = Provider(
            id = 7L,
            name = "Playlist 7",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            m3uUrl = "https://example.com/list.m3u"
        )
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            flowOf(ActiveLiveSource.CombinedM3uSource(44L))
        )
        whenever(combinedM3uRepository.getProfile(44L)).thenReturn(
            CombinedM3uProfile(id = 44L, name = "Weekend Set")
        )
        whenever(validateAndAddProvider.addM3u(any(), any())).thenReturn(
            ValidateAndAddProviderResult.Success(createdProvider)
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        viewModel.addM3u("https://example.com/list.m3u", "Playlist 7", "", "")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.pendingCombinedAttachProfileId).isEqualTo(44L)
        assertThat(viewModel.uiState.value.pendingCombinedAttachProfileName).isEqualTo("Weekend Set")
        assertThat(viewModel.uiState.value.createdProviderName).isEqualTo("Playlist 7")
        assertThat(viewModel.uiState.value.loginSuccess).isFalse()
        assertThat(viewModel.uiState.value.onboardingCompletion)
            .isEqualTo(ProviderSetupViewModel.OnboardingCompletion.READY)
    }

    @Test
    fun `login xtream saved with sync warning marks onboarding as resuming instead of ready`() = runTest {
        val createdProvider = Provider(
            id = 8L,
            name = "Premium",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com"
        )
        whenever(validateAndAddProvider.loginXtream(any(), any())).thenReturn(
            ValidateAndAddProviderResult.SavedWithWarning(
                provider = createdProvider,
                warning = "Provider login succeeded, but initial sync failed. The provider was saved and can be retried from Settings: timeout"
            )
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        viewModel.loginXtream("https://example.com", "alice", "secret", "Premium", "", "")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.loginSuccess).isFalse()
        assertThat(viewModel.uiState.value.onboardingCompletion)
            .isEqualTo(ProviderSetupViewModel.OnboardingCompletion.SAVED_RESUMING)
        assertThat(viewModel.uiState.value.createdProviderId).isEqualTo(8L)
        assertThat(viewModel.uiState.value.completionWarning).contains("initial sync failed")
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `confirm backup import completes onboarding when providers are restored`() = runTest {
        val importedProvider = Provider(
            id = 9L,
            name = "Restored",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com"
        )
        whenever(providerRepository.getProviders()).thenReturn(flowOf(listOf(importedProvider)))
        whenever(importBackup.confirm(any())).thenReturn(
            ImportBackupResult.Success(BackupImportResult(importedSections = listOf("Providers")))
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )
        val field = ProviderSetupViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ProviderSetupState>
        stateFlow.value = stateFlow.value.copy(
            pendingBackupUri = "content://backup.json",
            backupImportPlan = BackupImportPlan()
        )

        viewModel.confirmBackupImport()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.backupImportSuccess).isTrue()
        assertThat(viewModel.uiState.value.pendingBackupUri).isNull()
        assertThat(viewModel.uiState.value.isImportingBackup).isFalse()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `partial backup import does not apply pending Drive credentials`() = runTest {
        whenever(importBackup.confirm(any())).thenReturn(
            ImportBackupResult.Success(
                BackupImportResult(
                    outcome = BackupRestoreOutcome.PARTIAL,
                    failedSections = listOf("Saved library/history: 1 unresolved")
                )
            )
        )
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )
        val credentials = listOf(
            ProviderCredentials(
                serverUrl = "https://example.com",
                username = "alice",
                password = "secret"
            )
        )
        val field = ProviderSetupViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ProviderSetupState>
        stateFlow.value = stateFlow.value.copy(
            pendingBackupUri = "content://drive-backup.json",
            backupImportPlan = BackupImportPlan(),
            pendingDriveCredentials = credentials
        )

        viewModel.confirmBackupImport()
        advanceUntilIdle()

        verify(providerRepository, org.mockito.kotlin.never()).updateProviderPassword(any(), any(), any())
        assertThat(viewModel.uiState.value.pendingDriveCredentials).isEqualTo(credentials)
        assertThat(viewModel.uiState.value.pendingBackupUri).isEqualTo("content://drive-backup.json")
    }

    @Test
    fun `drive import shows snapshot choice before downloading when multiple exist`() = runTest {
        val snapshots = listOf(
            DriveBackupSnapshot("new", "streamvault_backup_bundle_new.json"),
            DriveBackupSnapshot("old", "streamvault_backup_bundle_old.json"),
        )
        whenever(driveBackupSyncManager.listBackups()).thenReturn(DomainResult.Success(snapshots))
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        viewModel.importBackupFromDrive()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.driveBackupOptions).isEqualTo(snapshots)
        assertThat(viewModel.uiState.value.isImportingBackup).isFalse()
        verify(driveBackupSyncManager, never()).pullBackup(any())
    }

    @Test
    fun `attach created provider to combined keeps combined source active`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        val seededState = viewModel.uiState.value.copy(
            createdProviderId = 12L,
            pendingCombinedAttachProfileId = 99L,
            onboardingCompletion = ProviderSetupViewModel.OnboardingCompletion.READY
        )
        val field = ProviderSetupViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ProviderSetupState>
        stateFlow.value = seededState

        viewModel.attachCreatedProviderToCombined()
        advanceUntilIdle()

        verify(combinedM3uRepository).addProvider(99L, 12L)
        verify(combinedM3uRepository).setActiveLiveSource(eq(ActiveLiveSource.CombinedM3uSource(99L)))
        assertThat(viewModel.uiState.value.loginSuccess).isTrue()
        assertThat(viewModel.uiState.value.onboardingCompletion)
            .isEqualTo(ProviderSetupViewModel.OnboardingCompletion.READY)
        assertThat(viewModel.uiState.value.pendingCombinedAttachProfileId).isNull()
    }

    @Test
    fun `skipping combined attach after saved warning keeps onboarding in resuming state`() = runTest {
        val createdProvider = Provider(
            id = 7L,
            name = "Playlist 7",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            m3uUrl = "https://example.com/list.m3u"
        )
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            flowOf(ActiveLiveSource.CombinedM3uSource(44L))
        )
        whenever(combinedM3uRepository.getProfile(44L)).thenReturn(
            CombinedM3uProfile(id = 44L, name = "Weekend Set")
        )
        whenever(validateAndAddProvider.addM3u(any(), any())).thenReturn(
            ValidateAndAddProviderResult.SavedWithWarning(
                provider = createdProvider,
                warning = "Playlist saved, but initial sync failed. Resume has been queued."
            )
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        viewModel.addM3u("https://example.com/list.m3u", "Playlist 7", "", "")
        advanceUntilIdle()
        viewModel.skipCreatedProviderCombinedAttach()

        assertThat(viewModel.uiState.value.pendingCombinedAttachProfileId).isNull()
        assertThat(viewModel.uiState.value.loginSuccess).isFalse()
        assertThat(viewModel.uiState.value.onboardingCompletion)
            .isEqualTo(ProviderSetupViewModel.OnboardingCompletion.SAVED_RESUMING)
        assertThat(viewModel.uiState.value.completionWarning).contains("initial sync failed")
    }

    @Test
    fun `stalker source defaults epg sync mode to background when user has not customized it`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.STALKER)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.epgSyncMode).isEqualTo(ProviderEpgSyncMode.BACKGROUND)
        assertThat(viewModel.uiState.value.hasCustomizedEpgSyncMode).isFalse()
    }

    @Test
    fun `xtream source defaults epg sync mode to background when user has not customized it`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.XTREAM)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.epgSyncMode).isEqualTo(ProviderEpgSyncMode.BACKGROUND)
        assertThat(viewModel.uiState.value.hasCustomizedEpgSyncMode).isFalse()
    }

    @Test
    fun `m3u source defaults epg sync mode to background when user has not customized it`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.M3U)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.epgSyncMode).isEqualTo(ProviderEpgSyncMode.BACKGROUND)
        assertThat(viewModel.uiState.value.hasCustomizedEpgSyncMode).isFalse()
    }

    @Test
    fun `source defaults do not override customized epg sync mode`() = runTest {
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        viewModel.updateEpgSyncMode(ProviderEpgSyncMode.SKIP)
        viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.STALKER)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.epgSyncMode).isEqualTo(ProviderEpgSyncMode.SKIP)
        assertThat(viewModel.uiState.value.hasCustomizedEpgSyncMode).isTrue()
    }

    @Test
    fun `editing m3u provider while combined source is active does not re-prompt for combined attach`() = runTest {
        val editedProvider = Provider(
            id = 7L,
            name = "Playlist 7",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            m3uUrl = "https://example.com/list.m3u"
        )
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            flowOf(ActiveLiveSource.CombinedM3uSource(44L))
        )
        whenever(validateAndAddProvider.addM3u(any(), any())).thenReturn(
            ValidateAndAddProviderResult.Success(editedProvider)
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        // Simulate being in edit mode for provider 7.
        val field = ProviderSetupViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ProviderSetupState>
        stateFlow.value = stateFlow.value.copy(isEditing = true, existingProviderId = 7L)

        viewModel.addM3u("https://example.com/list.m3u", "Playlist 7", "", "")
        advanceUntilIdle()

        // Edit flows must complete directly without the combined-attach dialog.
        assertThat(viewModel.uiState.value.pendingCombinedAttachProfileId).isNull()
        assertThat(viewModel.uiState.value.loginSuccess).isTrue()
        assertThat(viewModel.uiState.value.onboardingCompletion)
            .isEqualTo(ProviderSetupViewModel.OnboardingCompletion.READY)
    }

    @Test
    fun `m3u sync failure error does not include could not validate playlist prefix`() = runTest {
        whenever(validateAndAddProvider.addM3u(any(), any())).thenReturn(
            ValidateAndAddProviderResult.Error(
                message = "Playlist saved, but initial sync failed. The provider was saved and can be retried from Settings: timeout"
            )
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        viewModel.addM3u("https://example.com/list.m3u", "Playlist", "", "")
        advanceUntilIdle()

        val error = viewModel.uiState.value.error
        assertThat(error).doesNotContain("Could not validate playlist")
        assertThat(error).contains("saved")
    }

    @Test
    fun `stalker error maps sync failure to user friendly message`() = runTest {
        whenever(validateAndAddProvider.loginStalker(any(), any())).thenReturn(
            ValidateAndAddProviderResult.Error(
                message = "Provider login succeeded, but initial sync failed. The provider was saved and can be retried from Settings: timeout"
            )
        )

        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        viewModel.loginStalker(
            portalUrl = "https://portal.example.com",
            macAddress = "00:1A:79:12:34:56",
            authMode = StalkerAuthMode.AUTO,
            username = "",
            password = "",
            name = "MAG",
            httpUserAgent = "",
            httpHeaders = "",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        advanceUntilIdle()

        val error = viewModel.uiState.value.error
        assertThat(error).doesNotContain("initial sync failed. The provider was saved")
        assertThat(error).contains("sync failed")
    }

    @Test
    fun `accepting HTTP warning resubmits same Stalker setup with scoped grant`() = runTest {
        val challenge = StalkerTransportChallenge(
            reason = StalkerTransportChallengeReason.CLEARTEXT_HTTP,
            origin = StalkerTransportOrigin("http", "portal.example.com", 80),
            displayHost = "portal.example.com"
        )
        val provider = Provider(
            id = 22L,
            name = "MAG",
            type = ProviderType.STALKER_PORTAL,
            serverUrl = "http://portal.example.com"
        )
        whenever(validateAndAddProvider.loginStalker(any(), any()))
            .thenReturn(
                ValidateAndAddProviderResult.TransportConsentRequired(challenge),
                ValidateAndAddProviderResult.Success(provider)
            )
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        viewModel.loginStalker(
            portalUrl = "http://portal.example.com",
            macAddress = "00:1A:79:12:34:56",
            authMode = StalkerAuthMode.AUTO,
            username = "",
            password = "secret",
            name = "MAG",
            httpUserAgent = "",
            httpHeaders = "",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.stalkerTransportChallenge).isEqualTo(challenge)
        assertThat(viewModel.uiState.value.loginSuccess).isFalse()

        viewModel.acceptStalkerTransportChallenge()
        advanceUntilIdle()

        val commands = argumentCaptor<com.streamvault.domain.usecase.StalkerProviderSetupCommand>()
        verify(validateAndAddProvider, org.mockito.kotlin.times(2))
            .loginStalker(commands.capture(), any())
        assertThat(commands.secondValue.transportGrant?.mode)
            .isEqualTo(StalkerTransportMode.USER_ACCEPTED_HTTP)
        assertThat(commands.secondValue.transportGrant?.origin).isEqualTo(challenge.origin)
        assertThat(viewModel.uiState.value.loginSuccess).isTrue()
        assertThat(viewModel.uiState.value.stalkerTransportChallenge).isNull()
    }

    @Test
    fun `save without verification is offered only after inconclusive result and resubmits command`() = runTest {
        val provider = Provider(
            id = 23L,
            name = "MAG",
            type = ProviderType.STALKER_PORTAL,
            serverUrl = "https://portal.example.com"
        )
        whenever(validateAndAddProvider.loginStalker(any(), any()))
            .thenReturn(
                ValidateAndAddProviderResult.VerificationInconclusive(
                    "Authentication succeeded, but Live TV could not be verified."
                ),
                ValidateAndAddProviderResult.SavedWithWarning(
                    provider,
                    "Provider saved with Live TV verification pending."
                )
            )
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        viewModel.loginStalker(
            portalUrl = "https://portal.example.com",
            macAddress = "00:1A:79:12:34:56",
            authMode = StalkerAuthMode.AUTO,
            username = "",
            password = "secret",
            name = "MAG",
            httpUserAgent = "",
            httpHeaders = "",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.stalkerVerificationInconclusive).isNotNull()
        viewModel.saveStalkerWithoutVerification()
        advanceUntilIdle()

        val commands = argumentCaptor<com.streamvault.domain.usecase.StalkerProviderSetupCommand>()
        verify(validateAndAddProvider, org.mockito.kotlin.times(2))
            .loginStalker(commands.capture(), any())
        assertThat(commands.firstValue.saveWithoutVerification).isFalse()
        assertThat(commands.secondValue.saveWithoutVerification).isTrue()
        assertThat(commands.secondValue.password).isEqualTo("secret")
        assertThat(viewModel.uiState.value.onboardingCompletion)
            .isEqualTo(ProviderSetupViewModel.OnboardingCompletion.SAVED_RESUMING)
        assertThat(viewModel.uiState.value.stalkerVerificationInconclusive).isNull()
    }

    @Test
    fun `repair connection submits explicit broad discovery permission`() = runTest {
        whenever(validateAndAddProvider.loginStalker(any(), any())).thenReturn(
            ValidateAndAddProviderResult.Error("repair fixture")
        )
        val viewModel = ProviderSetupViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            validateAndAddProvider = validateAndAddProvider,
            importBackup = importBackup,
            driveBackupSyncManager = driveBackupSyncManager,
            providerQrPairingManager = providerQrPairingManager,
        )

        viewModel.loginStalker(
            portalUrl = "https://portal.example.com",
            macAddress = "00:1A:79:12:34:56",
            authMode = StalkerAuthMode.AUTO,
            username = "",
            password = "",
            name = "MAG",
            httpUserAgent = "",
            httpHeaders = "",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en",
            repairConnection = true
        )
        advanceUntilIdle()

        val command = argumentCaptor<com.streamvault.domain.usecase.StalkerProviderSetupCommand>()
        verify(validateAndAddProvider).loginStalker(command.capture(), any())
        assertThat(command.firstValue.repairConnection).isTrue()
    }
}
