package com.streamvault.app.backup

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.streamvault.app.BuildConfig
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object BackupFileBridge {
    const val MIME_TYPE_JSON = "application/json"
    private const val MAX_IMPORT_BYTES = 16L * 1024L * 1024L
    private const val BACKUP_EXPORTS_DIR = "Backups"
    private const val BACKUP_IMPORTS_DIR = "backup_imports"
    private const val MANAGED_EXPORTS_PREFERENCES = "managed_backup_exports"
    private const val MANAGED_EXPORT_URIS_KEY = "uris"
    private const val MAX_MANAGED_EXPORT_URIS = 100
    private const val MANAGED_EXPORT_PREFIX = "streamvault_backup_"
    private val exportNameFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")

    data class BackupFileCandidate(
        val displayName: String,
        val uri: Uri,
        val lastModifiedMs: Long,
    )

    /**
     * Lists files that StreamVault created itself and can therefore delete
     * safely. Arbitrary JSON files in a user-selected folder are intentionally
     * excluded; those remain under the user's normal file-manager control.
     */
    fun listManagedBackups(context: Context): List<BackupFileCandidate> {
        val candidates = mutableListOf<BackupFileCandidate>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                )
                val relativePath = Environment.DIRECTORY_DOWNLOADS + "/StreamVault/"
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf(relativePath, "$MANAGED_EXPORT_PREFIX%"),
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    while (cursor.moveToNext()) {
                        candidates += BackupFileCandidate(
                            displayName = cursor.getString(nameIndex).orEmpty(),
                            uri = ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                cursor.getLong(idIndex),
                            ),
                            lastModifiedMs = cursor.getLong(modifiedIndex) * 1000L,
                        )
                    }
                }
            }
        }

        val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        listBackupFiles(documentsDir)
            .filter { it.name.startsWith(MANAGED_EXPORT_PREFIX, ignoreCase = true) }
            .mapTo(candidates, ::candidateForFile)

        registeredExportUris(context)
            .mapNotNull { uriString -> candidateForUri(context, Uri.parse(uriString)) }
            .forEach(candidates::add)

        return candidates
            .distinctBy { it.uri.toString() }
            .sortedByDescending { it.lastModifiedMs }
    }

    /** Remembers a successful export made to a user-selected SAF destination. */
    fun rememberManagedExport(context: Context, uri: Uri) {
        val preferences = context.getSharedPreferences(MANAGED_EXPORTS_PREFERENCES, Context.MODE_PRIVATE)
        val uris = (preferences.getStringSet(MANAGED_EXPORT_URIS_KEY, emptySet()).orEmpty() + uri.toString())
            .toList()
            .takeLast(MAX_MANAGED_EXPORT_URIS)
            .toSet()
        preferences.edit().putStringSet(MANAGED_EXPORT_URIS_KEY, uris).apply()
    }

    /** Deletes one entry previously returned by [listManagedBackups]. */
    fun deleteManagedBackup(context: Context, candidate: BackupFileCandidate): Boolean {
        val deleted = runCatching {
            when (candidate.uri.scheme) {
                "content" -> context.contentResolver.delete(candidate.uri, null, null) > 0
                "file" -> candidate.uri.path?.let { File(it).delete() } == true
                else -> false
            }
        }.getOrDefault(false)
        if (deleted) forgetManagedExport(context, candidate.uri)
        return deleted
    }

    fun candidateForFile(file: File): BackupFileCandidate = BackupFileCandidate(
        displayName = file.name,
        uri = Uri.fromFile(file),
        lastModifiedMs = file.lastModified(),
    )

    fun createExportFile(context: Context): File {
        val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return createExportFile(documentsDir)
    }

    /**
     * Creates an export destination without invoking the system document picker.
     * Android TV images commonly omit DocumentsUI, so Q+ devices use the public
     * Downloads provider and older devices fall back to app-specific storage.
     */
    fun createPickerFreeExportUri(context: Context): Uri? {
        val fileName = nextExportFileName()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE_JSON)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/StreamVault/"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val publicUri = runCatching {
                context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values,
                )
            }.getOrNull()
            if (publicUri != null) return publicUri
        }
        return runCatching { Uri.fromFile(createExportFile(context)) }.getOrNull()
    }

    /** Publishes a picker-free export or removes its partial target on failure. */
    fun finishPickerFreeExport(context: Context, uri: Uri, success: Boolean): Boolean {
        if (uri.scheme == "content" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!success) {
                context.contentResolver.delete(uri, null, null)
                return true
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val published = context.contentResolver.update(uri, values, null, null) > 0
            if (!published) context.contentResolver.delete(uri, null, null)
            return published
        }
        if (!success) {
            uri.path?.let { File(it).delete() }
        }
        return success
    }

    /**
     * Finds backups that StreamVault can import without launching a document
     * picker. This includes visible MediaStore downloads and the
     * app-specific fallback directory used on older Android versions.
     */
    fun listPickerFreeBackups(context: Context): List<BackupFileCandidate> {
        val candidates = mutableListOf<BackupFileCandidate>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                )
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    while (cursor.moveToNext()) {
                        val displayName = cursor.getString(nameIndex).orEmpty()
                        if (!displayName.endsWith(".json", ignoreCase = true)) continue
                        val id = cursor.getLong(idIndex)
                        candidates += BackupFileCandidate(
                            displayName = displayName,
                            uri = ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                id,
                            ),
                            lastModifiedMs = cursor.getLong(modifiedIndex) * 1000L,
                        )
                    }
                }
            }
        }

        val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        listBackupFiles(documentsDir).forEach { file ->
            candidates += BackupFileCandidate(
                displayName = file.name,
                uri = Uri.fromFile(file),
                lastModifiedMs = file.lastModified(),
            )
        }

        return candidates
            .distinctBy { it.uri.toString() }
            .sortedByDescending { it.lastModifiedMs }
    }

    /**
     * Creates an empty timestamped backup file under [baseDir]/Backups. Used for app-private folders
     * on a USB drive, which can be written with a plain `file://` URI (no FileProvider needed since
     * FileProvider cannot serve secondary external volumes).
     */
    fun createExportFile(baseDir: File): File {
        val backupsDir = File(baseDir, BACKUP_EXPORTS_DIR).apply { mkdirs() }
        val stem = nextExportFileName().removeSuffix(".json")
        var suffix = 0
        var file: File
        do {
            val suffixText = if (suffix == 0) "" else "_$suffix"
            file = File(backupsDir, "$stem$suffixText.json")
            suffix++
        } while (file.exists())
        return file.also {
                it.parentFile?.mkdirs()
                it.createNewFile()
            }
    }

    /** Lists previously exported `.json` backups under [baseDir]/Backups, newest first. */
    fun listBackupFiles(baseDir: File): List<File> =
        File(baseDir, BACKUP_EXPORTS_DIR).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private fun registeredExportUris(context: Context): Set<String> =
        context.getSharedPreferences(MANAGED_EXPORTS_PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(MANAGED_EXPORT_URIS_KEY, emptySet())
            .orEmpty()

    private fun forgetManagedExport(context: Context, uri: Uri) {
        val preferences = context.getSharedPreferences(MANAGED_EXPORTS_PREFERENCES, Context.MODE_PRIVATE)
        val remaining = registeredExportUris(context) - uri.toString()
        preferences.edit().putStringSet(MANAGED_EXPORT_URIS_KEY, remaining).apply()
    }

    private fun candidateForUri(context: Context, uri: Uri): BackupFileCandidate? = when (uri.scheme) {
        "file" -> uri.path?.let { path ->
            File(path).takeIf { it.exists() && it.isFile }?.let(::candidateForFile)
        }
        "content" -> runCatching {
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                BackupFileCandidate(
                    displayName = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else "backup.json",
                    uri = uri,
                    lastModifiedMs = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) * 1000L else 0L,
                )
            }
        }.getOrNull()
        else -> null
    }

    fun copyToImportInbox(context: Context, sourceUri: Uri): Uri? {
        pruneImportInbox(context)
        val inboxDir = File(context.cacheDir, BACKUP_IMPORTS_DIR).apply { mkdirs() }
        val targetFile = File(inboxDir, "streamvault_import_${System.currentTimeMillis()}.json")
        return runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMPORT_BYTES) {
                            throw IOException("Backup exceeds the ${MAX_IMPORT_BYTES / (1024 * 1024)} MiB import limit")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return null
            providerUriForFile(context, targetFile)
        }.getOrElse {
            targetFile.delete()
            null
        }
    }

    fun providerUriForFile(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    }

    fun buildShareIntent(uri: Uri): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE_JSON
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "Share Sokatito backup")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun pruneImportInbox(context: Context) {
        val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        File(context.cacheDir, BACKUP_IMPORTS_DIR)
            .listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private fun nextExportFileName(): String =
        "streamvault_backup_${LocalDateTime.now().format(exportNameFormatter)}.json"

}
