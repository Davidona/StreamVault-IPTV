package com.streamvault.data.manager

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.BackupExportPlan
import com.streamvault.domain.manager.DriveAccount
import com.streamvault.domain.manager.DriveAuthState
import com.streamvault.domain.manager.DriveBackupArtifact
import com.streamvault.domain.manager.DriveBackupSnapshot
import com.streamvault.domain.manager.DriveBackupSyncManager
import com.streamvault.domain.manager.DriveSignInRequest
import com.streamvault.domain.manager.DriveSyncError
import com.streamvault.domain.manager.DriveSyncStatus
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val GOOGLE_DRIVE_API_BASE_URL = "https://www.googleapis.com"

private fun buildDefaultDriveHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

private fun buildDefaultGoogleDriveClient(context: Context): GoogleSignInClient {
    val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope("https://www.googleapis.com/auth/drive.appdata"))
        .build()
    return GoogleSignIn.getClient(context, options)
}

/**
 * Implementation backed by Google Sign-In + the Drive REST `appDataFolder` scope.
 *
 * - **No OAuth Client ID lives in this file** — Google Play Services resolves the
 *   client by matching the running app's `(packageName, signing SHA-1)` against
 *   the OAuth credentials registered in the Google Cloud Console project. Each
 *   maintainer / signing key must register its own OAuth client (see
 *   `docs/GOOGLE_DRIVE_SETUP.md`).
 * - New pushes use a timestamped versioned bundle containing the existing
 *   [BackupManager] JSON export and matching credentials snapshot. A bounded
 *   history lets users restore an older generation without reintroducing the
 *   old two-file generation mismatch.
 * - Pulls list snapshots newest-first and accept the bundle plus legacy
 *   standalone backup and credentials files for backwards compatibility.
 */
