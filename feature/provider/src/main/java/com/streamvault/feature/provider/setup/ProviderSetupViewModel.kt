package com.streamvault.app.ui.screens.provider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.pairing.ProviderQrPairingManager
import com.streamvault.app.pairing.ProviderQrPairingState
import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.data.remote.xtream.XtreamNetworkException
import com.streamvault.data.remote.xtream.XtreamParsingException
import com.streamvault.data.remote.xtream.XtreamRequestException
import com.streamvault.data.remote.xtream.XtreamResponseTooLargeException
import com.streamvault.data.security.CredentialDecryptionException
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupRestoreOutcome
import com.streamvault.domain.manager.DriveAuthState
import com.streamvault.domain.manager.DriveBackupSnapshot
import com.streamvault.domain.manager.DriveBackupSyncManager
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.model.Result as DomainResult
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerCatalogMode
import com.streamvault.domain.model.StalkerCompatibilityProfileIds
import com.streamvault.domain.model.StalkerProtocolPreference
import com.streamvault.domain.model.StalkerTransportChallenge
import com.streamvault.domain.model.StalkerTransportChallengeReason
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.ImportBackupCommand
import com.streamvault.domain.usecase.ImportBackupResult
import com.streamvault.domain.usecase.InspectBackupCommand
import com.streamvault.domain.usecase.InspectBackupResult
import com.streamvault.domain.usecase.JellyfinProviderSetupCommand
import com.streamvault.domain.usecase.JellyfinQuickConnectProviderSetupCommand
import com.streamvault.domain.usecase.M3uProviderSetupCommand
import com.streamvault.domain.usecase.StalkerProviderSetupCommand
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

