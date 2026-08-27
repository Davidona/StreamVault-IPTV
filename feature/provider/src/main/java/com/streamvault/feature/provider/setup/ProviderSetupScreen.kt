package com.streamvault.feature.provider.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.streamvault.core.ui.interaction.mouseClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator

import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.*
import com.streamvault.feature.provider.R
import com.streamvault.feature.provider.api.ProviderBackupPreviewContent
import com.streamvault.feature.provider.api.ProviderBackupPreviewRequest
import com.streamvault.core.ui.device.rememberIsTelevisionDevice
import com.streamvault.feature.provider.pairing.ProviderQrPairingState
import com.streamvault.feature.provider.pairing.ProviderQrPairingStatus
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.progress.extractProgressFraction
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.components.shell.StatusPill
import com.streamvault.core.ui.theme.*
import com.streamvault.data.remote.stalker.StalkerAdvancedOptions
import com.streamvault.data.remote.stalker.StalkerAdvancedOptionsCodec
import com.streamvault.data.remote.stalker.StalkerCompatibilityRegistry
import com.streamvault.domain.model.StalkerCatalogMode
import com.streamvault.domain.model.StalkerCompatibilityProfileIds
import com.streamvault.domain.model.StalkerProfileVerification
import com.streamvault.domain.model.StalkerProtocolPreference
import com.streamvault.domain.model.StalkerTransportChallengeReason
import com.streamvault.domain.manager.DriveBackupSnapshot
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.BarcodeFormat
import android.graphics.Bitmap
import com.streamvault.data.util.ProviderInputSanitizer
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.usecase.JellyfinProviderSetupCommand
import com.streamvault.domain.usecase.JellyfinQuickConnectProviderSetupCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import android.widget.Toast
import kotlin.coroutines.resume

// ??? Source type ?????????????????????????????????????????????????????????????


