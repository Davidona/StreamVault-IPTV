package com.streamvault.data.manager

import android.content.Context
import android.content.ContextWrapper
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.tasks.Tasks
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.DriveAuthState
import com.streamvault.domain.manager.DriveSyncError
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.InspectBackupCommand
import com.streamvault.domain.usecase.InspectBackupResult
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import android.os.Build
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.O])
class GoogleDriveBackupSyncManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private val context: Context = mock()
    private val backupManager: BackupManager = mock()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheDir = Files.createTempDirectory("drive-sync-test").toFile()
        whenever(context.cacheDir).thenReturn(cacheDir)
        wheneverBlocking { backupManager.exportConfig(any()) }.doSuspendableAnswer { invocation ->
            val uri = invocation.getArgument<String>(0)
            File(URI(uri)).writeText("{\"version\":11,\"providers\":[]}")
            Result.Success(Unit)
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        cacheDir.deleteRecursively()
    }

    @Test
    fun pushBackup_uploadsOneBundleContainingMatchingCredentials() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        enqueueFileList(emptyList())

        val manager = manager()
        val credentials = listOf(
            ProviderCredentials("https://example.com", "alice", "secret")
        )

        val result = manager.pushBackup(credentials)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val upload = server.takeRequest()
        assertThat(upload.method).isEqualTo("POST")
        assertThat(upload.path).contains("upload/drive/v3/files")
        val uploadBody = upload.body.readUtf8()
        assertThat(uploadBody).contains("streamvault-drive-bundle-v2")
        assertThat(uploadBody).contains("backupJson")
        assertThat(uploadBody).contains("alice")
    }

    @Test
    fun pullBackup_v2PreservesBackupJsonTextForChecksumVerification() = runTest {
        enqueueFileList(listOf("bundle-id"))
        val backupJson = """{"version":11, "providers":[], "text":"\\u003clegacy\\u003e"}"""
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                JSONObject()
                    .put("format", "streamvault-drive-bundle-v2")
                    .put("backupJson", backupJson)
                    .put("credentials", JSONArray())
                    .toString()
            )
        )

        val manager = manager()
        val result = manager.pullBackup()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val artifact = (result as Result.Success).data
        assertThat(File(URI(artifact.localUriString)).readText()).isEqualTo(backupJson)
        File(URI(artifact.localUriString)).delete()
    }

    @Test
    fun pullBackupFeedsReleaseObfuscatedPayloadIntoRealInspector() = runTest {
        val payload = """{"version":18,"portableFavorites":[{"a":{"a":{"a":"https://example.com","b":"user","c":null,"d":"XTREAM_CODES"},"b":"LIVE","c":"42","f":"News"},"b":3,"c":1700000000000}]}"""
        val checksum = "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray())
            .joinToString(separator = "") { "%02x".format(it) }
        val backupJson = """{"version":18,"checksum":"$checksum","portableFavorites":[{"a":{"a":{"a":"https://example.com","b":"user","c":null,"d":"XTREAM_CODES"},"b":"LIVE","c":"42","f":"News"},"b":3,"c":1700000000000}]}"""
        enqueueFileList(listOf("compact-backup-id"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"format":"streamvault-drive-bundle-v2","backupJson":${Gson().toJson(backupJson)},"credentials":[]}"""
            )
        )

        val stagedCache = Files.createTempDirectory("drive-inspector-test").toFile()
        val appContext = RuntimeEnvironment.getApplication()
        val integrationContext = object : ContextWrapper(appContext) {
            override fun getCacheDir(): File = stagedCache
        }
        val driveManager = GoogleDriveBackupSyncManager(
            context = integrationContext,
            backupManager = backupManager,
            httpClient = okhttp3.OkHttpClient(),
            driveApiBaseUrl = server.url("/").toString(),
            fixedAccessToken = "test-token",
        )
        val inspector = backupInspector(integrationContext)

        try {
            val pullResult = driveManager.pullBackup()
            assertThat(pullResult).isInstanceOf(Result.Success::class.java)
            val artifact = (pullResult as Result.Success).data
            assertThat(File(URI(artifact.localUriString)).readText()).isEqualTo(backupJson)

            val inspectResult = ImportBackup(inspector).inspect(InspectBackupCommand(artifact.localUriString))

            assertThat(inspectResult).isInstanceOf(InspectBackupResult.Success::class.java)
            assertThat((inspectResult as InspectBackupResult.Success).preview.favoriteCount).isEqualTo(1)
        } finally {
            stagedCache.deleteRecursively()
        }
    }

    @Test
    fun pullBackup_extractsBundleCredentialsAndBackupPayload() = runTest {
        enqueueFileList(listOf("bundle-id"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "format":"streamvault-drive-bundle-v1",
                  "backup":{"version":11,"providers":[]},
                  "credentials":[{"serverUrl":"https://example.com","username":"alice","password":"secret"}]
                }
                """.trimIndent()
            )
        )

        val manager = manager()
        val result = manager.pullBackup()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val artifact = (result as Result.Success).data
        assertThat(artifact.credentials).containsExactly(
            ProviderCredentials("https://example.com", "alice", "secret")
        )
        assertThat(File(URI(artifact.localUriString)).readText()).contains("\"version\":11")
        File(URI(artifact.localUriString)).delete()
        assertThat(manager.syncStatus.value.lastErrorMessage).isNull()
    }

    @Test
    fun malformedBundle_isRejectedAndRecordedAsImportFailure() = runTest {
        enqueueFileList(listOf("bundle-id"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"format":"unknown","backup":{},"credentials":[]}"""
            )
        )

        val manager = manager()
        val result = manager.pullBackup()

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).isEqualTo(DriveSyncError.IMPORT_FAILED)
        assertThat(manager.syncStatus.value.lastErrorMessage).isEqualTo(DriveSyncError.IMPORT_FAILED)
    }

    @Test
    fun oversizedEmbeddedBackup_isRejectedBeforeStaging() = runTest {
        enqueueFileList(listOf("bundle-id"))
        val oversizedValue = "x".repeat(17 * 1024 * 1024)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"format":"streamvault-drive-bundle-v1","backup":{"version":11,"padding":"$oversizedValue"},"credentials":[]}
                """.trimIndent()
            )
        )

        val manager = manager()
        val result = manager.pullBackup()

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).isEqualTo(DriveSyncError.PAYLOAD_TOO_LARGE)
        assertThat(cacheDir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun unauthorizedLookup_isClassifiedAsAuthFailureAndRecorded() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

        val manager = manager()
        val result = manager.pullBackup()

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).isEqualTo(DriveSyncError.AUTH_FAILED)
        assertThat(manager.syncStatus.value.lastErrorMessage).isEqualTo(DriveSyncError.AUTH_FAILED)
    }

    @Test
    fun uploadFailure_isClassifiedAsNetworkFailureAndRecorded() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

        val driveManager = manager()
        val result = driveManager.pushBackup(emptyList())

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).isEqualTo(DriveSyncError.NETWORK)
        assertThat(driveManager.syncStatus.value.lastErrorMessage).isEqualTo(DriveSyncError.NETWORK)
    }

    @Test
    fun downloadFailure_isClassifiedAsNetworkFailureAndRecorded() = runTest {
        enqueueFileList(listOf("bundle-id"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))

        val driveManager = manager()
        val result = driveManager.pullBackup()

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).isEqualTo(DriveSyncError.NETWORK)
        assertThat(driveManager.syncStatus.value.lastErrorMessage).isEqualTo(DriveSyncError.NETWORK)
    }

    @Test
    fun signOut_revokesAccessBeforeClearingLocalSession() = runTest {
        val client = mock<GoogleSignInClient>()
        val account = mock<GoogleSignInAccount>()
        whenever(client.revokeAccess()).thenReturn(Tasks.forResult(null))
        whenever(client.signOut()).thenReturn(Tasks.forResult(null))

        val driveManager = GoogleDriveBackupSyncManager(
            context = context,
            backupManager = backupManager,
            httpClient = okhttp3.OkHttpClient(),
            driveApiBaseUrl = server.url("/").toString(),
            fixedAccessToken = "test-token",
            authClientFactory = { client },
            cachedAccountProvider = { account },
        )

        val result = driveManager.signOut()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(client).revokeAccess()
        verify(client).signOut()
        assertThat(driveManager.authState.value).isEqualTo(DriveAuthState.SignedOut)
    }

    @Test
    fun concurrentPushes_areSerializedToOneCreateAndOneUpdate() = runTest {
        val uploadCount = AtomicInteger(0)
        val uploadMethods = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.method == "GET" && request.path.orEmpty().startsWith("/drive/v3/files") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("{\"files\":[]}")
                    }
                    request.method == "POST" -> {
                        uploadMethods += request.method.orEmpty()
                        uploadCount.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody("{}")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val driveManager = manager()
        val results = coroutineScope {
            listOf(
                async { driveManager.pushBackup(emptyList()) },
                async { driveManager.pushBackup(emptyList()) },
            ).awaitAll()
        }

        results.forEach { result ->
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }
        assertThat(uploadCount.get()).isEqualTo(2)
        assertThat(uploadMethods).containsExactly("POST", "POST")
    }

    @Test
    fun pushBackup_retainsAtMostTenTimestampedSnapshots() = runTest {
        val deletedPaths = CopyOnWriteArrayList<String>()
        val existingFiles = (0..10).joinToString(",") { index ->
            val minute = index.toString().padStart(2, '0')
            "{\"id\":\"id-$index\",\"name\":\"streamvault_backup_bundle_existing_$index.json\",\"modifiedTime\":\"2026-08-14T10:$minute:00.000Z\",\"size\":\"123\"}"
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" -> MockResponse().setResponseCode(200).setBody("{}")
                request.method == "GET" -> MockResponse().setResponseCode(200).setBody("{\"files\":[$existingFiles]}")
                request.method == "DELETE" -> {
                    deletedPaths += request.path.orEmpty()
                    MockResponse().setResponseCode(204)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val result = manager().pushBackup(emptyList())

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(deletedPaths).containsExactly("/drive/v3/files/id-0")
    }

    @Test
    fun listBackups_returnsSnapshotsNewestFirst() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"files":[
                  {"id":"older","name":"streamvault_backup_bundle_older.json","modifiedTime":"2026-08-13T10:00:00.000Z","size":"200"},
                  {"id":"newer","name":"streamvault_backup_bundle_newer.json","modifiedTime":"2026-08-14T10:00:00.000Z","size":"300"}
                ]}
                """.trimIndent(),
            ),
        )

        val result = manager().listBackups()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val snapshots = (result as Result.Success).data
        assertThat(snapshots.map { it.id }).containsExactly("newer", "older").inOrder()
        assertThat(snapshots.first().sizeBytes).isEqualTo(300L)
    }

    @Test
    fun pullBackup_downloadsTheExplicitlySelectedSnapshot() = runTest {
        enqueueFileList(listOf("newer", "older"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"format":"streamvault-drive-bundle-v2","backupJson":"{\"version\":11,\"providers\":[]}","credentials":[]}""",
            ),
        )

        val result = manager().pullBackup("older")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val listRequest = server.takeRequest()
        val downloadRequest = server.takeRequest()
        assertThat(listRequest.method).isEqualTo("GET")
        assertThat(downloadRequest.path).contains("/drive/v3/files/older")
        File(URI((result as Result.Success).data.localUriString)).delete()
    }

    @Test
    fun deleteBackup_deletesTheSelectedSnapshot() = runTest {
        enqueueFileList(listOf("newer", "older"))
        server.enqueue(MockResponse().setResponseCode(204))

        val result = manager().deleteBackup("older")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(server.takeRequest().method).isEqualTo("GET")
        val deleteRequest = server.takeRequest()
        assertThat(deleteRequest.method).isEqualTo("DELETE")
        assertThat(deleteRequest.path).contains("/drive/v3/files/older")
    }

    @Test
    fun deleteBackup_removesLegacyCredentialsCompanionToo() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[{"id":"legacy","name":"streamvault_backup.json","modifiedTime":"2026-08-14T10:00:00.000Z","size":"123"}]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("{\"files\":[{\"id\":\"credentials\"}]}")
        )
        server.enqueue(MockResponse().setResponseCode(204))

        val result = manager().deleteBackup("legacy")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(server.takeRequest().method).isEqualTo("GET")
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
        assertThat(server.takeRequest().path).contains("name%3D%27streamvault_credentials.json%27")
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
    }

    private fun manager(): GoogleDriveBackupSyncManager = GoogleDriveBackupSyncManager(
        context = context,
        backupManager = backupManager,
        httpClient = okhttp3.OkHttpClient(),
        driveApiBaseUrl = server.url("/").toString(),
        fixedAccessToken = "test-token",
    )

    private fun backupInspector(context: Context): BackupManagerImpl {
        val providerDao: ProviderDao = mock()
        wheneverBlocking { providerDao.getAllSync() }.thenReturn(emptyList())
        val playbackHistoryDao: PlaybackHistoryDao = mock()
        wheneverBlocking { playbackHistoryDao.getAllSync() }.thenReturn(emptyList())
        val recordingManager: RecordingManager = mock()
        whenever(recordingManager.observeRecordingItems()).thenReturn(flowOf(emptyList()))

        return BackupManagerImpl(
            context = context,
            preferencesRepository = mock<PreferencesRepository>(),
            credentialCrypto = mock<CredentialCrypto>(),
            providerDao = providerDao,
            favoriteDao = mock(),
            virtualGroupDao = mock(),
            playbackHistoryDao = playbackHistoryDao,
            movieDao = mock(),
            episodeDao = mock(),
            categoryRepository = mock<CategoryRepository>(),
            recordingScheduleDao = mock(),
            recordingManager = recordingManager,
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = Gson(),
            channelDao = mock(),
            seriesDao = mock(),
        )
    }

    private fun enqueueFileList(ids: List<String>) {
        val files = ids.mapIndexed { index, id ->
            "{\"id\":\"$id\",\"name\":\"streamvault_backup_bundle_20260814_100000_00$index.json\",\"modifiedTime\":\"2026-08-14T10:00:0$index.000Z\",\"size\":\"123\"}"
        }.joinToString(",")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("{\"files\":[$files]}")
        )
    }
}