@HiltViewModel
class ProviderSetupViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val validateAndAddProvider: ValidateAndAddProvider,
    private val importBackup: ImportBackup,
    private val driveBackupSyncManager: DriveBackupSyncManager,
    private val providerQrPairingManager: ProviderQrPairingManager,
) : ViewModel() {

    enum class OnboardingCompletion {
        NONE,
        READY,
        SAVED_RESUMING
    }

    enum class SetupSourceType {
        XTREAM,
        STALKER,
        M3U,
        JELLYFIN
    }

    private val _uiState = MutableStateFlow(ProviderSetupState())
    val uiState: StateFlow<ProviderSetupState> = _uiState.asStateFlow()
    private val _knownLocalM3uUrls = MutableStateFlow<Set<String>>(emptySet())
    val knownLocalM3uUrls: StateFlow<Set<String>> = _knownLocalM3uUrls.asStateFlow()
    val pairingState: StateFlow<ProviderQrPairingState> = providerQrPairingManager.state
    private var jellyfinQuickConnectJob: kotlinx.coroutines.Job? = null
    private var stalkerSetupJob: kotlinx.coroutines.Job? = null
    private var pendingStalkerCommand: StalkerProviderSetupCommand? = null

    init {
        viewModelScope.launch {
            providerRepository.getActiveProvider().collect { provider ->
                if (provider != null) {
                    _uiState.update { it.copy(hasExistingProvider = true) }
                }
            }
        }
        viewModelScope.launch {
            providerRepository.getProviders().collect { providers ->
                _knownLocalM3uUrls.value = providers
                    .mapNotNull { provider ->
                        provider.m3uUrl.takeIf { it.startsWith("file://") }
                    }
                    .toSet()
            }
        }
        viewModelScope.launch {
            driveBackupSyncManager.authState.collect { state ->
                _uiState.update {
                    it.copy(driveSignedIn = state is DriveAuthState.SignedIn)
                }
            }
        }
    }

    fun beginDriveSignIn(launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>) {
        viewModelScope.launch {
            when (val request = driveBackupSyncManager.beginSignIn()) {
                is DomainResult.Success -> {
                    val intent = request.data.intent as? android.content.Intent ?: return@launch
                    runCatching { launcher.launch(intent) }
                }
                is DomainResult.Error -> {
                    _uiState.update {
                        it.copy(error = "Drive sign-in unavailable: ${request.message}")
                    }
                }
                is DomainResult.Loading -> Unit
            }
        }
    }

    fun startPhonePairing() {
        viewModelScope.launch {
            providerQrPairingManager.startPairing()
        }
    }

    fun stopPhonePairing() {
        viewModelScope.launch {
            providerQrPairingManager.stopPairing()
        }
    }

    fun completeDriveSignIn(intentData: android.content.Intent?) {
        viewModelScope.launch {
            when (val signIn = driveBackupSyncManager.completeSignIn(intentData)) {
                is DomainResult.Success -> Unit
                is DomainResult.Error -> {
                    _uiState.update {
                        it.copy(error = "Drive sign-in failed: ${signIn.message}")
                    }
                }
                is DomainResult.Loading -> Unit
            }
        }
    }

    fun importBackupFromDrive() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImportingBackup = true,
                    driveBackupOptions = emptyList(),
                    syncProgress = "Downloading from Drive...",
                    validationError = null,
                    error = null,
                    pendingDriveCredentials = null
                )
            }
            when (val listResult = driveBackupSyncManager.listBackups()) {
                is DomainResult.Success -> {
                    if (listResult.data.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                isImportingBackup = false,
                                syncProgress = null,
                                pendingDriveCredentials = null,
                                error = "Drive pull failed: no backups found",
                            )
                        }
                    } else if (listResult.data.size > 1) {
                        _uiState.update {
                            it.copy(
                                isImportingBackup = false,
                                syncProgress = null,
                                driveBackupOptions = listResult.data,
                            )
                        }
                    } else {
                        downloadBackupFromDrive(listResult.data.single().id)
                    }
                }
                is DomainResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isImportingBackup = false,
                            syncProgress = null,
                            driveBackupOptions = emptyList(),
                            pendingDriveCredentials = null,
                            error = "Drive pull failed: ${listResult.message}"
                        )
                    }
                }
                is DomainResult.Loading -> Unit
            }
        }
    }

    fun selectDriveBackup(snapshotId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImportingBackup = true,
                    driveBackupOptions = emptyList(),
                    syncProgress = "Downloading from Drive...",
                    validationError = null,
                    error = null,
                    pendingDriveCredentials = null,
                )
            }
            downloadBackupFromDrive(snapshotId)
        }
    }

    fun dismissDriveBackupOptions() {
        _uiState.update { it.copy(driveBackupOptions = emptyList()) }
    }

    private suspend fun downloadBackupFromDrive(snapshotId: String) {
        when (val pullResult = driveBackupSyncManager.pullBackup(snapshotId)) {
            is DomainResult.Success -> {
                    // New bundles carry credentials with the exact backup
                    // snapshot. Legacy standalone backups fall back to the
                    // old companion credentials file.
                    val credentials = pullResult.data.credentials
                        ?: (driveBackupSyncManager.pullCredentials() as? DomainResult.Success)?.data
                    _uiState.update {
                        it.copy(
                            isImportingBackup = false,
                            driveBackupOptions = emptyList(),
                            pendingDriveCredentials = credentials,
                        )
                    }
                    inspectBackup(pullResult.data.localUriString, preserveDriveCredentials = true)
                }
                is DomainResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isImportingBackup = false,
                            syncProgress = null,
                            driveBackupOptions = emptyList(),
                            pendingDriveCredentials = null,
                            error = "Drive pull failed: ${pullResult.message}"
                        )
                    }
                }
                is DomainResult.Loading -> Unit
            }
    }

    private suspend fun applyPendingDriveCredentials() {
        val pending = _uiState.value.pendingDriveCredentials.orEmpty()
        if (pending.isEmpty()) return
        pending.forEach { cred ->
            providerRepository.updateProviderPassword(
                serverUrl = cred.serverUrl,
                username = cred.username,
                cleartextPassword = cred.password,
            )
        }
        _uiState.update { it.copy(pendingDriveCredentials = null) }
    }

    fun loadProvider(id: Long) {
        viewModelScope.launch {
            val provider = providerRepository.getProvider(id)
            if (provider != null) {
                _uiState.update {
                    it.copy(
                        isEditing = true,
                        existingProviderId = id,
                        name = provider.name,
                        serverUrl = provider.serverUrl,
                        username = provider.username,
                        password = "",
                        m3uUrl = provider.m3uUrl,
                        httpUserAgent = provider.httpUserAgent,
                        httpHeaders = provider.httpHeaders,
                        stalkerMacAddress = provider.stalkerMacAddress,
                        stalkerAuthMode = provider.stalkerAuthMode,
                        stalkerDeviceProfile = provider.stalkerDeviceProfile,
                        stalkerDeviceTimezone = provider.stalkerDeviceTimezone,
                        stalkerDeviceLocale = provider.stalkerDeviceLocale,
                        stalkerSerialNumber = provider.stalkerSerialNumber,
                        stalkerDeviceId = provider.stalkerDeviceId,
                        stalkerDeviceId2 = provider.stalkerDeviceId2,
                        stalkerSignature = provider.stalkerSignature,
                        stalkerAdvancedOptionsJson = provider.stalkerAdvancedOptionsJson,
                        stalkerProtocolPreference = provider.stalkerProtocolPreference
                            .takeUnless { it == StalkerProtocolPreference.MINISTRA_API_V3 }
                            ?: StalkerProtocolPreference.CLASSIC_MAG,
                        stalkerRequestedProfileId = provider.stalkerRequestedProfileId,
                        epgSyncMode = provider.epgSyncMode,
                        stalkerCatalogMode = provider.stalkerCatalogMode,
                        xtreamLiveSyncMode = provider.xtreamLiveSyncMode,
                        guideSourcePolicy = provider.guideSourcePolicy,
                        channelLogoSourcePolicy = provider.channelLogoSourcePolicy,
                        hasCustomizedEpgSyncMode = true,
                        m3uVodClassificationEnabled = provider.m3uVodClassificationEnabled,
                        selectedTab = when (provider.type) {
                            ProviderType.XTREAM_CODES -> 0
                            ProviderType.STALKER_PORTAL -> 1
                            ProviderType.M3U -> 2
                            ProviderType.JELLYFIN -> 3
                        },
                        m3uTab = if (provider.m3uUrl.startsWith("file://")) 1 else 0
                    )
                }
            }
        }
    }

    fun updateM3uTab(tab: Int) {
        _uiState.update { it.copy(m3uTab = tab) }
    }

    fun updateM3uVodClassificationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(m3uVodClassificationEnabled = enabled) }
    }

    fun updateEpgSyncMode(mode: ProviderEpgSyncMode) {
        _uiState.update { it.copy(epgSyncMode = mode, hasCustomizedEpgSyncMode = true) }
    }

    fun updateStalkerCatalogMode(mode: StalkerCatalogMode) {
        _uiState.update { it.copy(stalkerCatalogMode = mode) }
    }

    fun updateStalkerProtocolPreference(preference: StalkerProtocolPreference) {
        if (preference == StalkerProtocolPreference.MINISTRA_API_V3) return
        _uiState.update { it.copy(stalkerProtocolPreference = preference) }
    }

    fun updateStalkerRequestedProfile(profileId: String) {
        _uiState.update { it.copy(stalkerRequestedProfileId = profileId) }
    }

    fun updateXtreamLiveSyncMode(mode: ProviderXtreamLiveSyncMode) {
        _uiState.update { it.copy(xtreamLiveSyncMode = mode) }
    }

    fun updateGuideSourcePolicy(policy: GuideSourcePolicy) {
        _uiState.update { it.copy(guideSourcePolicy = policy) }
    }

    fun updateChannelLogoSourcePolicy(policy: ChannelLogoSourcePolicy) {
        _uiState.update { it.copy(channelLogoSourcePolicy = policy) }
    }

    fun applySourceDefaults(sourceType: SetupSourceType) {
        _uiState.update { current ->
            if (current.isEditing || current.hasCustomizedEpgSyncMode) {
                current
            } else {
                current.copy(
                    epgSyncMode = defaultEpgSyncModeFor(sourceType)
                )
            }
        }
    }

    fun loginStalker(
        portalUrl: String,
        macAddress: String,
        authMode: StalkerAuthMode,
        username: String,
        password: String,
        name: String,
        httpUserAgent: String,
        httpHeaders: String,
        deviceProfile: String,
        timezone: String,
        locale: String,
        serialNumber: String = "",
        deviceId: String = "",
        deviceId2: String = "",
        signature: String = "",
        stalkerAdvancedOptionsJson: String = "",
        protocolPreference: StalkerProtocolPreference = StalkerProtocolPreference.AUTO,
        requestedProfileId: String = StalkerCompatibilityProfileIds.AUTO,
        repairConnection: Boolean = false
    ) {
        val current = _uiState.value
        submitStalker(
            StalkerProviderSetupCommand(
                portalUrl = portalUrl,
                macAddress = macAddress,
                authMode = authMode,
                username = username,
                password = password,
                name = name,
                httpUserAgent = httpUserAgent,
                httpHeaders = httpHeaders,
                deviceProfile = deviceProfile,
                timezone = timezone,
                locale = locale,
                serialNumber = serialNumber,
                deviceId = deviceId,
                deviceId2 = deviceId2,
                signature = signature,
                stalkerAdvancedOptionsJson = stalkerAdvancedOptionsJson,
                protocolPreference = protocolPreference,
                repairConnection = repairConnection,
                requestedProfileId = requestedProfileId,
                epgSyncMode = current.epgSyncMode,
                catalogMode = current.stalkerCatalogMode,
                guideSourcePolicy = current.guideSourcePolicy,
                channelLogoSourcePolicy = current.channelLogoSourcePolicy,
                existingProviderId = current.existingProviderId.takeIf { current.isEditing }
            )
        )
    }

    fun acceptStalkerTransportChallenge() {
        val challenge = _uiState.value.stalkerTransportChallenge ?: return
        val command = pendingStalkerCommand ?: return
        if (challenge.reason == StalkerTransportChallengeReason.INVALID_TLS &&
            challenge.proposedSpkiSha256.isNullOrBlank()
        ) {
            _uiState.update {
                it.copy(
                    stalkerTransportChallenge = null,
                    error = "The provider certificate could not be read safely. Check the address and try again."
                )
            }
            pendingStalkerCommand = null
            return
        }
        _uiState.update { it.copy(stalkerTransportChallenge = null) }
        pendingStalkerCommand = null
        submitStalker(command.copy(transportGrant = challenge.acceptedGrant()))
    }

    fun dismissStalkerTransportChallenge() {
        pendingStalkerCommand = null
        _uiState.update {
            it.copy(
                stalkerTransportChallenge = null,
                isLoading = false,
                syncProgress = null
            )
        }
    }

    fun saveStalkerWithoutVerification() {
        val command = pendingStalkerCommand ?: return
        pendingStalkerCommand = null
        _uiState.update { it.copy(stalkerVerificationInconclusive = null) }
        submitStalker(command.copy(saveWithoutVerification = true))
    }

    fun dismissStalkerVerificationInconclusive() {
        pendingStalkerCommand = null
        _uiState.update {
            it.copy(
                stalkerVerificationInconclusive = null,
                isLoading = false,
                syncProgress = null
            )
        }
    }

    fun cancelStalkerSetup() {
        stalkerSetupJob?.cancel()
        stalkerSetupJob = null
        pendingStalkerCommand = null
        _uiState.update {
            it.copy(
                isLoading = false,
                syncProgress = null,
                stalkerTransportChallenge = null,
                stalkerVerificationInconclusive = null
            )
        }
    }

    private fun submitStalker(command: StalkerProviderSetupCommand) {
        _uiState.update {
            it.copy(
                validationError = null,
                error = null,
                completionWarning = null,
                onboardingCompletion = OnboardingCompletion.NONE,
                loginSuccess = false,
                stalkerTransportChallenge = null,
                stalkerVerificationInconclusive = null
            )
        }
        stalkerSetupJob?.cancel()
        stalkerSetupJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, validationError = null, syncProgress = "Connecting...")
            }
            when (val result = validateAndAddProvider.loginStalker(
                command,
                onProgress = { msg -> _uiState.update { it.copy(syncProgress = msg) } }
            )) {
                is ValidateAndAddProviderResult.Success -> {
                    pendingStalkerCommand = null
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = true,
                            onboardingCompletion = OnboardingCompletion.READY,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.SavedWithWarning -> {
                    pendingStalkerCommand = null
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = false,
                            onboardingCompletion = OnboardingCompletion.SAVED_RESUMING,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            completionWarning = result.warning,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.ValidationError -> {
                    pendingStalkerCommand = null
                    _uiState.update {
                        it.copy(isLoading = false, validationError = result.message, error = null, syncProgress = null)
                    }
                }
                is ValidateAndAddProviderResult.TransportConsentRequired -> {
                    pendingStalkerCommand = command
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            syncProgress = null,
                            error = null,
                            validationError = null,
                            stalkerTransportChallenge = result.challenge
                        )
                    }
                }
                is ValidateAndAddProviderResult.VerificationInconclusive -> {
                    pendingStalkerCommand = command
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            syncProgress = null,
                            error = null,
                            validationError = null,
                            stalkerVerificationInconclusive = result.message
                        )
                    }
                }
                is ValidateAndAddProviderResult.Error -> {
                    pendingStalkerCommand = null
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = mapStalkerLoginError(result),
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
            }
        }
    }

    fun loginXtream(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        httpUserAgent: String,
        httpHeaders: String
    ) {
        _uiState.update {
            it.copy(
                validationError = null,
                error = null,
                completionWarning = null,
                onboardingCompletion = OnboardingCompletion.NONE,
                loginSuccess = false
            )
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, validationError = null, syncProgress = "Connecting...") }
            val existingId = if (_uiState.value.isEditing) _uiState.value.existingProviderId else null

            when (val result = validateAndAddProvider.loginXtream(
                XtreamProviderSetupCommand(
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    name = name,
                    httpUserAgent = httpUserAgent,
                    httpHeaders = httpHeaders,
                    xtreamFastSyncEnabled = false,
                    epgSyncMode = _uiState.value.epgSyncMode,
                    xtreamLiveSyncMode = _uiState.value.xtreamLiveSyncMode,
                    guideSourcePolicy = _uiState.value.guideSourcePolicy,
                    channelLogoSourcePolicy = _uiState.value.channelLogoSourcePolicy,
                    existingProviderId = existingId
                ),
                onProgress = { msg -> _uiState.update { it.copy(syncProgress = msg) } }
            )) {
                is ValidateAndAddProviderResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = true,
                            onboardingCompletion = OnboardingCompletion.READY,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.SavedWithWarning -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = false,
                            onboardingCompletion = OnboardingCompletion.SAVED_RESUMING,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            completionWarning = result.warning,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.ValidationError -> {
                    _uiState.update {
                        it.copy(isLoading = false, validationError = result.message, error = null, syncProgress = null)
                    }
                }
                is ValidateAndAddProviderResult.TransportConsentRequired,
                is ValidateAndAddProviderResult.VerificationInconclusive -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "A transport warning was returned for a non-Stalker provider.",
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = mapXtreamLoginError(result),
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
            }
        }
    }

    fun addM3u(url: String, name: String, httpUserAgent: String, httpHeaders: String) {
        _uiState.update {
            it.copy(
                validationError = null,
                error = null,
                completionWarning = null,
                onboardingCompletion = OnboardingCompletion.NONE,
                loginSuccess = false
            )
        }

        if (url.isBlank()) {
            _uiState.update {
                it.copy(validationError = if (_uiState.value.m3uTab == 0) "Please enter M3U URL" else "Please select a file")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, validationError = null, syncProgress = "Validating...") }
            val existingId = if (_uiState.value.isEditing) _uiState.value.existingProviderId else null

            when (val result = validateAndAddProvider.addM3u(
                M3uProviderSetupCommand(
                    url = url,
                    name = name,
                    httpUserAgent = httpUserAgent,
                    httpHeaders = httpHeaders,
                    epgSyncMode = _uiState.value.epgSyncMode,
                    m3uVodClassificationEnabled = _uiState.value.m3uVodClassificationEnabled,
                    guideSourcePolicy = _uiState.value.guideSourcePolicy,
                    channelLogoSourcePolicy = _uiState.value.channelLogoSourcePolicy,
                    existingProviderId = existingId
                ),
                onProgress = { msg -> _uiState.update { it.copy(syncProgress = msg) } }
            )) {
                is ValidateAndAddProviderResult.Success -> {
                    // Only prompt to attach to the active combined profile for newly created
                    // providers; edits should never re-trigger the attach dialog because the
                    // decision was already made when the provider was first onboarded.
                    val activeCombinedProfileId = if (existingId == null) {
                        (combinedM3uRepository.getActiveLiveSource().first()
                            as? ActiveLiveSource.CombinedM3uSource)?.profileId
                    } else {
                        null
                    }
                    val activeCombinedProfileName = activeCombinedProfileId?.let { profileId ->
                        combinedM3uRepository.getProfile(profileId)?.name
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = activeCombinedProfileId == null,
                            onboardingCompletion = OnboardingCompletion.READY,
                            createdProviderId = result.provider.id,
                            createdProviderName = result.provider.name,
                            pendingCombinedAttachProfileId = activeCombinedProfileId,
                            pendingCombinedAttachProfileName = activeCombinedProfileName,
                            error = null,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.SavedWithWarning -> {
                    // Same combined-attach guard: only for new providers, not edits.
                    val activeCombinedProfileId = if (existingId == null) {
                        (combinedM3uRepository.getActiveLiveSource().first()
                            as? ActiveLiveSource.CombinedM3uSource)?.profileId
                    } else {
                        null
                    }
                    val activeCombinedProfileName = activeCombinedProfileId?.let { profileId ->
                        combinedM3uRepository.getProfile(profileId)?.name
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = false,
                            onboardingCompletion = OnboardingCompletion.SAVED_RESUMING,
                            createdProviderId = result.provider.id,
                            createdProviderName = result.provider.name,
                            pendingCombinedAttachProfileId = activeCombinedProfileId,
                            pendingCombinedAttachProfileName = activeCombinedProfileName,
                            error = null,
                            validationError = null,
                            completionWarning = result.warning,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.ValidationError -> {
                    _uiState.update {
                        it.copy(isLoading = false, validationError = result.message, error = null, syncProgress = null)
                    }
                }
                is ValidateAndAddProviderResult.TransportConsentRequired,
                is ValidateAndAddProviderResult.VerificationInconclusive -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "A transport warning was returned for a non-Stalker provider.",
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = mapM3uSetupError(result),
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
            }
        }
    }

    fun loginJellyfin(
        serverUrl: String,
        username: String,
        password: String,
        name: String
    ) {
        _uiState.update {
            it.copy(
                validationError = null,
                error = null,
                completionWarning = null,
                onboardingCompletion = OnboardingCompletion.NONE,
                loginSuccess = false
            )
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, validationError = null, syncProgress = "Connecting...") }
            val existingId = if (_uiState.value.isEditing) _uiState.value.existingProviderId else null

            when (val result = validateAndAddProvider.loginJellyfin(
                JellyfinProviderSetupCommand(
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    name = name,
                    existingProviderId = existingId
                ),
                onProgress = { msg -> _uiState.update { it.copy(syncProgress = msg) } }
            )) {
                is ValidateAndAddProviderResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = true,
                            onboardingCompletion = OnboardingCompletion.READY,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.SavedWithWarning -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = false,
                            onboardingCompletion = OnboardingCompletion.SAVED_RESUMING,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            completionWarning = result.warning,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.ValidationError -> {
                    _uiState.update {
                        it.copy(isLoading = false, validationError = result.message, error = null, syncProgress = null)
                    }
                }
                is ValidateAndAddProviderResult.TransportConsentRequired,
                is ValidateAndAddProviderResult.VerificationInconclusive -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "A transport warning was returned for a non-Stalker provider.",
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = mapJellyfinLoginError(result),
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
            }
        }
    }

    fun loginJellyfinQuickConnect(
        serverUrl: String,
        name: String
    ) {
        _uiState.update {
            it.copy(
                validationError = null,
                error = null,
                completionWarning = null,
                onboardingCompletion = OnboardingCompletion.NONE,
                loginSuccess = false
            )
        }

        jellyfinQuickConnectJob?.cancel()
        jellyfinQuickConnectJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, validationError = null, syncProgress = "Connecting...") }
            val existingId = if (_uiState.value.isEditing) _uiState.value.existingProviderId else null

            when (val result = validateAndAddProvider.loginJellyfinQuickConnect(
                JellyfinQuickConnectProviderSetupCommand(
                    serverUrl = serverUrl,
                    name = name,
                    existingProviderId = existingId
                ),
                onCode = { code ->
                    _uiState.update {
                        it.copy(jellyfinQuickConnectCode = code, syncProgress = "Waiting for approval on your Jellyfin server...", isLoading = false)
                    }
                },
                onProgress = { msg -> _uiState.update { it.copy(syncProgress = msg) } }
            )) {
                is ValidateAndAddProviderResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = true,
                            onboardingCompletion = OnboardingCompletion.READY,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.SavedWithWarning -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loginSuccess = false,
                            onboardingCompletion = OnboardingCompletion.SAVED_RESUMING,
                            createdProviderId = result.provider.id,
                            error = null,
                            validationError = null,
                            completionWarning = result.warning,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.ValidationError -> {
                    _uiState.update {
                        it.copy(isLoading = false, validationError = result.message, error = null, syncProgress = null)
                    }
                }
                is ValidateAndAddProviderResult.TransportConsentRequired,
                is ValidateAndAddProviderResult.VerificationInconclusive -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "A transport warning was returned for a non-Stalker provider.",
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
                is ValidateAndAddProviderResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = mapJellyfinLoginError(result),
                            validationError = null,
                            syncProgress = null
                        )
                    }
                }
            }
        }
    }

    fun cancelJellyfinQuickConnect() {
        jellyfinQuickConnectJob?.cancel()
        jellyfinQuickConnectJob = null
        _uiState.update {
            it.copy(
                syncProgress = null,
                jellyfinQuickConnectCode = "",
                isLoading = false,
                error = null,
                validationError = null
            )
        }
    }

    fun inspectBackup(uriString: String, preserveDriveCredentials: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    syncProgress = "Reading backup...",
                    validationError = null,
                    error = null,
                    pendingDriveCredentials = if (preserveDriveCredentials) {
                        it.pendingDriveCredentials
                    } else {
                        null
                    }
                )
            }
            val result = importBackup.inspect(InspectBackupCommand(uriString))
            _uiState.update { state ->
                when (result) {
                    is InspectBackupResult.Error -> state.copy(
                        syncProgress = null,
                        pendingDriveCredentials = null,
                        error = "Import failed: ${result.message}"
                    )

                    is InspectBackupResult.Success -> state.copy(
                        syncProgress = null,
                        pendingBackupUri = result.uriString,
                        backupPreview = result.preview,
                        backupImportPlan = result.defaultPlan
                    )
                }
            }
        }
    }

    fun dismissBackupPreview() {
        _uiState.update {
            it.copy(
                backupPreview = null,
                pendingBackupUri = null,
                backupImportPlan = BackupImportPlan(),
                pendingDriveCredentials = null
            )
        }
    }

    fun setBackupConflictStrategy(strategy: BackupConflictStrategy) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(conflictStrategy = strategy)) }
    }

    fun setImportPreferences(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importPreferences = enabled)) }
    }

    fun setImportProviders(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importProviders = enabled)) }
    }

    fun setImportSavedLibrary(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importSavedLibrary = enabled)) }
    }

    fun setImportPlaybackHistory(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importPlaybackHistory = enabled)) }
    }

    fun setImportMultiViewPresets(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importMultiViewPresets = enabled)) }
    }

    fun setImportRecordingSchedules(enabled: Boolean) {
        _uiState.update { it.copy(backupImportPlan = it.backupImportPlan.copy(importRecordingSchedules = enabled)) }
    }

    fun confirmBackupImport() {
        var capturedUri: String? = null
        var capturedPlan: BackupImportPlan? = null
        _uiState.update { state ->
            if (state.isImportingBackup || state.pendingBackupUri == null) return@update state
            val plan = state.backupImportPlan
            if (!plan.importPreferences && !plan.importProviders && !plan.importSavedLibrary &&
                !plan.importPlaybackHistory && !plan.importMultiViewPresets && !plan.importRecordingSchedules
            ) {
                return@update state.copy(error = "Select at least one section to import")
            }
            capturedUri = state.pendingBackupUri
            capturedPlan = plan
            state.copy(
                isImportingBackup = true,
                syncProgress = null,
                validationError = null,
                error = null
            )
        }
        val uriString = capturedUri ?: return
        val plan = capturedPlan ?: return
        viewModelScope.launch {
            val result = importBackup.confirm(ImportBackupCommand(uriString, plan))
            val completed = (result as? ImportBackupResult.Success)
                ?.result
                ?.outcome == BackupRestoreOutcome.COMPLETE
            if (completed) {
                applyPendingDriveCredentials()
            }
            val hasProviders = if (result is ImportBackupResult.Success) {
                providerRepository.getProviders().first().isNotEmpty()
            } else {
                false
            }
            _uiState.update { state ->
                state.copy(
                    isImportingBackup = false,
                    syncProgress = null,
                    backupPreview = if (completed) null else state.backupPreview,
                    pendingBackupUri = if (completed) null else state.pendingBackupUri,
                    backupImportPlan = if (completed) BackupImportPlan() else state.backupImportPlan,
                    backupImportSuccess = hasProviders,
                    error = if (result is ImportBackupResult.Error) {
                        "Import failed: ${result.message}"
                    } else if (!hasProviders) {
                        "Backup imported, but it did not add any providers."
                    } else {
                        null
                    }
                )
            }
        }
    }

    fun attachCreatedProviderToCombined() {
        val profileId = _uiState.value.pendingCombinedAttachProfileId ?: return
        val providerId = _uiState.value.createdProviderId ?: return
        viewModelScope.launch {
            combinedM3uRepository.addProvider(profileId, providerId)
            combinedM3uRepository.setActiveLiveSource(ActiveLiveSource.CombinedM3uSource(profileId))
            _uiState.update {
                it.copy(
                    pendingCombinedAttachProfileId = null,
                    pendingCombinedAttachProfileName = null,
                    loginSuccess = it.onboardingCompletion == OnboardingCompletion.READY
                )
            }
        }
    }

    fun skipCreatedProviderCombinedAttach() {
        _uiState.update {
            it.copy(
                pendingCombinedAttachProfileId = null,
                pendingCombinedAttachProfileName = null,
                loginSuccess = it.onboardingCompletion == OnboardingCompletion.READY
            )
        }
    }

    fun dismissCompletionWarning() {
        _uiState.update { it.copy(completionWarning = null) }
    }

    private fun mapXtreamLoginError(result: ValidateAndAddProviderResult.Error): String {
        val failure = result.exception
        return when {
            result.message.startsWith(PROVIDER_LOGIN_SYNC_FAILED_PREFIX, ignoreCase = true) ->
                "Login succeeded, but the initial sync failed while loading the playlist"

            failure.hasCause<CredentialDecryptionException>() ->
                failure.findCause<CredentialDecryptionException>()?.message
                    ?: CredentialDecryptionException.MESSAGE

            failure.hasCause<SSLPeerUnverifiedException>() ||
                failure.hasCause<CertificateException>() ||
                failure.hasCause<SSLException>() ->
                "Secure connection failed - the server's TLS certificate is not trusted on this device"

            failure.hasCause<XtreamAuthenticationException>() ->
                "Login failed - please check your credentials and server URL"

            failure.findCause<XtreamRequestException>()?.statusCode in setOf(403, 408, 429) ->
                "Server is temporarily busy - try syncing again in a moment"

            failure.findCause<XtreamRequestException>()?.statusCode == 401 ->
                "Login failed - please check your credentials and server URL"

            failure.findCause<XtreamRequestException>()?.statusCode in 500..599 ->
                "Server is temporarily busy - try syncing again in a moment"

            failure.hasCause<SocketTimeoutException>() ||
                failure.hasCause<InterruptedIOException>() ||
                failure.hasCause<UnknownHostException>() ||
                failure.hasCause<ConnectException>() ||
                failure.hasCause<NoRouteToHostException>() ||
                failure.hasCause<XtreamNetworkException>() ->
                "Cannot reach server - check your internet connection and server URL"

            failure.hasCause<XtreamResponseTooLargeException>() ->
                "Server returned an unusually large response - try again later or contact the provider"

            failure.hasCause<XtreamParsingException>() ->
                "Server returned unreadable data - verify the provider details and try again"

            else -> result.message
        }
    }

    /**
     * Maps M3U setup errors to user-friendly messages. Handles both the case where the playlist
     * was stored but the initial sync failed (saved-with-error path, distinct from the Xtream
     * sync failure prefix) and delegates to [mapXtreamLoginError] for errors that originated
     * from an auto-converted Xtream playlist URL.
     */
    private fun mapM3uSetupError(result: ValidateAndAddProviderResult.Error): String {
        if (result.message.startsWith(M3U_PLAYLIST_SYNC_FAILED_PREFIX, ignoreCase = true)) {
            return "Playlist saved, but the initial sync failed while loading the content"
        }
        // Auto-converted Xtream playlist URLs go through loginXtream internally, so the
        // same Xtream exception types apply.
        return mapXtreamLoginError(result)
    }

    /**
     * Maps Stalker portal setup errors to user-friendly messages consistent with the
     * Xtream error mapping. The Stalker stack throws [java.io.IOException] for network and
     * portal errors, so the same transport exception checks apply.
     */
    private fun mapStalkerLoginError(result: ValidateAndAddProviderResult.Error): String {
        if (result.message.startsWith(PROVIDER_LOGIN_SYNC_FAILED_PREFIX, ignoreCase = true)) {
            return "Login succeeded, but the initial sync failed while loading the channel list"
        }
        val failure = result.exception
        return when {
            result.message.contains("requires account credentials", ignoreCase = true) ->
                "Portal requires account credentials - switch the Stalker auth mode or add the username and password"

            result.message.contains("partially accepted MAC identity", ignoreCase = true) ->
                "Portal accepted the MAC address, but playback entitlement is incomplete for this session"

            result.message.contains("stricter MAG emulation", ignoreCase = true) ->
                "Portal requires stricter MAG emulation - keep the MAC and advanced device identity fields aligned with the working device"

            result.message.contains("legacy MAG recipe", ignoreCase = true) ->
                "Portal matched a legacy MAG recipe and was retried automatically, but playback still failed"

            result.message.contains("rediscovery attempted", ignoreCase = true) ->
                "The saved Stalker portal recipe failed, and the app already retried discovery automatically"

            result.message.contains("unsupported portal profile", ignoreCase = true) ->
                "Portal authenticated, but this Stalker profile is not supported yet"

            result.message.contains("no working recipe succeeded", ignoreCase = true) ->
                "Portal family was detected, but none of the known Stalker recipes worked for this connection"

            failure.hasCause<CredentialDecryptionException>() ->
                failure.findCause<CredentialDecryptionException>()?.message
                    ?: CredentialDecryptionException.MESSAGE

            failure.hasCause<SSLPeerUnverifiedException>() ||
                failure.hasCause<CertificateException>() ||
                failure.hasCause<SSLException>() ->
                "Secure connection failed - the server's TLS certificate is not trusted on this device"

            failure.hasCause<SocketTimeoutException>() ||
                failure.hasCause<InterruptedIOException>() ||
                failure.hasCause<UnknownHostException>() ||
                failure.hasCause<ConnectException>() ||
                failure.hasCause<NoRouteToHostException>() ->
                "Cannot reach portal - check your internet connection and portal URL"

            else -> result.message
        }
    }


    private inline fun <reified T : Throwable> Throwable?.findCause(): T? {
        return generateSequence(this) { it.cause }
            .filterIsInstance<T>()
            .firstOrNull()
    }

    private inline fun <reified T : Throwable> Throwable?.hasCause(): Boolean =
        findCause<T>() != null

    private fun mapJellyfinLoginError(result: ValidateAndAddProviderResult.Error): String {
        val failure = result.exception
        return when {
            result.message.startsWith(PROVIDER_LOGIN_SYNC_FAILED_PREFIX, ignoreCase = true) ->
                "Login succeeded, but the initial sync failed while loading the Jellyfin library"

            failure.hasCause<CredentialDecryptionException>() ->
                failure.findCause<CredentialDecryptionException>()?.message
                    ?: CredentialDecryptionException.MESSAGE

            failure.hasCause<SSLPeerUnverifiedException>() ||
                failure.hasCause<CertificateException>() ||
                failure.hasCause<SSLException>() ->
                "Secure connection failed - the server's TLS certificate is not trusted on this device"

            failure.hasCause<SocketTimeoutException>() ||
                failure.hasCause<InterruptedIOException>() ||
                failure.hasCause<UnknownHostException>() ||
                failure.hasCause<ConnectException>() ||
                failure.hasCause<NoRouteToHostException>() ->
                "Cannot reach server - check your internet connection and server URL"

            else -> result.message
        }
    }

    private companion object {
        private const val PROVIDER_LOGIN_SYNC_FAILED_PREFIX =
            "Provider login succeeded, but initial sync failed"
        private const val M3U_PLAYLIST_SYNC_FAILED_PREFIX =
            "Playlist saved, but initial sync failed"
    }
}