// ??? Screen ??????????????????????????????????????????????????????????????????

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderSetupScreen(
    onProviderAdded: () -> Unit,
    onBack: () -> Unit,
    editProviderId: Long? = null,
    initialImportUri: String? = null,
    backupPreviewContent: ProviderBackupPreviewContent,
    viewModel: ProviderSetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val knownLocalM3uUrls by viewModel.knownLocalM3uUrls.collectAsStateWithLifecycle()
    val pairingState by viewModel.pairingState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fileImportFailedMessage = stringResource(R.string.setup_file_import_failed)
    val coroutineScope = rememberCoroutineScope()

    // ?? Local form state ??????????????????????????????????????????????????????
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var m3uUrl by rememberSaveable { mutableStateOf("") }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var httpUserAgent by rememberSaveable { mutableStateOf("") }
    var httpHeaders by rememberSaveable { mutableStateOf("") }
    var stalkerMacAddress by rememberSaveable { mutableStateOf("") }
    var stalkerAuthMode by rememberSaveable { mutableStateOf(StalkerAuthMode.AUTO) }
    var stalkerDeviceProfile by rememberSaveable { mutableStateOf("") }
    var stalkerDeviceTimezone by rememberSaveable { mutableStateOf("") }
    var stalkerDeviceLocale by rememberSaveable { mutableStateOf("") }
    var stalkerSerialNumber by rememberSaveable { mutableStateOf("") }
    var stalkerDeviceId by rememberSaveable { mutableStateOf("") }
    var stalkerDeviceId2 by rememberSaveable { mutableStateOf("") }
    var stalkerSignature by rememberSaveable { mutableStateOf("") }
    var stalkerHwVersion by rememberSaveable { mutableStateOf("") }
    var stalkerApiUserAgent by rememberSaveable { mutableStateOf("") }
    var stalkerPlayerUserAgent by rememberSaveable { mutableStateOf("") }
    var stalkerPlayerHeaders by rememberSaveable { mutableStateOf("") }
    var stalkerXUserAgentLink by rememberSaveable { mutableStateOf(StalkerAdvancedOptions.LINK_ETHERNET) }
    var stalkerProxyEnabled by rememberSaveable { mutableStateOf(false) }
    var stalkerProxyHost by rememberSaveable { mutableStateOf("") }
    var stalkerProxyPort by rememberSaveable { mutableStateOf("") }
    val stalkerRequestRules = remember { mutableStateListOf<StalkerRequestRuleUiState>() }
    var jellyfinQuickConnectCode by rememberSaveable { mutableStateOf("") }
    var fileImportError by rememberSaveable { mutableStateOf<String?>(null) }
    var handledInitialImportUri by rememberSaveable { mutableStateOf<String?>(null) }
    var showDiscardDraftDialog by rememberSaveable { mutableStateOf(false) }
    var showImportOptionsDialog by rememberSaveable { mutableStateOf(false) }

    // ?? File import helper ????????????????????????????????????????????????????
    fun importM3uUri(uri: android.net.Uri) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        fileImportError = fileImportFailedMessage
                    }
                    return@launch
                }
                inputStream.use {
                    var fileName = "Local_Playlist"
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx != -1) {
                                val displayName = c.getString(idx)
                                fileName = if (displayName.contains(".")) displayName.substringBeforeLast(".") else displayName
                            }
                        }
                    }
                    val ext = if (uri.toString().substringBefore('?').lowercase().endsWith(".m3u8")) "m3u8" else "m3u"
                    val outFile = java.io.File(context.filesDir, "m3u_${System.currentTimeMillis()}.$ext")
                    outFile.outputStream().use { out -> inputStream.copyTo(out) }
                    cleanupOldImportedM3uFiles(
                        filesDir = context.filesDir,
                        protectedFileUris = knownLocalM3uUrls + "file://${outFile.absolutePath}",
                        keepLatest = 20
                    )
                    withContext(Dispatchers.Main) {
                        viewModel.updateM3uTab(1)
                        m3uUrl = "file://${outFile.absolutePath}"
                        if (name.isEmpty()) name = ProviderInputSanitizer.sanitizeProviderNameForEditing(fileName)
                        fileImportError = null
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { fileImportError = resolveFileImportError(context, e) }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? -> if (uri != null) importM3uUri(uri) }

    val backupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? -> uri?.let { viewModel.inspectBackup(it.toString()) } }

    val driveSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result -> viewModel.completeDriveSignIn(result.data) }

    // ?? Effects ???????????????????????????????????????????????????????????????
    LaunchedEffect(knownLocalM3uUrls) {
        cleanupOldImportedM3uFilesAsync(context.filesDir, knownLocalM3uUrls, 20)
    }

    LaunchedEffect(initialImportUri) {
        val importUri = initialImportUri?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (handledInitialImportUri == importUri) return@LaunchedEffect
        handledInitialImportUri = importUri
        selectedTab = 2
        viewModel.updateM3uTab(1)
        runCatching { android.net.Uri.parse(importUri) }.getOrNull()?.let(::importM3uUri)
    }

    LaunchedEffect(uiState.backupImportSuccess) {
        if (uiState.backupImportSuccess) {
            onProviderAdded()
        }
    }
    LaunchedEffect(pairingState.status) {
        if (pairingState.status == ProviderQrPairingStatus.COMPLETE) {
            delay(1200)
            onProviderAdded()
        }
    }
    ProviderSetupCompletionLayer(
        uiState = uiState,
        knownLocalM3uUrls = knownLocalM3uUrls,
        selectedM3uUrl = m3uUrl,
        filesDir = context.filesDir,
        onProviderAdded = onProviderAdded,
        onAttachCreatedProvider = viewModel::attachCreatedProviderToCombined,
        onSkipCreatedProviderCombinedAttach = viewModel::skipCreatedProviderCombinedAttach
    )

    LaunchedEffect(editProviderId) {
        if (editProviderId != null) viewModel.loadProvider(editProviderId)
    }

    LaunchedEffect(uiState.isEditing, uiState.existingProviderId) {
        if (uiState.isEditing) {
            selectedTab = uiState.selectedTab
            name = uiState.name
            serverUrl = uiState.serverUrl
            username = uiState.username
            password = uiState.password
            m3uUrl = uiState.m3uUrl
            val isEditingStalker = uiState.selectedTab == 1
            httpUserAgent = if (isEditingStalker) "" else uiState.httpUserAgent
            httpHeaders = uiState.httpHeaders
            stalkerMacAddress = uiState.stalkerMacAddress
            stalkerAuthMode = uiState.stalkerAuthMode
            stalkerDeviceProfile = uiState.stalkerDeviceProfile
            stalkerDeviceTimezone = uiState.stalkerDeviceTimezone
            stalkerDeviceLocale = uiState.stalkerDeviceLocale
            stalkerSerialNumber = uiState.stalkerSerialNumber
            stalkerDeviceId = uiState.stalkerDeviceId
            stalkerDeviceId2 = uiState.stalkerDeviceId2
            stalkerSignature = uiState.stalkerSignature
            val advanced = StalkerAdvancedOptionsCodec.decode(uiState.stalkerAdvancedOptionsJson)
            val legacy = StalkerAdvancedOptionsCodec.decodeLegacyEditFields(uiState.stalkerAdvancedOptionsJson)
            stalkerSerialNumber = uiState.stalkerSerialNumber.ifBlank { legacy.serialNumber }
            stalkerDeviceId = uiState.stalkerDeviceId.ifBlank { legacy.deviceId }
            stalkerDeviceId2 = uiState.stalkerDeviceId2.ifBlank { legacy.deviceId2 }
            stalkerSignature = uiState.stalkerSignature.ifBlank { legacy.signature }
            stalkerHwVersion = advanced.hwVersion.ifBlank { legacy.hwVersion }
            stalkerApiUserAgent = advanced.apiUserAgent.ifBlank {
                legacy.apiUserAgent.ifBlank {
                    if (isEditingStalker) uiState.httpUserAgent else ""
                }
            }
            stalkerPlayerUserAgent = advanced.playerUserAgent.ifBlank { legacy.playerUserAgent }
            stalkerPlayerHeaders = advanced.playerHeaders.ifBlank { legacy.playerHeaders }
            stalkerXUserAgentLink = advanced.normalizedLink.takeIf { advanced != StalkerAdvancedOptions() }
                ?: legacy.xUserAgentLink
            stalkerProxyEnabled = if (advanced.proxyEnabled || advanced.proxyHost.isNotBlank() || advanced.proxyPort != null) {
                advanced.proxyEnabled
            } else {
                legacy.proxyEnabled
            }
            stalkerProxyHost = advanced.proxyHost.ifBlank { legacy.proxyHost }
            stalkerProxyPort = advanced.proxyPort?.toString() ?: legacy.proxyPort?.toString().orEmpty()
            stalkerRequestRules.clear()
            stalkerRequestRules.addAll(advanced.requestRules.map { it.toUiState() })
        }
    }

    fun buildStalkerAdvancedOptionsJson(): String =
        StalkerAdvancedOptionsCodec.encode(
            StalkerAdvancedOptions(
                hwVersion = stalkerHwVersion.trim(),
                apiUserAgent = stalkerApiUserAgent.trim(),
                playerUserAgent = stalkerPlayerUserAgent.trim(),
                playerHeaders = stalkerPlayerHeaders.trim(),
                xUserAgentLink = stalkerXUserAgentLink,
                proxyEnabled = stalkerProxyEnabled,
                proxyHost = stalkerProxyHost.trim(),
                proxyPort = stalkerProxyPort.trim().toIntOrNull(),
                requestRules = stalkerRequestRules.map { it.toRule() }
                    .filter { rule ->
                        rule.action.isNotBlank() || rule.blockRequest || rule.paramOverrides.isNotEmpty()
                    }
            )
        )

    // ?? Derived UI source type ????????????????????????????????????????????????
    val sourceType = when {
        selectedTab == 0 -> SourceType.XTREAM
        selectedTab == 1 -> SourceType.STALKER
        selectedTab == 3 -> SourceType.JELLYFIN
        uiState.m3uTab == 1 -> SourceType.M3U_FILE
        else -> SourceType.M3U_URL
    }

    fun onSourceTypeSelected(type: SourceType) {
        if (uiState.isEditing) return
        when (type) {
            SourceType.XTREAM  -> {
                selectedTab = 0
                viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.XTREAM)
            }
            SourceType.STALKER -> {
                selectedTab = 1
                viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.STALKER)
            }
            SourceType.M3U_URL -> {
                selectedTab = 2
                viewModel.updateM3uTab(0)
                viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.M3U)
            }
            SourceType.M3U_FILE-> {
                selectedTab = 2
                viewModel.updateM3uTab(1)
                viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.M3U)
            }
            SourceType.JELLYFIN -> {
                selectedTab = 3
                viewModel.applySourceDefaults(ProviderSetupViewModel.SetupSourceType.JELLYFIN)
            }
        }
    }

    val hasUnsavedDraft = !uiState.isEditing && name.isBlank() && (
        serverUrl.isNotBlank() ||
            username.isNotBlank() ||
            password.isNotBlank() ||
            httpUserAgent.isNotBlank() ||
            httpHeaders.isNotBlank() ||
            stalkerMacAddress.isNotBlank() ||
            stalkerAuthMode != StalkerAuthMode.AUTO ||
            stalkerDeviceProfile.isNotBlank() ||
            stalkerDeviceTimezone.isNotBlank() ||
            stalkerDeviceLocale.isNotBlank() ||
            stalkerSerialNumber.isNotBlank() ||
            stalkerDeviceId.isNotBlank() ||
            stalkerDeviceId2.isNotBlank() ||
            stalkerSignature.isNotBlank() ||
            stalkerHwVersion.isNotBlank() ||
            stalkerApiUserAgent.isNotBlank() ||
            stalkerPlayerUserAgent.isNotBlank() ||
            stalkerXUserAgentLink != StalkerAdvancedOptions.LINK_ETHERNET ||
            stalkerProxyEnabled ||
            stalkerProxyHost.isNotBlank() ||
            stalkerProxyPort.isNotBlank() ||
            stalkerRequestRules.isNotEmpty() ||
            m3uUrl.isNotBlank()
        )

    BackHandler {
        if (hasUnsavedDraft) {
            showDiscardDraftDialog = true
        } else {
            onBack()
        }
    }

    // ?? Layout ????????????????????????????????????????????????????????????????
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(BackgroundDeep, Background, Surface)))
    ) {
        val isWide = maxWidth >= 700.dp
        val hPad = if (isWide) 24.dp else 16.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = hPad, vertical = 16.dp)
        ) {
            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    SourceTypeSelectorPanel(
                        sourceType = sourceType,
                        isEditing = uiState.isEditing,
                        isEditLabel = if (uiState.isEditing) androidx.compose.ui.res.stringResource(R.string.setup_edit_provider)
                                      else androidx.compose.ui.res.stringResource(R.string.setup_provider_title),
                        onSelect = ::onSourceTypeSelected,
                        onImportClick = { showImportOptionsDialog = true },
                        modifier = Modifier.width(200.dp).fillMaxHeight()
                    )
                    ProviderFormContent(
                        sourceType = sourceType,
                        uiState = uiState,
                        pairingState = pairingState,
                        name = name, onNameChange = { name = ProviderInputSanitizer.sanitizeProviderNameForEditing(it) },
                        serverUrl = serverUrl, onServerUrlChange = { serverUrl = ProviderInputSanitizer.sanitizeUrlForEditing(it) },
                        username = username, onUsernameChange = { username = ProviderInputSanitizer.sanitizeUsernameForEditing(it) },
                        password = password, onPasswordChange = { password = ProviderInputSanitizer.sanitizePasswordForEditing(it) },
                        m3uUrl = m3uUrl, onM3uUrlChange = { m3uUrl = ProviderInputSanitizer.sanitizeUrlForEditing(it) },
                        httpUserAgent = httpUserAgent, onHttpUserAgentChange = { httpUserAgent = ProviderInputSanitizer.sanitizeHttpUserAgentForEditing(it) },
                        httpHeaders = httpHeaders, onHttpHeadersChange = { httpHeaders = ProviderInputSanitizer.sanitizeHttpHeadersForEditing(it) },
                        stalkerMacAddress = stalkerMacAddress, onStalkerMacAddressChange = { stalkerMacAddress = ProviderInputSanitizer.sanitizeMacAddressForEditing(it) },
                        stalkerAuthMode = stalkerAuthMode, onStalkerAuthModeChange = { stalkerAuthMode = it },
                        stalkerDeviceProfile = stalkerDeviceProfile, onStalkerDeviceProfileChange = { stalkerDeviceProfile = ProviderInputSanitizer.sanitizeDeviceProfileForEditing(it) },
                        stalkerDeviceTimezone = stalkerDeviceTimezone, onStalkerDeviceTimezoneChange = { stalkerDeviceTimezone = ProviderInputSanitizer.sanitizeTimezoneForEditing(it) },
                        stalkerDeviceLocale = stalkerDeviceLocale, onStalkerDeviceLocaleChange = { stalkerDeviceLocale = ProviderInputSanitizer.sanitizeLocaleForEditing(it) },
                        stalkerSerialNumber = stalkerSerialNumber, onStalkerSerialNumberChange = { stalkerSerialNumber = ProviderInputSanitizer.sanitizeStalkerSerialForEditing(it) },
                        stalkerDeviceId = stalkerDeviceId, onStalkerDeviceIdChange = { stalkerDeviceId = ProviderInputSanitizer.sanitizeStalkerDeviceIdForEditing(it) },
                        stalkerDeviceId2 = stalkerDeviceId2, onStalkerDeviceId2Change = { stalkerDeviceId2 = ProviderInputSanitizer.sanitizeStalkerDeviceIdForEditing(it) },
                        stalkerSignature = stalkerSignature, onStalkerSignatureChange = { stalkerSignature = ProviderInputSanitizer.sanitizeStalkerSignatureForEditing(it) },
                        stalkerHwVersion = stalkerHwVersion, onStalkerHwVersionChange = { stalkerHwVersion = it },
                        stalkerApiUserAgent = stalkerApiUserAgent, onStalkerApiUserAgentChange = { stalkerApiUserAgent = ProviderInputSanitizer.sanitizeHttpUserAgentForEditing(it) },
                        stalkerPlayerUserAgent = stalkerPlayerUserAgent, onStalkerPlayerUserAgentChange = { stalkerPlayerUserAgent = ProviderInputSanitizer.sanitizeHttpUserAgentForEditing(it) },
                        stalkerPlayerHeaders = stalkerPlayerHeaders, onStalkerPlayerHeadersChange = { stalkerPlayerHeaders = ProviderInputSanitizer.sanitizeHttpHeadersForEditing(it) },
                        stalkerXUserAgentLink = stalkerXUserAgentLink, onStalkerXUserAgentLinkChange = { stalkerXUserAgentLink = it },
                        stalkerProxyEnabled = stalkerProxyEnabled, onStalkerProxyEnabledChange = { stalkerProxyEnabled = it },
                        stalkerProxyHost = stalkerProxyHost, onStalkerProxyHostChange = { stalkerProxyHost = it.trim() },
                        stalkerProxyPort = stalkerProxyPort, onStalkerProxyPortChange = { stalkerProxyPort = it.filter(Char::isDigit).take(5) },
                        stalkerRequestRules = stalkerRequestRules,
                        onAddStalkerRequestRule = { stalkerRequestRules.add(StalkerRequestRuleUiState()) },
                        onUpdateStalkerRequestRule = { index, rule -> if (index in stalkerRequestRules.indices) stalkerRequestRules[index] = rule },
                        onRemoveStalkerRequestRule = { index -> if (index in stalkerRequestRules.indices) stalkerRequestRules.removeAt(index) },
                        fileImportError = fileImportError,
                        onFilePick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        onLoginXtream = { viewModel.loginXtream(serverUrl, username, password, name, httpUserAgent, httpHeaders) },
                        onLoginStalker = { viewModel.loginStalker(serverUrl, stalkerMacAddress, stalkerAuthMode, username, password, name, "", httpHeaders, stalkerDeviceProfile, stalkerDeviceTimezone, stalkerDeviceLocale, stalkerSerialNumber, stalkerDeviceId, stalkerDeviceId2, stalkerSignature, buildStalkerAdvancedOptionsJson(), uiState.stalkerProtocolPreference, uiState.stalkerRequestedProfileId) },
                        onRepairStalker = { viewModel.loginStalker(serverUrl, stalkerMacAddress, stalkerAuthMode, username, password, name, "", httpHeaders, stalkerDeviceProfile, stalkerDeviceTimezone, stalkerDeviceLocale, stalkerSerialNumber, stalkerDeviceId, stalkerDeviceId2, stalkerSignature, buildStalkerAdvancedOptionsJson(), uiState.stalkerProtocolPreference, uiState.stalkerRequestedProfileId, repairConnection = true) },
                        onAddM3u = { viewModel.addM3u(m3uUrl, name, httpUserAgent, httpHeaders) },
                        onLoginJellyfin = { viewModel.loginJellyfin(serverUrl, username, password, name) },
                        quickConnectCode = uiState.jellyfinQuickConnectCode,
                        onQuickConnectRequest = { viewModel.loginJellyfinQuickConnect(serverUrl.trim(), name.trim()) },
                        onStartPhonePairing = viewModel::startPhonePairing,
                        onStopPhonePairing = viewModel::stopPhonePairing,
                        onToggleM3uVodClassification = { viewModel.updateM3uVodClassificationEnabled(!uiState.m3uVodClassificationEnabled) },
                        onSelectEpgSyncMode = viewModel::updateEpgSyncMode,
                        onSelectStalkerCatalogMode = viewModel::updateStalkerCatalogMode,
                        onSelectStalkerProtocolPreference = viewModel::updateStalkerProtocolPreference,
                        onSelectStalkerProfile = viewModel::updateStalkerRequestedProfile,
                        onSelectXtreamLiveSyncMode = viewModel::updateXtreamLiveSyncMode,
                        onSelectGuideSourcePolicy = viewModel::updateGuideSourcePolicy,
                        onSelectChannelLogoSourcePolicy = viewModel::updateChannelLogoSourcePolicy,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SourceTypeTabRow(
                        sourceType = sourceType,
                        isEditing = uiState.isEditing,
                        onSelect = ::onSourceTypeSelected,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ProviderFormContent(
                        sourceType = sourceType,
                        uiState = uiState,
                        pairingState = pairingState,
                        name = name, onNameChange = { name = ProviderInputSanitizer.sanitizeProviderNameForEditing(it) },
                        serverUrl = serverUrl, onServerUrlChange = { serverUrl = ProviderInputSanitizer.sanitizeUrlForEditing(it) },
                        username = username, onUsernameChange = { username = ProviderInputSanitizer.sanitizeUsernameForEditing(it) },
                        password = password, onPasswordChange = { password = ProviderInputSanitizer.sanitizePasswordForEditing(it) },
                        m3uUrl = m3uUrl, onM3uUrlChange = { m3uUrl = ProviderInputSanitizer.sanitizeUrlForEditing(it) },
                        httpUserAgent = httpUserAgent, onHttpUserAgentChange = { httpUserAgent = ProviderInputSanitizer.sanitizeHttpUserAgentForEditing(it) },
                        httpHeaders = httpHeaders, onHttpHeadersChange = { httpHeaders = ProviderInputSanitizer.sanitizeHttpHeadersForEditing(it) },
                        stalkerMacAddress = stalkerMacAddress, onStalkerMacAddressChange = { stalkerMacAddress = ProviderInputSanitizer.sanitizeMacAddressForEditing(it) },
                        stalkerAuthMode = stalkerAuthMode, onStalkerAuthModeChange = { stalkerAuthMode = it },
                        stalkerDeviceProfile = stalkerDeviceProfile, onStalkerDeviceProfileChange = { stalkerDeviceProfile = ProviderInputSanitizer.sanitizeDeviceProfileForEditing(it) },
                        stalkerDeviceTimezone = stalkerDeviceTimezone, onStalkerDeviceTimezoneChange = { stalkerDeviceTimezone = ProviderInputSanitizer.sanitizeTimezoneForEditing(it) },
                        stalkerDeviceLocale = stalkerDeviceLocale, onStalkerDeviceLocaleChange = { stalkerDeviceLocale = ProviderInputSanitizer.sanitizeLocaleForEditing(it) },
                        stalkerSerialNumber = stalkerSerialNumber, onStalkerSerialNumberChange = { stalkerSerialNumber = ProviderInputSanitizer.sanitizeStalkerSerialForEditing(it) },
                        stalkerDeviceId = stalkerDeviceId, onStalkerDeviceIdChange = { stalkerDeviceId = ProviderInputSanitizer.sanitizeStalkerDeviceIdForEditing(it) },
                        stalkerDeviceId2 = stalkerDeviceId2, onStalkerDeviceId2Change = { stalkerDeviceId2 = ProviderInputSanitizer.sanitizeStalkerDeviceIdForEditing(it) },
                        stalkerSignature = stalkerSignature, onStalkerSignatureChange = { stalkerSignature = ProviderInputSanitizer.sanitizeStalkerSignatureForEditing(it) },
                        stalkerHwVersion = stalkerHwVersion, onStalkerHwVersionChange = { stalkerHwVersion = it },
                        stalkerApiUserAgent = stalkerApiUserAgent, onStalkerApiUserAgentChange = { stalkerApiUserAgent = ProviderInputSanitizer.sanitizeHttpUserAgentForEditing(it) },
                        stalkerPlayerUserAgent = stalkerPlayerUserAgent, onStalkerPlayerUserAgentChange = { stalkerPlayerUserAgent = ProviderInputSanitizer.sanitizeHttpUserAgentForEditing(it) },
                        stalkerPlayerHeaders = stalkerPlayerHeaders, onStalkerPlayerHeadersChange = { stalkerPlayerHeaders = ProviderInputSanitizer.sanitizeHttpHeadersForEditing(it) },
                        stalkerXUserAgentLink = stalkerXUserAgentLink, onStalkerXUserAgentLinkChange = { stalkerXUserAgentLink = it },
                        stalkerProxyEnabled = stalkerProxyEnabled, onStalkerProxyEnabledChange = { stalkerProxyEnabled = it },
                        stalkerProxyHost = stalkerProxyHost, onStalkerProxyHostChange = { stalkerProxyHost = it.trim() },
                        stalkerProxyPort = stalkerProxyPort, onStalkerProxyPortChange = { stalkerProxyPort = it.filter(Char::isDigit).take(5) },
                        stalkerRequestRules = stalkerRequestRules,
                        onAddStalkerRequestRule = { stalkerRequestRules.add(StalkerRequestRuleUiState()) },
                        onUpdateStalkerRequestRule = { index, rule -> if (index in stalkerRequestRules.indices) stalkerRequestRules[index] = rule },
                        onRemoveStalkerRequestRule = { index -> if (index in stalkerRequestRules.indices) stalkerRequestRules.removeAt(index) },
                        fileImportError = fileImportError,
                        onFilePick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        onLoginXtream = { viewModel.loginXtream(serverUrl, username, password, name, httpUserAgent, httpHeaders) },
                        onLoginStalker = { viewModel.loginStalker(serverUrl, stalkerMacAddress, stalkerAuthMode, username, password, name, "", httpHeaders, stalkerDeviceProfile, stalkerDeviceTimezone, stalkerDeviceLocale, stalkerSerialNumber, stalkerDeviceId, stalkerDeviceId2, stalkerSignature, buildStalkerAdvancedOptionsJson(), uiState.stalkerProtocolPreference, uiState.stalkerRequestedProfileId) },
                        onRepairStalker = { viewModel.loginStalker(serverUrl, stalkerMacAddress, stalkerAuthMode, username, password, name, "", httpHeaders, stalkerDeviceProfile, stalkerDeviceTimezone, stalkerDeviceLocale, stalkerSerialNumber, stalkerDeviceId, stalkerDeviceId2, stalkerSignature, buildStalkerAdvancedOptionsJson(), uiState.stalkerProtocolPreference, uiState.stalkerRequestedProfileId, repairConnection = true) },
                        onAddM3u = { viewModel.addM3u(m3uUrl, name, httpUserAgent, httpHeaders) },
                        onLoginJellyfin = { viewModel.loginJellyfin(serverUrl, username, password, name) },
                        quickConnectCode = uiState.jellyfinQuickConnectCode,
                        onQuickConnectRequest = { viewModel.loginJellyfinQuickConnect(serverUrl.trim(), name.trim()) },
                        onStartPhonePairing = viewModel::startPhonePairing,
                        onStopPhonePairing = viewModel::stopPhonePairing,
                        onToggleM3uVodClassification = { viewModel.updateM3uVodClassificationEnabled(!uiState.m3uVodClassificationEnabled) },
                        onSelectEpgSyncMode = viewModel::updateEpgSyncMode,
                        onSelectStalkerCatalogMode = viewModel::updateStalkerCatalogMode,
                        onSelectStalkerProtocolPreference = viewModel::updateStalkerProtocolPreference,
                        onSelectStalkerProfile = viewModel::updateStalkerRequestedProfile,
                        onSelectXtreamLiveSyncMode = viewModel::updateXtreamLiveSyncMode,
                        onSelectGuideSourcePolicy = viewModel::updateGuideSourcePolicy,
                        onSelectChannelLogoSourcePolicy = viewModel::updateChannelLogoSourcePolicy,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }

            if (!uiState.isEditing && !isWide) {
                ImportOptionsButton(
                    text = stringResource(R.string.settings_restore_data),
                    onClick = { showImportOptionsDialog = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 6.dp)
                        .zIndex(1f)
                )
            }
        }
    }

    if (uiState.syncProgress != null) {
        SyncProgressDialog(
            message = uiState.syncProgress!!,
            quickConnectCode = if (uiState.jellyfinQuickConnectCode.isNotBlank()) uiState.jellyfinQuickConnectCode else null,
            serverUrl = serverUrl,
            onCancel = when {
                uiState.jellyfinQuickConnectCode.isNotBlank() ->
                    ({ viewModel.cancelJellyfinQuickConnect() })
                uiState.selectedTab == 1 && uiState.isLoading ->
                    ({ viewModel.cancelStalkerSetup() })
                else -> null
            }
        )
    }

    val backupPreview = uiState.backupPreview
    if (backupPreview != null && uiState.pendingBackupUri != null) {
        backupPreviewContent(
            ProviderBackupPreviewRequest(
            preview = backupPreview,
            plan = uiState.backupImportPlan,
            onDismiss = { viewModel.dismissBackupPreview() },
            onStrategySelected = { viewModel.setBackupConflictStrategy(it) },
            onImportPreferencesChanged = { viewModel.setImportPreferences(it) },
            onImportProvidersChanged = { viewModel.setImportProviders(it) },
            onImportSavedLibraryChanged = { viewModel.setImportSavedLibrary(it) },
            onImportPlaybackHistoryChanged = { viewModel.setImportPlaybackHistory(it) },
            onImportMultiViewChanged = { viewModel.setImportMultiViewPresets(it) },
            onImportRecordingSchedulesChanged = { viewModel.setImportRecordingSchedules(it) },
            isImporting = uiState.isImportingBackup,
            onConfirm = { viewModel.confirmBackupImport() }
            )
        )
    }

    if (uiState.driveBackupOptions.isNotEmpty()) {
        DriveBackupSnapshotChoiceDialog(
            snapshots = uiState.driveBackupOptions,
            onSelect = viewModel::selectDriveBackup,
            onDismiss = viewModel::dismissDriveBackupOptions,
        )
    }

uiState.stalkerTransportChallenge?.let { challenge ->
        val (title, body) = when (challenge.reason) {
            StalkerTransportChallengeReason.INVALID_TLS ->
                stringResource(R.string.stalker_transport_invalid_tls_title) to
                    stringResource(
                        R.string.stalker_transport_invalid_tls_body,
                        challenge.displayHost
                    )
            StalkerTransportChallengeReason.CLEARTEXT_HTTP ->
                stringResource(R.string.stalker_transport_http_title) to
                    stringResource(
                        R.string.stalker_transport_http_body,
                        challenge.displayHost
                    )
            StalkerTransportChallengeReason.ORIGIN_CHANGED ->
                stringResource(R.string.stalker_transport_origin_changed_title) to
                    stringResource(
                        R.string.stalker_transport_origin_changed_body,
                        challenge.displayHost
                    )
        }
        PremiumDialog(
            title = title,
            subtitle = body,
            onDismissRequest = viewModel::dismissStalkerTransportChallenge,
            content = {},
            footer = {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.stalker_transport_go_back),
                    onClick = viewModel::dismissStalkerTransportChallenge
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.stalker_transport_connect_anyway),
                    onClick = viewModel::acceptStalkerTransportChallenge,
                    emphasized = true
                )
            }
        )
    }

    uiState.stalkerVerificationInconclusive?.let {
        PremiumDialog(
            title = stringResource(R.string.stalker_verification_inconclusive_title),
            subtitle = stringResource(R.string.stalker_verification_inconclusive_body),
            onDismissRequest = viewModel::dismissStalkerVerificationInconclusive,
            content = {},
            footer = {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.stalker_transport_go_back),
                    onClick = viewModel::dismissStalkerVerificationInconclusive
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.stalker_save_without_verification),
                    onClick = viewModel::saveStalkerWithoutVerification,
                    emphasized = true
                )
            }
        )
    }

    if (showDiscardDraftDialog) {
        PremiumDialog(
            title = stringResource(R.string.setup_discard_draft_title),
            subtitle = stringResource(R.string.setup_discard_draft_body),
            onDismissRequest = { showDiscardDraftDialog = false },
            content = {},
            footer = {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.setup_discard_draft_cancel),
                    onClick = { showDiscardDraftDialog = false }
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.setup_discard_draft_confirm),
                    onClick = {
                        showDiscardDraftDialog = false
                        onBack()
                    },
                    emphasized = true
                )
            }
        )
    }

    if (showImportOptionsDialog) {
        ImportOptionsDialog(
            isImportingBackup = uiState.isImportingBackup || uiState.syncProgress != null,
            driveSignedIn = uiState.driveSignedIn,
            onDismiss = { showImportOptionsDialog = false },
            onImportBackup = {
                showImportOptionsDialog = false
                backupImportLauncher.launch(arrayOf("application/json"))
            },
            onImportFromDrive = {
                showImportOptionsDialog = false
                viewModel.importBackupFromDrive()
            },
            onDriveSignIn = {
                showImportOptionsDialog = false
                viewModel.beginDriveSignIn(driveSignInLauncher)
            }
        )
    }

}


