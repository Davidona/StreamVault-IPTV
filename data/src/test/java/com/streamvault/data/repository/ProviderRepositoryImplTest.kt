package com.streamvault.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.MovieCategoryHydrationDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProgramReminderDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderConfigRevisionDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.dao.ProviderDeletionCleanupDao
import com.streamvault.data.local.dao.RecordingRunDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.SeriesCategoryHydrationDao
import com.streamvault.data.local.dao.StalkerIndexJobDao
import com.streamvault.data.local.entity.*
import com.streamvault.data.local.entity.ProviderConfigRevisionState
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.StalkerIndexJobEntity
import com.streamvault.data.local.entity.ProviderDeletionCleanupEntity
import com.streamvault.data.manager.recording.RecordingAlarmScheduler
import com.streamvault.data.manager.reminder.ProgramReminderAlarmScheduler
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.jellyfin.JellyfinProvider
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerApiError
import com.streamvault.data.remote.stalker.StalkerDeviceProfile
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerProviderProfile
import com.streamvault.data.remote.stalker.StalkerSession
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.dto.XtreamAuthResponse
import com.streamvault.data.remote.dto.XtreamServerInfo
import com.streamvault.data.remote.dto.XtreamUserInfo
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.provider.ProviderConfigurationCodec
import com.streamvault.data.provider.ProviderCapabilityResolver
import com.streamvault.data.provider.TypedProviderClientFactory
import com.streamvault.data.remote.stalker.StalkerPortalStateStore
import com.streamvault.data.remote.stalker.StalkerRemoteIdentityResolver
import com.streamvault.data.provider.toProviderSnapshot
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.sync.SyncManager
import com.streamvault.data.sync.ProviderWorkflowDisposition
import com.streamvault.data.sync.ProviderWorkflowOutcome
import com.streamvault.data.sync.ProviderWorkflowRunner
import com.streamvault.data.sync.ProviderWorkflowCommitFence
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderSavedWithSyncErrorException
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.StalkerIndexState
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerTransportGrant
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.StalkerTransportOrigin
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProviderRepositoryImplTest {

    private val providerDao: ProviderDao = mock()
    private val providerSnapshotDao: ProviderSnapshotDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val channelDao: ChannelDao = mock()
    private val movieDao: MovieDao = mock()
    private val seriesDao: SeriesDao = mock()
    private val programDao: ProgramDao = mock()
    private val recordingRunDao: RecordingRunDao = mock()
    private val programReminderDao: ProgramReminderDao = mock()
    private val stalkerApiService: StalkerApiService = mock()
    private val xtreamApiService: XtreamApiService = mock()
    private val credentialCrypto: CredentialCrypto = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val syncManager: SyncManager = mock()
    private val syncMetadataRepository: SyncMetadataRepository = mock()
    private val recordingAlarmScheduler: RecordingAlarmScheduler = mock()
    private val programReminderAlarmScheduler: ProgramReminderAlarmScheduler = mock()
    private val jellyfinProvider: JellyfinProvider = mock()
    private val stalkerIndexJobDao: StalkerIndexJobDao = mock()
    private val movieCategoryHydrationDao: MovieCategoryHydrationDao = mock()
    private val seriesCategoryHydrationDao: SeriesCategoryHydrationDao = mock()
    private val providerDeletionCleanupDao: ProviderDeletionCleanupDao = mock()
    private val providerDeletionCleanupEnqueuer: ProviderDeletionCleanupEnqueuer = mock()
    private val providerConfigRevisionDao: ProviderConfigRevisionDao = mock()
    private val providerWorkflowRunner: ProviderWorkflowRunner = mock()
    private val providerCapabilityResolver: ProviderCapabilityResolver = mock()
    private val stalkerRemoteIdentityResolver: StalkerRemoteIdentityResolver = mock()
    private val stalkerPortalStateStore: StalkerPortalStateStore = mock()
    private val typedProviderClientFactory = TypedProviderClientFactory(
        xtreamApiService = xtreamApiService,
        stalkerApiService = stalkerApiService,
        jellyfinProvider = jellyfinProvider,
        preferencesRepository = preferencesRepository,
        stalkerRemoteIdentityResolver = stalkerRemoteIdentityResolver,
        stalkerPortalStateStore = stalkerPortalStateStore
    )
    private val gson = Gson()
    private val appContext: Context = mock()
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private fun createRepository(
        transactionRunner: DatabaseTransactionRunner = this.transactionRunner
    ) = ProviderRepositoryImpl(
        providerDao = providerDao,
        providerSnapshotDao = providerSnapshotDao,
        categoryDao = categoryDao,
        channelDao = channelDao,
        movieDao = movieDao,
        seriesDao = seriesDao,
        programDao = programDao,
        recordingRunDao = recordingRunDao,
        programReminderDao = programReminderDao,
        stalkerApiService = stalkerApiService,
        credentialCrypto = credentialCrypto,
        providerConfigurationCodec = ProviderConfigurationCodec(gson, credentialCrypto),
        preferencesRepository = preferencesRepository,
        syncManager = syncManager,
        syncMetadataRepository = syncMetadataRepository,
        transactionRunner = transactionRunner,
        recordingAlarmScheduler = recordingAlarmScheduler,
        programReminderAlarmScheduler = programReminderAlarmScheduler,
        jellyfinProvider = jellyfinProvider,
        stalkerIndexJobDao = stalkerIndexJobDao,
        stalkerPortalStateStore = stalkerPortalStateStore,
        movieCategoryHydrationDao = movieCategoryHydrationDao,
        seriesCategoryHydrationDao = seriesCategoryHydrationDao,
        providerDeletionCleanupDao = providerDeletionCleanupDao,
        providerDeletionCleanupEnqueuer = providerDeletionCleanupEnqueuer,
        providerConfigRevisionDao = providerConfigRevisionDao,
        gson = gson,
        providerWorkflowRunner = providerWorkflowRunner,
        providerWorkflowCommitFence = ProviderWorkflowCommitFence(),
        providerCapabilityResolver = providerCapabilityResolver,
        typedProviderClientFactory = typedProviderClientFactory,
        appContext = appContext
    )

    private val repository = createRepository()

    init {
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
        whenever(credentialCrypto.encryptIfNeeded(any())).thenAnswer { invocation ->
            invocation.getArgument<String>(0)
        }
        runBlocking {
            whenever(providerSnapshotDao.getConfig(any())).thenReturn(null)
            whenever(providerSnapshotDao.commitConfiguration(any())).thenReturn(true)
            whenever(providerConfigRevisionDao.latestRevision(any())).thenReturn(0L)
            whenever(providerConfigRevisionDao.claimForSync(any(), any(), any())).thenReturn(1)
            whenever(providerConfigRevisionDao.getState(any(), any()))
                .thenReturn(ProviderConfigRevisionState.COMMITTED)
            whenever(
                providerWorkflowRunner.execute(any(), any(), any(), any(), any(), any(), any())
            ).thenAnswer { invocation ->
                val block = invocation.getArgument<suspend () -> ProviderWorkflowOutcome>(6)
                when (val outcome = runBlocking { block() }) {
                    is ProviderWorkflowOutcome.Success -> ProviderWorkflowDisposition.SUCCEEDED
                    is ProviderWorkflowOutcome.Failure -> if (outcome.retryable) {
                        ProviderWorkflowDisposition.RETRY
                    } else {
                        ProviderWorkflowDisposition.FAILED
                    }
                }
            }
            whenever(
                syncManager.syncWithProviderOverride(
                    any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(), anyOrNull()
                )
            ).thenReturn(Result.success(Unit))
            whenever(categoryDao.getByProviderAndTypeSync(any(), any())).thenReturn(emptyList())
            whenever(programDao.countByProvider(any())).thenReturn(0)
            whenever(channelDao.countByProvider(any())).thenReturn(0)
            whenever(movieDao.countByProvider(any())).thenReturn(0)
            whenever(seriesDao.countByProvider(any())).thenReturn(0)
            whenever(providerDeletionCleanupDao.countByProvider(any())).thenReturn(1)
        }
    }

    @Test
    fun `redacted settings update preserves the stored typed credential`() = runTest {
        whenever(credentialCrypto.decryptIfNeeded(any())).thenAnswer { invocation ->
            invocation.getArgument<String>(0)
        }
        val codec = ProviderConfigurationCodec(gson, credentialCrypto)
        val storedConfiguration = XtreamConfig(
            serverUrl = "https://example.com",
            username = "user",
            password = "secret"
        )
        val encodedConfiguration = codec.encode(storedConfiguration)
        whenever(providerSnapshotDao.getConfig(7L)).thenReturn(
            ProviderConfigEntity(
                providerId = 7L,
                type = ProviderType.XTREAM_CODES,
                schemaVersion = storedConfiguration.schemaVersion,
                configurationGeneration = 3L,
                identityKey = codec.identityKey(storedConfiguration),
                encryptedConfigJson = encodedConfiguration,
                updatedAt = 1L
            )
        )
        whenever(providerSnapshotDao.commitConfiguration(any())).thenReturn(true)

        val publicProjection = Provider(
            id = 7L,
            name = "Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = storedConfiguration.serverUrl,
            username = storedConfiguration.username,
            password = "",
            guideSourcePolicy = GuideSourcePolicy.EXTERNAL_ONLY
        )

        val result = repository.updateProvider(publicProjection)

        assertThat(result.isSuccess).isTrue()
        val committed = argumentCaptor<ProviderConfigEntity>()
        verify(providerSnapshotDao).commitConfiguration(committed.capture())
        val decoded = codec.decode(committed.firstValue.type, committed.firstValue.encryptedConfigJson)
            as XtreamConfig
        assertThat(decoded.password).isEqualTo("secret")
        assertThat(decoded.guideSourcePolicy).isEqualTo(GuideSourcePolicy.EXTERNAL_ONLY)
    }

    @Test
    fun `buildStalkerSearchIndexOnce resets terminal crawl progress before queueing fresh jobs`() = runTest {
        whenever(providerDao.getById(7L)).thenReturn(
            ProviderEntity(
                id = 7L,
                name = "Stalker",
                type = ProviderType.STALKER_PORTAL,
                serverUrl = "https://portal.example.com",
                status = ProviderStatus.ACTIVE
            )
        )
        whenever(stalkerIndexJobDao.get(7L, ContentType.MOVIE.name)).thenReturn(
            StalkerIndexJobEntity(
                providerId = 7L,
                section = ContentType.MOVIE,
                state = StalkerIndexState.TRUNCATED,
                completedCategories = 9,
                indexedRows = 900,
                lastError = "page limit"
            )
        )

        val result = repository.buildStalkerSearchIndexOnce(7L)

        assertThat(result.isSuccess).isTrue()
        verify(movieCategoryHydrationDao).deleteByProvider(7L)
        verify(seriesCategoryHydrationDao).deleteByProvider(7L)
        val jobs = argumentCaptor<StalkerIndexJobEntity>()
        verify(stalkerIndexJobDao, org.mockito.kotlin.times(2)).upsert(jobs.capture())
        assertThat(jobs.allValues.map { it.section }).containsExactly(ContentType.MOVIE, ContentType.SERIES)
        jobs.allValues.forEach { job ->
            assertThat(job.state).isEqualTo(StalkerIndexState.QUEUED)
            assertThat(job.completedCategories).isEqualTo(0)
            assertThat(job.indexedRows).isEqualTo(0)
            assertThat(job.lastError).isNull()
        }
        verify(syncManager).scheduleStalkerIndexSync(7L, force = false)
    }

    @Test
    fun `save without verification requires an inconclusive readiness result and stays inactive`() = runTest {
        StalkerProvider.clearSharedAuthCacheForTests()
        whenever(providerDao.insert(any())).thenReturn(41L)
        whenever(credentialCrypto.encryptIfNeeded(any())).thenAnswer { invocation ->
            invocation.arguments.first() as String
        }
        doAnswer { invocation ->
            val profile = invocation.getArgument<StalkerDeviceProfile>(0)
            if (profile.requireCatalogValidation) {
                Result.error(
                    "Authentication succeeded, but Live TV readiness could not be verified.",
                    StalkerApiError.ReadinessInconclusive(
                        evidenceCode = "LIVE_BUDGET_EXHAUSTED",
                        cause = java.io.IOException("temporary Live timeout")
                    )
                )
            } else {
                Result.success(
                    StalkerSession(
                        loadUrl = "https://portal.example.com/server/load.php",
                        portalReferer = "https://portal.example.com/c/",
                        token = "token"
                    ) to StalkerProviderProfile(
                        accountName = "MAG",
                        statusLabel = "1",
                        authAccess = true
                    )
                )
            }
        }.whenever(stalkerApiService).authenticate(any())

        val result = repository.loginStalker(
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            name = "MAG",
            authMode = StalkerAuthMode.MAC_ONLY,
            transportGrant = StalkerTransportGrant(
                mode = StalkerTransportMode.VERIFIED_HTTPS,
                origin = StalkerTransportOrigin("https", "portal.example.com", 443),
                consentedAt = 0L
            ),
            saveWithoutVerification = true
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(ProviderSavedWithSyncErrorException::class.java)
        val saved = (error.exception as ProviderSavedWithSyncErrorException).provider
        assertThat(saved.id).isEqualTo(41L)
        assertThat(saved.status).isEqualTo(ProviderStatus.PARTIAL)
        assertThat(saved.isActive).isFalse()
        assertThat(saved.stalkerTransportOrigin).isEqualTo("https://portal.example.com:443")
        verify(stalkerApiService, org.mockito.kotlin.times(2)).authenticate(any())
        verify(syncManager, never()).sync(
            any(),
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            any()
        )
        verify(syncManager).scheduleProviderSyncResume(41L, 1L)
    }

    @Test
    fun `deleteProvider cancels recording and reminder alarms before deleting provider rows`() = runTest {
        whenever(recordingRunDao.getIdsByProvider(7L)).thenReturn(listOf("run-1", "run-2"))
        whenever(programReminderDao.getIdsByProvider(7L)).thenReturn(listOf(11L, 12L))

        val result = repository.deleteProvider(7L)

        assertThat(result.isSuccess).isTrue()
        val cleanup = argumentCaptor<List<ProviderDeletionCleanupEntity>>()
        verify(providerDeletionCleanupDao).insertAll(cleanup.capture())
        assertThat(cleanup.firstValue.map { it.action }).containsExactly(
            ProviderDeletionCleanupWorker.RECORDING_ALARM,
            ProviderDeletionCleanupWorker.RECORDING_ALARM,
            ProviderDeletionCleanupWorker.REMINDER_ALARM,
            ProviderDeletionCleanupWorker.REMINDER_ALARM,
            ProviderDeletionCleanupWorker.SYNC_RUNTIME
        ).inOrder()
        verify(programDao).deleteByProvider(7L)
        verify(providerDao).delete(7L)
        verify(providerDeletionCleanupEnqueuer, org.mockito.kotlin.atLeastOnce()).enqueue()
        verify(recordingAlarmScheduler, never()).cancel(any())
        verify(programReminderAlarmScheduler, never()).cancel(any())
        verify(syncManager, never()).onProviderDeleted(any())
        verify(recordingRunDao, org.mockito.kotlin.atLeastOnce()).getIdsByProvider(7L)
        verify(programReminderDao, org.mockito.kotlin.atLeastOnce()).getIdsByProvider(7L)
    }

    @Test
    fun `deleteProvider commits database cleanup before sync side effects`() = runTest {
        val events = mutableListOf<String>()
        val trackedTransactionRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                events += "transaction:start"
                return try {
                    block()
                } finally {
                    events += "transaction:end"
                }
            }
        }
        val trackedRepository = createRepository(transactionRunner = trackedTransactionRunner)

        whenever(recordingRunDao.getIdsByProvider(7L)).thenReturn(emptyList())
        whenever(programReminderDao.getIdsByProvider(7L)).thenReturn(emptyList())
        doAnswer {
            events += "programs:delete"
            Unit
        }.whenever(programDao).deleteByProvider(7L)
        doAnswer {
            events += "provider:delete"
            Unit
        }.whenever(providerDao).delete(7L)
        doAnswer {
            events += "cleanup:enqueue"
            Unit
        }.whenever(providerDeletionCleanupEnqueuer).enqueue()

        val result = trackedRepository.deleteProvider(7L)

        assertThat(result.isSuccess).isTrue()
        assertThat(events).containsExactly(
            "transaction:start",
            "programs:delete",
            "provider:delete",
            "transaction:end",
            "cleanup:enqueue"
        ).inOrder()
    }

    @Test
    fun `deleteProvider keeps success after post commit cleanup failure`() = runTest {
        whenever(recordingRunDao.getIdsByProvider(7L)).thenReturn(listOf("run-1"))
        whenever(programReminderDao.getIdsByProvider(7L)).thenReturn(listOf(11L))
        var cleanupAttempted = false
        doAnswer {
            cleanupAttempted = true
            throw IllegalStateException("cleanup enqueue failed")
        }
            .whenever(providerDeletionCleanupEnqueuer).enqueue()

        val result = repository.deleteProvider(7L)

        assertThat(result.isSuccess).isTrue()
        verify(programDao).deleteByProvider(7L)
        verify(providerDao).delete(7L)
        assertThat(cleanupAttempted).isTrue()
        verify(recordingAlarmScheduler, never()).cancel(any())
        verify(programReminderAlarmScheduler, never()).cancel(any())
    }

    @Test
    fun `validateM3u marks provider active only after successful onboarding`() = runTest {
        val existingProvider = Provider(
            id = 5L,
            name = "Playlist",
            type = ProviderType.M3U,
            serverUrl = "https://example.com/list.m3u",
            m3uUrl = "https://example.com/list.m3u",
            status = ProviderStatus.UNKNOWN
        )

        whenever(providerSnapshotDao.findProviderIdByIdentityKey(any())).thenReturn(existingProvider.id)
        whenever(providerDao.getById(existingProvider.id)).thenReturn(existingProvider.toEntity())
        whenever(providerCapabilityResolver.snapshot(existingProvider.id)).thenReturn(existingProvider.toProviderSnapshot())
        whenever(syncManager.sync(5L, false, null)).thenReturn(Result.success(Unit))
        whenever(syncManager.currentSyncState(5L)).thenReturn(SyncState.Success(123L))

        val result = repository.validateM3u(
            url = "https://example.com/list.m3u",
            name = "Playlist",
            epgSyncMode = ProviderEpgSyncMode.UPFRONT,
            m3uVodClassificationEnabled = false,
            onProgress = null,
            id = null
        )

        assertThat(result.isSuccess).isTrue()
        verify(providerDao).setActive(5L)
        verify(providerDao, never()).deactivateAll()
        verify(providerDao, never()).activate(5L)
    }

    @Test
    fun `validateM3u returns saved provider sync error exception when initial sync fails after save`() = runTest {
        whenever(credentialCrypto.encryptIfNeeded("")).thenReturn("")
        whenever(providerDao.insert(any())).thenReturn(9L)
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Playlist",
                type = ProviderType.M3U,
                serverUrl = "https://example.com/list.m3u",
                m3uUrl = "https://example.com/list.m3u",
                isActive = false,
                status = ProviderStatus.PARTIAL
            )
        )
        whenever(syncManager.sync(eq(9L), eq(false), anyOrNull(), anyOrNull(), anyOrNull(), eq(false)))
            .thenReturn(Result.error("timeout"))

        val result = repository.validateM3u(
            url = "https://example.com/list.m3u",
            name = "Playlist",
            epgSyncMode = ProviderEpgSyncMode.UPFRONT,
            m3uVodClassificationEnabled = false,
            onProgress = {},
            id = null
        )

        assertThat(result.isError).isTrue()
        val failure = (result as Result.Error).exception as ProviderSavedWithSyncErrorException
        assertThat(failure.provider.id).isEqualTo(9L)
        assertThat(failure.provider.status).isEqualTo(ProviderStatus.PARTIAL)
        assertThat(failure.provider.isActive).isFalse()
        assertThat(failure.message).contains("Playlist saved, but initial sync failed")
        verify(providerDao, never()).setActive(9L)
        verify(syncManager).scheduleProviderSyncResume(9L)
    }

    @Test
    fun `validateM3u persists new provider inactive until onboarding succeeds`() = runTest {
        whenever(credentialCrypto.encryptIfNeeded("")).thenReturn("")
        whenever(providerDao.insert(any())).thenReturn(9L)
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Playlist",
                type = ProviderType.M3U,
                serverUrl = "https://example.com/list.m3u",
                m3uUrl = "https://example.com/list.m3u",
                isActive = false,
                status = ProviderStatus.PARTIAL
            )
        )
        whenever(syncManager.sync(9L, false, null)).thenReturn(Result.success(Unit))
        whenever(syncManager.currentSyncState(9L)).thenReturn(SyncState.Success(123L))

        val result = repository.validateM3u(
            url = "https://example.com/list.m3u",
            name = "Playlist",
            epgSyncMode = ProviderEpgSyncMode.UPFRONT,
            m3uVodClassificationEnabled = false,
            onProgress = null,
            id = null
        )

        assertThat(result.isSuccess).isTrue()
        val insertedProviders = argumentCaptor<ProviderEntity>()
        verify(providerDao).insert(insertedProviders.capture())
        assertThat(insertedProviders.firstValue.isActive).isFalse()
        verify(providerDao).setActive(9L)
    }

    @Test
    fun `refreshProviderData leaves xtream provider inactive partial when sync commits no live channels`() = runTest {
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "https://example.com",
                username = "user",
                isActive = false,
                status = ProviderStatus.PARTIAL,
                lastSyncedAt = 0L
            )
        )
        whenever(syncManager.sync(9L, false, null, null, null)).thenReturn(Result.success(Unit))
        whenever(syncManager.currentSyncState(9L)).thenReturn(SyncState.Success(123L))
        whenever(channelDao.getCount(9L)).thenReturn(flowOf(0))

        val result = repository.refreshProviderData(
            providerId = 9L,
            force = false,
            movieFastSyncOverride = null,
            epgSyncModeOverride = null,
            onProgress = null
        )

        assertThat(result.isSuccess).isTrue()
        val updatedProvider = argumentCaptor<ProviderEntity>()
        verify(providerDao).update(updatedProvider.capture())
        assertThat(updatedProvider.firstValue.isActive).isFalse()
        assertThat(updatedProvider.firstValue.status).isEqualTo(ProviderStatus.PARTIAL)
        assertThat(updatedProvider.firstValue.lastSyncedAt).isGreaterThan(0L)
        verify(syncManager).scheduleProviderSyncResume(9L)
        verify(providerDao, never()).setActive(9L)
    }

    @Test
    fun `refreshProviderData activates xtream when sync commits vod despite partial epg`() = runTest {
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "https://example.com",
                username = "user",
                isActive = false,
                status = ProviderStatus.PARTIAL,
                lastSyncedAt = 0L
            )
        )
        whenever(syncManager.sync(9L, false, null, null, null)).thenReturn(Result.success(Unit))
        whenever(syncManager.currentSyncState(9L)).thenReturn(
            SyncState.Partial("EPG was empty")
        )
        whenever(channelDao.getCount(9L)).thenReturn(flowOf(0))
        whenever(syncMetadataRepository.getMetadata(9L)).thenReturn(
            SyncMetadata(providerId = 9L, movieCount = 63, seriesCount = 63)
        )

        val result = repository.refreshProviderData(
            providerId = 9L,
            force = false,
            movieFastSyncOverride = null,
            epgSyncModeOverride = null,
            onProgress = null
        )

        assertThat(result.isSuccess).isTrue()
        verify(providerDao).setActive(9L)
        val updatedProvider = argumentCaptor<ProviderEntity>()
        verify(providerDao).update(updatedProvider.capture())
        assertThat(updatedProvider.firstValue.isActive).isTrue()
        assertThat(updatedProvider.firstValue.status).isEqualTo(ProviderStatus.PARTIAL)
        verify(syncManager, never()).scheduleProviderSyncResume(9L)
    }

    @Test
    fun `validateM3u edit path rejects update when new URL already belongs to a different provider`() = runTest {
        val editTarget = Provider(
            id = 5L,
            name = "Playlist A",
            type = ProviderType.M3U,
            serverUrl = "https://example.com/a.m3u",
            m3uUrl = "https://example.com/a.m3u",
            status = ProviderStatus.ACTIVE
        )
        val collision = ProviderEntity(
            id = 9L,
            name = "Playlist B",
            type = ProviderType.M3U,
            serverUrl = "https://example.com/b.m3u",
            m3uUrl = "https://example.com/b.m3u",
            status = ProviderStatus.ACTIVE
        )
        // Provider 9 already owns the URL we want to move provider 5 to.
        whenever(providerSnapshotDao.findProviderIdByIdentityKey(any())).thenReturn(collision.id)
        whenever(providerDao.getById(collision.id)).thenReturn(collision)

        val result = repository.validateM3u(
            url = "https://example.com/b.m3u",
            name = "Playlist A",
            epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
            m3uVodClassificationEnabled = false,
            onProgress = null,
            id = editTarget.id
        )

        assertThat(result.isError).isTrue()
        assertThat((result as Result.Error).message).contains("already exists")
        verify(providerDao, never()).insert(any())
        verify(providerDao, never()).update(any())
    }

    @Test
    fun `validateM3u edit path allows update when URL belongs to the same provider being edited`() = runTest {
        val editTarget = Provider(
            id = 5L,
            name = "Playlist A",
            type = ProviderType.M3U,
            serverUrl = "https://example.com/a.m3u",
            m3uUrl = "https://example.com/a.m3u",
            status = ProviderStatus.ACTIVE
        )
        // The collision query returns the same provider being edited — that is not a conflict.
        whenever(providerSnapshotDao.findProviderIdByIdentityKey(any())).thenReturn(editTarget.id)
        whenever(providerDao.getById(editTarget.id)).thenReturn(editTarget.toEntity())
        whenever(providerCapabilityResolver.snapshot(editTarget.id)).thenReturn(editTarget.toProviderSnapshot())
        whenever(syncManager.sync(5L, false, null)).thenReturn(Result.success(Unit))
        whenever(syncManager.currentSyncState(5L)).thenReturn(SyncState.Success(123L))

        val result = repository.validateM3u(
            url = "https://example.com/a.m3u",
            name = "Playlist A renamed",
            epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
            m3uVodClassificationEnabled = false,
            onProgress = null,
            id = editTarget.id
        )

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `setActiveProvider uses transactional activation api`() = runTest {
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Playlist",
                type = ProviderType.M3U,
                serverUrl = "https://example.com/list.m3u",
                m3uUrl = "https://example.com/list.m3u"
            )
        )

        val result = repository.setActiveProvider(9L)

        assertThat(result.isSuccess).isTrue()
        verify(providerDao).setActive(9L)
        verify(providerDao, never()).deactivateAll()
        verify(providerDao, never()).activate(9L)
    }

    @Test
    fun `setActiveProvider rejects xtream provider while live onboarding is incomplete`() = runTest {
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "https://example.com",
                username = "user",
                isActive = false,
                status = ProviderStatus.PARTIAL
            )
        )
        whenever(channelDao.getCount(9L)).thenReturn(flowOf(0))

        val result = repository.setActiveProvider(9L)

        assertThat(result.isError).isTrue()
        assertThat((result as Result.Error).message).contains("no content has been committed yet")
        verify(providerDao, never()).setActive(9L)
        verify(syncManager).scheduleProviderSyncResume(9L)
    }

    @Test
    fun `setActiveProvider allows xtream provider with committed live channels`() = runTest {
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "https://example.com",
                username = "user",
                status = ProviderStatus.PARTIAL
            )
        )
        whenever(channelDao.getCount(9L)).thenReturn(flowOf(12))

        val result = repository.setActiveProvider(9L)

        assertThat(result.isSuccess).isTrue()
        verify(providerDao).setActive(9L)
        verify(syncManager, never()).scheduleProviderSyncResume(9L)
    }

    @Test
    fun `setActiveProvider allows xtream provider with no live but committed vod`() = runTest {
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "https://example.com",
                username = "user",
                status = ProviderStatus.PARTIAL
            )
        )
        whenever(channelDao.getCount(9L)).thenReturn(flowOf(0))
        whenever(syncMetadataRepository.getMetadata(9L)).thenReturn(
            SyncMetadata(providerId = 9L, movieCount = 7)
        )

        val result = repository.setActiveProvider(9L)

        assertThat(result.isSuccess).isTrue()
        verify(providerDao).setActive(9L)
        verify(syncManager, never()).scheduleProviderSyncResume(9L)
    }

    @Test
    fun `loginXtream does not fail onboarding when provider has no live but committed vod`() = runTest {
        whenever(credentialCrypto.encryptIfNeeded("pass")).thenReturn("pass")
        whenever(providerDao.insert(any())).thenReturn(9L)
        whenever(syncManager.sync(eq(9L), eq(false), anyOrNull(), anyOrNull(), anyOrNull(), eq(true)))
            .thenReturn(Result.success(Unit))
        whenever(syncManager.currentSyncState(9L)).thenReturn(SyncState.Success(123L))
        whenever(channelDao.getCount(9L)).thenReturn(flowOf(0))
        whenever(syncMetadataRepository.getMetadata(9L)).thenReturn(
            SyncMetadata(providerId = 9L, movieCount = 3)
        )
        whenever(xtreamApiService.authenticate(any(), any())).thenReturn(
            XtreamAuthResponse(
                userInfo = XtreamUserInfo(
                    username = "user",
                    password = "pass",
                    auth = 1,
                    status = "Active"
                ),
                serverInfo = XtreamServerInfo(
                    url = "example.com",
                    port = "80",
                    serverProtocol = "http"
                )
            )
        )

        val result = repository.loginXtream(
            serverUrl = "https://example.com",
            username = "user",
            password = "pass",
            name = "Xtream",
            httpUserAgent = "",
            httpHeaders = "",
            xtreamFastSyncEnabled = false,
            epgSyncMode = ProviderEpgSyncMode.UPFRONT,
            xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
            onProgress = null,
            id = null
        )

        assertThat(result.isSuccess).isTrue()
        verify(providerDao).setActive(9L)
        verify(syncManager, never()).scheduleProviderSyncResume(9L)
    }

    @Test
    fun `loginXtream does not fail onboarding when provider has no live but committed vod categories`() = runTest {
        whenever(credentialCrypto.encryptIfNeeded("pass")).thenReturn("pass")
        whenever(providerDao.insert(any())).thenReturn(9L)
        whenever(syncManager.sync(eq(9L), eq(false), anyOrNull(), anyOrNull(), anyOrNull(), eq(true)))
            .thenReturn(Result.success(Unit))
        whenever(syncManager.currentSyncState(9L)).thenReturn(SyncState.Success(123L))
        whenever(channelDao.getCount(9L)).thenReturn(flowOf(0))
        whenever(syncMetadataRepository.getMetadata(9L)).thenReturn(SyncMetadata(providerId = 9L))
        whenever(categoryDao.getByProviderAndTypeSync(9L, "MOVIE")).thenReturn(
            listOf(
                CategoryEntity(
                    providerId = 9L,
                    categoryId = 42L,
                    name = "Action",
                    parentId = null,
                    type = com.streamvault.domain.model.ContentType.MOVIE
                )
            )
        )
        whenever(categoryDao.getByProviderAndTypeSync(9L, "SERIES")).thenReturn(emptyList())
        whenever(xtreamApiService.authenticate(any(), any())).thenReturn(
            XtreamAuthResponse(
                userInfo = XtreamUserInfo(
                    username = "user",
                    password = "pass",
                    auth = 1,
                    status = "Active"
                ),
                serverInfo = XtreamServerInfo(
                    url = "example.com",
                    port = "80",
                    serverProtocol = "http"
                )
            )
        )

        val result = repository.loginXtream(
            serverUrl = "https://example.com",
            username = "user",
            password = "pass",
            name = "Xtream",
            httpUserAgent = "",
            httpHeaders = "",
            xtreamFastSyncEnabled = false,
            epgSyncMode = ProviderEpgSyncMode.UPFRONT,
            xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
            onProgress = null,
            id = null
        )

        assertThat(result.isSuccess).isTrue()
        verify(providerDao).setActive(9L)
        verify(syncManager, never()).scheduleProviderSyncResume(9L)
    }
}