data class ProviderSetupState(
    val isLoading: Boolean = false,
    val loginSuccess: Boolean = false,
    val onboardingCompletion: ProviderSetupViewModel.OnboardingCompletion = ProviderSetupViewModel.OnboardingCompletion.NONE,
    val backupImportSuccess: Boolean = false,
    val hasExistingProvider: Boolean = false,
    val error: String? = null,
    val validationError: String? = null,
    val completionWarning: String? = null,
    val syncProgress: String? = null,
    val isEditing: Boolean = false,
    val existingProviderId: Long? = null,
    val selectedTab: Int = 0,
    val m3uTab: Int = 0,
    val name: String = "",
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val httpUserAgent: String = "",
    val httpHeaders: String = "",
    val stalkerMacAddress: String = "",
    val stalkerAuthMode: StalkerAuthMode = StalkerAuthMode.AUTO,
    val stalkerDeviceProfile: String = "",
    val stalkerDeviceTimezone: String = "",
    val stalkerDeviceLocale: String = "",
    val stalkerSerialNumber: String = "",
    val stalkerDeviceId: String = "",
    val stalkerDeviceId2: String = "",
    val stalkerSignature: String = "",
    val stalkerAdvancedOptionsJson: String = "",
    val stalkerProtocolPreference: StalkerProtocolPreference = StalkerProtocolPreference.AUTO,
    val stalkerTransportChallenge: StalkerTransportChallenge? = null,
    val stalkerVerificationInconclusive: String? = null,
    val stalkerRequestedProfileId: String = StalkerCompatibilityProfileIds.AUTO,
    val jellyfinQuickConnectCode: String = "",
    val createdProviderId: Long? = null,
    val createdProviderName: String? = null,
    val pendingCombinedAttachProfileId: Long? = null,
    val pendingCombinedAttachProfileName: String? = null,
    val isImportingBackup: Boolean = false,
    val backupPreview: BackupPreview? = null,
    val pendingBackupUri: String? = null,
    val backupImportPlan: BackupImportPlan = BackupImportPlan(),
    val pendingDriveCredentials: List<ProviderCredentials>? = null,
    val driveSignedIn: Boolean = false,
    val driveBackupOptions: List<DriveBackupSnapshot> = emptyList(),
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
    val stalkerCatalogMode: StalkerCatalogMode = StalkerCatalogMode.ON_DEMAND,
    val xtreamLiveSyncMode: ProviderXtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
    val guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
    val channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
    val hasCustomizedEpgSyncMode: Boolean = false,
    val m3uVodClassificationEnabled: Boolean = false
)

private fun defaultEpgSyncModeFor(sourceType: ProviderSetupViewModel.SetupSourceType): ProviderEpgSyncMode = when (sourceType) {
    ProviderSetupViewModel.SetupSourceType.STALKER,
    ProviderSetupViewModel.SetupSourceType.XTREAM,
    ProviderSetupViewModel.SetupSourceType.M3U,
    ProviderSetupViewModel.SetupSourceType.JELLYFIN -> ProviderEpgSyncMode.BACKGROUND
}
