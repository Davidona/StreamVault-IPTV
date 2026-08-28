package com.streamvault.feature.settings.presentation

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import com.streamvault.core.ui.components.shell.CoreAppScreenScaffold
import com.streamvault.core.ui.components.shell.AppTopBarCloseAction
import com.streamvault.core.ui.components.shell.NavigationChrome
import com.streamvault.core.ui.components.shell.UiDestination
import com.streamvault.core.ui.device.isTelevisionDevice
import java.io.File
import com.streamvault.core.ui.theme.*
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.feature.settings.R
import com.streamvault.feature.settings.api.SettingsBackupFileCandidate
import com.streamvault.feature.settings.api.SettingsPlatformHost
import com.streamvault.domain.model.LegacyProvider as Provider
import androidx.compose.ui.res.stringResource
import com.streamvault.domain.model.Result
import kotlinx.coroutines.delay
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
    onAddProvider: () -> Unit = {},
    onEditProvider: (Provider) -> Unit = {},
    onNavigateToParentalControl: (Long) -> Unit = {},
    initialBackupImportUri: String? = null,
    onCloseApp: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settingsNavFocusRequester = remember { FocusRequester() }

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
        viewModel.inspectBackup(uri)
    }

    LaunchedEffect(currentRoute, dialogState.selectedCategory) {
        delay(80)
            settingsNavFocusRequester.requestFocusSafely(tag = "SettingsScreen", target = "Selected settings section")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CoreAppScreenScaffold(
            currentDestinationId = currentRoute,
            destinations = navigationDestinations,
            onDestinationSelected = { if (!uiState.isSyncing) onNavigate(it) },
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_providers_subtitle),
            navigationChrome = NavigationChrome.TopBar,
            compactHeader = true,
            showScreenHeader = false,
            topBarActions = {
                AppTopBarCloseAction(
                    onClick = onCloseApp,
                    contentDescription = stringResource(R.string.settings_close_app)
                )
            }
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                SettingsNavigationRail(
                    selectedCategory = dialogState.selectedCategory,
                    focusRequester = settingsNavFocusRequester,
                    onCategorySelected = { dialogState.selectedCategory = it }
                )

                // Thin vertical separator
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.07f))
                )

                SettingsContentPane(
                    uiState = uiState,
                    viewModel = viewModel,
                    context = context,
                    appVersionLabel = "${platformHost.buildInfo.versionName} (${platformHost.buildInfo.versionCode})",
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
                    modifier = Modifier.weight(1f)
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

