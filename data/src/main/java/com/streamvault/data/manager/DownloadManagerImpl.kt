package com.streamvault.data.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.streamvault.data.local.dao.DownloadDao
import com.streamvault.data.local.entity.DownloadEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.data.remote.xtream.ResolvedStreamUrl
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.util.runSuspendCatching
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadRequest
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.domain.model.DownloadSourceType
import com.streamvault.domain.model.DownloadStorageConfig
import com.streamvault.domain.model.Result
import com.streamvault.domain.manager.TorrentDownloadStreamResolver
import com.streamvault.domain.repository.DownloadManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class DownloadManagerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val preferencesRepository: PreferencesRepository,
    private val okHttpClient: OkHttpClient,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver,
    private val torrentDownloadStreamResolver: TorrentDownloadStreamResolver,
    private val applicationScope: CoroutineScope
) : DownloadManager {

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeCalls = ConcurrentHashMap<String, okhttp3.Call>()
    private val playbackPausedIds = ConcurrentHashMap.newKeySet<String>()
    private val schedulerMutex = Mutex()
    private val recoveryMutex = Mutex()
    private val transferStateMachine = DownloadTransferStateMachine()
    private val transferCopier = DownloadTransferCopier()
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val ownerId = UUID.randomUUID().toString()
    @Volatile private var playbackActive = false

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                applicationScope.launch(Dispatchers.IO) {
                    downloadDao.getRetryablePausedOnce(MAX_RETRIES)
                        .filter { it.retryCount == 0 }
                        .forEach { scheduleExisting(it.id, keepPaused = true) }
                    startNextQueued()
                }
            }
        })
    }

    override fun observeAllDownloads(): Flow<List<DownloadItem>> {
        return downloadDao.getAll().map { downloads -> downloads.map { it.toDomain() } }
    }

    override fun observeDownload(id: String): Flow<DownloadItem?> {
        return downloadDao.getById(id).map { it?.toDomain() }
    }

    override fun observeStorageState(): Flow<DownloadStorageConfig> {
        return preferencesRepository.downloadTreeUri.combine(observeAllDownloads()) { treeUri, _ ->
            DownloadStorageConfig(
                treeUri = treeUri,
                displayName = treeUri?.substringAfterLast('/')?.ifBlank { treeUri },
                outputDirectory = null,
                availableBytes = null,
                isWritable = !treeUri.isNullOrBlank()
            )
        }
    }

    override suspend fun enqueueDownload(request: DownloadRequest): Result<DownloadItem> {
        return runSuspendCatching {
            val entity = DownloadEntity.fromRequest(
                request = request,
                outputUri = null,
                outputDisplayPath = null
            )
            downloadDao.insert(entity)
            startNextQueued()
            entity.toDomain()
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.error("Failed to enqueue download", it) }
        )
    }

    override suspend fun resumeDownload(id: String): Result<Unit> {
        return runSuspendCatching {
            downloadDao.getByIdOnce(id)?.let { entity ->
                if (entity.status != DownloadStatus.COMPLETED && entity.status != DownloadStatus.CANCELLED) {
                    deleteOutput(entity)
                    downloadDao.update(
                        entity.copy(
                            outputUri = null,
                            outputDisplayPath = null,
                            status = DownloadStatus.PENDING,
                            bytesWritten = 0L,
                            totalBytes = null,
                            supportsResume = false,
                            retryCount = 0,
                            failureReason = null,
                            ownerId = null,
                            heartbeatAt = null
                        )
                    )
                }
            }
            startNextQueued()
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.error("Failed to resume download", it) }
        )
    }

    override suspend fun cancelDownload(id: String): Result<Unit> {
        return runSuspendCatching {
            activeCalls.remove(id)?.cancel()
            activeJobs.remove(id)?.cancelAndJoin()
            downloadDao.getByIdOnce(id)?.let { entity ->
                downloadDao.update(
                    entity.copy(
                        status = DownloadStatus.CANCELLED,
                        ownerId = null,
                        heartbeatAt = null
                    )
                )
            }
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.error("Failed to cancel download", it) }
        )
    }

    override fun onPlaybackStarted() {
        playbackActive = true
        applicationScope.launch(Dispatchers.IO) { runSuspendCatching { pauseActiveDownloadsForPlayback() } }
    }

    override fun onPlaybackStopped() {
        playbackActive = false
        applicationScope.launch(Dispatchers.IO) { runSuspendCatching { startNextQueued() } }
    }

    override suspend fun deleteDownload(id: String): Result<Unit> {
        return runSuspendCatching {
            activeCalls.remove(id)?.cancel()
            activeJobs.remove(id)?.cancelAndJoin()
            downloadDao.getByIdOnce(id)?.let { deleteOutput(it) }
            downloadDao.deleteById(id)
            startNextQueued()
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.error("Failed to delete download", it) }
        )
    }

    override suspend fun updateStorageConfig(
        treeUri: String?,
        displayName: String?
    ): Result<DownloadStorageConfig> {
        return runSuspendCatching {
            preferencesRepository.setDownloadTreeUri(treeUri)
            DownloadStorageConfig(
                treeUri = treeUri,
                displayName = displayName,
                outputDirectory = null,
                availableBytes = null,
                isWritable = !treeUri.isNullOrBlank()
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.error("Failed to update download folder", it) }
        )
    }

    private suspend fun scheduleExisting(id: String, keepPaused: Boolean = false) {
        ensureInterruptedDownloadsRecovered()
        schedulerMutex.withLock {
            if (activeJobs.containsKey(id)) return
            val entity = downloadDao.getByIdOnce(id) ?: return
            if (entity.status == DownloadStatus.COMPLETED || entity.status == DownloadStatus.CANCELLED) return
            if (playbackActive || activeJobs.isNotEmpty()) {
                if (!keepPaused && entity.status != DownloadStatus.PAUSED) {
                    downloadDao.update(entity.copy(status = DownloadStatus.PENDING, failureReason = null))
                }
                return
            }
            startDownloadJob(entity)
        }
    }

    private suspend fun startDownloadJob(entity: DownloadEntity) {
        if (downloadDao.claimForDownload(entity.id, ownerId, System.currentTimeMillis()) != 1) return
        val claimed = downloadDao.getByIdOnce(entity.id)
            ?.takeIf { it.ownerId == ownerId && it.status == DownloadStatus.DOWNLOADING }
            ?: return
        val job = applicationScope.launch(start = CoroutineStart.LAZY) {
            captureDownload(claimed)
        }
        activeJobs[entity.id] = job
        job.start()
    }

    private suspend fun startNextQueued() {
        ensureInterruptedDownloadsRecovered()
        schedulerMutex.withLock {
            if (playbackActive || activeJobs.isNotEmpty()) return
            val next = downloadDao.getQueuedOnce()
                .firstOrNull {
                    !activeJobs.containsKey(it.id) &&
                        (it.status == DownloadStatus.PENDING || it.retryCount == 0) &&
                        it.retryCount < MAX_RETRIES
                }
                ?: return
            startDownloadJob(next)
        }
    }

    override suspend fun recoverInterruptedDownloads(): Result<Int> = runSuspendCatching {
        val recovered = ensureInterruptedDownloadsRecovered()
        startNextQueued()
        recovered
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.error("Failed to recover interrupted downloads", it) }
    )

    private suspend fun ensureInterruptedDownloadsRecovered(): Int = recoveryMutex.withLock {
        // Re-scan whenever the scheduler is idle. A process can observe an orphan after the
        // application-start coroutine has already run, and a cached one-time result would leave
        // that durable row unowned until a later process restart.
        if (activeJobs.isNotEmpty()) return@withLock 0
        val interrupted = downloadDao.getOrphanedDownloading(ownerId)
        interrupted.forEach { entity ->
            val targetLength = outputLength(entity)
            when (
                planInterruptedDownload(
                    bytesWritten = entity.bytesWritten,
                    totalBytes = entity.totalBytes,
                    supportsResume = entity.supportsResume,
                    targetLength = targetLength
                )
            ) {
                InterruptedDownloadPlan.COMPLETE -> {
                    val completedBytes = requireNotNull(targetLength)
                    downloadDao.update(
                        entity.copy(
                            status = DownloadStatus.COMPLETED,
                            bytesWritten = completedBytes,
                            totalBytes = entity.totalBytes ?: completedBytes,
                            retryCount = 0,
                            completedAt = System.currentTimeMillis(),
                            failureReason = null,
                            ownerId = null,
                            heartbeatAt = null
                        )
                    )
                }
                InterruptedDownloadPlan.RESUME -> {
                    downloadDao.update(
                        entity.copy(
                            status = DownloadStatus.PENDING,
                            completedAt = null,
                            failureReason = INTERRUPTED_RESUME_REASON,
                            ownerId = null,
                            heartbeatAt = null
                        )
                    )
                }
                InterruptedDownloadPlan.RESTART -> {
                    deleteOutput(entity)
                    downloadDao.update(
                        entity.copy(
                            outputUri = null,
                            outputDisplayPath = null,
                            status = DownloadStatus.PENDING,
                            bytesWritten = 0L,
                            totalBytes = null,
                            supportsResume = false,
                            completedAt = null,
                            failureReason = INTERRUPTED_RESTART_REASON,
                            ownerId = null,
                            heartbeatAt = null
                        )
                    )
                }
            }
        }
        interrupted.size
    }

    private suspend fun pauseActiveDownloadsForPlayback() {
        activeJobs.entries.toList().forEach { (id, job) ->
            playbackPausedIds.add(id)
            activeCalls.remove(id)?.cancel()
            job.cancel(PlaybackStartedCancellation())
            job.join()
        }
    }

    override suspend fun pauseDownloadForForegroundServiceTimeout(id: String): Result<Unit> = runSuspendCatching {
        activeCalls.remove(id)?.cancel()
        activeJobs.remove(id)?.let { job ->
            job.cancel(ForegroundServiceTimeoutCancellation())
            job.join()
        }
        downloadDao.getByIdOnce(id)
            ?.takeIf { it.status == DownloadStatus.DOWNLOADING }
            ?.let { pauseForForegroundServiceTimeout(it) }
    }.fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { Result.error("Failed to pause download after foreground-service timeout", it) }
    )

    private suspend fun captureDownload(initial: DownloadEntity) {
        var current = downloadDao.getByIdOnce(initial.id)
            ?.takeIf { it.ownerId == ownerId && it.ownerEpoch == initial.ownerEpoch }
            ?: return

        try {
            val resumeFrom = current.bytesWritten.takeIf {
                current.supportsResume && it > 0L && outputLength(current) == it
            } ?: 0L
            if (current.bytesWritten > 0L && resumeFrom == 0L) {
                deleteOutput(current)
                current = current.copy(
                    outputUri = null,
                    outputDisplayPath = null,
                    bytesWritten = 0L,
                    totalBytes = null,
                    supportsResume = false,
                    heartbeatAt = System.currentTimeMillis()
                )
                downloadDao.update(current)
            }
            val resolvedStream = resolveFreshDownloadStream(current)
            val requestUrl = resolvedStream?.url?.takeIf { it.isNotBlank() } ?: current.streamUrl
            if (current.sourceType == DownloadSourceType.PROVIDER.name && requestUrl != current.streamUrl) {
                current = current.copy(streamUrl = requestUrl)
                downloadDao.update(current)
            }
            val request = buildDownloadHttpRequest(
                url = requestUrl,
                headers = resolvedStream?.headers.orEmpty(),
                userAgent = resolvedStream?.userAgent,
                resumeFrom = resumeFrom
            )
            val call = okHttpClient.newCall(request)
            activeCalls[initial.id] = call
            call.useCancellableResponse { response ->
                val body = response.body ?: error("Empty response body")
                val resumeDecision = resolveResumeResponse(
                    resumeFrom = resumeFrom,
                    responseCode = response.code,
                    contentLength = body.contentLength().takeIf { it >= 0L },
                    acceptRanges = response.header("Accept-Ranges"),
                    contentRange = response.header("Content-Range")
                )
                if (resumeDecision == DownloadResumeResponse.RESTART) {
                    throw RestartFromZeroException()
                }
                if (resumeDecision == DownloadResumeResponse.COMPLETE) {
                    downloadDao.update(
                        current.copy(
                            status = DownloadStatus.COMPLETED,
                            bytesWritten = resumeFrom,
                            totalBytes = resumeFrom,
                            retryCount = 0,
                            completedAt = System.currentTimeMillis(),
                            failureReason = null,
                            ownerId = null,
                            heartbeatAt = null
                        )
                    )
                    return@useCancellableResponse
                }
                if (resumeDecision == DownloadResumeResponse.FAIL) {
                    throw ProtocolException("Invalid partial download response")
                }
                if (!response.isSuccessful) throw HttpDownloadException(response.code)
                val transfer = resumeDecision as DownloadResumeResponse.TRANSFER
                if (!transfer.append && resumeFrom > 0L) {
                    deleteOutput(current)
                    current = current.copy(bytesWritten = 0L, outputUri = null, outputDisplayPath = null, supportsResume = false)
                }
                val append = transfer.append
                val totalBytes = transfer.totalBytes
                val supportsResume = transfer.supportsResume
                val target = runSuspendCatching { createOutputTarget(current, response.header("Content-Type"), append) }
                    .getOrElse { error ->
                        if (!append) throw DownloadStorageException(error)
                        throw RestartFromZeroException(error)
                    }
                var bytesWritten = if (append && current.outputUri != null) resumeFrom else 0L
                current = current.copy(
                    outputUri = target.uri.toString(),
                    outputDisplayPath = target.displayPath,
                    bytesWritten = bytesWritten,
                    totalBytes = totalBytes,
                    supportsResume = supportsResume,
                    status = DownloadStatus.DOWNLOADING,
                    heartbeatAt = System.currentTimeMillis()
                )
                downloadDao.update(current)

                target.output.use { output ->
                    body.byteStream().use { input ->
                        bytesWritten = transferCopier.copy(
                            input = input,
                            output = output,
                            startingBytes = bytesWritten
                        ) { progress ->
                            bytesWritten = progress.bytesWritten
                            current = current.copy(bytesWritten = bytesWritten)

                            if (progress.shouldCheckpoint) {
                                current = current.copy(
                                    outputUri = target.uri.toString(),
                                    outputDisplayPath = target.displayPath,
                                    bytesWritten = bytesWritten,
                                    totalBytes = totalBytes,
                                    supportsResume = supportsResume,
                                    status = DownloadStatus.DOWNLOADING,
                                    heartbeatAt = System.currentTimeMillis()
                                )
                                downloadDao.update(current)
                            }
                        }
                    }
                }

                downloadDao.update(
                    current.copy(
                        outputUri = target.uri.toString(),
                        outputDisplayPath = target.displayPath,
                        status = DownloadStatus.COMPLETED,
                        bytesWritten = bytesWritten,
                        totalBytes = totalBytes ?: bytesWritten,
                        supportsResume = supportsResume,
                        retryCount = 0,
                        completedAt = System.currentTimeMillis(),
                        failureReason = null,
                        ownerId = null,
                        heartbeatAt = null
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            downloadDao.getByIdOnce(initial.id)?.let { entity ->
                val cancellationKind = when {
                    cancelled is PlaybackStartedCancellation || playbackPausedIds.contains(initial.id) ->
                        DownloadCancellationKind.PLAYBACK_STARTED
                    cancelled is ForegroundServiceTimeoutCancellation ->
                        DownloadCancellationKind.FOREGROUND_SERVICE_TIMEOUT
                    else -> DownloadCancellationKind.USER
                }
                val transition = transferStateMachine.cancellation(cancellationKind)
                if (transition.resetOutput) {
                    resetInterruptedDownload(current, transition.status, transition.reason.orEmpty())
                } else {
                    downloadDao.update(
                        entity.copy(
                            status = transition.status,
                            failureReason = transition.reason,
                            ownerId = null,
                            heartbeatAt = null
                        )
                    )
                }
            }
            throw cancelled
        } catch (restart: RestartFromZeroException) {
            val reset = current.copy(
                bytesWritten = 0L,
                outputUri = null,
                outputDisplayPath = null,
                supportsResume = false
            )
            deleteOutput(current)
            downloadDao.update(reset)
            captureDownload(reset)
        } catch (error: Throwable) {
            if (downloadDao.getByIdOnce(initial.id) != null) {
                if (playbackPausedIds.contains(initial.id)) {
                    resetInterruptedDownload(current, DownloadStatus.PAUSED, "Waiting for playback to stop")
                } else {
                    handleDownloadFailure(current, error)
                }
            }
        } finally {
            activeCalls.remove(initial.id)
            playbackPausedIds.remove(initial.id)
            activeJobs.remove(initial.id)
            startNextQueued()
        }
    }

    private suspend fun handleDownloadFailure(entity: DownloadEntity, error: Throwable) {
        val reason = error.message ?: error::class.java.simpleName
        val transition = transferStateMachine.failure(
            currentRetryCount = entity.retryCount,
            kind = if (isTransient(error)) DownloadFailureKind.TRANSIENT else DownloadFailureKind.PERMANENT,
            reason = reason
        )
        if (transition.status == DownloadStatus.FAILED) {
            resetInterruptedDownload(entity, transition.status, transition.reason)
            return
        }

        val retryCount = transition.retryCount
        resetInterruptedDownload(entity, transition.status, transition.reason, retryCount)
        applicationScope.launch(Dispatchers.IO) {
            delay(requireNotNull(transition.retryAfterMillis))
            downloadDao.getByIdOnce(entity.id)
                ?.takeIf { it.status == DownloadStatus.PAUSED && it.retryCount == retryCount }
                ?.let { downloadDao.update(it.copy(status = DownloadStatus.PENDING)) }
            startNextQueued()
        }
    }

    private suspend fun resetInterruptedDownload(
        entity: DownloadEntity,
        status: DownloadStatus,
        reason: String,
        retryCount: Int = 0
    ) {
        deleteOutput(entity)
        downloadDao.update(
            entity.copy(
                outputUri = null,
                outputDisplayPath = null,
                status = status,
                bytesWritten = 0L,
                totalBytes = null,
                supportsResume = false,
                retryCount = retryCount,
                completedAt = null,
                failureReason = reason,
                ownerId = null,
                heartbeatAt = null
            )
        )
    }

    private suspend fun pauseForForegroundServiceTimeout(entity: DownloadEntity) {
        downloadDao.update(
            entity.copy(
                status = DownloadStatus.PAUSED,
                completedAt = null,
                failureReason = FOREGROUND_SERVICE_TIMEOUT_REASON,
                ownerId = null,
                heartbeatAt = null
            )
        )
    }

    private fun outputLength(entity: DownloadEntity): Long? {
        entity.outputDisplayPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isAbsolute && it.exists() }
            ?.let { return it.length() }
        val outputUri = entity.outputUri?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            DocumentFile.fromSingleUri(context, Uri.parse(outputUri))
                ?.takeIf { it.exists() && it.canWrite() }
                ?.length()
        }.getOrNull()
    }

    private fun isTransient(error: Throwable): Boolean {
        if (isPermanentDownloadStorageFailure(error)) return false
        val httpCode = (error as? HttpDownloadException)?.code
        return error is SocketException ||
            error is SocketTimeoutException ||
            error is ProtocolException ||
            httpCode != null && httpCode >= 500 ||
            !isNetworkAvailable()
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun resolveFreshDownloadStream(entity: DownloadEntity): ResolvedStreamUrl? {
        return when (runCatching { DownloadSourceType.valueOf(entity.sourceType) }
            .getOrDefault(DownloadSourceType.PROVIDER)
        ) {
            DownloadSourceType.PROVIDER -> {
                val logicalUrl = entity.sourceStreamUrl?.takeIf { it.isNotBlank() } ?: entity.streamUrl
                runSuspendCatching {
                    xtreamStreamUrlResolver.resolveAndCommitMetadata(
                        url = logicalUrl,
                        fallbackProviderId = entity.providerId,
                        fallbackStreamId = entity.sourceStreamId ?: entity.contentId,
                        fallbackContentType = entity.contentType.toPlaybackContentType(),
                        fallbackContainerExtension = entity.containerExtension,
                        preferStableUrl = true
                    )
                }.getOrNull()
            }
            DownloadSourceType.DIRECT_URL -> ResolvedStreamUrl(
                url = entity.streamUrl,
                headers = entity.requestHeaders()
            )
            DownloadSourceType.TORRENT_MAGNET -> ResolvedStreamUrl(
                url = torrentDownloadStreamResolver.resolve(entity.streamUrl)
            )
        }
    }

    private fun DownloadContentType.toPlaybackContentType(): ContentType = when (this) {
        DownloadContentType.MOVIE -> ContentType.MOVIE
        DownloadContentType.SERIES_EPISODE -> ContentType.SERIES_EPISODE
    }

    private suspend fun createOutputTarget(
        entity: DownloadEntity,
        contentTypeHeader: String?,
        append: Boolean
    ): OutputTarget = withContext(Dispatchers.IO) {
        if (append && !entity.outputDisplayPath.isNullOrBlank()) {
            val file = File(entity.outputDisplayPath)
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                return@withContext OutputTarget(uri, file.absolutePath, FileOutputStream(file, true).buffered())
            }
        }
        if (append && !entity.outputUri.isNullOrBlank()) {
            val uri = Uri.parse(entity.outputUri)
            val output = context.contentResolver.openOutputStream(uri, "wa")
                ?: throw FileNotFoundException("Could not append selected download file")
            return@withContext OutputTarget(uri, entity.outputDisplayPath ?: uri.toString(), output)
        }

        val mimeType = contentTypeHeader
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.startsWith("video/") }
            ?: "video/mp4"
        val fileName = buildFileName(entity, mimeType)
        val treeUri = preferencesRepository.downloadTreeUri.first()

        if (!treeUri.isNullOrBlank()) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                ?: throw FileNotFoundException("Selected download folder is unavailable")
            val file = tree.createFile(mimeType, fileName)
                ?: throw FileNotFoundException("Could not create download file in selected folder")
            val output = context.contentResolver.openOutputStream(file.uri)
                ?: throw FileNotFoundException("Could not open selected download file")
            return@withContext OutputTarget(
                uri = file.uri,
                displayPath = file.name ?: fileName,
                output = output
            )
        }

        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            "StreamVault"
        ).apply { mkdirs() }
        val file = uniqueFile(directory, fileName)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        OutputTarget(
            uri = uri,
            displayPath = file.absolutePath,
            output = file.outputStream()
        )
    }

    private fun deleteOutput(entity: DownloadEntity) {
        entity.outputUri
            ?.takeIf { it.isNotBlank() }
            ?.let { outputUri ->
                val uri = Uri.parse(outputUri)
                val deleted = runCatching {
                    DocumentFile.fromSingleUri(context, uri)?.delete() == true
                }.getOrDefault(false)
                if (!deleted) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }

        // App-storage outputs use FileProvider URIs, for which DocumentFile.delete() reports
        // success without deleting anything, so judge the result by the file itself.
        val displayPath = entity.outputDisplayPath?.takeIf { it.isNotBlank() } ?: return
        val file = File(displayPath)
        if (!file.isAbsolute || !file.exists() || !isInDownloadDirectory(file)) return
        if (!runCatching { file.delete() }.getOrDefault(false)) {
            Log.w(TAG, "Download output could not be deleted")
        }
    }

    private fun isInDownloadDirectory(file: File): Boolean {
        val parent = runCatching { file.canonicalFile.parentFile }.getOrNull() ?: return false
        return listOfNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), context.filesDir)
            .any { root -> runCatching { File(root, "StreamVault").canonicalFile }.getOrNull() == parent }
    }

    private fun buildFileName(entity: DownloadEntity, mimeType: String): String {
        val extension = entity.containerExtension?.takeIf { it.isNotBlank() }
            ?: extensionFromUrl(entity.streamUrl)
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: "mp4"
        val prefix = when (entity.contentType) {
            DownloadContentType.MOVIE -> entity.contentName
            DownloadContentType.SERIES_EPISODE -> listOfNotNull(
                entity.contentName,
                entity.seasonNumber?.let { "S$it" },
                entity.episodeNumber?.let { "E$it" }
            ).joinToString(" ")
        }
        val safeName = prefix
            .replace(Regex("[\\\\/:*?\"<>|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { entity.id }
        return "$safeName.$extension"
    }

    private fun extensionFromUrl(url: String): String? {
        val segment = Uri.parse(url).lastPathSegment ?: return null
        val extension = segment.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.length in 2..5 }
        return extension?.takeIf { ext -> ext.all { it.isLetterOrDigit() } }
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        val baseName = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        var candidate = File(directory, fileName)
        var index = 2
        while (candidate.exists()) {
            val suffix = if (extension.isBlank()) " ($index)" else " ($index).$extension"
            candidate = File(directory, "$baseName$suffix")
            index += 1
        }
        return candidate
    }

    private data class OutputTarget(
        val uri: Uri,
        val displayPath: String,
        val output: OutputStream
    )

    private companion object {
        const val TAG = "DownloadManager"
        const val MAX_RETRIES = 5
        const val INTERRUPTED_RESUME_REASON =
            "Interrupted after app process stopped; validated output queued to resume."
        const val INTERRUPTED_RESTART_REASON =
            "Interrupted output could not be validated; queued to restart safely."
        const val FOREGROUND_SERVICE_TIMEOUT_REASON =
            "Download paused because Android exhausted the foreground-service time allowance."
    }

    private class HttpDownloadException(val code: Int) : Exception("HTTP $code")

    private class RestartFromZeroException(cause: Throwable? = null) : Exception(cause)

    private class PlaybackStartedCancellation : CancellationException("Playback started")

    private class ForegroundServiceTimeoutCancellation :
        CancellationException("Android exhausted the foreground-service time allowance")
}

