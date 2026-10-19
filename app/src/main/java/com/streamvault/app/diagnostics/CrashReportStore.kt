package com.streamvault.app.diagnostics

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.streamvault.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class CrashReportSummary(
    val timestamp: String,
    val exception: String,
    val fileName: String,
    val content: String
)

object CrashReportStore {
    const val MIME_TYPE_TEXT = "text/plain"
    private const val CRASH_DIR = "diagnostics/crash"
    private const val LATEST_FILE_NAME = "latest-crash.txt"
    private const val TEMP_FILE_NAME = "$LATEST_FILE_NAME.tmp"
    private const val ARCHIVED_FILE_PREFIX = "crash-"
    private const val ARCHIVED_FILE_SUFFIX = ".txt"
    private const val MAX_STORED_REPORTS = 5
    private const val MAX_ARCHIVED_REPORTS = MAX_STORED_REPORTS - 1
    private const val PLAYBACK_SUPPORT_FILE_NAME = "latest-playback-support.txt"
    private const val MAX_REPORT_CHARS = 64_000
    private val installed = AtomicBoolean(false)
    private val writingCrash = AtomicBoolean(false)
    private val reportLock = Any()
    private val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val urlPattern = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val sensitiveParamPattern = Regex(
        """(?i)(password|passwd|pwd|username|user|token|auth|key|apikey|api_key|signature|sig)=([^&\s]+)"""
    )

    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (writingCrash.compareAndSet(false, true)) {
                runCatching {
                    saveReport(application, buildReport(application, thread, throwable))
                }
                writingCrash.set(false)
            }
            previousHandler?.uncaughtException(thread, throwable)
                ?: run {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
        }
    }

    fun latestReport(context: Context): CrashReportSummary? {
        val file = latestReportFile(context)
        if (!file.isFile || file.length() <= 0L) return null
        val content = runCatching { file.readText(Charsets.UTF_8).take(MAX_REPORT_CHARS) }.getOrNull()
            ?: return null
        return CrashReportSummary(
            timestamp = parseField(content, "Timestamp") ?: "Unknown",
            exception = parseField(content, "Exception") ?: "Unknown crash",
            fileName = file.name,
            content = content
        )
    }

    fun latestReportFile(context: Context): File =
        File(context.filesDir, "$CRASH_DIR/$LATEST_FILE_NAME").also { file ->
            file.parentFile?.mkdirs()
        }

    fun deleteLatestReport(context: Context): Boolean = synchronized(reportLock) {
        val latest = latestReportFile(context)
        val directory = latest.parentFile ?: return@synchronized false
        val reports = archivedReports(directory) + latest + File(directory, TEMP_FILE_NAME)
        var foundReport = false
        var allDeleted = true
        reports.distinct().forEach { file ->
            if (file.exists()) {
                foundReport = true
                if (!file.delete()) allDeleted = false
            }
        }
        foundReport && allDeleted
    }

    fun recordHandledFailure(context: Context, operation: String, throwable: Throwable) {
        runCatching {
            saveReport(
                context,
                buildReport(context, Thread.currentThread(), throwable)
                    .replaceFirst("StreamVault Crash Report", "StreamVault Failure Report")
                    .plus("\nOperation: ${sanitize(operation)}\n")
            )
        }
    }

    fun providerUriForFile(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)

    fun buildShareIntent(uri: Uri): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE_TEXT
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "Share StreamVault crash report")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun buildReport(context: Context, thread: Thread, throwable: Throwable): String {
        return buildString {
            appendLine("StreamVault Crash Report")
            appendLine("========================")
            appendLine("Timestamp: ${OffsetDateTime.now().format(formatter)}")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Build Type: ${BuildConfig.BUILD_TYPE}")
            appendLine("Package: ${BuildConfig.APPLICATION_ID}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Hardware: ${Build.HARDWARE}")
            appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Android Release: ${Build.VERSION.RELEASE}")
            appendLine("Thread: ${thread.name}")
            appendLine("Exception: ${throwable.javaClass.name}")
            throwable.message?.takeIf { it.isNotBlank() }?.let { message ->
                appendLine("Message: ${sanitize(message)}")
            }
            appendLine()
            appendLine("Stacktrace:")
            appendLine(sanitize(throwable.stackTraceString()))
            appendLine()
            appendLine("Memory:")
            appendLine(memorySnapshot())
            appendLine()
            appendLine("Files Dir Free MB: ${bytesToMb(context.filesDir.freeSpace)}")
            latestPlaybackSupport(context)?.let { playbackSupport ->
                appendLine()
                appendLine("Latest Playback Support:")
                appendLine(playbackSupport)
            }
        }.take(MAX_REPORT_CHARS)
    }

    private fun saveReport(context: Context, content: String) = synchronized(reportLock) {
        val latest = latestReportFile(context)
        val directory = latest.parentFile ?: return@synchronized
        val temporary = File(directory, TEMP_FILE_NAME)

        // Write the new report before rotating so a failed write preserves the current report.
        temporary.writeText(content, Charsets.UTF_8)

        if (latest.isFile && latest.length() > 0L) {
            archiveLatestReport(latest)
        } else {
            latest.delete()
        }
        trimArchivedReports(directory)

        if (!temporary.renameTo(latest)) {
            temporary.copyTo(latest, overwrite = true)
            temporary.delete()
        }
    }

    private fun archiveLatestReport(latest: File) {
        val directory = latest.parentFile ?: return
        val timestamp = latest.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        var suffix = 0
        var archived: File
        do {
            archived = File(directory, "$ARCHIVED_FILE_PREFIX$timestamp-$suffix$ARCHIVED_FILE_SUFFIX")
            suffix++
        } while (archived.exists())

        if (!latest.renameTo(archived)) {
            runCatching { latest.copyTo(archived, overwrite = false) }
                .onSuccess { latest.delete() }
        }
    }

    private fun trimArchivedReports(directory: File) {
        val reports = archivedReports(directory)
            .sortedWith(compareBy<File> { archiveTimestamp(it) }.thenBy { it.name })
        reports.take((reports.size - MAX_ARCHIVED_REPORTS).coerceAtLeast(0))
            .forEach(File::delete)
    }

    private fun archiveTimestamp(file: File): Long =
        file.name.removePrefix(ARCHIVED_FILE_PREFIX)
            .substringBefore('-')
            .toLongOrNull()
            ?: file.lastModified()

    private fun archivedReports(directory: File): List<File> =
        directory.listFiles { file ->
            file.isFile && file.name.startsWith(ARCHIVED_FILE_PREFIX) &&
                file.name.endsWith(ARCHIVED_FILE_SUFFIX)
        }?.toList().orEmpty()

    private fun latestPlaybackSupport(context: Context): String? {
        val file = File(context.filesDir, "$CRASH_DIR/$PLAYBACK_SUPPORT_FILE_NAME")
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { file.readText(Charsets.UTF_8).take(MAX_REPORT_CHARS / 2) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::sanitize)
    }

    private fun Throwable.stackTraceString(): String {
        val writer = StringWriter()
        printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun sanitize(value: String): String {
        val withoutSensitiveParams = sensitiveParamPattern.replace(value) { match ->
            "${match.groupValues[1]}=<redacted>"
        }
        return urlPattern.replace(withoutSensitiveParams) { match ->
            val scheme = match.value.substringBefore("://", "https")
            "$scheme://<redacted-url>"
        }
    }

    private fun memorySnapshot(): String {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        return String.format(
            Locale.US,
            "javaUsedMb=%.1f javaMaxMb=%.1f nativeHeapMb=%.1f",
            used.toDouble() / (1024.0 * 1024.0),
            runtime.maxMemory().toDouble() / (1024.0 * 1024.0),
            android.os.Debug.getNativeHeapAllocatedSize().toDouble() / (1024.0 * 1024.0)
        )
    }

    private fun bytesToMb(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes.toDouble() / (1024.0 * 1024.0))

    private fun parseField(content: String, field: String): String? =
        content.lineSequence()
            .firstOrNull { it.startsWith("$field: ") }
            ?.substringAfter(": ")
            ?.takeIf { it.isNotBlank() }
}