// ??? Form content ?????????????????????????????????????????????????????????????








// ??? Source type selector ׳³ֲ³ײ²ֲ׳²ֲ²ײ²ֲ¿׳²ֲ²ײ²ֲ½ wide layout (left sidebar) ????????????????????????


// ??? Source type row ׳³ֲ³ײ²ֲ׳²ֲ²ײ²ֲ¿׳²ֲ²ײ²ֲ½ narrow layout (top tabs) ???????????????????????????????

// ??? ProviderTextField ????????????????????????????????????????????????????????
//
// Key fix: uses BasicTextField with decorationBox and tracks focus via
// onFocusEvent { it.hasFocus } ׳³ֲ³ײ²ֲ׳²ֲ²ײ²ֲ¿׳²ֲ²ײ²ֲ½ hasFocus is true when this node OR any
// descendant (the actual cursor/text composable) has focus. The old approach
// used onFocusChanged { isFocused } on an outer Box, which became false the
// moment the inner BasicTextField took focus, breaking keyboard scroll.




// ??? TabButton (used by SourceTypeTabRow) ?????????????????????????????????????

// ׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬ Revealing password visual transformation ׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬


// ׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬ File cleanup helpers ׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬׳³ג€™׳’ג‚¬ֲ׳’ג€ֲ¬

// ??? Jellyfin form ?????????????????????????????????????????????????????????????
