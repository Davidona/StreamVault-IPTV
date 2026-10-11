package com.streamvault.data.manager.recording

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.RecordingStatus
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RecordingSmbPlaybackEntryPoint {
    fun database(): StreamVaultDatabase
    fun credentialCrypto(): CredentialCrypto
}

/** Streams completed SMB recordings through an app-private content URI for Media3 playback. */
internal class RecordingSmbPlaybackProvider : ContentProvider() {
    private val streamExecutor by lazy {
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "recording-smb-playback").apply { isDaemon = true }
        }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? =
        if (uri.pathSegments.size == 2 && uri.pathSegments[0] == RECORDING_PATH) MIME_TYPE else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("SMB recordings are read-only.")
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != RECORDING_PATH) {
            throw FileNotFoundException("Unknown SMB recording.")
        }
        val recordingId = segments[1].removeSuffix(".ts")
        val appContext = context?.applicationContext
            ?: throw FileNotFoundException("Application context is unavailable.")
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext,
            RecordingSmbPlaybackEntryPoint::class.java,
        )
        val run = runBlocking(Dispatchers.IO) {
            entryPoint.database().recordingRunDao().getById(recordingId)
        } ?: throw FileNotFoundException("Recording not found.")
        if (run.status != RecordingStatus.COMPLETED) {
            throw FileNotFoundException("Only completed recordings can be played.")
        }
        val smbUrl = run.outputUri?.takeIf { it.startsWith("smb://", ignoreCase = true) }
            ?: throw FileNotFoundException("Recording is not stored on an SMB share.")
        val credentials = runCatching {
            decodeSmbCredentials(run.outputCredentialsEncrypted, entryPoint.credentialCrypto())
        }.getOrNull() ?: throw FileNotFoundException("SMB credentials are unavailable.")

        val pipe = ParcelFileDescriptor.createPipe()
        try {
            streamExecutor.execute {
                runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { destination ->
                        SmbRecordingStorage.openInputStream(smbUrl, credentials).use { source ->
                            source.copyTo(destination, bufferSize = COPY_BUFFER_SIZE)
                        }
                    }
                }
            }
            return pipe[0]
        } catch (error: Throwable) {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
            throw IOException("Could not open SMB recording stream.", error)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val RECORDING_PATH = "recording"
        const val MIME_TYPE = "video/mp2t"
        const val COPY_BUFFER_SIZE = 128 * 1024
    }
}
