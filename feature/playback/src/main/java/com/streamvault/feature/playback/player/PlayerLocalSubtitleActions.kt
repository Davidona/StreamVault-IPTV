package com.streamvault.feature.playback.player

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import com.streamvault.feature.playback.R
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_LOCAL_SUBTITLE_BYTES = 20L * 1024L * 1024L

private class UnsupportedLocalSubtitleFileException : IOException()
private class LocalSubtitleFileTooLargeException : IOException()

private data class ImportedLocalSubtitle(
    val file: File,
    val displayName: String
)

internal fun PlayerViewModel.loadLocalSrtSubtitle(uri: Uri) {
    viewModelScope.launch {
        try {
            val subtitle = withContext(Dispatchers.IO) { copyLocalSrtSubtitle(uri) }
            deactivateLiveTranslation()
            playerEngine.addExternalSubtitle(
                subtitleUri = Uri.fromFile(subtitle.file),
                language = "und",
                label = subtitle.displayName
            )
            showPlayerNotice(
                appContext.getString(R.string.player_subtitle_loaded, subtitle.displayName)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UnsupportedLocalSubtitleFileException) {
            showPlayerNotice(appContext.getString(R.string.player_subtitle_invalid_file))
        } catch (_: LocalSubtitleFileTooLargeException) {
            showPlayerNotice(appContext.getString(R.string.player_subtitle_file_too_large))
        } catch (_: Exception) {
            showPlayerNotice(appContext.getString(R.string.player_subtitle_load_failed))
        }
    }
}

private fun PlayerViewModel.copyLocalSrtSubtitle(uri: Uri): ImportedLocalSubtitle {
    val resolver = appContext.contentResolver
    val displayName = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "subtitle.srt"

    if (!displayName.substringAfterLast('.', missingDelimiterValue = "").equals("srt", ignoreCase = true)) {
        throw UnsupportedLocalSubtitleFileException()
    }

    val file = File.createTempFile("streamvault-subtitle-", ".srt", appContext.cacheDir)
    try {
        val input = resolver.openInputStream(uri) ?: throw IOException("Unable to open selected subtitle")
        var copiedBytes = 0L
        input.use { source ->
            file.outputStream().buffered().use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copiedBytes += count
                    if (copiedBytes > MAX_LOCAL_SUBTITLE_BYTES) {
                        throw LocalSubtitleFileTooLargeException()
                    }
                    destination.write(buffer, 0, count)
                }
            }
        }
        if (copiedBytes == 0L) throw IOException("Selected subtitle file is empty")
        return ImportedLocalSubtitle(file = file, displayName = displayName)
    } catch (failure: Throwable) {
        file.delete()
        throw failure
    }
}
