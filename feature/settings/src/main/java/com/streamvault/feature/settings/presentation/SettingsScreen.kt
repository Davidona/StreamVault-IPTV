package com.streamvault.feature.settings.presentation

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.streamvault.core.ui.components.shell.UiDestination
import com.streamvault.core.ui.device.isTelevisionDevice
import java.io.File
import com.streamvault.core.ui.theme.*
import com.streamvault.feature.settings.R
import com.streamvault.feature.settings.api.SettingsBackupFileCandidate
import com.streamvault.feature.settings.api.SettingsPlatformHost
import com.streamvault.domain.model.LegacyProvider as Provider
import androidx.compose.ui.res.stringResource
import com.streamvault.domain.model.Result
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val backupFileNameFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")

private fun buildBackupFileName(): String =
    "streamvault_backup_${LocalDateTime.now().format(backupFileNameFormatter)}.json"

// Fire OS and some Android TV images do not ship a usable AOSP DocumentsUI.
// TV backup actions therefore use the app-managed local path directly instead
// of launching an external picker that may show "you need an app".
private fun Context.isFireTv(): Boolean =
    packageManager.hasSystemFeature("amazon.hardware.fire_tv")

@Composable
public fun SettingsScreen(
    onNavigate: (String) -> Unit,
    currentRoute: String,
    platformHost: SettingsPlatformHost,
    navigationDestinations: List<UiDestination> = emptyList(),
    onBack: () -> Unit = {},
    onAddProvider: () -> Unit = {},
    onEditProvider: (Provider) -> Unit = {},
    onNavigateToParentalControl: (Long) -> Unit = {},
    initialBackupImportUri: String? = null,
    onCloseApp: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settingsNavFocusRequester = remember { FocusRequester() }
    val settingsSearchFocusRequester = remember { FocusRequester() }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val unknownBackupDate = stringResource(R.string.settings_drive_unknown_backup_date)
    val noLocalBackupsMessage = stringResource(R.string.settings_backup_no_local_files)
    val folderCreateFailedMessage = stringResource(R.string.settings_backup_folder_create_failed)
    val folderReadFailedMessage = stringResource(R.string.settings_backup_folder_read_failed)
    val noFolderBackupsMessage = stringResource(R.string.settings_backup_no_files_in_folder)
    val sharePrepareFailedMessage = stringResource(R.string.settings_backup_share_prepare_failed)
    val shareFailedMessage = stringResource(R.string.settings_backup_share_failed)
    val pickerFreeFailedMessage = stringResource(R.string.settings_backup_picker_free_failed)
    val pickerFreeSavedMessage = stringResource(R.string.settings_backup_picker_free_saved)
    val deletedBackupMessage = stringResource(R.string.settings_backup_deleted)
    val deleteBackupFailedMessage = stringResource(R.string.settings_backup_delete_failed)
    val usbBackupFailedMessage = stringResource(R.string.settings_backup_usb_failed)
    val crashReportShareFailedMessage = stringResource(R.string.settings_crash_report_share_failed)
    val folderPickerUnavailableMessage = stringResource(R.string.settings_backup_folder_picker_unavailable)
    val screenLabels = rememberSettingsScreenLabels(
        uiState = uiState,
        context = context,
        officialBuildStatus = platformHost.officialBuildStatus()
    )
    val dialogState = rememberSettingsScreenDialogState()
    val providerState = rememberSettingsProviderSectionState(dialogState)
    var compactPane by rememberSaveable { mutableStateOf(SettingsCompactPane.CATEGORIES) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var settingsSearchQuery by rememberSaveable { mutableStateOf("") }
    var searchRequestId by rememberSaveable { mutableLongStateOf(0L) }
    var categorySelectionId by rememberSaveable { mutableLongStateOf(0L) }
    var currentSettingsPage by remember { mutableStateOf<SettingsPage?>(null) }
    var searchOriginCategoryId by rememberSaveable { mutableIntStateOf(SettingsCategory.SOURCES.legacyId) }
    var searchOriginPageName by rememberSaveable { mutableStateOf<String?>(null) }
    var searchOriginCompactPane by rememberSaveable { mutableStateOf(SettingsCompactPane.CATEGORIES) }
    var restoredPage by remember { mutableStateOf<SettingsPage?>(null) }
    var restorePageRequestId by rememberSaveable { mutableLongStateOf(0L) }
    var requestedDestination by remember { mutableStateOf<SettingsSearchTarget?>(null) }
    var searchReturnResultId by rememberSaveable { mutableStateOf<String?>(null) }
    val searchResultsListState = rememberLazyListState()
    var returnFocusToSearch by remember { mutableStateOf(false) }
    var searchButtonPlaced by remember { mutableStateOf(false) }
    val searchButtonModifier = Modifier
        .focusRequester(settingsSearchFocusRequester)
        .onGloballyPositioned { searchButtonPlaced = true }

    fun openSettingsSearch() {
        searchOriginCategoryId = dialogState.selectedCategory
        searchOriginPageName = currentSettingsPage?.name
        searchOriginCompactPane = compactPane
        searchButtonPlaced = false
        returnFocusToSearch = false
        searchReturnResultId = null
        searchVisible = true
    }

    fun dismissSettingsSearch() {
        searchVisible = false
        returnFocusToSearch = true
        searchReturnResultId = null
        requestedDestination = null
        dialogState.selectedCategory = searchOriginCategoryId
        compactPane = searchOriginCompactPane
        restoredPage = searchOriginPageName?.let(SettingsPage::valueOf)
        restorePageRequestId += 1L
    }

    fun returnToSearchResults() {
        requestedDestination = null
        searchVisible = true
    }
    var handledInitialBackupImportUri by remember { mutableStateOf<String?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.exportConfig(
                uriString = it.toString(),
                onSuccess = { platformHost.backupFiles.rememberManagedExport(it) },
            )
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.inspectBackup(it.toString()) }
    }

    var pendingImportCandidates by remember { mutableStateOf<List<BackupDialogItem>>(emptyList()) }

    fun restoreBackupFromLocalStorage() {
        val candidates = platformHost.backupFiles.listPickerFreeBackups()
            .map {
                BackupDialogItem(
                    id = it.uri.toString(),
                    title = it.displayName,
                    subtitle = formatBackupTimestamp(
                        it.lastModifiedMs,
                        unknownBackupDate,
                    ),
                )
            }
        when {
            candidates.isEmpty() ->
                viewModel.showUserMessage(noLocalBackupsMessage)
            candidates.size == 1 -> viewModel.inspectBackup(candidates.first().id)
            else -> pendingImportCandidates = candidates
        }
    }

    val exportTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val folder = DocumentFile.fromTreeUri(context, treeUri)
        if (folder == null || !folder.canWrite()) {
            viewModel.showUserMessage(folderCreateFailedMessage)
            return@rememberLauncherForActivityResult
        }
        val newFile = folder.createFile(platformHost.backupFiles.jsonMimeType, buildBackupFileName())
        if (newFile == null) {
            viewModel.showUserMessage(folderCreateFailedMessage)
            return@rememberLauncherForActivityResult
        }
        viewModel.exportConfig(
            uriString = newFile.uri.toString(),
            onSuccess = { platformHost.backupFiles.rememberManagedExport(newFile.uri) },
        )
    }

    val importTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val folder = DocumentFile.fromTreeUri(context, treeUri)
        if (folder == null) {
            viewModel.showUserMessage(folderReadFailedMessage)
            return@rememberLauncherForActivityResult
        }
        val candidates = folder.listFiles()
            .filter { it.isFile && it.name?.endsWith(".json", ignoreCase = true) == true }
            .sortedByDescending { it.lastModified() }
            .map {
                BackupDialogItem(
                    id = it.uri.toString(),
                    title = it.name ?: "backup.json",
                    subtitle = formatBackupTimestamp(
                        it.lastModified(),
                        unknownBackupDate,
                    ),
                )
            }
        when {
            candidates.isEmpty() ->
                viewModel.showUserMessage(noFolderBackupsMessage)
            candidates.size == 1 -> viewModel.inspectBackup(candidates.first().id)
            else -> pendingImportCandidates = candidates
        }
    }

    fun shareBackup() {
        val file = runCatching { platformHost.backupFiles.createShareExportFile() }.getOrNull()
        if (file == null) {
            viewModel.showUserMessage(sharePrepareFailedMessage)
            return
        }
        val uri = platformHost.backupFiles.providerUri(file)
        viewModel.exportConfig(uri.toString()) {
            if (platformHost.shareBackup(uri) is Result.Error) {
                viewModel.showUserMessage(shareFailedMessage)
            }
        }
    }

    fun exportBackupWithoutPicker() {
        val uri = platformHost.backupFiles.createPickerFreeExportUri()
        if (uri == null) {
            viewModel.showUserMessage(pickerFreeFailedMessage)
            return
        }
        viewModel.exportConfig(
            uriString = uri.toString(),
            successMessage = pickerFreeSavedMessage,
            onFinished = { success ->
                val published = platformHost.backupFiles.finishPickerFreeExport(uri, success)
                if (success && !published) {
                    viewModel.showUserMessage(pickerFreeFailedMessage)
                }
            },
        )
    }

    // Fire-Stick-only: app-private folder on a plugged-in USB OTG drive. Null on every other device
    // and when no removable drive is attached, which keeps all USB controls hidden elsewhere.
    val usbStorageDir: File? = remember(platformHost) { platformHost.removableBackupDirectory() }

    var showLocalBackupManager by remember { mutableStateOf(false) }
    var managedLocalBackups by remember { mutableStateOf<List<SettingsBackupFileCandidate>>(emptyList()) }

    fun refreshManagedLocalBackups() {
        val usbCandidates = usbStorageDir
            ?.let { dir ->
                platformHost.backupFiles.listBackups(dir)
                    .filter { it.displayName.startsWith("streamvault_backup_", ignoreCase = true) }
            }
            .orEmpty()
        managedLocalBackups = (platformHost.backupFiles.listManagedBackups() + usbCandidates)
            .distinctBy { it.uri.toString() }
            .sortedByDescending { it.lastModifiedMs }
    }

    fun manageLocalBackups() {
        refreshManagedLocalBackups()
        showLocalBackupManager = true
    }

    fun deleteLocalBackup(candidate: SettingsBackupFileCandidate) {
        if (platformHost.backupFiles.delete(candidate)) {
            refreshManagedLocalBackups()
            viewModel.showUserMessage(deletedBackupMessage)
        } else {
            viewModel.showUserMessage(deleteBackupFailedMessage)
        }
    }

    fun createBackupToUsb() {
        val dir = usbStorageDir ?: return
        val file = runCatching { platformHost.backupFiles.createExportFile(dir) }.getOrNull()
        if (file == null) {
            viewModel.showUserMessage(usbBackupFailedMessage)
            return
        }
        viewModel.exportConfig(Uri.fromFile(file).toString())
    }

    fun restoreBackupFromUsb() {
        val dir = usbStorageDir ?: return
        val candidates = platformHost.backupFiles.listBackups(dir)
            .map {
                BackupDialogItem(
                    id = it.uri.toString(),
                    title = it.displayName,
                    subtitle = formatBackupTimestamp(
                        it.lastModifiedMs,
                        unknownBackupDate,
                    ),
                )
            }
        when {
            candidates.isEmpty() ->
                viewModel.showUserMessage(noFolderBackupsMessage)
            candidates.size == 1 -> viewModel.inspectBackup(candidates.first().id)
            else -> pendingImportCandidates = candidates
        }
    }

    fun shareCrashReport() {
        val result = platformHost.shareCrashReport()
        if (result is Result.Error) {
            viewModel.showUserMessage(result.message.ifBlank {
                crashReportShareFailedMessage
            })
            viewModel.refreshCrashReport()
        }
    }

    val driveSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.completeDriveSignIn(result.data)
    }

    val recordingFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val displayName = DocumentFile.fromTreeUri(context, it)?.name
            viewModel.updateRecordingFolder(it.toString(), displayName)
        }
    }

    val uriHandler = LocalUriHandler.current

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.userMessageShown()
        }
    }

    LaunchedEffect(uiState.recordingItems) {
        dialogState.selectedRecordingId = when {
            uiState.recordingItems.isEmpty() -> null
            dialogState.selectedRecordingId == null -> uiState.recordingItems.first().id
            uiState.recordingItems.any { item -> item.id == dialogState.selectedRecordingId } -> dialogState.selectedRecordingId
            else -> uiState.recordingItems.first().id
        }
    }

    LaunchedEffect(initialBackupImportUri) {
        val uri = initialBackupImportUri?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (handledInitialBackupImportUri == uri) return@LaunchedEffect
        handledInitialBackupImportUri = uri
        dialogState.selectedCategory = 5
        compactPane = SettingsCompactPane.CONTENT
        viewModel.inspectBackup(uri)
    }

    BackHandler {
        if (compactPane == SettingsCompactPane.CONTENT) {
            compactPane = SettingsCompactPane.CATEGORIES
        } else onBack()
    }

    LaunchedEffect(searchVisible, returnFocusToSearch, searchButtonPlaced) {
        if (!searchVisible && returnFocusToSearch && searchButtonPlaced) {
            settingsSearchFocusRequester.requestFocus()
            returnFocusToSearch = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(com.streamvault.core.ui.design.AppColors.Canvas)) {
        if (searchVisible) {
            SettingsSearchSurface(
                query = settingsSearchQuery,
                onQueryChange = { settingsSearchQuery = it },
                onDismiss = ::dismissSettingsSearch,
                listState = searchResultsListState,
                returnResultId = searchReturnResultId,
                unavailableIds = buildSet {
                    if (usbStorageDir == null) {
                        add("recording.usb_storage")
                        add("backup.usb_create")
                        add("backup.usb_restore")
                    }
                    if (uiState.activeProviderId == null) {
                        add("sources.parental_categories")
                        add("privacy.category_protection")
                        add("privacy.category_visibility")
                    }
                    if (uiState.combinedProfiles.isEmpty()) {
                        add("sources.combined.active")
                        add("sources.combined.members")
                        add("sources.combined.delete")
                    }
                    if (uiState.providers.isEmpty()) {
                        add("sources.active_provider")
                        add("sources.edit")
                        add("sources.sync")
                        add("sources.delete")
                        add("guide.assignment")
                        add("guide.policy")
                        add("guide.logo_policy")
                        add("guide.time_shift")
                    }
                    if (uiState.epgSources.isEmpty()) {
                        add("guide.assignment")
                        add("guide.source.enabled")
                        add("guide.source.refresh")
                        add("guide.source.timezone")
                        add("guide.source.delete")
                    }
                },
                disabledExplanations = buildMap {
                    if (!uiState.playerAudioVideoSyncEnabled) {
                        put("playback.av_offset", context.getString(R.string.settings_search_requires_av_sync))
                    }
                    if (!uiState.playerLiveTranslationEnabled) {
                        put("playback.translation_endpoint", context.getString(R.string.settings_search_requires_translation))
                    }
                    if (!uiState.playerLiveClockEnabled) {
                        val explanation = context.getString(R.string.settings_search_requires_live_clock)
                        put("live.clock_position", explanation)
                        put("live.clock_size", explanation)
                        put("live.clock_font", explanation)
                    }
                    if (uiState.liveChannelGroupingMode != com.streamvault.domain.model.LiveChannelGroupingMode.GROUPED) {
                        val explanation = context.getString(R.string.settings_search_requires_grouping)
                        put("live.group_label", explanation)
                        put("live.variant_preference", explanation)
                    }
                    if (uiState.vodDuplicateHandlingMode == com.streamvault.domain.model.VodDuplicateHandlingMode.SHOW_ALL) {
                        put("vod.variant_preference", context.getString(R.string.settings_search_requires_duplicate_grouping))
                    }
                    if (!uiState.autoCheckAppUpdates) {
                        put("about.auto_update_download", context.getString(R.string.settings_search_requires_update_checks))
                    }
                },
                onResultSelected = { result ->
                    dialogState.selectedCategory = result.category.legacyId
                    compactPane = SettingsCompactPane.CONTENT
                    searchRequestId += 1L
                    requestedDestination = SettingsSearchTarget(
                        category = result.category,
                        page = result.page,
                        itemId = result.id,
                        requestId = searchRequestId,
                    )
                    searchReturnResultId = result.id
                    returnFocusToSearch = false
                    searchVisible = false
                },
            )
        } else {
            SettingsAdaptiveLayout(
                compactNavigationVisible = compactPane == SettingsCompactPane.CATEGORIES,
                navigation = { compact ->
                SettingsNavigationRail(
                    selectedCategory = dialogState.selectedCategory,
                    focusRequester = settingsNavFocusRequester,
                    onCategorySelected = {
                        dialogState.selectedCategory = it
                        categorySelectionId += 1L
                        if (compact) {
                            compactPane = SettingsCompactPane.CONTENT
                        }
                    },
                    onBack = onBack,
                    onSearch = ::openSettingsSearch,
                    onExit = onCloseApp,
                    searchModifier = searchButtonModifier,
                    compact = compact
                )
            }) { compact ->
                SettingsContentPane(
                    uiState = uiState,
                    viewModel = viewModel,
                    context = context,
                    appVersionLabel = "${platformHost.buildInfo.versionName} (${platformHost.buildInfo.versionCode})",
                    onCloseApp = onCloseApp,
                    screenLabels = screenLabels,
                    dialogState = dialogState,
                    providerState = providerState,
                    onAddProvider = onAddProvider,
                    onEditProvider = onEditProvider,
                    onNavigateToParentalControl = onNavigateToParentalControl,
                    onChooseRecordingFolder = {
                        try {
                            recordingFolderLauncher.launch(null)
                        } catch (e: ActivityNotFoundException) {
                            viewModel.showUserMessage(
                                folderPickerUnavailableMessage
                            )
                        }
                    },
                    onUseUsbRecordingStorage = usbStorageDir?.let { dir ->
                        { viewModel.useUsbRecordingStorage(File(dir, "recordings").absolutePath) }
                    },
                    onCreateBackupUsb = usbStorageDir?.let { { createBackupToUsb() } },
                    onRestoreBackupUsb = usbStorageDir?.let { { restoreBackupFromUsb() } },
                    onCreateBackup = {
                        if (context.isTelevisionDevice()) {
                            exportBackupWithoutPicker()
                        } else {
                            val onFireTv = context.isFireTv()
                            val primary: () -> Unit = if (onFireTv) {
                                { exportTreeLauncher.launch(null) }
                            } else {
                                { createDocumentLauncher.launch("streamvault_backup.json") }
                            }
                            val fallback: () -> Unit = if (onFireTv) {
                                { createDocumentLauncher.launch("streamvault_backup.json") }
                            } else {
                                { exportTreeLauncher.launch(null) }
                            }
                            try {
                                primary()
                            } catch (e: ActivityNotFoundException) {
                                try {
                                    fallback()
                                } catch (e2: ActivityNotFoundException) {
                                    exportBackupWithoutPicker()
                                }
                            }
                        }
                    },
                    onManageLocalBackups = ::manageLocalBackups,
                    onShareBackup = ::shareBackup,
                    onViewCrashReport = viewModel::viewCrashReport,
                    onShareCrashReport = ::shareCrashReport,
                    onDeleteCrashReport = viewModel::deleteCrashReport,
                    onRestoreBackup = {
                        if (context.isTelevisionDevice()) {
                            restoreBackupFromLocalStorage()
                        } else {
                            val onFireTv = context.isFireTv()
                            val primary: () -> Unit = if (onFireTv) {
                                { importTreeLauncher.launch(null) }
                            } else {
                                {
                                    openDocumentLauncher.launch(
                                        arrayOf("application/json", "text/json", "application/x-json", "application/octet-stream", "*/*")
                                    )
                                }
                            }
                            val fallback: () -> Unit = if (onFireTv) {
                                {
                                    openDocumentLauncher.launch(
                                        arrayOf("application/json", "text/json", "application/x-json", "application/octet-stream", "*/*")
                                    )
                                }
                            } else {
                                { importTreeLauncher.launch(null) }
                            }
                            try {
                                primary()
                            } catch (e: ActivityNotFoundException) {
                                try {
                                    fallback()
                                } catch (e2: ActivityNotFoundException) {
                                    restoreBackupFromLocalStorage()
                                }
                            }
                        }
                    },
                    onDriveSignIn = { viewModel.beginDriveSignIn(driveSignInLauncher) },
                    onDriveSignOut = viewModel::signOutDrive,
                    onDrivePush = viewModel::pushToDrive,
                    onDrivePull = viewModel::pullFromDrive,
                    onOpenUri = uriHandler::openUri,
                    modifier = Modifier.fillMaxSize(),
                    compact = compact,
                    onCategoryBack = if (compact) {
                        {
                            compactPane = SettingsCompactPane.CATEGORIES
                        }
                    } else null,
                    onSearch = if (compact) (::openSettingsSearch) else null,
                    onExit = onCloseApp,
                    searchModifier = searchButtonModifier,
                    requestedDestination = requestedDestination,
                    onSearchResultBack = ::returnToSearchResults,
                    restoredPage = restoredPage,
                    restorePageRequestId = restorePageRequestId,
                    onPageChanged = { currentSettingsPage = it },
                    categorySelectionId = categorySelectionId,
                )
            }
        }

    SettingsScreenOverlays(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        viewModel = viewModel,
        context = context,
        scope = scope,
        dialogState = dialogState,
        recordingBrowserContent = {
            SettingsRecordingBrowserDialog(
                showRecordingBrowserDialog = true,
                uiState = uiState,
                selectedRecordingId = dialogState.selectedRecordingId,
                onSelectedRecordingChange = { dialogState.selectedRecordingId = it },
                onShowRecordingBrowserDialogChange = { dialogState.showRecordingBrowserDialog = it },
                platformHost = platformHost,
                viewModel = viewModel
            )
        },
        modifier = Modifier
    )

    if (pendingImportCandidates.isNotEmpty()) {
        BackupSelectionDialog(
            title = stringResource(R.string.settings_backup_choose_file_title),
            subtitle = stringResource(R.string.settings_restore_subtitle),
            items = pendingImportCandidates,
            emptyMessage = noLocalBackupsMessage,
            onSelect = { uri ->
                pendingImportCandidates = emptyList()
                viewModel.inspectBackup(uri)
            },
            onDismiss = { pendingImportCandidates = emptyList() },
        )
    }

    if (showLocalBackupManager) {
        BackupManagementDialog(
            title = stringResource(R.string.settings_manage_local_backups),
            subtitle = stringResource(R.string.settings_manage_local_backups_subtitle),
            items = managedLocalBackups.map { candidate ->
                BackupDialogItem(
                    id = candidate.uri.toString(),
                    title = candidate.displayName,
                    subtitle = formatLocalBackupDetails(candidate),
                )
            },
            emptyMessage = stringResource(R.string.settings_backup_no_managed_files),
            onDelete = { uri ->
                managedLocalBackups
                    .firstOrNull { it.uri.toString() == uri }
                    ?.let(::deleteLocalBackup)
            },
            onDismiss = { showLocalBackupManager = false },
        )
    }

    if (uiState.driveBackupOptions.isNotEmpty()) {
        BackupSelectionDialog(
            title = stringResource(R.string.settings_drive_choose_backup_title),
            subtitle = stringResource(R.string.settings_drive_choose_backup_subtitle),
            items = uiState.driveBackupOptions.map { snapshot ->
                BackupDialogItem(
                    id = snapshot.id,
                    title = snapshot.fileName,
                    subtitle = formatSnapshotDetails(snapshot),
                )
            },
            emptyMessage = noLocalBackupsMessage,
            onSelect = viewModel::selectDriveBackup,
            onDismiss = viewModel::dismissDriveBackupOptions,
        )
    }

    if (uiState.driveBackupManagementOptions.isNotEmpty()) {
        BackupManagementDialog(
            title = stringResource(R.string.settings_drive_manage_title),
            subtitle = stringResource(R.string.settings_drive_manage_subtitle),
            items = uiState.driveBackupManagementOptions.map { snapshot ->
                BackupDialogItem(
                    id = snapshot.id,
                    title = snapshot.fileName,
                    subtitle = formatSnapshotDetails(snapshot),
                )
            },
            emptyMessage = stringResource(R.string.settings_drive_manage_subtitle),
            isBusy = uiState.driveIsBusy,
            onDelete = viewModel::deleteDriveBackup,
            onDismiss = viewModel::dismissDriveBackupManagement,
        )
    }
}

}

@Composable
internal fun formatLocalBackupDetails(candidate: SettingsBackupFileCandidate): String {
    return formatBackupTimestamp(
        candidate.lastModifiedMs,
        stringResource(R.string.settings_drive_unknown_backup_date),
    )
}