@Singleton
class GoogleDriveBackupSyncManager private constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val httpClient: OkHttpClient,
    driveApiBaseUrl: String,
    private val fixedAccessToken: String?,
    private val authClientFactory: () -> GoogleSignInClient,
    private val cachedAccountProvider: () -> GoogleSignInAccount?,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) : DriveBackupSyncManager {

    private val driveApiBaseUrl = driveApiBaseUrl.trimEnd('/')

    @Inject
    constructor(
        @ApplicationContext context: Context,
        backupManager: BackupManager,
    ) : this(
        context = context,
        backupManager = backupManager,
        httpClient = buildDefaultDriveHttpClient(),
        driveApiBaseUrl = GOOGLE_DRIVE_API_BASE_URL,
        fixedAccessToken = null,
        authClientFactory = { buildDefaultGoogleDriveClient(context) },
        cachedAccountProvider = { GoogleSignIn.getLastSignedInAccount(context) },
        constructorMarker = Unit,
    )

    internal constructor(
        context: Context,
        backupManager: BackupManager,
        httpClient: OkHttpClient,
        driveApiBaseUrl: String,
        fixedAccessToken: String,
        authClientFactory: (() -> GoogleSignInClient)? = null,
        cachedAccountProvider: (() -> GoogleSignInAccount?)? = null,
    ) : this(
        context = context,
        backupManager = backupManager,
        httpClient = httpClient,
        driveApiBaseUrl = driveApiBaseUrl,
        fixedAccessToken = fixedAccessToken,
        authClientFactory = authClientFactory ?: { buildDefaultGoogleDriveClient(context) },
        cachedAccountProvider = cachedAccountProvider ?: { GoogleSignIn.getLastSignedInAccount(context) },
        constructorMarker = Unit,
    )

    private val _authState = MutableStateFlow<DriveAuthState>(DriveAuthState.SignedOut)
    override val authState: StateFlow<DriveAuthState> = _authState.asStateFlow()

    private val _syncStatus = MutableStateFlow(DriveSyncStatus())
    override val syncStatus: StateFlow<DriveSyncStatus> = _syncStatus.asStateFlow()

    /**
     * Serializes operations that share the Drive file identity and auth state.
     * The UI also suppresses duplicate actions, but callers outside that UI
     * must not be able to race two lookup-then-upload sequences.
     */
    private val operationMutex = Mutex()

    init {
        if (fixedAccessToken != null) {
            _authState.value = DriveAuthState.SignedIn(DriveAccount("test", "test"))
        } else {
            // Restore last known account silently on cold start.
            val cached = cachedAccountProvider()
            if (cached != null && hasAppDataScope(cached)) {
                _authState.value = DriveAuthState.SignedIn(cached.toDriveAccount())
            }
        }
    }

    override suspend fun beginSignIn(): Result<DriveSignInRequest> = withContext(Dispatchers.IO) {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (availability != ConnectionResult.SUCCESS) {
            return@withContext Result.Error(
                message = DriveSyncError.PLAY_SERVICES_UNAVAILABLE,
                exception = null,
            )
        }
        _authState.value = DriveAuthState.Pending
        Result.Success(DriveSignInRequest(intent = buildClient().signInIntent))
    }

    override suspend fun completeSignIn(signInData: Any?): Result<DriveAccount> =
        withContext(Dispatchers.IO) {
            try {
                val data = signInData as? android.content.Intent
                Log.d("DriveSync", "completeSignIn intent=$data extras=${data?.extras}")
                val account = GoogleSignIn.getSignedInAccountFromIntent(data).awaitTask()
                Log.d("DriveSync", "account=$account email=${account?.email} grantedScopes=${account?.grantedScopes}")
                if (account == null) {
                    Log.w("DriveSync", "completeSignIn: account is null (user cancelled or sign-in failed silently)")
                    _authState.value = DriveAuthState.SignedOut
                    return@withContext Result.Error(DriveSyncError.AUTH_FAILED)
                }
                if (!hasAppDataScope(account)) {
                    Log.w("DriveSync", "completeSignIn: drive.appdata scope NOT granted, grantedScopes=${account.grantedScopes}")
                    _authState.value = DriveAuthState.SignedOut
                    return@withContext Result.Error(DriveSyncError.AUTH_FAILED)
                }
                val driveAccount = account.toDriveAccount()
                Log.d("DriveSync", "completeSignIn: SUCCESS for ${driveAccount.email}")
                _authState.value = DriveAuthState.SignedIn(driveAccount)
                Result.Success(driveAccount)
            } catch (apiError: ApiException) {
                Log.e("DriveSync", "completeSignIn: ApiException statusCode=${apiError.statusCode} message=${apiError.message}", apiError)
                _authState.value = DriveAuthState.SignedOut
                Result.Error(DriveSyncError.AUTH_FAILED, apiError)
            } catch (t: Throwable) {
                Log.e("DriveSync", "completeSignIn: Throwable", t)
                _authState.value = DriveAuthState.SignedOut
                Result.Error(DriveSyncError.AUTH_FAILED, t)
            }
        }

    override suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                val client = buildClient()
                val revokeFailure = runCatching {
                    if (cachedAccountProvider() != null) {
                        client.revokeAccess().awaitTask()
                    }
                }.exceptionOrNull()
                client.signOut().awaitTask()
                _authState.value = DriveAuthState.SignedOut
                if (revokeFailure != null) {
                    driveError(DriveSyncError.AUTH_FAILED, revokeFailure)
                } else {
                    Result.Success(Unit)
                }
            } catch (t: Throwable) {
                _authState.value = DriveAuthState.SignedOut
                driveError(DriveSyncError.AUTH_FAILED, t)
            }
        }
    }

    /**
     * Lightweight `await()` shim for `Task<T>` — avoids adding the
     * `kotlinx-coroutines-play-services` artifact just to bridge two callbacks.
     */
    private suspend fun <T> Task<T>.awaitTask(): T? = suspendCancellableCoroutine { cont ->
        if (isComplete) {
            val exception = exception
            if (exception == null) {
                @Suppress("UNCHECKED_CAST")
                if (isCanceled) cont.resume(null) else cont.resume(result as T)
            } else {
                cont.resumeWithException(exception)
            }
            return@suspendCancellableCoroutine
        }
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.resume(null) }
    }

    suspend fun pushBackup(credentials: List<ProviderCredentials> = emptyList()): Result<DriveSyncStatus> =
        pushBackup(credentials, BackupExportPlan())

    override suspend fun pushBackup(
        credentials: List<ProviderCredentials>,
        plan: BackupExportPlan,
    ): Result<DriveSyncStatus> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val accessTokenResult = accessTokenResult()
            if (accessTokenResult is Result.Error) return@withContext accessTokenResult
            val token = (accessTokenResult as Result.Success).data

            val tempFile = File.createTempFile("drive_sync_backup_", ".json", context.cacheDir)
            val bundleFile = File.createTempFile("drive_sync_bundle_", ".json", context.cacheDir)
            val exportUri = tempFile.toURI().toString()

            try {
                val exportResult = backupManager.exportConfig(exportUri, plan)
                if (exportResult is Result.Error) {
                    return@withContext driveError(DriveSyncError.EXPORT_FAILED, exportResult.exception)
                }
                if (!tempFile.exists() || tempFile.length() == 0L || tempFile.length() > MAX_BACKUP_BYTES) {
                    return@withContext driveError(DriveSyncError.EXPORT_FAILED)
                }

                val bundleBuilt = try {
                    // Keep the exported backup text unchanged. Re-parsing it into a
                    // JSONObject and serializing it again can alter escaping/order and
                    // breaks exact-byte checksum compatibility for older releases.
                    val backupJson = tempFile.readText(Charsets.UTF_8)
                    JSONObject(backupJson)
                    val bundle = JSONObject()
                        .put("format", DRIVE_BUNDLE_FORMAT)
                        .put("backupJson", backupJson)
                        .put("credentials", (if (plan.exportProviders) credentials else emptyList()).toJsonArray())
                    bundleFile.writeText(bundle.toString(), Charsets.UTF_8)
                    bundleFile.length() > 0L && bundleFile.length() <= MAX_DRIVE_FILE_BYTES
                } catch (t: Throwable) {
                    Log.e("DriveSync", "pushBackup: failed to build bundle", t)
                    false
                }
                if (!bundleBuilt) {
                    return@withContext driveError(DriveSyncError.EXPORT_FAILED)
                }

                val bundleFileName = buildBundleFileName()
                val uploadOk = try {
                    uploadAppDataFile(
                        authToken = token,
                        payload = bundleFile,
                        fileName = bundleFileName,
                        overwriteExisting = false,
                    )
                } catch (io: IOException) {
                    return@withContext driveHttpError(io)
                }
                if (!uploadOk) {
                    return@withContext driveError(DriveSyncError.NETWORK)
                }

                trimRemoteBackups(token)

                val updated = _syncStatus.value.copy(
                    lastPushAtMs = System.currentTimeMillis(),
                    lastErrorMessage = null,
                )
                _syncStatus.value = updated
                Result.Success(updated)
            } finally {
                // Export can fail or be cancelled before the upload's cleanup is reached.
                tempFile.delete()
                bundleFile.delete()
            }
        }
    }

    override suspend fun listBackups(): Result<List<DriveBackupSnapshot>> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val accessTokenResult = accessTokenResult()
            if (accessTokenResult is Result.Error) return@withContext accessTokenResult
            val token = (accessTokenResult as Result.Success).data
            try {
                Result.Success(listRemoteBackups(token).map { it.toSnapshot() })
            } catch (io: IOException) {
                driveHttpError(io)
            }
        }
    }

    override suspend fun deleteBackup(snapshotId: String): Result<Unit> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val accessTokenResult = accessTokenResult()
            if (accessTokenResult is Result.Error) return@withContext accessTokenResult
            val token = (accessTokenResult as Result.Success).data
            val selected = try {
                listRemoteBackups(token).firstOrNull { it.id == snapshotId }
            } catch (io: IOException) {
                return@withContext driveHttpError(io)
            }
            if (selected == null) {
                return@withContext driveError(DriveSyncError.NO_REMOTE_BACKUP)
            }
            try {
                deleteRemoteFile(token, selected.id)
                if (!selected.isBundle) {
                    // Legacy installs stored credentials in a sibling file.
                    // Remove it with the legacy backup so sensitive orphaned
                    // credentials are not left behind after user cleanup.
                    val legacyCredentialsId = findRemoteFileId(token, CREDENTIALS_FILE_NAME)
                    if (legacyCredentialsId != null) {
                        runCatching { deleteRemoteFile(token, legacyCredentialsId) }
                            .onFailure { error ->
                                Log.w("DriveSync", "legacy credentials cleanup failed", error)
                            }
                    }
                }
                Result.Success(Unit)
            } catch (io: IOException) {
                driveHttpError(io)
            }
        }
    }

    override suspend fun pullBackup(snapshotId: String?): Result<DriveBackupArtifact> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
        Log.d("DriveSync", "pullBackup: start")
        val accessTokenResult = accessTokenResult()
        if (accessTokenResult is Result.Error) return@withContext accessTokenResult
        val token = (accessTokenResult as Result.Success).data
        Log.d("DriveSync", "pullBackup: token OK, looking up remote snapshots")

        val remoteBackups = try {
            listRemoteBackups(token)
        } catch (io: IOException) {
            Log.e("DriveSync", "pullBackup: NETWORK error while finding snapshots", io)
            return@withContext driveHttpError(io)
        }
        val selected = if (snapshotId == null) {
            remoteBackups.firstOrNull()
        } else {
            remoteBackups.firstOrNull { it.id == snapshotId }
        }
        if (selected == null) {
            Log.w("DriveSync", "pullBackup: NO_REMOTE_BACKUP (no bundle or legacy file found)")
            return@withContext driveError(DriveSyncError.NO_REMOTE_BACKUP)
        }
        val legacyFallback = !selected.isBundle
        val fileId = selected.id
        Log.d("DriveSync", "pullBackup: remote fileId=$fileId, name=${selected.name}, bundle=${selected.isBundle}, downloading")

        val target = File.createTempFile("drive_sync_backup_", ".json", context.cacheDir).apply { delete() }
        val downloadTarget = if (legacyFallback) {
            target
        } else {
            File.createTempFile("drive_sync_bundle_", ".json", context.cacheDir).apply { delete() }
        }
        val downloadOk = try {
            downloadAppDataFile(
                authToken = token,
                fileId = fileId,
                target = downloadTarget,
                maxBytes = if (legacyFallback) MAX_BACKUP_BYTES else MAX_DRIVE_FILE_BYTES,
            )
        } catch (tooLarge: DrivePayloadTooLargeException) {
            downloadTarget.delete()
            return@withContext driveError(DriveSyncError.PAYLOAD_TOO_LARGE, tooLarge)
        } catch (io: IOException) {
            Log.e("DriveSync", "pullBackup: NETWORK error during download", io)
            downloadTarget.delete()
            return@withContext driveHttpError(io)
        }
        if (!downloadOk || !downloadTarget.exists() || downloadTarget.length() == 0L) {
            Log.w("DriveSync", "pullBackup: download failed or empty file (ok=$downloadOk exists=${downloadTarget.exists()} size=${downloadTarget.length()})")
            downloadTarget.delete()
            return@withContext driveError(DriveSyncError.NETWORK)
        }

        val credentials: List<ProviderCredentials>?
        if (legacyFallback) {
            credentials = null
        } else {
            try {
                val bundle = JSONObject(downloadTarget.readText(Charsets.UTF_8))
                val format = bundle.optString("format")
                val backupJson = when (format) {
                    DRIVE_BUNDLE_FORMAT -> bundle.optString("backupJson", "")
                    LEGACY_DRIVE_BUNDLE_FORMAT -> bundle.optJSONObject("backup")?.toString().orEmpty()
                    else -> throw IllegalArgumentException("Unsupported Drive backup bundle format")
                }
                if (backupJson.isBlank()) {
                    throw IllegalArgumentException("Drive backup bundle is missing backup")
                }
                // Validate that the preserved text is one JSON object, but write
                // the original text rather than JSONObject.toString() so the
                // backup checksum sees the bytes that were exported.
                JSONObject(backupJson)
                val credentialsArray = bundle.optJSONArray("credentials")
                    ?: throw IllegalArgumentException("Drive backup bundle is missing credentials")
                if (backupJson.toByteArray(Charsets.UTF_8).size.toLong() > MAX_BACKUP_BYTES) {
                    throw DrivePayloadTooLargeException()
                }
                target.writeBytes(backupJson.toByteArray(Charsets.UTF_8))
                credentials = credentialsArray.toProviderCredentials()
            } catch (tooLarge: DrivePayloadTooLargeException) {
                target.delete()
                downloadTarget.delete()
                return@withContext driveError(DriveSyncError.PAYLOAD_TOO_LARGE, tooLarge)
            } catch (t: Throwable) {
                Log.e("DriveSync", "pullBackup: bundle parse error", t)
                target.delete()
                downloadTarget.delete()
                return@withContext driveError(DriveSyncError.IMPORT_FAILED, t)
            } finally {
                downloadTarget.delete()
            }
        }
        if (!target.exists() || target.length() == 0L) {
            target.delete()
            return@withContext driveError(DriveSyncError.IMPORT_FAILED)
        }
        Log.d("DriveSync", "pullBackup: downloaded ${target.length()} bytes to ${target.absolutePath}")

        _syncStatus.value = _syncStatus.value.copy(
            lastPullAtMs = System.currentTimeMillis(),
            lastErrorMessage = null,
        )
            Result.Success(
                DriveBackupArtifact(
                    localUriString = target.toURI().toString(),
                    sizeBytes = target.length(),
                    credentials = credentials,
                ),
            )
        }
    }

    override suspend fun pushCredentials(
        credentials: List<ProviderCredentials>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
        Log.d("DriveSync", "pushCredentials: start (count=${credentials.size})")
        val accessTokenResult = accessTokenResult()
        if (accessTokenResult is Result.Error) return@withContext accessTokenResult
        val token = (accessTokenResult as Result.Success).data

        val json = credentials.toJsonArray().toString()

        val tempFile = File.createTempFile("drive_sync_credentials_", ".json", context.cacheDir)
        tempFile.writeText(json)

        val uploadOk = try {
            uploadAppDataFile(token, tempFile, CREDENTIALS_FILE_NAME)
        } catch (io: IOException) {
            return@withContext driveHttpError(io)
        } finally {
            tempFile.delete()
        }
        if (!uploadOk) {
            Log.w("DriveSync", "pushCredentials: upload failed")
            return@withContext driveError(DriveSyncError.NETWORK)
        }
        Log.d("DriveSync", "pushCredentials: SUCCESS")
        _syncStatus.value = _syncStatus.value.copy(lastErrorMessage = null)
            Result.Success(Unit)
        }
    }

    override suspend fun pullCredentials(): Result<List<ProviderCredentials>> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
        Log.d("DriveSync", "pullCredentials: start")
        val accessTokenResult = accessTokenResult()
        if (accessTokenResult is Result.Error) return@withContext accessTokenResult
        val token = (accessTokenResult as Result.Success).data

        val fileId = try {
            findRemoteFileId(token, CREDENTIALS_FILE_NAME)
        } catch (io: IOException) {
            return@withContext driveHttpError(io)
        }
        if (fileId == null) {
            Log.d("DriveSync", "pullCredentials: no remote credentials file (pre-M3 backup), returning empty list")
            return@withContext Result.Success(emptyList())
        }

        val target = File.createTempFile("drive_sync_credentials_", ".json", context.cacheDir).apply { delete() }
        val downloadOk = try {
            downloadAppDataFile(token, fileId, target, MAX_DRIVE_FILE_BYTES)
        } catch (tooLarge: DrivePayloadTooLargeException) {
            target.delete()
            return@withContext driveError(DriveSyncError.PAYLOAD_TOO_LARGE, tooLarge)
        } catch (io: IOException) {
            target.delete()
            return@withContext driveHttpError(io)
        }
        if (!downloadOk || !target.exists() || target.length() == 0L) {
            target.delete()
            return@withContext driveError(DriveSyncError.NETWORK)
        }

        val parsed = try {
            JSONArray(target.readText(Charsets.UTF_8)).toProviderCredentials()
        } catch (t: Throwable) {
            Log.e("DriveSync", "pullCredentials: JSON parse error", t)
            target.delete()
            return@withContext driveError(DriveSyncError.IMPORT_FAILED, t)
        } finally {
            target.delete()
        }
        Log.d("DriveSync", "pullCredentials: SUCCESS (count=${parsed.size})")
        _syncStatus.value = _syncStatus.value.copy(lastErrorMessage = null)
            Result.Success(parsed)
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun buildClient(): GoogleSignInClient {
        return authClientFactory()
    }

    private fun hasAppDataScope(account: GoogleSignInAccount): Boolean =
        GoogleSignIn.hasPermissions(account, Scope(SCOPE_APP_DATA))

    private fun requireSignedInAccount(): GoogleSignInAccount? {
        val cached = cachedAccountProvider() ?: return null
        if (!hasAppDataScope(cached)) return null
        return cached
    }

    /**
     * Synchronous OAuth2 token retrieval. Must be called off the main thread.
     * The token is never logged.
     */
    private fun fetchAccessToken(account: GoogleSignInAccount): String? = try {
        val androidAccount = account.account ?: return null
        GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$SCOPE_APP_DATA")
    } catch (t: Throwable) {
        null
    }

    private fun accessTokenResult(): Result<String> {
        fixedAccessToken?.let { return Result.Success(it) }
        val account = requireSignedInAccount()
            ?: return driveError(DriveSyncError.NOT_SIGNED_IN)
        val token = fetchAccessToken(account)
            ?: return driveError(DriveSyncError.AUTH_FAILED)
        return Result.Success(token)
    }

    private fun driveError(code: String, exception: Throwable? = null): Result.Error {
        _syncStatus.value = _syncStatus.value.copy(lastErrorMessage = code)
        return Result.Error(code, exception)
    }

    private fun driveHttpError(exception: IOException): Result.Error {
        val statusCode = (exception as? DriveHttpException)?.statusCode
        val code = if (statusCode == 401 || statusCode == 403) {
            DriveSyncError.AUTH_FAILED
        } else {
            DriveSyncError.NETWORK
        }
        return driveError(code, exception)
    }

    private suspend fun findRemoteFileId(authToken: String, fileName: String): String? {
        val url = "$driveApiBaseUrl/drive/v3/files" +
            "?spaces=appDataFolder" +
            "&q=" + uriEncode("name='$fileName'") +
            "&orderBy=modifiedTime%20desc" +
            "&fields=files(id,modifiedTime,size)"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $authToken")
            .get()
            .build()
        httpClient.newCall(request).useCancellableResponse { response ->
            if (!response.isSuccessful) {
                throw DriveHttpException(response.code, "Drive file lookup failed")
            }
            val body = response.body?.string().orEmpty()
            val files = JSONObject(body).optJSONArray("files")
                ?: throw IOException("Drive file lookup response is missing files")
            if (files.length() == 0) return null
            return files.getJSONObject(0).getString("id")
        }
    }

    private suspend fun uploadAppDataFile(
        authToken: String,
        payload: File,
        fileName: String,
        overwriteExisting: Boolean = true,
    ): Boolean {
        val existingId = if (overwriteExisting) {
            findRemoteFileId(authToken, fileName)
        } else {
            null
        }
        val boundary = "streamvault-${System.nanoTime()}"
        val (httpMethod, endpoint) = if (existingId != null) {
            "PATCH" to "$driveApiBaseUrl/upload/drive/v3/files/$existingId?uploadType=multipart"
        } else {
            "POST" to "$driveApiBaseUrl/upload/drive/v3/files?uploadType=multipart"
        }

        val metadata = JSONObject().apply {
            put("name", fileName)
            if (existingId == null) {
                put("parents", JSONArray().put("appDataFolder"))
            }
        }.toString()

        val body = MultipartRelatedBody(boundary, metadata, payload)
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $authToken")
            .method(httpMethod, body)
            .build()

        httpClient.newCall(request).useCancellableResponse { response ->
            if (!response.isSuccessful) {
                throw DriveHttpException(response.code, "Drive upload failed")
            }
            return true
        }
    }

    /**
     * Returns the backup files we own in Drive, newest first. The fixed names
     * keep old installs and pre-history backups visible; timestamped bundles
     * are the files created by current pushes.
     */
    private suspend fun listRemoteBackups(authToken: String): List<RemoteBackupFile> {
        val query = "trashed = false and (" +
            "name = '$BUNDLE_FILE_NAME' or " +
            "name contains '$BUNDLE_FILE_PREFIX' or " +
            "name = '$BACKUP_FILE_NAME')"
        val url = "$driveApiBaseUrl/drive/v3/files" +
            "?spaces=appDataFolder" +
            "&q=" + uriEncode(query) +
            "&orderBy=modifiedTime%20desc" +
            "&pageSize=100" +
            "&fields=files(id,name,modifiedTime,size)"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $authToken")
            .get()
            .build()
        httpClient.newCall(request).useCancellableResponse { response ->
            if (!response.isSuccessful) {
                throw DriveHttpException(response.code, "Drive backup list failed")
            }
            val body = response.body?.string().orEmpty()
            val files = JSONObject(body).optJSONArray("files")
                ?: throw IOException("Drive backup list response is missing files")
            return (0 until files.length())
                .mapNotNull { index ->
                    val file = files.optJSONObject(index) ?: return@mapNotNull null
                    val id = file.optString("id").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val name = file.optString("name").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    RemoteBackupFile(
                        id = id,
                        name = name,
                        modifiedAtMs = parseDriveTimestamp(file.optString("modifiedTime")),
                        sizeBytes = file.optLong("size", 0L).coerceAtLeast(0L),
                        isBundle = name == BUNDLE_FILE_NAME || name.startsWith(BUNDLE_FILE_PREFIX),
                    )
                }
                .sortedWith(
                    compareByDescending<RemoteBackupFile> { it.modifiedAtMs ?: Long.MIN_VALUE }
                        .thenByDescending { it.id },
                )
        }
    }

    private suspend fun trimRemoteBackups(authToken: String) {
        try {
            val timestamped = listRemoteBackups(authToken)
                .filter { it.isBundle && it.name.startsWith(BUNDLE_FILE_PREFIX) }
            timestamped.drop(MAX_REMOTE_BACKUPS).forEach { remote ->
                runCatching { deleteRemoteFile(authToken, remote.id) }
                    .onFailure { error ->
                        Log.w("DriveSync", "retention cleanup failed for ${remote.id}", error)
                    }
            }
        } catch (io: IOException) {
            // Retention is housekeeping. A successful upload must remain a
            // success even if listing/cleanup is temporarily unavailable.
            Log.w("DriveSync", "retention listing failed", io)
        }
    }

    private suspend fun deleteRemoteFile(authToken: String, fileId: String) {
        val request = Request.Builder()
            .url("$driveApiBaseUrl/drive/v3/files/$fileId")
            .addHeader("Authorization", "Bearer $authToken")
            .delete()
            .build()
        httpClient.newCall(request).useCancellableResponse { response ->
            if (!response.isSuccessful) {
                throw DriveHttpException(response.code, "Drive backup retention delete failed")
            }
        }
    }

    private fun parseDriveTimestamp(value: String): Long? {
        if (value.isBlank()) return null
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
        )
        return formats.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            }.getOrNull()
        }
    }

    private fun buildBundleFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        return "$BUNDLE_FILE_PREFIX${timestamp}_${UUID.randomUUID().toString().take(8)}.json"
    }

    private suspend fun downloadAppDataFile(
        authToken: String,
        fileId: String,
        target: File,
        maxBytes: Long,
    ): Boolean {
        val request = Request.Builder()
            .url("$driveApiBaseUrl/drive/v3/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $authToken")
            .get()
            .build()
        httpClient.newCall(request).useCancellableResponse { response ->
            if (!response.isSuccessful) {
                throw DriveHttpException(response.code, "Drive download failed")
            }
            val body = response.body ?: return false
            if (body.contentLength() > maxBytes) {
                throw DrivePayloadTooLargeException()
            }
            body.byteStream().use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            throw DrivePayloadTooLargeException()
                        }
                        sink.write(buffer, 0, read)
                    }
                }
            }
            return true
        }
    }

    private fun GoogleSignInAccount.toDriveAccount(): DriveAccount =
        DriveAccount(email = email, displayName = displayName)

    private fun uriEncode(value: String): String =
        // URLEncoder.encode(String, Charset) is API 33+ only.
        // We support minSdk 28, so use the String charset name overload.
        java.net.URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val SCOPE_APP_DATA = "https://www.googleapis.com/auth/drive.appdata"
        const val BUNDLE_FILE_NAME = "streamvault_backup_bundle.json"
        const val BUNDLE_FILE_PREFIX = "streamvault_backup_bundle_"
        const val BACKUP_FILE_NAME = "streamvault_backup.json"
        const val CREDENTIALS_FILE_NAME = "streamvault_credentials.json"
        const val DRIVE_BUNDLE_FORMAT = "streamvault-drive-bundle-v2"
        const val LEGACY_DRIVE_BUNDLE_FORMAT = "streamvault-drive-bundle-v1"
        const val MAX_BACKUP_BYTES = 16L * 1024L * 1024L
        // v2 stores the backup as an escaped JSON string, so allow the bundle
        // to be up to roughly twice the standalone backup admission limit.
        const val MAX_DRIVE_FILE_BYTES = 40L * 1024L * 1024L
        const val MAX_REMOTE_BACKUPS = 10
    }
}