internal enum class InterruptedDownloadPlan {
    COMPLETE,
    RESUME,
    RESTART
}

internal fun planInterruptedDownload(
    bytesWritten: Long,
    totalBytes: Long?,
    supportsResume: Boolean,
    targetLength: Long?
): InterruptedDownloadPlan {
    if (targetLength != null && totalBytes != null && totalBytes > 0L && targetLength == totalBytes) {
        return InterruptedDownloadPlan.COMPLETE
    }
    if (supportsResume && bytesWritten > 0L && targetLength == bytesWritten) {
        return InterruptedDownloadPlan.RESUME
    }
    return InterruptedDownloadPlan.RESTART
}

internal sealed interface DownloadResumeResponse {
    data class TRANSFER(
        val append: Boolean,
        val totalBytes: Long?,
        val supportsResume: Boolean
    ) : DownloadResumeResponse

    data object COMPLETE : DownloadResumeResponse
    data object RESTART : DownloadResumeResponse
    data object FAIL : DownloadResumeResponse
}

internal fun resolveResumeResponse(
    resumeFrom: Long,
    responseCode: Int,
    contentLength: Long?,
    acceptRanges: String?,
    contentRange: String?
): DownloadResumeResponse {
    val parsedRange = parseContentRange(contentRange)
    if (resumeFrom > 0L) {
        if (responseCode == 416) {
            return if (parsedRange?.total == resumeFrom) {
                DownloadResumeResponse.COMPLETE
            } else {
                DownloadResumeResponse.RESTART
            }
        }
        if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
            if (parsedRange?.start != resumeFrom) return DownloadResumeResponse.RESTART
            return DownloadResumeResponse.TRANSFER(
                append = true,
                totalBytes = parsedRange.total
                    ?: contentLength?.takeIf { it >= 0L }?.let { resumeFrom + it },
                supportsResume = true
            )
        }
        if (responseCode == HttpURLConnection.HTTP_OK) {
            return DownloadResumeResponse.TRANSFER(
                append = false,
                totalBytes = contentLength?.takeIf { it >= 0L },
                supportsResume = acceptRanges.hasByteRangeSupport()
            )
        }
        return if (responseCode in 200..299) {
            DownloadResumeResponse.RESTART
        } else {
            DownloadResumeResponse.TRANSFER(
                append = false,
                totalBytes = contentLength?.takeIf { it >= 0L },
                supportsResume = false
            )
        }
    }

    if (responseCode == HttpURLConnection.HTTP_PARTIAL && parsedRange?.start != 0L) {
        return DownloadResumeResponse.FAIL
    }
    return DownloadResumeResponse.TRANSFER(
        append = false,
        totalBytes = parsedRange?.total ?: contentLength?.takeIf { it >= 0L },
        supportsResume = acceptRanges.hasByteRangeSupport() ||
            responseCode == HttpURLConnection.HTTP_PARTIAL
    )
}

internal fun buildDownloadHttpRequest(
    url: String,
    headers: Map<String, String>,
    userAgent: String?,
    resumeFrom: Long
): Request = Request.Builder()
    .url(url)
    .get()
    .apply {
        headers.forEach { (name, value) -> header(name, value) }
        userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
        if (resumeFrom > 0L) header("Range", "bytes=$resumeFrom-")
    }
    .build()

private data class ParsedContentRange(
    val start: Long?,
    val total: Long?
)

private fun parseContentRange(header: String?): ParsedContentRange? {
    val value = header?.trim() ?: return null
    Regex("""bytes\s+(\d+)-\d+/(\d+|\*)""", RegexOption.IGNORE_CASE)
        .matchEntire(value)
        ?.let { match ->
            return ParsedContentRange(
                start = match.groupValues[1].toLongOrNull(),
                total = match.groupValues[2].takeUnless { it == "*" }?.toLongOrNull()
            )
        }
    Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)
        .matchEntire(value)
        ?.let { match ->
            return ParsedContentRange(
                start = null,
                total = match.groupValues[1].toLongOrNull()
            )
        }
    return null
}

private fun String?.hasByteRangeSupport(): Boolean =
    this?.contains("bytes", ignoreCase = true) == true
