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
    private const val PLAYBACK_SUPPORT_FILE_NAME = "latest-playback-support.txt"
    private const val MAX_REPORT_CHARS = 64_000
    private val installed = AtomicBoolean(false)
    private val writingCrash = AtomicBoolean(false)
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
                    latestReportFile(application).writeText(
                        buildReport(application, thread, throwable),
                        Charsets.UTF_8
                    )
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

    fun deleteLatestReport(context: Context): Boolean =
        latestReportFile(context).delete()

    fun recordHandledFailure(context: Context, operation: String, throwable: Throwable) {
        runCatching {
            latestReportFile(context).writeText(
                buildReport(context, Thread.currentThread(), throwable)
                    .replaceFirst("StreamVault Crash Report", "StreamVault Failure Report")
                    .plus("\nOperation: ${sanitize(operation)}\n"),
                Charsets.UTF_8
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