private data class RemoteBackupFile(
    val id: String,
    val name: String,
    val modifiedAtMs: Long?,
    val sizeBytes: Long,
    val isBundle: Boolean,
)

private fun RemoteBackupFile.toSnapshot(): DriveBackupSnapshot = DriveBackupSnapshot(
    id = id,
    fileName = name,
    modifiedAtMs = modifiedAtMs,
    sizeBytes = sizeBytes,
    isBundle = isBundle,
)

private class DriveHttpException(
    val statusCode: Int,
    message: String,
) : IOException("$message: HTTP $statusCode")

private class DrivePayloadTooLargeException : IOException("Drive payload exceeds the maximum supported size")
private const val MAX_CREDENTIAL_COUNT = 10_000

private fun List<ProviderCredentials>.toJsonArray(): JSONArray = JSONArray().apply {
    forEach { credential ->
        put(
            JSONObject().apply {
                put("serverUrl", credential.serverUrl)
                put("username", credential.username)
                put("password", credential.password)
            },
        )
    }
}

private fun JSONArray.toProviderCredentials(): List<ProviderCredentials> {
    if (length() > MAX_CREDENTIAL_COUNT) {
        throw IllegalArgumentException("Too many provider credentials in Drive backup")
    }
    return (0 until length()).map { index ->
        val objectValue = getJSONObject(index)
        ProviderCredentials(
            serverUrl = objectValue.getString("serverUrl"),
            username = objectValue.getString("username"),
            password = objectValue.getString("password"),
        )
    }
}

/**
 * `multipart/related` body the Drive REST API expects for a single-shot create
 * or update — metadata part first, then the JSON payload, separated by the
 * given [boundary]. Streams the payload from disk to avoid loading the full
 * backup in memory.
 */
private class MultipartRelatedBody(
    private val boundary: String,
    private val metadataJson: String,
    private val payload: File,
) : okhttp3.RequestBody() {

    override fun contentType(): okhttp3.MediaType =
        "multipart/related; boundary=$boundary".toMediaTypeOrNull()!!

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("--$boundary\r\n")
        sink.writeUtf8("Content-Type: application/json; charset=UTF-8\r\n\r\n")
        sink.writeUtf8(metadataJson)
        sink.writeUtf8("\r\n--$boundary\r\n")
        sink.writeUtf8("Content-Type: application/json\r\n\r\n")
        val payloadBody = payload.asRequestBody("application/json".toMediaTypeOrNull())
        payloadBody.writeTo(sink)
        sink.writeUtf8("\r\n--$boundary--\r\n")
    }

    @Suppress("unused")
    private fun emptyRequestBody() = "".toRequestBody(null)
}
