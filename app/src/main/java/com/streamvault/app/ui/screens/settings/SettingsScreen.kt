package com.streamvault.app.ui.screens.settings

import com.streamvault.feature.settings.presentation.*

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.streamvault.app.backup.BackupFileBridge
import com.streamvault.app.BuildConfig
import com.streamvault.app.MainActivity
import com.streamvault.app.device.isFireTvDevice
import com.streamvault.app.device.isTelevisionDevice
import com.streamvault.app.device.removableAppStorageDirs
import java.io.File
import com.streamvault.app.diagnostics.CrashReportStore
import com.streamvault.app.util.OfficialBuildVerifier
import com.streamvault.core.ui.components.shell.AppTopBarCloseAction
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.core.ui.theme.*
import com.streamvault.domain.model.LegacyProvider as Provider
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.feature.settings.api.SettingsOfficialBuildStatus
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

private fun Context.findMainActivity(): MainActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is MainActivity) return current
        current = current.baseContext
    }
    return null
}


@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit,
    onAddProvider: () -> Unit = {},
    onEditProvider: (Provider) -> Unit = {},
    onNavigateToParentalControl: (Long) -> Unit = {},
    currentRoute: String,
    initialBackupImportUri: String? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settingsNavFocusRequester = remember { FocusRequester() }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val mainActivity = context.findMainActivity()
    val officialBuildVerification = remember(context.packageName) { OfficialBuildVerifier.verify(context) }
    val screenLabels = rememberSettingsScreenLabels(
        uiState = uiState,
        context = context,
        officialBuildStatus = when (officialBuildVerification.status) {
            com.streamvault.app.util.OfficialBuildStatus.OFFICIAL -> SettingsOfficialBuildStatus.OFFICIAL
            com.streamvault.app.util.OfficialBuildStatus.UNOFFICIAL -> SettingsOfficialBuildStatus.UNOFFICIAL
            com.streamvault.app.util.OfficialBuildStatus.VERIFICATION_UNAVAILABLE ->
                SettingsOfficialBuildStatus.VERIFICATION_UNAVAILABLE
        }
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
                onSuccess = { BackupFileBridge.rememberManagedExport(context, it) },
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
        val candidates = BackupFileBridge.listPickerFreeBackups(context)
            .map {
                BackupDialogItem(
                    id = it.uri.toString(),
                    title = it.displayName,
                    subtitle = formatBackupTimestamp(
                        it.lastModifiedMs,
                        context.getString(R.string.settings_drive_unknown_backup_date),
                    ),
                )
            }
        when {
            candidates.isEmpty() ->
                viewModel.showUserMessage(context.getString(R.string.settings_backup_no_local_files))
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
            viewModel.showUserMessage(context.getString(R.string.settings_backup_folder_create_failed))
            return@rememberLauncherForActivityResult
        }
        val newFile = folder.createFile(BackupFileBridge.MIME_TYPE_JSON, buildBackupFileName())
        if (newFile == null) {
            viewModel.showUserMessage(context.getString(R.string.settings_backup_folder_create_failed))
            return@rememberLauncherForActivityResult
        }
        viewModel.exportConfig(
            uriString = newFile.uri.toString(),
            onSuccess = { BackupFileBridge.rememberManagedExport(context, newFile.uri) },
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
            viewModel.showUserMessage(context.getString(R.string.settings_backup_folder_read_failed))
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
                        context.getString(R.string.settings_drive_unknown_backup_date),
                    ),
                )
            }
        when {
            candidates.isEmpty() ->
                viewModel.showUserMessage(context.getString(R.string.settings_backup_no_files_in_folder))
            candidates.size == 1 -> viewModel.inspectBackup(candidates.first().id)
            else -> pendingImportCandidates = candidates
        }
    }

    fun shareBackup() {
        val file = runCatching { BackupFileBridge.createExportFile(context) }.getOrNull()
        if (file == null) {
            viewModel.showUserMessage(context.getString(R.string.settings_backup_share_prepare_failed))
            return
        }
        val uri = BackupFileBridge.providerUriForFile(context, file)
        viewModel.exportConfig(uri.toString()) {
            runCatching { context.startActivity(BackupFileBridge.buildShareIntent(uri)) }
                .onFailure { viewModel.showUserMessage(context.getString(R.string.settings_backup_share_failed)) }
        }
    }

    fun exportBackupWithoutPicker() {
        val uri = BackupFileBridge.createPickerFreeExportUri(context)
        if (uri == null) {
            viewModel.showUserMessage(context.getString(R.string.settings_backup_picker_free_failed))
            return
        }
        viewModel.exportConfig(
            uriString = uri.toString(),
            successMessage = context.getString(R.string.settings_backup_picker_free_saved),
            onFinished = { success ->
                val published = BackupFileBridge.finishPickerFreeExport(context, uri, success)
                if (success && !published) {
                    viewModel.showUserMessage(context.getString(R.string.settings_backup_picker_free_failed))
                }
            },
        )
    }

    // Fire-Stick-only: app-private folder on a plugged-in USB OTG drive. Null on every other device
    // and when no removable drive is attached, which keeps all USB controls hidden elsewhere.
    val usbStorageDir: File? = remember(context) {
        if (context.isFireTvDevice()) context.removableAppStorageDirs().firstOrNull() else null
    }

    var showLocalBackupManager by remember { mutableStateOf(false) }
    var managedLocalBackups by remember { mutableStateOf<List<BackupFileBridge.BackupFileCandidate>>(emptyList()) }

    fun refreshManagedLocalBackups() {
        val usbCandidates = usbStorageDir
            ?.let { dir ->
                BackupFileBridge.listBackupFiles(dir)
                    .filter { it.name.startsWith("streamvault_backup_", ignoreCase = true) }
                    .map(BackupFileBridge::candidateForFile)
            }
            .orEmpty()
        managedLocalBackups = (BackupFileBridge.listManagedBackups(context) + usbCandidates)
            .distinctBy { it.uri.toString() }
            .sortedByDescending { it.lastModifiedMs }
    }

    fun manageLocalBackups() {
        refreshManagedLocalBackups()
        showLocalBackupManager = true
    }

    fun deleteLocalBackup(candidate: BackupFileBridge.BackupFileCandidate) {
        if (BackupFileBridge.deleteManagedBackup(context, candidate)) {
            refreshManagedLocalBackups()
            viewModel.showUserMessage(context.getString(R.string.settings_backup_deleted))
        } else {
            viewModel.showUserMessage(context.getString(R.string.settings_backup_delete_failed))
        }
    }

    fun createBackupToUsb() {
        val dir = usbStorageDir ?: return
        val file = runCatching { BackupFileBridge.createExportFile(dir) }.getOrNull()
        if (file == null) {
            viewModel.showUserMessage(context.getString(R.string.settings_backup_usb_failed))
            return
        }
        viewModel.exportConfig(Uri.fromFile(file).toString())
    }

    fun restoreBackupFromUsb() {
        val dir = usbStorageDir ?: return
        val candidates = BackupFileBridge.listBackupFiles(dir)
            .map {
                BackupDialogItem(
                    id = Uri.fromFile(it).toString(),
                    title = it.name,
                    subtitle = formatBackupTimestamp(
                        it.lastModified(),
                        context.getString(R.string.settings_drive_unknown_backup_date),
                    ),
                )
            }
        when {
            candidates.isEmpty() ->
                viewModel.showUserMessage(context.getString(R.string.settings_backup_no_files_in_folder))
            candidates.size == 1 -> viewModel.inspectBackup(candidates.first().id)
            else -> pendingImportCandidates = candidates
        }
    }

    fun shareCrashReport() {
        val file = CrashReportStore.latestReportFile(context)
        if (!file.isFile || file.length() <= 0L) {
            viewModel.showUserMessage(context.getString(R.string.settings_crash_report_missing))
            viewModel.refreshCrashReport()
            return
        }
        val uri = CrashReportStore.providerUriForFile(context, file)
        runCatching { context.startActivity(CrashReportStore.buildShareIntent(uri)) }
            .onFailure { viewModel.showUserMessage(context.getString(R.string.settings_crash_report_share_failed)) }
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
        AppScreenScaffold(
            currentRoute = currentRoute,
            onNavigate = { if (!uiState.isSyncing) onNavigate(it) },
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_providers_subtitle),
            navigationChrome = AppNavigationChrome.TopBar,
            compactHeader = true,
            showScreenHeader = false,
            topBarActions = {
                AppTopBarCloseAction(
                    onClick = { mainActivity?.finishAffinity() },
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
                    appVersionLabel = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
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
                                context.getString(R.string.settings_backup_folder_picker_unavailable)
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
                mainActivity = mainActivity,
                currentRoute = currentRoute,
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
            emptyMessage = stringResource(R.string.settings_backup_no_local_files),
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
            emptyMessage = stringResource(R.string.settings_backup_no_local_files),
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
internal fun formatLocalBackupDetails(candidate: BackupFileBridge.BackupFileCandidate): String {
    return formatBackupTimestamp(
        candidate.lastModifiedMs,
        stringResource(R.string.settings_drive_unknown_backup_date),
    )
}

internal fun formatBackupTimestamp(lastModifiedMs: Long, unknownDate: String): String {
    val date = if (lastModifiedMs > 0L) {
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT,
        ).format(java.util.Date(lastModifiedMs))
    } else {
        unknownDate
    }
    return date
}

