package com.streamvault.data.manager

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.EpgSourceDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.dao.BackupRestoreCheckpointDao
import com.streamvault.data.local.dao.BackupRestoreLedgerDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.RecordingScheduleDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.VirtualGroupDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.provider.ProviderConfigurationCodec
import com.streamvault.data.local.entity.EpgSourceEntity
import com.streamvault.data.local.entity.BackupRestoreCheckpointEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.EpisodeEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.RecordingScheduleEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.local.entity.VirtualGroupEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.provider.toProviderSnapshot
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.manager.BackupData
import com.streamvault.domain.manager.ActiveLiveSourceBackup
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupRestoreOutcome
import com.streamvault.domain.manager.BackupProviderReference
import com.streamvault.domain.manager.PortableCategoryReference
import com.streamvault.domain.manager.PortableCategorySortReference
import com.streamvault.domain.manager.PortableChannelReference
import com.streamvault.domain.manager.PortableContentReference
import com.streamvault.domain.manager.PortableFavoriteBackup
import com.streamvault.domain.manager.PortableCustomGroupBackup
import com.streamvault.domain.manager.PortablePlaybackHistoryBackup
import com.streamvault.domain.manager.PortableProtectedContentBackup
import com.streamvault.domain.manager.PortableSearchHistoryBackup
import com.streamvault.domain.manager.PortableEpgTimeShiftReference
import com.streamvault.domain.manager.PortableProviderPreferencesBackup
import com.streamvault.domain.manager.PortableVariantSelectionReference
import com.streamvault.domain.manager.PortableVirtualGroupReference
import com.streamvault.domain.manager.ProtectedCategoryBackup
import com.streamvault.domain.manager.CombinedM3uProfileBackup
import com.streamvault.domain.manager.CombinedM3uProfileMemberBackup
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.manager.ProviderEpgAssignmentBackup
import com.streamvault.domain.manager.ManualEpgMappingBackup
import com.streamvault.domain.manager.M3uClassificationOverrideBackup
import com.streamvault.domain.manager.M3uClassificationRuleBackup
import com.streamvault.domain.manager.ProgramReminderBackup
import com.streamvault.domain.manager.RecordingStorageBackup
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.manager.RecordingScheduleImportDisposition
import com.streamvault.domain.manager.ScheduledRecordingBackup
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.EpgSource
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.repository.ProviderSnapshotRepository
import com.streamvault.domain.model.XmltvTimezonePolicy
import com.streamvault.domain.repository.CategoryRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BackupManagerImplTest {

    @Test
    fun `v14 import durably queues catalog state that is unavailable before sync`() = runBlocking {
        val context: Context = mock()
        val resolver: ContentResolver = mock()
        val ledgerDao: BackupRestoreLedgerDao = mock()
        val providerDao: ProviderDao = mock()
        val provider = BackupProviderReference(
            serverUrl = "https://example.com/",
            username = "user",
            providerType = ProviderType.XTREAM_CODES
        )
        val content = PortableContentReference(
            provider = provider,
            contentType = ContentType.LIVE,
            remoteContentId = "42",
            name = "News"
        )
        val backup = BackupData(
            version = 14,
            portableFavorites = listOf(PortableFavoriteBackup(content, position = 3))
        )
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(resolver.openInputStream(anyOrNull())).thenReturn(
            ByteArrayInputStream(Gson().toJson(backup).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(emptyList())

        val result = backupManagerForValidation(
            context = context,
            providerDao = providerDao,
            restoreLedgerDao = ledgerDao
        ).importConfig(
            "content://v14-pending",
            BackupImportPlan(
                importPreferences = false,
                importProviders = false,
                importSavedLibrary = true,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.WAITING_FOR_SYNC)
        assertThat(imported.restoreJobId).isNotEmpty()
        assertThat(imported.pendingCount).isEqualTo(1)
        assertThat(imported.affectedProviders).containsExactly(provider)
        verify(ledgerDao).insertLedger(
            argThat { backupVersion == 14 && id == imported.restoreJobId },
            argThat { items ->
                items.single().section == "FAVORITES" &&
                    items.single().stableReferenceKey.contains("LIVE:42") &&
                    items.single().payloadJson.contains("\"position\":3")
            }
        )
    }

    @Test
    fun `v14 parser preserves portable content state without local database ids`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val provider = BackupProviderReference(
            serverUrl = "https://example.com/",
            username = "user",
            providerType = ProviderType.XTREAM_CODES
        )
        val channel = PortableContentReference(
            provider = provider,
            contentType = ContentType.LIVE,
            remoteContentId = "42",
            remoteCategoryId = "7",
            name = "News",
            urlFallback = "https://example.com/live/user/pass/42.ts"
        )
        val episode = PortableContentReference(
            provider = provider,
            contentType = ContentType.SERIES_EPISODE,
            remoteContentId = "episode-9",
            parentRemoteContentId = "series-3",
            name = "Pilot"
        )
        val backup = BackupData(
            version = 14,
            portableFavorites = listOf(PortableFavoriteBackup(channel, position = 2)),
            portableCustomGroups = listOf(
                PortableCustomGroupBackup(
                    provider = provider,
                    contentType = ContentType.LIVE,
                    name = "Family",
                    icon = "★",
                    position = 1,
                    members = listOf(PortableFavoriteBackup(channel, position = 0))
                )
            ),
            portablePlaybackHistory = listOf(
                PortablePlaybackHistoryBackup(
                    content = episode,
                    resumePositionMs = 12_000,
                    totalDurationMs = 60_000,
                    lastWatchedAt = 1234,
                    watchCount = 2,
                    watchedStatus = "IN_PROGRESS"
                )
            ),
            portableProtectedContent = listOf(PortableProtectedContentBackup(channel)),
            portableSearchHistory = listOf(
                PortableSearchHistoryBackup(
                    query = "news",
                    contentScope = "LIVE",
                    provider = provider,
                    usedAt = 5678,
                    useCount = 3
                )
            )
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(anyOrNull())).thenReturn(
            ByteArrayInputStream(Gson().toJson(backup).toByteArray())
        )

        val manager = backupManagerForValidation(context)
        val readMethod = BackupManagerImpl::class.java
            .getDeclaredMethod("readBackupData", String::class.java)
            .apply { isAccessible = true }
        val parsed = readMethod.invoke(manager, "content://v14-portable-state")
        val dataField = parsed!!::class.java.getDeclaredField("data").apply { isAccessible = true }
        val parsedData = dataField.get(parsed) as BackupData

        assertThat(parsedData.version).isEqualTo(14)
        assertThat(parsedData.portableFavorites?.single()?.content).isEqualTo(channel)
        assertThat(parsedData.portableCustomGroups?.single()?.members?.single()?.position).isEqualTo(0)
        assertThat(parsedData.portablePlaybackHistory?.single()?.content?.parentRemoteContentId)
            .isEqualTo("series-3")
        assertThat(parsedData.portableProtectedContent?.single()?.content?.remoteContentId).isEqualTo("42")
        assertThat(parsedData.portableSearchHistory?.single()?.useCount).isEqualTo(3)
    }

    @Test
    fun `import creates protected category placeholder when provider catalog was deleted`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val providerDao: ProviderDao = mock()
        val categoryRepository: CategoryRepository = mock()
        val categoryDao: CategoryDao = mock()
        val gson = Gson()
        val backupProvider = Provider(
            id = 100L,
            name = "Provider",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com",
            username = "user",
            password = "pass"
        )
        val targetProvider = backupProvider.copy(id = 7L)
        val backup = BackupData(
            protectedCategories = listOf(
                ProtectedCategoryBackup(
                    providerServerUrl = backupProvider.serverUrl,
                    providerUsername = backupProvider.username,
                    categoryId = 42L,
                    categoryName = "Locked Sports",
                    type = ContentType.LIVE,
                    providerType = ProviderType.XTREAM_CODES
                )
            )
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(Uri.parse("content://protected-placeholder"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backup).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(
            listOf(
                ProviderEntity(
                    id = targetProvider.id,
                    name = targetProvider.name,
                    type = targetProvider.type,
                    serverUrl = targetProvider.serverUrl,
                    username = targetProvider.username,
                    password = targetProvider.password
                )
            )
        )
        whenever(categoryRepository.getCategories(targetProvider.id)).thenReturn(flowOf(emptyList()))

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = mock(),
            credentialCrypto = mock(),
            providerDao = providerDao,
            favoriteDao = mock(),
            virtualGroupDao = mock(),
            playbackHistoryDao = mock(),
            movieDao = mock(),
            episodeDao = mock(),
            categoryRepository = categoryRepository,
            recordingScheduleDao = mock(),
            recordingManager = mock(),
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = gson,
            channelDao = mock(),
            seriesDao = mock(),
            categoryDao = categoryDao,
            providerSnapshotRepository = snapshotRepositoryFor(targetProvider)
        )

        val result = manager.importConfig(
            uriString = "content://protected-placeholder",
            plan = BackupImportPlan(
                importPreferences = false,
                importProviders = false,
                importSavedLibrary = true,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data.unresolvedReferences).isEmpty()
        verify(categoryDao).insertAll(argThat { rows ->
            rows.single() == CategoryEntity(
                categoryId = 42L,
                name = "Locked Sports",
                type = ContentType.LIVE,
                providerId = targetProvider.id,
                isUserProtected = true
            )
        })
        verify(categoryRepository).setCategoryProtection(
            providerId = targetProvider.id,
            categoryId = 42L,
            type = ContentType.LIVE,
            isProtected = true
        )
        Unit
    }

    @Test
    fun `custom backup parser preserves newly portable user state sections`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val provider = BackupProviderReference(
            serverUrl = "https://example.com",
            username = "user",
            providerType = ProviderType.M3U
        )
        val backup = BackupData(
            version = 13,
            recordingStorage = RecordingStorageBackup("{title}", retentionDays = 14, maxSimultaneousRecordings = 3),
            providerEpgAssignments = listOf(ProviderEpgAssignmentBackup(provider, "https://example.com/guide.xml")),
            manualEpgMappings = listOf(
                ManualEpgMappingBackup(
                    channel = PortableChannelReference(provider, 42L, "News", "https://example.com/news"),
                    sourceUrl = "https://example.com/guide.xml",
                    xmltvChannelId = "news.example"
                )
            ),
            m3uClassificationOverrides = listOf(
                M3uClassificationOverrideBackup(provider, "news-key", 42L, "LIVE")
            ),
            m3uClassificationRules = listOf(M3uClassificationRuleBackup(provider, "News", "LIVE")),
            programReminders = listOf(
                ProgramReminderBackup(provider, "42", "News", "Headlines", 4_102_444_800_000L)
            )
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(anyOrNull())).thenReturn(
            ByteArrayInputStream(Gson().toJson(backup).toByteArray())
        )

        val manager = backupManagerForValidation(context)
        val readMethod = BackupManagerImpl::class.java
            .getDeclaredMethod("readBackupData", String::class.java)
            .apply { isAccessible = true }
        val parsed = readMethod.invoke(manager, "content://portable-state")
        val dataField = parsed!!::class.java.getDeclaredField("data").apply { isAccessible = true }
        val parsedData = dataField.get(parsed) as BackupData

        assertThat(parsedData.recordingStorage?.retentionDays).isEqualTo(14)
        assertThat(parsedData.providerEpgAssignments?.single()?.sourceUrl)
            .isEqualTo("https://example.com/guide.xml")
        assertThat(parsedData.manualEpgMappings?.single()?.xmltvChannelId).isEqualTo("news.example")
        assertThat(parsedData.m3uClassificationOverrides?.single()?.sourceKey).isEqualTo("news-key")
        assertThat(parsedData.m3uClassificationRules?.single()?.groupKey).isEqualTo("News")
        assertThat(parsedData.programReminders?.single()?.programTitle).isEqualTo("Headlines")
    }

    @Test
    fun `release obfuscated backup fields remain importable`() = runBlocking {
        val payload = """{"version":12,"portableProviderPreferences":{"a":[{"a":"https://example.com","b":"user"}],"e":false,"f":[],"g":[],"h":[],"i":[{"a":{"a":"https://example.com","b":"user"},"b":"News","c":"LIVE","d":50}],"j":true,"k":[{"a":{"a":"https://example.com","b":"user"},"b":"LIVE","c":"TITLE_ASC"}],"l":true,"m":[{"a":{"a":"https://example.com","b":"user"},"b":30}],"n":true,"o":[{"a":{"a":"https://example.com","b":"user"},"b":"news","c":400}],"p":true,"q":[{"a":{"a":"https://example.com","b":"user"},"b":"movie","c":401}],"r":true,"s":[],"t":[{"a":{"a":{"a":"https://example.com","b":"user"},"b":400,"c":"News","d":"https://example.com/news"},"b":"FIT","c":125}],"u":true}}"""
        val checksum = "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray())
            .joinToString(separator = "") { "%02x".format(it) }
        val json = """{"version":12,"checksum":"$checksum","portableProviderPreferences":{"a":[{"a":"https://example.com","b":"user"}],"e":false,"f":[],"g":[],"h":[],"i":[{"a":{"a":"https://example.com","b":"user"},"b":"News","c":"LIVE","d":50}],"j":true,"k":[{"a":{"a":"https://example.com","b":"user"},"b":"LIVE","c":"TITLE_ASC"}],"l":true,"m":[{"a":{"a":"https://example.com","b":"user"},"b":30}],"n":true,"o":[{"a":{"a":"https://example.com","b":"user"},"b":"news","c":400}],"p":true,"q":[{"a":{"a":"https://example.com","b":"user"},"b":"movie","c":401}],"r":true,"s":[],"t":[{"a":{"a":{"a":"https://example.com","b":"user"},"b":400,"c":"News","d":"https://example.com/news"},"b":"FIT","c":125}],"u":true}}"""
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val uriString = "content://obfuscated-backup"
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(anyOrNull())).thenReturn(ByteArrayInputStream(json.toByteArray()))

        val manager = backupManagerForValidation(context)
        val readMethod = BackupManagerImpl::class.java
            .getDeclaredMethod("readBackupData", String::class.java)
            .apply { isAccessible = true }
        val parsed = readMethod.invoke(manager, uriString)
        val dataField = parsed!!::class.java.getDeclaredField("data").apply { isAccessible = true }
        val data = dataField.get(parsed) as BackupData

        assertThat(data.portableProviderPreferences?.providers?.single()?.serverUrl)
            .isEqualTo("https://example.com")
        assertThat(data.portableProviderPreferences?.providers?.single()?.username)
            .isEqualTo("user")
        assertThat(data.portableProviderPreferences?.pinnedCategories?.single()?.name)
            .isEqualTo("News")
        assertThat(data.portableProviderPreferences?.categorySortModes?.single()?.mode)
            .isEqualTo("TITLE_ASC")
        assertThat(data.portableProviderPreferences?.epgTimeShifts?.single()?.minutes)
            .isEqualTo(30)
        assertThat(data.portableProviderPreferences?.liveVariantSelections?.single()?.rawItemId)
            .isEqualTo(400L)
        assertThat(data.portableProviderPreferences?.vodVariantSelections?.single()?.rawItemId)
            .isEqualTo(401L)
        assertThat(data.portableProviderPreferences?.channelPreferences?.single()?.aspectRatio)
            .isEqualTo("FIT")
        assertThat(data.portableProviderPreferences?.channelPreferences?.single()?.audioVideoOffsetMs)
            .isEqualTo(125)

        val verifyMethod = BackupManagerImpl::class.java
            .getDeclaredMethod("verifyChecksum", parsed::class.java)
            .apply { isAccessible = true }
        assertThat(verifyMethod.invoke(manager, parsed) as Boolean).isTrue()
    }

    @Test
    fun `importConfig preserves explicit EPG source timezone interpretation`() = runBlocking {
        val context: Context = mock()
        val providerDao: ProviderDao = mock()
        val epgSourceDao: EpgSourceDao = mock()
        val file = File.createTempFile("streamvault-epg-timezone", ".json")
        val backupData = BackupData(
            version = 10,
            epgSources = listOf(
                EpgSource(
                    id = 91L,
                    name = "Local-time guide",
                    url = "https://example.com/guide.xml",
                    timezonePolicy = XmltvTimezonePolicy.EXPLICIT_ZONE,
                    timezoneId = "Europe/Amsterdam"
                )
            )
        )
        FileOutputStream(file).use { it.write(Gson().toJson(backupData).toByteArray()) }
        whenever(providerDao.getAllSync()).thenReturn(emptyList())
        whenever(epgSourceDao.getByUrl("https://example.com/guide.xml")).thenReturn(
            EpgSourceEntity(
                id = 7L,
                name = "Existing",
                url = "https://example.com/guide.xml"
            )
        )

        try {
            val result = backupManagerForValidation(
                context = context,
                providerDao = providerDao,
                epgSourceDao = epgSourceDao
            ).importConfig(
                uriString = file.toURI().toString(),
                plan = BackupImportPlan(
                    importPreferences = false,
                    importProviders = true,
                    importSavedLibrary = false,
                    importPlaybackHistory = false,
                    importMultiViewPresets = false,
                    importRecordingSchedules = false,
                    conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING
                )
            )

            assertThat(result).isInstanceOf(Result.Success::class.java)
            verify(epgSourceDao).update(argThat<EpgSourceEntity> {
                id == 7L &&
                    timezonePolicy == XmltvTimezonePolicy.EXPLICIT_ZONE &&
                    timezoneId == "Europe/Amsterdam"
            })
        } finally {
            file.delete()
        }
        Unit
    }

    @Test
    fun `inspectBackup rejects oversized non seekable input with typed byte limit`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val uri = Uri.parse("content://oversized-stream")
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(uri)).thenReturn(
            PrefixThenPaddingInputStream(
                prefix = """{"version":9,"preferences":{"key":"value"}}""".toByteArray(),
                totalBytes = 16 * 1024 * 1024 + 1
            )
        )

        val result = backupManagerForValidation(context).inspectBackup("content://oversized-stream")

        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(BackupAdmissionException::class.java)
        assertThat((error.exception as BackupAdmissionException).reason)
            .isEqualTo(BackupAdmissionReason.BYTE_LIMIT)
    }

    @Test
    fun `importConfig rejects oversized seekable file before mutation`() = runBlocking {
        val file = File.createTempFile("streamvault-oversized-backup", ".json")
        try {
            FileOutputStream(file).buffered().use { output ->
                output.write("""{"version":9,"preferences":{"key":"value"}}""".toByteArray())
                val padding = ByteArray(64 * 1024) { ' '.code.toByte() }
                repeat(257) { output.write(padding) }
            }
            val preferencesRepository: PreferencesRepository = mock()

            val result = backupManagerForValidation(
                context = mock(),
                preferencesRepository = preferencesRepository
            ).importConfig(file.toURI().toString(), preferencesOnlyPlan())

            val error = result as Result.Error
            assertThat((error.exception as BackupAdmissionException).reason)
                .isEqualTo(BackupAdmissionReason.BYTE_LIMIT)
            verify(preferencesRepository, never()).setParentalControlLevel(any())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `inspectBackup rejects deeply nested json while streaming`() = runBlocking {
        val nested = buildString {
            append("""{"version":9,"unknown":""")
            repeat(65) { append('[') }
            append('0')
            repeat(65) { append(']') }
            append('}')
        }

        val error = inspectAdmissionFailure("deep-json", nested)

        assertThat(error.reason).isEqualTo(BackupAdmissionReason.DEPTH_LIMIT)
    }

    @Test
    fun `inspectBackup rejects million entry section before materializing it`() = runBlocking {
        val json = buildString(2_100_000) {
            append("""{"version":9,"multiViewPresets":{"preset_1":[""")
            repeat(1_000_000) { index ->
                if (index > 0) append(',')
                append('0')
            }
            append("]}}")
        }

        val error = inspectAdmissionFailure("million-entry", json)

        assertThat(error.reason).isEqualTo(BackupAdmissionReason.SECTION_LIMIT)
        assertThat(error.message).contains("preset entries")
    }

    @Test
    fun `inspectBackup rejects overlong strings before object allocation`() = runBlocking {
        val json = """{"version":9,"preferences":{"key":"${"x".repeat(8_193)}"}}"""

        val error = inspectAdmissionFailure("long-string", json)

        assertThat(error.reason).isEqualTo(BackupAdmissionReason.FIELD_LIMIT)
    }

    @Test
    fun `inspectBackup reports malformed and truncated json as typed admission failures`() = runBlocking {
        listOf(
            """{"version":9,"preferences":""",
            """{"version":9,"preferences":{"key":]}"""
        ).forEachIndexed { index, json ->
            val error = inspectAdmissionFailure("malformed-$index", json)
            assertThat(error.reason).isEqualTo(BackupAdmissionReason.MALFORMED)
        }
    }

    @Test
    fun `inspectBackup rejects unsupported version before reading sections`() = runBlocking {
        val error = inspectAdmissionFailure(
            "unsupported-version",
            """{"version":15,"preferences":{"value":"${"x".repeat(8_193)}"}}"""
        )

        assertThat(error.reason).isEqualTo(BackupAdmissionReason.UNSUPPORTED_VERSION)
    }

    @Test
    fun `inspectBackup rejects a missing or displaced version header`() = runBlocking {
        listOf(
            "{}",
            """{"preferences":{},"version":9}"""
        ).forEachIndexed { index, json ->
            val error = inspectAdmissionFailure("missing-header-$index", json)
            assertThat(error.reason).isEqualTo(BackupAdmissionReason.MALFORMED)
        }
    }

    @Test
    fun `inspectBackup rejects duplicate top level fields`() = runBlocking {
        val error = inspectAdmissionFailure(
            "duplicate-field",
            """{"version":9,"preferences":{},"preferences":{}}"""
        )

        assertThat(error.reason).isEqualTo(BackupAdmissionReason.DUPLICATE_FIELD)
    }

    @Test
    fun `inspectBackup propagates cancellation during streaming read`() {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(Uri.parse("content://cancel-mid-read"))).thenReturn(
            CancellingInputStream("""{"version":9,"preferences":{""".toByteArray())
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                backupManagerForValidation(context).inspectBackup("content://cancel-mid-read")
            }
        }
    }

    @Test
    fun `recording conflict benchmark remains linear for duplicate heavy sections`() {
        val provider = Provider(
            id = 7L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user"
        )
        val incomingItem = ScheduledRecordingBackup(
            providerServerUrl = provider.serverUrl,
            providerUsername = provider.username,
            channelId = 100L,
            channelName = "News",
            streamUrl = "https://example.com/live",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_060_000L
        )
        val existingItem = RecordingItem(
            id = "existing",
            providerId = provider.id,
            channelId = incomingItem.channelId,
            channelName = incomingItem.channelName,
            streamUrl = incomingItem.streamUrl,
            scheduledStartMs = incomingItem.scheduledStartMs,
            scheduledEndMs = incomingItem.scheduledEndMs,
            status = RecordingStatus.SCHEDULED
        )
        val incoming = List(20_000) { incomingItem }
        val existing = List(20_000) { existingItem }
        var conflicts = 0

        val elapsedMs = measureTimeMillis {
            conflicts = backupManagerForValidation(mock()).countScheduledRecordingConflicts(
                incoming = incoming,
                providersByIdentity = mapOf(provider.backupIdentityForTest() to provider),
                existing = existing
            )
        }

        assertThat(conflicts).isEqualTo(incoming.size)
        assertThat(elapsedMs).isLessThan(5_000L)
    }

    @Test
    fun `inspectBackup rejects structurally empty json instead of previewing version zero`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(Uri.parse("content://empty-backup"))).thenReturn(
            ByteArrayInputStream("{}".toByteArray())
        )

        val manager = backupManagerForValidation(context = context)

        val result = manager.inspectBackup("content://empty-backup")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).exception).isInstanceOf(BackupAdmissionException::class.java)
        assertThat((result.exception as BackupAdmissionException).reason)
            .isEqualTo(BackupAdmissionReason.MALFORMED)
    }

    @Test
    fun `importConfig rejects structurally empty json before mutating data`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(Uri.parse("content://empty-backup-import"))).thenReturn(
            ByteArrayInputStream("{}".toByteArray())
        )

        val manager = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository
        )

        val result = manager.importConfig("content://empty-backup-import")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).exception).isInstanceOf(BackupAdmissionException::class.java)
        assertThat((result.exception as BackupAdmissionException).reason)
            .isEqualTo(BackupAdmissionReason.MALFORMED)
        verify(preferencesRepository, never()).setParentalControlLevel(any())
    }

    @Test
    fun `import strips Stalker transport consent and requires attention for HTTP`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val providerDao: ProviderDao = mock()
        val credentialCrypto: CredentialCrypto = mock()
        val gson = Gson()
        val backup = BackupData(
            providers = listOf(
                Provider(
                    id = 9L,
                    name = "MAG",
                    type = ProviderType.STALKER_PORTAL,
                    serverUrl = "http://portal.example.com/c/",
                    stalkerMacAddress = "00:1A:79:12:34:56",
                    stalkerTransportMode = StalkerTransportMode.USER_ACCEPTED_HTTP,
                    stalkerTransportOrigin = "http://portal.example.com",
                    stalkerTlsSpkiSha256 = "sha256/must-not-survive",
                    stalkerTransportConsentAt = 1234L,
                    isActive = true,
                    status = ProviderStatus.ACTIVE
                )
            )
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(Uri.parse("content://stalker-backup"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backup).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(emptyList())
        whenever(providerDao.insert(any())).thenReturn(9L)
        whenever(credentialCrypto.encryptIfNeeded(any())).thenAnswer { invocation ->
            invocation.arguments.first() as String
        }
        whenever(credentialCrypto.decryptIfNeeded(any())).thenAnswer { invocation ->
            invocation.arguments.first() as String
        }
        val providerSnapshotDao: ProviderSnapshotDao = mock()
        val configurationCodec = ProviderConfigurationCodec(gson, credentialCrypto)
        whenever(providerSnapshotDao.getConfig(any())).thenReturn(null)
        whenever(providerSnapshotDao.commitConfiguration(any())).thenReturn(true)
        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = mock(),
            credentialCrypto = credentialCrypto,
            providerDao = providerDao,
            favoriteDao = mock(),
            virtualGroupDao = mock(),
            playbackHistoryDao = mock(),
            movieDao = mock(),
            episodeDao = mock(),
            channelDao = mock(),
            seriesDao = mock(),
            categoryRepository = mock(),
            recordingScheduleDao = mock(),
            recordingManager = mock(),
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = gson,
            providerSnapshotRepository = mock<ProviderSnapshotRepository>(),
            providerSnapshotDao = providerSnapshotDao,
            providerConfigurationCodec = configurationCodec
        )

        val result = manager.importConfig(
            uriString = "content://stalker-backup",
            plan = BackupImportPlan(
                importPreferences = false,
                importProviders = true,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data.failedSections).isEmpty()
        assertThat((result as Result.Success).data.outcome).isEqualTo(BackupRestoreOutcome.COMPLETE)
        val inserted = org.mockito.kotlin.argumentCaptor<ProviderEntity>()
        verify(providerDao).insert(inserted.capture())
        assertThat(inserted.firstValue.isActive).isFalse()
        assertThat(inserted.firstValue.status).isEqualTo(ProviderStatus.PARTIAL)
        val storedConfig = org.mockito.kotlin.argumentCaptor<ProviderConfigEntity>()
        verify(providerSnapshotDao).commitConfiguration(storedConfig.capture())
        val restoredConfig = configurationCodec.decode(
            storedConfig.firstValue.type,
            storedConfig.firstValue.encryptedConfigJson
        ) as StalkerConfig
        assertThat(restoredConfig.transportGrant).isNull()
    }

    @Test
    fun `importConfig replace history only deletes imported providers and resyncs them in transaction`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)

        val providerDao: ProviderDao = mock()
        val playbackHistoryDao: PlaybackHistoryDao = mock()
        val movieDao: MovieDao = mock()
        val episodeDao: EpisodeDao = mock()
        val transactionRunner = RecordingTransactionRunner()
        val gson = Gson()

        val backupProvider = Provider(
            id = 100L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user",
            password = "",
            stalkerMacAddress = ""
        )
        val backupData = BackupData(
            providers = listOf(backupProvider),
            playbackHistory = listOf(
                PlaybackHistory(
                    contentId = 55L,
                    contentType = ContentType.MOVIE,
                    providerId = 100L,
                    title = "Movie",
                    streamUrl = "https://stream.example.test/movie.mp4",
                    resumePositionMs = 12_000L,
                    totalDurationMs = 5_400_000L
                )
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://backup"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(
            listOf(
                ProviderEntity(
                    id = 7L,
                    name = "Stored Provider",
                    type = ProviderType.M3U,
                    serverUrl = "https://example.com",
                    username = "user"
                )
            )
        )
        whenever(movieDao.getById(55L)).thenReturn(
            MovieEntity(id = 55L, streamId = 5500L, name = "Movie", providerId = 7L)
        )

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = mock<PreferencesRepository>(),
            credentialCrypto = mock<CredentialCrypto>(),
            providerDao = providerDao,
            favoriteDao = mock<FavoriteDao>(),
            virtualGroupDao = mock<VirtualGroupDao>(),
            playbackHistoryDao = playbackHistoryDao,
            movieDao = movieDao,
            episodeDao = episodeDao,
            categoryRepository = mock<CategoryRepository>(),
            recordingScheduleDao = mock<RecordingScheduleDao>(),
            recordingManager = mock<RecordingManager>(),
            transactionRunner = transactionRunner,
            gson = gson,
            channelDao = mock(),
            seriesDao = mock(),
            providerSnapshotRepository = snapshotRepositoryFor(backupProvider.copy(id = 7L))
        )

        val result = manager.importConfig(
            uriString = "content://backup",
            plan = BackupImportPlan(
                importPreferences = false,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = true,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(transactionRunner.calls).isEqualTo(1)
        verify(playbackHistoryDao).deleteByProvider(7L)
        verify(playbackHistoryDao, never()).deleteAll()
        verify(playbackHistoryDao).insertOrUpdate(argThat {
            providerId == 7L &&
                contentId == 55L &&
                contentType == ContentType.MOVIE
        })
        verify(movieDao).syncWatchProgressFromHistoryByProvider(7L)
        verify(episodeDao).syncWatchProgressFromHistoryByProvider(7L)
        verify(movieDao, never()).syncAllWatchProgressFromHistory()
        verify(episodeDao, never()).syncAllWatchProgressFromHistory()
    }

    @Test
    fun `importConfig keeps saved library and history writes inside one room transaction`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)

        val providerDao: ProviderDao = mock()
        val favoriteDao: FavoriteDao = mock()
        val playbackHistoryDao: PlaybackHistoryDao = mock()
        val movieDao: MovieDao = mock()
        val episodeDao: EpisodeDao = mock()
        val transactionRunner = RecordingTransactionRunner()
        val gson = Gson()
        val backupProvider = Provider(
            id = 100L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user",
            password = "",
            stalkerMacAddress = ""
        )
        val backupData = BackupData(
            providers = listOf(backupProvider),
            favorites = listOf(
                Favorite(
                    providerId = 100L,
                    contentId = 88L,
                    contentType = ContentType.MOVIE,
                    position = 0
                )
            ),
            playbackHistory = listOf(
                PlaybackHistory(
                    contentId = 55L,
                    contentType = ContentType.MOVIE,
                    providerId = 100L,
                    title = "Movie",
                    streamUrl = "https://stream.example.test/movie.mp4",
                    watchCount = 1
                )
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-transaction"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(
            listOf(
                ProviderEntity(
                    id = 7L,
                    name = "Stored Provider",
                    type = ProviderType.M3U,
                    serverUrl = "https://example.com",
                    username = "user"
                )
            )
        )
        whenever(movieDao.getById(88L)).thenReturn(
            MovieEntity(id = 88L, streamId = 8800L, name = "Favorite", providerId = 7L)
        )
        whenever(movieDao.getById(55L)).thenReturn(
            MovieEntity(id = 55L, streamId = 5500L, name = "Movie", providerId = 7L)
        )
        whenever(favoriteDao.get(any(), any(), any(), any())).thenReturn(null)
        doAnswer {
            assertThat(transactionRunner.isInTransaction).isTrue()
            1L
        }.whenever(favoriteDao).insert(any())
        doAnswer {
            assertThat(transactionRunner.isInTransaction).isTrue()
            Unit
        }.whenever(playbackHistoryDao).deleteByProvider(any())
        doAnswer {
            assertThat(transactionRunner.isInTransaction).isTrue()
            Unit
        }.whenever(playbackHistoryDao).insertOrUpdate(any())
        doAnswer {
            assertThat(transactionRunner.isInTransaction).isTrue()
            Unit
        }.whenever(movieDao).syncWatchProgressFromHistoryByProvider(any())
        doAnswer {
            assertThat(transactionRunner.isInTransaction).isTrue()
            Unit
        }.whenever(episodeDao).syncWatchProgressFromHistoryByProvider(any())

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = mock<PreferencesRepository>(),
            credentialCrypto = mock<CredentialCrypto>(),
            providerDao = providerDao,
            favoriteDao = favoriteDao,
            virtualGroupDao = mock<VirtualGroupDao>(),
            playbackHistoryDao = playbackHistoryDao,
            movieDao = movieDao,
            episodeDao = episodeDao,
            categoryRepository = mock<CategoryRepository>(),
            recordingScheduleDao = mock<RecordingScheduleDao>(),
            recordingManager = mock<RecordingManager>(),
            transactionRunner = transactionRunner,
            gson = gson,
            channelDao = mock(),
            seriesDao = mock(),
            providerSnapshotRepository = snapshotRepositoryFor(backupProvider.copy(id = 7L))
        )

        val result = manager.importConfig(
            uriString = "content://backup-transaction",
            plan = BackupImportPlan(
                importPreferences = false,
                importProviders = false,
                importSavedLibrary = true,
                importPlaybackHistory = true,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(transactionRunner.calls).isEqualTo(1)
        verify(favoriteDao).insert(any())
        verify(playbackHistoryDao).insertOrUpdate(any())
    }

    @Test
    fun `importConfig resolves saved library and episode history through remote catalog ids`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val providerDao: ProviderDao = mock()
        val favoriteDao: FavoriteDao = mock()
        val playbackHistoryDao: PlaybackHistoryDao = mock()
        val movieDao: MovieDao = mock()
        val episodeDao: EpisodeDao = mock()
        val seriesDao: SeriesDao = mock()
        val gson = Gson()
        val backupProvider = Provider(
            id = 100L,
            name = "Provider",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com",
            username = "user"
        )
        val targetMovie = MovieEntity(
            id = 188L,
            streamId = 9001L,
            name = "Movie",
            providerId = 7L
        )
        val targetSeries = SeriesEntity(
            id = 200L,
            seriesId = 3001L,
            providerSeriesId = "series-remote",
            name = "Series",
            providerId = 7L
        )
        val targetEpisode = EpisodeEntity(
            id = 300L,
            episodeId = 7001L,
            title = "Episode",
            episodeNumber = 1,
            seasonNumber = 1,
            seriesId = targetSeries.id,
            providerId = 7L
        )
        val backupData = BackupData(
            providers = listOf(backupProvider),
            favorites = listOf(
                Favorite(
                    providerId = backupProvider.id,
                    contentId = 88L,
                    contentType = ContentType.MOVIE,
                    remoteContentId = "9001"
                )
            ),
            playbackHistory = listOf(
                PlaybackHistory(
                    contentId = 55L,
                    contentType = ContentType.SERIES_EPISODE,
                    providerId = backupProvider.id,
                    remoteContentId = "7001",
                    remoteSeriesId = "series-remote",
                    title = "Episode",
                    streamUrl = "https://stream.example.test/episode.mp4"
                )
            )
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(Uri.parse("content://remote-id-restore"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(
            listOf(
                ProviderEntity(
                    id = 7L,
                    name = "Target Provider",
                    type = ProviderType.XTREAM_CODES,
                    serverUrl = backupProvider.serverUrl,
                    username = backupProvider.username
                )
            )
        )
        whenever(movieDao.getByStreamId(7L, 9001L)).thenReturn(targetMovie)
        whenever(seriesDao.getByProviderSeriesId(7L, "series-remote")).thenReturn(targetSeries)
        whenever(episodeDao.getByProviderSeriesAndEpisodeId(7L, targetSeries.id, 7001L))
            .thenReturn(targetEpisode)
        whenever(favoriteDao.get(any(), any(), any(), any())).thenReturn(null)
        whenever(playbackHistoryDao.get(any(), any(), any())).thenReturn(null)

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = mock(),
            credentialCrypto = mock(),
            providerDao = providerDao,
            favoriteDao = favoriteDao,
            virtualGroupDao = mock(),
            playbackHistoryDao = playbackHistoryDao,
            movieDao = movieDao,
            episodeDao = episodeDao,
            categoryRepository = mock(),
            recordingScheduleDao = mock(),
            recordingManager = mock(),
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = gson,
            channelDao = mock(),
            seriesDao = seriesDao,
            providerSnapshotRepository = snapshotRepositoryFor(backupProvider.copy(id = 7L))
        )

        val result = manager.importConfig(
            uriString = "content://remote-id-restore",
            plan = BackupImportPlan(
                importPreferences = false,
                importProviders = false,
                importSavedLibrary = true,
                importPlaybackHistory = true,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.KEEP_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.COMPLETE)
        assertThat(imported.unresolvedReferences).isEmpty()
        verify(favoriteDao).insert(argThat {
            providerId == 7L && contentId == targetMovie.id && contentType.name == ContentType.MOVIE.name
        })
        verify(playbackHistoryDao).insertOrUpdate(argThat {
            providerId == 7L &&
                contentId == targetEpisode.id &&
                seriesId == targetSeries.id &&
                contentType == ContentType.SERIES_EPISODE
        })
    }

    @Test
    fun `replace saved library removes old groups and restores memberships into new groups`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val providerDao: ProviderDao = mock()
        val favoriteDao: FavoriteDao = mock()
        val virtualGroupDao: VirtualGroupDao = mock()
        val movieDao: MovieDao = mock()
        val gson = Gson()
        val backupProvider = Provider(
            id = 100L,
            name = "Provider",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com",
            username = "user"
        )
        val targetMovie = MovieEntity(
            id = 188L,
            streamId = 9001L,
            name = "Movie",
            providerId = 7L
        )
        val backupData = BackupData(
            providers = listOf(backupProvider),
            virtualGroups = listOf(
                com.streamvault.domain.model.VirtualGroup(
                    id = 10L,
                    providerId = backupProvider.id,
                    name = "Pinned",
                    contentType = ContentType.MOVIE
                )
            ),
            favorites = listOf(
                Favorite(
                    providerId = backupProvider.id,
                    contentId = 88L,
                    contentType = ContentType.MOVIE,
                    remoteContentId = "9001",
                    groupId = 10L
                )
            )
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(Uri.parse("content://replace-library"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(
            listOf(
                ProviderEntity(
                    id = 7L,
                    name = "Target Provider",
                    type = ProviderType.XTREAM_CODES,
                    serverUrl = backupProvider.serverUrl,
                    username = backupProvider.username
                )
            )
        )
        whenever(movieDao.getByStreamId(7L, 9001L)).thenReturn(targetMovie)
        whenever(virtualGroupDao.insert(any())).thenReturn(88L)
        whenever(favoriteDao.get(any(), any(), any(), any())).thenReturn(null)

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = mock(),
            credentialCrypto = mock(),
            providerDao = providerDao,
            favoriteDao = favoriteDao,
            virtualGroupDao = virtualGroupDao,
            playbackHistoryDao = mock(),
            movieDao = movieDao,
            episodeDao = mock(),
            categoryRepository = mock(),
            recordingScheduleDao = mock(),
            recordingManager = mock(),
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = gson,
            channelDao = mock(),
            seriesDao = mock(),
            providerSnapshotRepository = snapshotRepositoryFor(backupProvider.copy(id = 7L))
        )

        val result = manager.importConfig(
            uriString = "content://replace-library",
            plan = BackupImportPlan(
                importPreferences = false,
                importProviders = false,
                importSavedLibrary = true,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data.outcome).isEqualTo(BackupRestoreOutcome.COMPLETE)
        verify(favoriteDao).deleteByProviderAndType(7L, ContentType.MOVIE.name)
        verify(virtualGroupDao).deleteByProviderAndType(7L, ContentType.MOVIE.name)
        verify(virtualGroupDao).insert(argThat {
            id == 0L && providerId == 7L && name == "Pinned" && contentType == ContentType.MOVIE
        })
        verify(favoriteDao).insert(argThat {
            providerId == 7L && contentId == targetMovie.id && groupId == 88L
        })
    }

    @Test
    fun `replace history does not delete a provider when any portable item is unresolved`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val providerDao: ProviderDao = mock()
        val playbackHistoryDao: PlaybackHistoryDao = mock()
        val movieDao: MovieDao = mock()
        val gson = Gson()
        val backupProvider = Provider(
            id = 100L,
            name = "Provider",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com",
            username = "user"
        )
        val targetMovie = MovieEntity(
            id = 188L,
            streamId = 9001L,
            name = "Movie",
            providerId = 7L
        )
        val backupData = BackupData(
            providers = listOf(backupProvider),
            playbackHistory = listOf(
                PlaybackHistory(
                    contentId = 1L,
                    contentType = ContentType.MOVIE,
                    providerId = backupProvider.id,
                    remoteContentId = "9001",
                    title = "Known",
                    streamUrl = "https://stream.example.test/known.mp4"
                ),
                PlaybackHistory(
                    contentId = 2L,
                    contentType = ContentType.MOVIE,
                    providerId = backupProvider.id,
                    remoteContentId = "9999",
                    title = "Missing",
                    streamUrl = "https://stream.example.test/missing.mp4"
                )
            )
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(Uri.parse("content://replace-history-safe"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(
            listOf(
                ProviderEntity(
                    id = 7L,
                    name = "Target Provider",
                    type = ProviderType.XTREAM_CODES,
                    serverUrl = backupProvider.serverUrl,
                    username = backupProvider.username
                )
            )
        )
        whenever(movieDao.getByStreamId(7L, 9001L)).thenReturn(targetMovie)
        whenever(movieDao.getByStreamId(7L, 9999L)).thenReturn(null)

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = mock(),
            credentialCrypto = mock(),
            providerDao = providerDao,
            favoriteDao = mock(),
            virtualGroupDao = mock(),
            playbackHistoryDao = playbackHistoryDao,
            movieDao = movieDao,
            episodeDao = mock(),
            categoryRepository = mock(),
            recordingScheduleDao = mock(),
            recordingManager = mock(),
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = gson,
            channelDao = mock(),
            seriesDao = mock(),
            providerSnapshotRepository = snapshotRepositoryFor(backupProvider.copy(id = 7L))
        )

        val result = manager.importConfig(
            uriString = "content://replace-history-safe",
            plan = BackupImportPlan(
                importPreferences = false,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = true,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.PARTIAL)
        assertThat(imported.unresolvedReferences).isNotEmpty()
        verify(playbackHistoryDao, never()).deleteByProvider(7L)
        verify(playbackHistoryDao, never()).insertOrUpdate(any())
    }

    @Test
    fun `inspectBackup uses provider and stable content identities for saved conflicts`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val providerDao: ProviderDao = mock()
        val favoriteDao: FavoriteDao = mock()
        val playbackHistoryDao: PlaybackHistoryDao = mock()
        val virtualGroupDao: VirtualGroupDao = mock()
        val categoryRepository: CategoryRepository = mock()
        val recordingManager: RecordingManager = mock()
        val movieDao: MovieDao = mock()
        val sourceProvider = Provider(
            id = 100L,
            name = "Source",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com",
            username = "user"
        )
        val targetProvider = sourceProvider.copy(id = 7L, name = "Target")
        val existingGroup = VirtualGroupEntity(
            id = 77L,
            providerId = targetProvider.id,
            name = "Pinned",
            contentType = ContentType.MOVIE
        )
        val existingFavorite = com.streamvault.data.local.entity.FavoriteEntity(
            id = 55L,
            providerId = targetProvider.id,
            contentId = 188L,
            contentType = ContentType.MOVIE,
            groupId = existingGroup.id
        )
        val gson = Gson()
        val backupData = BackupData(
            providers = listOf(sourceProvider),
            virtualGroups = listOf(
                com.streamvault.domain.model.VirtualGroup(
                    id = 10L,
                    providerId = sourceProvider.id,
                    name = "Pinned",
                    contentType = ContentType.MOVIE
                )
            ),
            favorites = listOf(
                Favorite(
                    providerId = sourceProvider.id,
                    contentId = 88L,
                    contentType = ContentType.MOVIE,
                    remoteContentId = "9001",
                    groupId = 10L
                )
            )
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(Uri.parse("content://preview-identities"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(listOf(targetProvider.toEntity()))
        whenever(virtualGroupDao.getByType(targetProvider.id, ContentType.LIVE.name))
            .thenReturn(flowOf(emptyList()))
        whenever(virtualGroupDao.getByType(targetProvider.id, ContentType.MOVIE.name))
            .thenReturn(flowOf(listOf(existingGroup)))
        whenever(virtualGroupDao.getByType(targetProvider.id, ContentType.SERIES.name))
            .thenReturn(flowOf(emptyList()))
        whenever(favoriteDao.getAllByType(any(), any())).thenReturn(flowOf(listOf(existingFavorite)))
        whenever(playbackHistoryDao.getAllSync()).thenReturn(emptyList())
        whenever(categoryRepository.getCategories(targetProvider.id)).thenReturn(flowOf(emptyList()))
        whenever(recordingManager.observeRecordingItems()).thenReturn(flowOf(emptyList()))
        whenever(movieDao.getById(existingFavorite.contentId)).thenReturn(
            MovieEntity(
                id = existingFavorite.contentId,
                streamId = 9001L,
                name = "Movie",
                providerId = targetProvider.id
            )
        )

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = mock(),
            credentialCrypto = mock(),
            providerDao = providerDao,
            favoriteDao = favoriteDao,
            virtualGroupDao = virtualGroupDao,
            playbackHistoryDao = playbackHistoryDao,
            movieDao = movieDao,
            episodeDao = mock(),
            categoryRepository = categoryRepository,
            recordingScheduleDao = mock(),
            recordingManager = recordingManager,
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = gson,
            channelDao = mock(),
            seriesDao = mock(),
            providerSnapshotRepository = snapshotRepositoryFor(targetProvider)
        )

        val result = manager.inspectBackup("content://preview-identities")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val preview = (result as Result.Success).data
        assertThat(preview.groupConflicts).isEqualTo(1)
        assertThat(preview.favoriteConflicts).isEqualTo(1)
    }

    @Test
    fun `importConfig does not restore preferences before room-backed import succeeds`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)

        val providerDao: ProviderDao = mock()
        val favoriteDao: FavoriteDao = mock()
        val movieDao: MovieDao = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val gson = Gson()
        val backupProvider = Provider(
            id = 100L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user",
            password = "",
            stalkerMacAddress = ""
        )
        val backupData = BackupData(
            preferences = mapOf("parentalControlLevel" to "4"),
            providers = listOf(backupProvider),
            favorites = listOf(
                Favorite(
                    providerId = 100L,
                    contentId = 88L,
                    contentType = ContentType.MOVIE,
                    position = 0
                )
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-preferences-order"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(
            listOf(
                ProviderEntity(
                    id = 7L,
                    name = "Stored Provider",
                    type = ProviderType.M3U,
                    serverUrl = "https://example.com",
                    username = "user"
                )
            )
        )
        whenever(movieDao.getById(88L)).thenReturn(
            MovieEntity(id = 88L, streamId = 8800L, name = "Favorite", providerId = 7L)
        )
        whenever(favoriteDao.get(any(), any(), any(), any())).thenReturn(null)
        whenever(favoriteDao.insert(any())).thenThrow(IllegalStateException("favorite insert failed"))

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = preferencesRepository,
            credentialCrypto = mock<CredentialCrypto>(),
            providerDao = providerDao,
            favoriteDao = favoriteDao,
            virtualGroupDao = mock<VirtualGroupDao>(),
            playbackHistoryDao = mock<PlaybackHistoryDao>(),
            movieDao = movieDao,
            episodeDao = mock<EpisodeDao>(),
            categoryRepository = mock<CategoryRepository>(),
            recordingScheduleDao = mock<RecordingScheduleDao>(),
            recordingManager = mock<RecordingManager>(),
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = gson,
            channelDao = mock(),
            seriesDao = mock(),
            providerSnapshotRepository = snapshotRepositoryFor(backupProvider.copy(id = 7L))
        )

        val result = manager.importConfig(
            uriString = "content://backup-preferences-order",
            plan = BackupImportPlan(
                importPreferences = true,
                importProviders = false,
                importSavedLibrary = true,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data.outcome)
            .isEqualTo(BackupRestoreOutcome.FAILED_BEFORE_COMMIT)
        verify(preferencesRepository, never()).setParentalControlLevel(any())
    }

    @Test
    fun `importConfig reports a preference write failure as partial after room commit`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(emptyList())
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-preference-partial"))).thenReturn(
            ByteArrayInputStream(Gson().toJson(BackupData(preferences = mapOf("parentalControlLevel" to "4"))).toByteArray())
        )
        doThrow(IllegalStateException("DataStore unavailable"))
            .whenever(preferencesRepository).setParentalControlLevel(4)

        val result = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao
        ).importConfig(
            uriString = "content://backup-preference-partial",
            plan = BackupImportPlan(
                importPreferences = true,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val import = (result as Result.Success).data
        assertThat(import.outcome).isEqualTo(BackupRestoreOutcome.PARTIAL)
        assertThat(import.failedSections.single()).contains("Preferences: DataStore unavailable")
    }

    @Test
    fun `importConfig clears optional guide preferences when backup has no selected value`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(emptyList())
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-guide-defaults"))).thenReturn(
            ByteArrayInputStream(
                Gson().toJson(
                    BackupData(
                        preferences = mapOf(
                            "guideDefaultCategoryId" to "0",
                            "guideAnchorTime" to "0"
                        )
                    )
                ).toByteArray()
            )
        )

        val result = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao
        ).importConfig(
            uriString = "content://backup-guide-defaults",
            plan = BackupImportPlan(
                importPreferences = true,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(preferencesRepository).clearGuideDefaultCategoryId()
        verify(preferencesRepository).clearGuideAnchorTime()
        Unit
    }

    @Test
    fun `importConfig propagates cancellation without reporting a false outcome`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        doThrow(CancellationException("cancelled"))
            .whenever(contentResolver).openInputStream(Uri.parse("content://backup-cancelled"))
        val manager = backupManagerForValidation(context = context)

        assertThrows(CancellationException::class.java) {
            runBlocking { manager.importConfig("content://backup-cancelled") }
        }
        Unit
    }

    @Test
    fun `importConfig reports unresolved portable provider preferences without applying raw ids`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val providerDao: ProviderDao = mock()
        val preferencesRepository: PreferencesRepository = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(emptyList())
        whenever(contentResolver.openInputStream(Uri.parse("content://portable-unresolved"))).thenReturn(
            ByteArrayInputStream(
                Gson().toJson(
                    BackupData(
                        preferences = mapOf("hiddenChannels_2" to "99"),
                        portableProviderPreferences = PortableProviderPreferencesBackup(
                            providers = listOf(BackupProviderReference("https://missing", "user"))
                        )
                    )
                ).toByteArray()
            )
        )

        val result = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao,
            channelDao = mock()
        ).importConfig(
            "content://portable-unresolved",
            BackupImportPlan(
                importPreferences = true,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false
            )
        )

        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.PARTIAL)
        assertThat(imported.unresolvedReferences).contains("Provider https://missing (user)")
        verify(preferencesRepository, never()).setHiddenChannelIds(2L, setOf(99L))
        Unit
    }

    @Test
    fun `portable import does not replace hidden channels with a partial resolved subset`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        val categoryRepository: CategoryRepository = mock()
        val channelDao: ChannelDao = mock()
        val reference = BackupProviderReference("https://example.com", "user")
        val targetProvider = Provider(
            id = 7L,
            name = "Target",
            type = ProviderType.XTREAM_CODES,
            serverUrl = reference.serverUrl,
            username = reference.username
        )
        val available = PortableChannelReference(
            provider = reference,
            streamId = 400L,
            name = "World News",
            streamUrl = "https://example.com/live/400"
        )
        val missing = available.copy(streamId = 401L, name = "Missing")
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(listOf(targetProvider.toEntity()))
        whenever(categoryRepository.getCategories(targetProvider.id)).thenReturn(flowOf(emptyList()))
        whenever(channelDao.getByProviderSync(targetProvider.id)).thenReturn(
            listOf(
                ChannelEntity(
                    id = 140L,
                    streamId = available.streamId,
                    name = available.name,
                    streamUrl = available.streamUrl,
                    providerId = targetProvider.id
                )
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://portable-hidden-partial"))).thenReturn(
            ByteArrayInputStream(
                Gson().toJson(
                    BackupData(
                        portableProviderPreferences = PortableProviderPreferencesBackup(
                            providers = listOf(reference),
                            hiddenChannels = listOf(available, missing)
                        )
                    )
                ).toByteArray()
            )
        )

        val result = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao,
            categoryRepository = categoryRepository,
            channelDao = channelDao,
            storedProviders = listOf(targetProvider)
        ).importConfig("content://portable-hidden-partial", preferencesOnlyPlan())

        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.PARTIAL)
        assertThat(imported.unresolvedReferences).contains("Hidden channel Missing (stream 401) at https://example.com")
        verify(preferencesRepository, never()).setHiddenChannelIds(targetProvider.id, setOf(140L))
        Unit
    }

    @Test
    fun `portable category identity prefers a matching remote id when the name changed`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        val categoryRepository: CategoryRepository = mock()
        val reference = BackupProviderReference("https://example.com", "user")
        val targetProvider = Provider(
            id = 7L,
            name = "Target",
            type = ProviderType.XTREAM_CODES,
            serverUrl = reference.serverUrl,
            username = reference.username
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(listOf(targetProvider.toEntity()))
        whenever(categoryRepository.getCategories(targetProvider.id)).thenReturn(
            flowOf(listOf(Category(id = 50L, name = "Renamed News", type = ContentType.LIVE)))
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://portable-category-renamed"))).thenReturn(
            ByteArrayInputStream(
                Gson().toJson(
                    BackupData(
                        portableProviderPreferences = PortableProviderPreferencesBackup(
                            providers = listOf(reference),
                            hiddenCategories = listOf(
                                PortableCategoryReference(reference, "News", ContentType.LIVE, 50L)
                            )
                        )
                    )
                ).toByteArray()
            )
        )

        val result = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao,
            categoryRepository = categoryRepository,
            storedProviders = listOf(targetProvider)
        ).importConfig("content://portable-category-renamed", preferencesOnlyPlan())

        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.COMPLETE)
        verify(preferencesRepository).setHiddenCategoryIds(targetProvider.id, ContentType.LIVE, setOf(50L))
        Unit
    }

    @Test
    fun `portable split-screen import does not replace a preset with a partial resolved subset`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        val categoryRepository: CategoryRepository = mock()
        val channelDao: ChannelDao = mock()
        val reference = BackupProviderReference("https://example.com", "user")
        val targetProvider = Provider(
            id = 7L,
            name = "Target",
            type = ProviderType.XTREAM_CODES,
            serverUrl = reference.serverUrl,
            username = reference.username
        )
        val available = PortableChannelReference(
            provider = reference,
            streamId = 400L,
            name = "World News",
            streamUrl = "https://example.com/live/400"
        )
        val missing = available.copy(streamId = 401L, name = "Missing")
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(listOf(targetProvider.toEntity()))
        whenever(categoryRepository.getCategories(targetProvider.id)).thenReturn(flowOf(emptyList()))
        whenever(channelDao.getByProviderSync(targetProvider.id)).thenReturn(
            listOf(
                ChannelEntity(
                    id = 140L,
                    streamId = available.streamId,
                    name = available.name,
                    streamUrl = available.streamUrl,
                    providerId = targetProvider.id
                )
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://portable-preset-partial"))).thenReturn(
            ByteArrayInputStream(
                Gson().toJson(
                    BackupData(
                        portableMultiViewPresets = mapOf("preset_1" to listOf(available, missing))
                    )
                ).toByteArray()
            )
        )

        val result = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao,
            categoryRepository = categoryRepository,
            channelDao = channelDao,
            storedProviders = listOf(targetProvider)
        ).importConfig(
            "content://portable-preset-partial",
            BackupImportPlan(
                importPreferences = false,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = true,
                importRecordingSchedules = false
            )
        )

        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.PARTIAL)
        verify(preferencesRepository, never()).setMultiViewPreset(0, listOf(140L))
        Unit
    }

    @Test
    fun `portable variant selection resolves remote item ids instead of restoring local ids`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        val categoryRepository: CategoryRepository = mock()
        val channelDao: ChannelDao = mock()
        val reference = BackupProviderReference("https://example.com", "user")
        val targetProvider = Provider(
            id = 7L,
            name = "Target",
            type = ProviderType.XTREAM_CODES,
            serverUrl = reference.serverUrl,
            username = reference.username
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(listOf(targetProvider.toEntity()))
        whenever(categoryRepository.getCategories(targetProvider.id)).thenReturn(flowOf(emptyList()))
        whenever(channelDao.getByStreamId(targetProvider.id, 900L)).thenReturn(
            ChannelEntity(
                id = 140L,
                streamId = 900L,
                name = "Preferred News",
                streamUrl = "https://example.com/live/900",
                providerId = targetProvider.id
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://portable-variant-identity"))).thenReturn(
            ByteArrayInputStream(
                Gson().toJson(
                    BackupData(
                        portableProviderPreferences = PortableProviderPreferencesBackup(
                            providers = listOf(reference),
                            liveVariantSelections = listOf(
                                PortableVariantSelectionReference(
                                    provider = reference,
                                    logicalGroupId = "news",
                                    rawItemId = 400L,
                                    remoteItemId = "900",
                                    contentType = ContentType.LIVE
                                )
                            ),
                            liveVariantSelectionsSpecified = true
                        )
                    )
                ).toByteArray()
            )
        )

        val result = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao,
            categoryRepository = categoryRepository,
            channelDao = channelDao,
            storedProviders = listOf(targetProvider)
        ).importConfig("content://portable-variant-identity", preferencesOnlyPlan())

        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.COMPLETE)
        verify(preferencesRepository).replacePreferredLiveVariants(targetProvider.id, mapOf("news" to 140L))
        Unit
    }

    @Test
    fun `replace saved library preserves a scope when one favorite cannot be resolved`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val providerDao: ProviderDao = mock()
        val favoriteDao: FavoriteDao = mock()
        val channelDao: ChannelDao = mock()
        val categoryRepository: CategoryRepository = mock()
        val provider = Provider(
            id = 7L,
            name = "Target",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com",
            username = "user"
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(listOf(provider.toEntity()))
        whenever(categoryRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))
        whenever(channelDao.getByStreamId(provider.id, 400L)).thenReturn(
            ChannelEntity(
                id = 140L,
                streamId = 400L,
                name = "World News",
                streamUrl = "https://example.com/live/400",
                providerId = provider.id
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://saved-library-partial"))).thenReturn(
            ByteArrayInputStream(
                Gson().toJson(
                    BackupData(
                        providers = listOf(provider),
                        favorites = listOf(
                            Favorite(providerId = provider.id, contentId = 1L, contentType = ContentType.LIVE, remoteContentId = "400"),
                            Favorite(providerId = provider.id, contentId = 2L, contentType = ContentType.LIVE, remoteContentId = "401")
                        )
                    )
                ).toByteArray()
            )
        )

        val result = backupManagerForValidation(
            context = context,
            providerDao = providerDao,
            favoriteDao = favoriteDao,
            channelDao = channelDao,
            categoryRepository = categoryRepository,
            storedProviders = listOf(provider)
        ).importConfig(
            "content://saved-library-partial",
            BackupImportPlan(
                importPreferences = false,
                importProviders = false,
                importSavedLibrary = true,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING
            )
        )

        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.PARTIAL)
        verify(favoriteDao, never()).deleteByProviderAndType(provider.id, ContentType.LIVE.name)
        Unit
    }

    @Test
    fun `portable export uses semantic identities instead of local provider category group and channel ids`() = runBlocking {
        val context: Context = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val categoryRepository: CategoryRepository = mock()
        val virtualGroupDao: VirtualGroupDao = mock()
        val channelDao: ChannelDao = mock()
        val provider = Provider(
            id = 2L,
            name = "Source",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com",
            username = "user"
        )
        val category = Category(
            id = 50L,
            roomId = 500L,
            name = "News",
            type = ContentType.LIVE
        )
        whenever(preferencesRepository.lastActiveProviderId).thenReturn(flowOf(provider.id))
        whenever(preferencesRepository.guideDefaultCategoryId).thenReturn(flowOf(category.id))
        whenever(preferencesRepository.promotedLiveGroupIds).thenReturn(flowOf(setOf(60L)))
        whenever(preferencesRepository.getHiddenChannelIds(provider.id)).thenReturn(flowOf(setOf(40L)))
        whenever(preferencesRepository.getHiddenCategoryIds(eq(provider.id), any())).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE))
            .thenReturn(flowOf(setOf(category.id)))
        whenever(preferencesRepository.getPinnedCategoryIds(eq(provider.id), any()))
            .thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getPinnedCategoryIds(provider.id, ContentType.LIVE))
            .thenReturn(flowOf(setOf(category.id)))
        whenever(preferencesRepository.getCategorySortMode(eq(provider.id), any()))
            .thenReturn(flowOf(com.streamvault.domain.model.CategorySortMode.DEFAULT))
        whenever(preferencesRepository.epgTimeShiftsByProvider)
            .thenReturn(flowOf(mapOf(provider.id to 30)))
        whenever(preferencesRepository.liveVariantSelections)
            .thenReturn(flowOf(mapOf("${provider.id}|news" to 400L)))
        whenever(preferencesRepository.vodVariantSelections)
            .thenReturn(flowOf(mapOf("${provider.id}|movie" to 401L)))
        whenever(categoryRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(category)))
        whenever(virtualGroupDao.getById(60L)).thenReturn(
            VirtualGroupEntity(
                id = 60L,
                providerId = provider.id,
                name = "My News",
                contentType = ContentType.LIVE
            )
        )
        whenever(channelDao.getById(40L)).thenReturn(
            ChannelEntity(
                id = 40L,
                streamId = 400L,
                name = "World News",
                streamUrl = "https://example.com/live/400",
                providerId = provider.id
            )
        )
        whenever(channelDao.getById(400L)).thenReturn(
            ChannelEntity(
                id = 400L,
                streamId = 900L,
                name = "Preferred News",
                streamUrl = "https://example.com/live/900",
                providerId = provider.id
            )
        )
        val movieDao: MovieDao = mock()
        whenever(movieDao.getById(401L)).thenReturn(
            MovieEntity(
                id = 401L,
                streamId = 801L,
                name = "Movie",
                providerId = provider.id
            )
        )

        val portable = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            categoryRepository = categoryRepository,
            virtualGroupDao = virtualGroupDao,
            channelDao = channelDao,
            movieDao = movieDao
        ).buildPortableProviderPreferences(listOf(provider))

        val providerReference = BackupProviderReference(
            provider.serverUrl,
            provider.username,
            providerType = provider.type
        )
        assertThat(portable.providers).containsExactly(providerReference)
        assertThat(portable.activeProvider).isEqualTo(providerReference)
        assertThat(portable.guideDefaultCategory?.remoteCategoryId).isEqualTo(50L)
        assertThat(portable.guideDefaultCategory?.name).isEqualTo("News")
        assertThat(portable.promotedLiveGroups.single().name).isEqualTo("My News")
        assertThat(portable.hiddenChannels.single().streamId).isEqualTo(400L)
        assertThat(portable.hiddenChannels.single().name).isEqualTo("World News")
        assertThat(portable.hiddenCategories.single().remoteCategoryId).isEqualTo(50L)
        assertThat(portable.pinnedCategories.single().remoteCategoryId).isEqualTo(50L)
        assertThat(portable.pinnedCategoriesSpecified).isTrue()
        assertThat(portable.epgTimeShifts.single().minutes).isEqualTo(30)
        assertThat(portable.epgTimeShiftsSpecified).isTrue()
        assertThat(portable.liveVariantSelections.single().logicalGroupId).isEqualTo("news")
        assertThat(portable.vodVariantSelections.single().rawItemId).isEqualTo(401L)
        assertThat(portable.unresolvedReferences).isEmpty()
    }

    @Test
    fun `local backup credentials hydrate redacted provider before restore`() {
        val manager = backupManagerForValidation(mock())
        val provider = Provider(
            id = 9L,
            name = "Source",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "http://EXAMPLE.com:80/",
            username = " user ",
            password = "",
        )
        val backup = BackupData(
            providers = listOf(provider),
            providerCredentials = listOf(
                ProviderCredentials(
                    serverUrl = "HTTP://example.com",
                    username = "user",
                    password = "secret",
                )
            )
        )

        val projectionMethod = BackupManagerImpl::class.java
            .getDeclaredMethod("withLegacyProviderProjection", BackupData::class.java)
            .apply { isAccessible = true }
        val hydrated = projectionMethod.invoke(manager, backup) as BackupData

        assertThat(hydrated.providers?.single()?.password).isEqualTo("secret")
    }

    @Test
    fun `portable export ignores the legacy no-active-provider sentinel`() = runBlocking {
        val context: Context = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val categoryRepository: CategoryRepository = mock()
        val provider = Provider(
            id = 2L,
            name = "Source",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com",
            username = "user"
        )
        whenever(preferencesRepository.lastActiveProviderId).thenReturn(flowOf(-1L))
        whenever(preferencesRepository.guideDefaultCategoryId).thenReturn(flowOf(null))
        whenever(preferencesRepository.promotedLiveGroupIds).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getHiddenChannelIds(provider.id)).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getHiddenCategoryIds(eq(provider.id), any()))
            .thenReturn(flowOf(emptySet()))
        whenever(categoryRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))

        val portable = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            categoryRepository = categoryRepository
        ).buildPortableProviderPreferences(listOf(provider))

        assertThat(portable.activeProvider).isNull()
        assertThat(portable.unresolvedReferences)
            .doesNotContain("Active provider id -1 was not found during export")
    }

    @Test
    fun `portable import matches provider identity across harmless url formatting differences`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        val categoryRepository: CategoryRepository = mock()
        val reference = BackupProviderReference("http://EXAMPLE.com:80/", " user ")
        val targetProvider = Provider(
            id = 7L,
            name = "Target",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "HTTP://example.COM",
            username = "user"
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(listOf(targetProvider.toEntity()))
        whenever(categoryRepository.getCategories(targetProvider.id)).thenReturn(flowOf(emptyList()))
        whenever(contentResolver.openInputStream(Uri.parse("content://portable-normalized-identity")))
            .thenReturn(
                ByteArrayInputStream(
                    Gson().toJson(
                        BackupData(
                            portableProviderPreferences = PortableProviderPreferencesBackup(
                                providers = listOf(reference),
                                activeProvider = reference,
                                unresolvedReferences = listOf(
                                    "Active provider id -1 was not found during export"
                                )
                            )
                        )
                    ).toByteArray()
                )
            )

        val result = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao,
            categoryRepository = categoryRepository,
            storedProviders = listOf(targetProvider)
        ).importConfig("content://portable-normalized-identity", preferencesOnlyPlan())

        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.COMPLETE)
        assertThat(imported.unresolvedReferences).isEmpty()
        verify(preferencesRepository).setLastActiveProviderId(targetProvider.id)
    }

    @Test
    fun `portable import restores hidden category ids before provider catalog is synced`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        val categoryRepository: CategoryRepository = mock()
        val reference = BackupProviderReference("https://example.com", "user")
        val targetProvider = Provider(
            id = 7L,
            name = "Target",
            type = ProviderType.XTREAM_CODES,
            serverUrl = reference.serverUrl,
            username = reference.username
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(listOf(targetProvider.toEntity()))
        whenever(categoryRepository.getCategories(targetProvider.id)).thenReturn(flowOf(emptyList()))
        whenever(contentResolver.openInputStream(Uri.parse("content://portable-hidden-unsynced"))).thenReturn(
            ByteArrayInputStream(
                Gson().toJson(
                    BackupData(
                        portableProviderPreferences = PortableProviderPreferencesBackup(
                            providers = listOf(reference),
                            hiddenCategories = listOf(
                                PortableCategoryReference(reference, "News", ContentType.LIVE, 50L)
                            )
                        )
                    )
                ).toByteArray()
            )
        )

        val result = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao,
            categoryRepository = categoryRepository,
            storedProviders = listOf(targetProvider)
        ).importConfig("content://portable-hidden-unsynced", preferencesOnlyPlan())

        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.COMPLETE)
        assertThat(imported.unresolvedReferences).isEmpty()
        verify(preferencesRepository).setHiddenCategoryIds(7L, ContentType.LIVE, setOf(50L))
    }

    @Test
    fun `portable import restores pinned and guide category ids before provider catalog is synced`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        val categoryRepository: CategoryRepository = mock()
        val reference = BackupProviderReference("https://example.com", "user")
        val category = PortableCategoryReference(reference, "News", ContentType.LIVE, 50L)
        val targetProvider = Provider(
            id = 7L,
            name = "Target",
            type = ProviderType.XTREAM_CODES,
            serverUrl = reference.serverUrl,
            username = reference.username
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(listOf(targetProvider.toEntity()))
        whenever(categoryRepository.getCategories(targetProvider.id)).thenReturn(flowOf(emptyList()))
        whenever(contentResolver.openInputStream(Uri.parse("content://portable-category-settings-unsynced")))
            .thenReturn(
                ByteArrayInputStream(
                    Gson().toJson(
                        BackupData(
                            portableProviderPreferences = PortableProviderPreferencesBackup(
                                providers = listOf(reference),
                                guideDefaultCategory = category,
                                guideDefaultCategorySpecified = true,
                                pinnedCategories = listOf(category),
                                pinnedCategoriesSpecified = true
                            )
                        )
                    ).toByteArray()
                )
            )

        val result = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao,
            categoryRepository = categoryRepository,
            storedProviders = listOf(targetProvider)
        ).importConfig("content://portable-category-settings-unsynced", preferencesOnlyPlan())

        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.COMPLETE)
        assertThat(imported.unresolvedReferences).isEmpty()
        verify(preferencesRepository).setGuideDefaultCategoryId(50L)
        verify(preferencesRepository).setPinnedCategoryIds(7L, ContentType.LIVE, setOf(50L))
    }

    @Test
    fun `replacing an existing provider preserves catalog rows needed by portable preferences`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        val categoryRepository: CategoryRepository = mock()
        val channelDao: ChannelDao = mock()
        val credentialCrypto: CredentialCrypto = mock()
        val reference = BackupProviderReference("https://example.com", "user")
        val existingProvider = Provider(
            id = 7L,
            name = "Existing",
            type = ProviderType.XTREAM_CODES,
            serverUrl = reference.serverUrl,
            username = reference.username
        )
        val providerChannels = mutableListOf(
            ChannelEntity(
                id = 140L,
                streamId = 400L,
                name = "World News",
                streamUrl = "https://example.com/live/400",
                providerId = existingProvider.id
            )
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(listOf(existingProvider.toEntity()))
        whenever(providerDao.insert(any())).thenAnswer {
            providerChannels.clear()
            existingProvider.id
        }
        whenever(channelDao.getByProviderSync(existingProvider.id)).thenAnswer { providerChannels.toList() }
        whenever(categoryRepository.getCategories(existingProvider.id)).thenReturn(flowOf(emptyList()))
        whenever(credentialCrypto.encryptIfNeeded(any())).thenAnswer { it.arguments.first() as String }
        whenever(credentialCrypto.decryptIfNeeded(any())).thenAnswer { it.arguments.first() as String }
        whenever(contentResolver.openInputStream(Uri.parse("content://replace-provider-portable-preferences")))
            .thenReturn(
                ByteArrayInputStream(
                    Gson().toJson(
                        BackupData(
                            providers = listOf(existingProvider.copy(id = 2L, name = "Backup")),
                            portableProviderPreferences = PortableProviderPreferencesBackup(
                                providers = listOf(reference),
                                hiddenChannels = listOf(
                                    PortableChannelReference(
                                        provider = reference,
                                        streamId = 400L,
                                        name = "World News",
                                        streamUrl = "https://example.com/live/400"
                                    )
                                )
                            )
                        )
                    ).toByteArray()
                )
            )

        val result = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao,
            categoryRepository = categoryRepository,
            channelDao = channelDao,
            credentialCrypto = credentialCrypto,
            storedProviders = listOf(existingProvider)
        ).importConfig(
            "content://replace-provider-portable-preferences",
            BackupImportPlan(
                importPreferences = true,
                importProviders = true,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.COMPLETE)
        assertThat(imported.unresolvedReferences).isEmpty()
        verify(preferencesRepository).setHiddenChannelIds(existingProvider.id, setOf(140L))
    }

    @Test
    fun `provider replacement does not retain catalog rows from another provider type`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val providerDao: ProviderDao = mock()
        val credentialCrypto: CredentialCrypto = mock()
        val existingProvider = Provider(
            id = 7L,
            name = "Existing M3U",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user"
        )
        val backupProvider = existingProvider.copy(
            id = 2L,
            name = "Backup Xtream",
            type = ProviderType.XTREAM_CODES
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(listOf(existingProvider.toEntity()))
        whenever(providerDao.insert(any())).thenReturn(8L)
        whenever(credentialCrypto.encryptIfNeeded(any())).thenAnswer { it.arguments.first() as String }
        whenever(credentialCrypto.decryptIfNeeded(any())).thenAnswer { it.arguments.first() as String }
        whenever(contentResolver.openInputStream(Uri.parse("content://replace-provider-different-type")))
            .thenReturn(
                ByteArrayInputStream(
                    Gson().toJson(BackupData(providers = listOf(backupProvider))).toByteArray()
                )
            )

        val result = backupManagerForValidation(
            context = context,
            providerDao = providerDao,
            credentialCrypto = credentialCrypto,
            storedProviders = listOf(existingProvider)
        ).importConfig(
            "content://replace-provider-different-type",
            BackupImportPlan(
                importPreferences = false,
                importProviders = true,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING
            )
        )

        assertThat((result as Result.Success).data.outcome).isEqualTo(BackupRestoreOutcome.COMPLETE)
        verify(providerDao).insert(any())
        verify(providerDao, never()).update(any())
    }

    @Test
    fun `semantic json round trip resolves shifted target ids after room restore`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        val categoryRepository: CategoryRepository = mock()
        val virtualGroupDao: VirtualGroupDao = mock()
        val channelDao: ChannelDao = mock()
        val providerReference = BackupProviderReference("https://example.com", "user")
        val targetProvider = Provider(
            id = 7L,
            name = "Target",
            type = ProviderType.XTREAM_CODES,
            serverUrl = providerReference.serverUrl,
            username = providerReference.username
        )
        val portable = PortableProviderPreferencesBackup(
            providers = listOf(providerReference),
            activeProvider = providerReference,
            guideDefaultCategory = PortableCategoryReference(
                provider = providerReference,
                name = "News",
                type = ContentType.LIVE,
                remoteCategoryId = 50L
            ),
            guideDefaultCategorySpecified = true,
            promotedLiveGroups = listOf(
                PortableVirtualGroupReference(providerReference, "My News", ContentType.LIVE)
            ),
            hiddenChannels = listOf(
                PortableChannelReference(
                    provider = providerReference,
                    streamId = 400L,
                    name = "World News",
                    streamUrl = "https://example.com/live/400"
                )
            ),
            hiddenCategories = listOf(
                PortableCategoryReference(providerReference, "News", ContentType.LIVE, 50L)
            ),
            pinnedCategories = listOf(
                PortableCategoryReference(providerReference, "News", ContentType.LIVE, 50L)
            ),
            pinnedCategoriesSpecified = true,
            categorySortModes = listOf(
                PortableCategorySortReference(providerReference, ContentType.LIVE, "TITLE_ASC")
            ),
            categorySortModesSpecified = true,
            epgTimeShifts = listOf(PortableEpgTimeShiftReference(providerReference, 30)),
            epgTimeShiftsSpecified = true,
            liveVariantSelections = listOf(
                com.streamvault.domain.manager.PortableVariantSelectionReference(
                    providerReference,
                    "news",
                    400L
                )
            ),
            liveVariantSelectionsSpecified = true,
            vodVariantSelections = listOf(
                com.streamvault.domain.manager.PortableVariantSelectionReference(
                    providerReference,
                    "movie",
                    401L
                )
            ),
            vodVariantSelectionsSpecified = true
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(listOf(targetProvider.toEntity()))
        whenever(categoryRepository.getCategories(targetProvider.id)).thenReturn(
            flowOf(listOf(Category(id = 150L, roomId = 1_500L, name = "News", type = ContentType.LIVE)))
        )
        whenever(virtualGroupDao.getByType(targetProvider.id, ContentType.LIVE.name)).thenReturn(
            flowOf(
                listOf(
                    VirtualGroupEntity(
                        id = 160L,
                        providerId = targetProvider.id,
                        name = "My News",
                        contentType = ContentType.LIVE
                    )
                )
            )
        )
        whenever(channelDao.getByProviderSync(targetProvider.id)).thenReturn(
            listOf(
                ChannelEntity(
                    id = 140L,
                    streamId = 400L,
                    name = "World News",
                    streamUrl = "https://example.com/live/400",
                    providerId = targetProvider.id
                )
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://portable-shifted"))).thenReturn(
            ByteArrayInputStream(
                Gson().toJson(
                    BackupData(
                        version = 9,
                        preferences = mapOf(
                            "guideDefaultCategoryId" to "50",
                            "promotedLiveGroupIds" to "60",
                            "hiddenChannels_2" to "40",
                            "hiddenCategories_2_LIVE" to "50"
                        ),
                        portableProviderPreferences = portable,
                        combinedM3uProfiles = listOf(
                            CombinedM3uProfileBackup(
                                name = "All Playlists",
                                members = listOf(
                                    CombinedM3uProfileMemberBackup(
                                        provider = providerReference,
                                        priority = 0
                                    )
                                )
                            )
                        ),
                        activeLiveSource = ActiveLiveSourceBackup(
                            type = "provider",
                            provider = providerReference
                        )
                    )
                ).toByteArray()
            )
        )

        val result = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao,
            categoryRepository = categoryRepository,
            virtualGroupDao = virtualGroupDao,
            channelDao = channelDao,
            storedProviders = listOf(targetProvider)
        ).importConfig("content://portable-shifted", preferencesOnlyPlan())

        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.COMPLETE)
        assertThat(imported.unresolvedReferences).isEmpty()
        assertThat(imported.skippedSections).contains("Combined M3U Profiles")
        verify(preferencesRepository).setLastActiveProviderId(7L)
        verify(preferencesRepository).setGuideDefaultCategoryId(150L)
        verify(preferencesRepository).setPromotedLiveGroupIds(setOf(160L))
        verify(preferencesRepository).setHiddenChannelIds(7L, setOf(140L))
        verify(preferencesRepository).setHiddenCategoryIds(7L, ContentType.LIVE, setOf(150L))
        verify(preferencesRepository).setPinnedCategoryIds(7L, ContentType.LIVE, setOf(150L))
        verify(preferencesRepository).setCategorySortMode(
            7L,
            ContentType.LIVE,
            com.streamvault.domain.model.CategorySortMode.TITLE_ASC
        )
        verify(preferencesRepository).setEpgTimeShiftMinutes(7L, 30)
        verify(preferencesRepository).replacePreferredLiveVariants(7L, mapOf("news" to 400L))
        verify(preferencesRepository).replacePreferredVodVariants(7L, mapOf("movie" to 401L))
        verify(preferencesRepository, never()).setHiddenChannelIds(2L, setOf(40L))
    }

    @Test
    fun `duplicate semantic group names remain unresolved and are not guessed`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        val categoryRepository: CategoryRepository = mock()
        val virtualGroupDao: VirtualGroupDao = mock()
        val channelDao: ChannelDao = mock()
        val reference = BackupProviderReference("https://example.com", "user")
        val targetProvider = Provider(
            id = 7L,
            name = "Target",
            type = ProviderType.XTREAM_CODES,
            serverUrl = reference.serverUrl,
            username = reference.username
        )
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(listOf(targetProvider.toEntity()))
        whenever(categoryRepository.getCategories(targetProvider.id)).thenReturn(flowOf(emptyList()))
        whenever(channelDao.getByProviderSync(targetProvider.id)).thenReturn(emptyList())
        whenever(virtualGroupDao.getByType(targetProvider.id, ContentType.LIVE.name)).thenReturn(
            flowOf(
                listOf(
                    VirtualGroupEntity(10L, targetProvider.id, "News", contentType = ContentType.LIVE),
                    VirtualGroupEntity(11L, targetProvider.id, "NEWS", contentType = ContentType.LIVE)
                )
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://portable-duplicate-group"))).thenReturn(
            ByteArrayInputStream(
                Gson().toJson(
                    BackupData(
                        portableProviderPreferences = PortableProviderPreferencesBackup(
                            providers = listOf(reference),
                            promotedLiveGroups = listOf(
                                PortableVirtualGroupReference(reference, "News", ContentType.LIVE)
                            )
                        )
                    )
                ).toByteArray()
            )
        )

        val result = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao,
            categoryRepository = categoryRepository,
            virtualGroupDao = virtualGroupDao,
            channelDao = channelDao,
            storedProviders = listOf(targetProvider)
        ).importConfig("content://portable-duplicate-group", preferencesOnlyPlan())

        val imported = (result as Result.Success).data
        assertThat(imported.outcome).isEqualTo(BackupRestoreOutcome.PARTIAL)
        assertThat(imported.unresolvedReferences)
            .contains("Group News [LIVE] at https://example.com")
        verify(preferencesRepository, never()).setPromotedLiveGroupIds(any())
    }

    @Test
    fun `legacy v8 provider scoped ids remain restorable`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(emptyList())
        whenever(contentResolver.openInputStream(Uri.parse("content://legacy-v8"))).thenReturn(
            ByteArrayInputStream(
                Gson().toJson(
                    BackupData(
                        version = 8,
                        preferences = mapOf(
                            "guideDefaultCategoryId" to "50",
                            "promotedLiveGroupIds" to "60",
                            "hiddenChannels_2" to "40",
                            "hiddenCategories_2_LIVE" to "50"
                        )
                    )
                ).toByteArray()
            )
        )

        val result = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao
        ).importConfig("content://legacy-v8", preferencesOnlyPlan())

        assertThat((result as Result.Success).data.outcome).isEqualTo(BackupRestoreOutcome.COMPLETE)
        verify(preferencesRepository).setGuideDefaultCategoryId(50L)
        verify(preferencesRepository).setPromotedLiveGroupIds(setOf(60L))
        verify(preferencesRepository).setHiddenChannelIds(2L, setOf(40L))
        verify(preferencesRepository).setHiddenCategoryIds(2L, ContentType.LIVE, setOf(50L))
    }

    @Test
    fun `portable preferences map to the target provider for keep and replace conflicts`() = runBlocking {
        BackupConflictStrategy.entries.forEach { conflictStrategy ->
            val context: Context = mock()
            val contentResolver: ContentResolver = mock()
            val preferencesRepository: PreferencesRepository = mock()
            val providerDao: ProviderDao = mock()
            val categoryRepository: CategoryRepository = mock()
            val virtualGroupDao: VirtualGroupDao = mock()
            val channelDao: ChannelDao = mock()
            val credentialCrypto: CredentialCrypto = mock()
            val sourceProvider = Provider(
                id = 2L,
                name = "Source",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "https://example.com",
                username = "user"
            )
            val targetProvider = Provider(
                id = 7L,
                name = "Target",
                type = ProviderType.XTREAM_CODES,
                serverUrl = sourceProvider.serverUrl,
                username = sourceProvider.username
            )
            val reference = BackupProviderReference(sourceProvider.serverUrl, sourceProvider.username)
            val uriString = "content://portable-conflict-${conflictStrategy.name}"
            var insertCalls = 0
            var updateCalls = 0
            whenever(context.contentResolver).thenReturn(contentResolver)
            whenever(providerDao.getAllSync()).thenReturn(listOf(targetProvider.toEntity()))
            doAnswer {
                insertCalls += 1
                targetProvider.id
            }.whenever(providerDao).insert(any())
            doAnswer {
                updateCalls += 1
                Unit
            }.whenever(providerDao).update(any())
            whenever(credentialCrypto.encryptIfNeeded(any())).thenReturn("")
            whenever(categoryRepository.getCategories(targetProvider.id)).thenReturn(flowOf(emptyList()))
            whenever(channelDao.getByProviderSync(targetProvider.id)).thenReturn(emptyList())
            whenever(contentResolver.openInputStream(Uri.parse(uriString))).thenReturn(
                ByteArrayInputStream(
                    Gson().toJson(
                        BackupData(
                            providers = listOf(sourceProvider),
                            portableProviderPreferences = PortableProviderPreferencesBackup(
                                providers = listOf(reference)
                            )
                        )
                    ).toByteArray()
                )
            )

            val result = backupManagerForValidation(
                context = context,
                preferencesRepository = preferencesRepository,
                providerDao = providerDao,
                categoryRepository = categoryRepository,
                virtualGroupDao = virtualGroupDao,
                channelDao = channelDao,
                credentialCrypto = credentialCrypto,
                storedProviders = listOf(targetProvider)
            ).importConfig(
                uriString,
                preferencesOnlyPlan().copy(
                    importProviders = true,
                    conflictStrategy = conflictStrategy
                )
            )

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat((result as Result.Success).data.outcome)
                .isEqualTo(BackupRestoreOutcome.COMPLETE)
            verify(preferencesRepository).setHiddenChannelIds(targetProvider.id, emptySet())
            assertThat(insertCalls).isEqualTo(0)
            assertThat(updateCalls).isEqualTo(
                if (conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) 0 else 1
            )
        }
    }

    @Test
    fun `importConfig checkpoints room completion in the same durable restore`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val providerDao: ProviderDao = mock()
        val checkpointDao: BackupRestoreCheckpointDao = mock()
        val gson = Gson()
        var checkpoint: BackupRestoreCheckpointEntity? = null
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(emptyList())
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-checkpoint"))).thenReturn(
            ByteArrayInputStream(
                gson.toJson(
                    BackupData(
                        providers = listOf(
                            Provider(
                                id = 9L,
                                name = "backup provider",
                                type = ProviderType.M3U,
                                serverUrl = "https://example.com"
                            )
                        )
                    )
                ).toByteArray()
            )
        )
        whenever(checkpointDao.get(any())).thenAnswer { checkpoint }
        doAnswer { invocation ->
            checkpoint = invocation.getArgument(0)
            1L
        }.whenever(checkpointDao).insertIfAbsent(any())
        doAnswer { invocation ->
            checkpoint = checkpoint!!.copy(
                roomComplete = invocation.getArgument(1),
                preferencesComplete = invocation.getArgument(2),
                presetsComplete = invocation.getArgument(3),
                schedulesComplete = invocation.getArgument(4),
                state = invocation.getArgument(5),
                lastError = invocation.getArgument(6),
                updatedAt = invocation.getArgument(7)
            )
            1
        }.whenever(checkpointDao).update(any(), any(), any(), any(), any(), any(), anyOrNull(), any())

        val result = backupManagerForValidation(
            context = context,
            providerDao = providerDao,
            checkpointDao = checkpointDao
        ).importConfig(
            uriString = "content://backup-checkpoint",
            plan = BackupImportPlan(
                importPreferences = false,
                importProviders = false,
                importSavedLibrary = true,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(checkpoint!!.roomComplete).isTrue()
        assertThat(checkpoint!!.state).isEqualTo("COMPLETE")
    }

    @Test
    fun `importConfig replays a completed restore when the same backup is explicitly imported again`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val providerDao: ProviderDao = mock()
        val checkpointDao: BackupRestoreCheckpointDao = mock()
        val credentialCrypto: CredentialCrypto = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(emptyList())
        whenever(providerDao.insert(any())).thenReturn(42L)
        whenever(credentialCrypto.encryptIfNeeded(any())).thenReturn("")
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-complete-retry"))).thenReturn(
            ByteArrayInputStream(
                Gson().toJson(
                    BackupData(
                        providers = listOf(
                            Provider(
                                id = 3L,
                                name = "provider",
                                type = ProviderType.M3U,
                                serverUrl = "https://example.com"
                            )
                        )
                    )
                ).toByteArray()
            )
        )
        whenever(checkpointDao.get(any())).thenReturn(
            BackupRestoreCheckpointEntity(
                restoreKey = "existing",
                roomComplete = true,
                state = "COMPLETE",
                createdAt = 1L,
                updatedAt = 1L
            )
        )

        val result = backupManagerForValidation(
            context = context,
            providerDao = providerDao,
            credentialCrypto = credentialCrypto,
            checkpointDao = checkpointDao
        ).importConfig("content://backup-complete-retry")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data.outcome).isEqualTo(BackupRestoreOutcome.COMPLETE)
        verify(providerDao, org.mockito.kotlin.atLeastOnce()).getAllSync()
        verify(providerDao).insert(any())
        verify(checkpointDao).update(
            any(),
            eq(false),
            eq(false),
            eq(false),
            eq(false),
            eq("RUNNING"),
            anyOrNull(),
            any()
        )
        Unit
    }

    @Test
    fun `importConfig restores audio video sync enabled preference`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)

        val providerDao: ProviderDao = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val gson = Gson()
        val backupData = BackupData(
            preferences = mapOf(
                "playerAudioVideoSyncEnabled" to "true",
                "playerAudioVideoOffsetMs" to "150"
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-av-sync-preferences"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(emptyList())

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = preferencesRepository,
            credentialCrypto = mock<CredentialCrypto>(),
            providerDao = providerDao,
            favoriteDao = mock<FavoriteDao>(),
            virtualGroupDao = mock<VirtualGroupDao>(),
            playbackHistoryDao = mock<PlaybackHistoryDao>(),
            movieDao = mock<MovieDao>(),
            episodeDao = mock<EpisodeDao>(),
            categoryRepository = mock<CategoryRepository>(),
            recordingScheduleDao = mock<RecordingScheduleDao>(),
            recordingManager = mock<RecordingManager>(),
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = gson,
            channelDao = mock(),
            seriesDao = mock()
        )

        val result = manager.importConfig(
            uriString = "content://backup-av-sync-preferences",
            plan = BackupImportPlan(
                importPreferences = true,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.KEEP_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(preferencesRepository).setPlayerAudioVideoSyncEnabled(true)
        verify(preferencesRepository).setPlayerAudioVideoOffsetMs(150)
    }

    @Test
    fun `importConfig restores separate audio and video decoder preferences`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val providerDao: ProviderDao = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val gson = Gson()
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(emptyList())
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-decoder-preferences"))).thenReturn(
            ByteArrayInputStream(
                gson.toJson(
                    BackupData(
                        preferences = mapOf(
                            "playerAudioDecoderMode" to "SOFTWARE",
                            "playerVideoDecoderMode" to "HARDWARE",
                            "playerDecoderMode" to "COMPATIBILITY"
                        )
                    )
                ).toByteArray()
            )
        )
        val manager = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao
        )

        val result = manager.importConfig(
            uriString = "content://backup-decoder-preferences",
            plan = BackupImportPlan(
                importPreferences = true,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.KEEP_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(preferencesRepository).setPlayerAudioDecoderMode(DecoderMode.SOFTWARE)
        verify(preferencesRepository).setPlayerVideoDecoderMode(DecoderMode.HARDWARE)
    }

    @Test
    fun `importConfig restores legacy decoder preference to both axes`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val providerDao: ProviderDao = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val gson = Gson()
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(providerDao.getAllSync()).thenReturn(emptyList())
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-legacy-decoder-preference"))).thenReturn(
            ByteArrayInputStream(
                gson.toJson(
                    BackupData(
                        preferences = mapOf("playerDecoderMode" to "COMPATIBILITY")
                    )
                ).toByteArray()
            )
        )
        val manager = backupManagerForValidation(
            context = context,
            preferencesRepository = preferencesRepository,
            providerDao = providerDao
        )

        val result = manager.importConfig(
            uriString = "content://backup-legacy-decoder-preference",
            plan = BackupImportPlan(
                importPreferences = true,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.KEEP_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(preferencesRepository).setPlayerAudioDecoderMode(DecoderMode.COMPATIBILITY)
        verify(preferencesRepository).setPlayerVideoDecoderMode(DecoderMode.COMPATIBILITY)
    }

    @Test
    fun `importConfig restores top navigation destinations preference`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)

        val providerDao: ProviderDao = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val gson = Gson()
        val backupData = BackupData(
            preferences = mapOf(
                "appTopLevelDestinations" to "home,search,settings"
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-top-navigation"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(emptyList())

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = preferencesRepository,
            credentialCrypto = mock<CredentialCrypto>(),
            providerDao = providerDao,
            favoriteDao = mock<FavoriteDao>(),
            virtualGroupDao = mock<VirtualGroupDao>(),
            playbackHistoryDao = mock<PlaybackHistoryDao>(),
            movieDao = mock<MovieDao>(),
            episodeDao = mock<EpisodeDao>(),
            categoryRepository = mock<CategoryRepository>(),
            recordingScheduleDao = mock<RecordingScheduleDao>(),
            recordingManager = mock<RecordingManager>(),
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = gson,
            channelDao = mock(),
            seriesDao = mock()
        )

        val result = manager.importConfig(
            uriString = "content://backup-top-navigation",
            plan = BackupImportPlan(
                importPreferences = true,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.KEEP_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(preferencesRepository).setAppTopLevelDestinations(
            listOf(
                AppTopLevelDestination.HOME,
                AppTopLevelDestination.SEARCH,
                AppTopLevelDestination.SETTINGS
            )
        )
    }

    @Test
    fun `importConfig restores home dashboard shelves preference`() = runBlocking {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        val preferencesRepository: PreferencesRepository = mock()
        val providerDao: ProviderDao = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        val gson = Gson()
        val backupData = BackupData(
            preferences = mapOf(
                "appHomeDashboardShelves" to "favorite_channels,recommended_movies,top_rated_movies"
            )
        )
        whenever(contentResolver.openInputStream(Uri.parse("content://backup-home-dashboard"))).thenReturn(
            ByteArrayInputStream(gson.toJson(backupData).toByteArray())
        )
        whenever(providerDao.getAllSync()).thenReturn(emptyList())

        val manager = BackupManagerImpl(
            context = context,
            preferencesRepository = preferencesRepository,
            credentialCrypto = mock<CredentialCrypto>(),
            providerDao = providerDao,
            favoriteDao = mock<FavoriteDao>(),
            virtualGroupDao = mock<VirtualGroupDao>(),
            playbackHistoryDao = mock<PlaybackHistoryDao>(),
            movieDao = mock<MovieDao>(),
            episodeDao = mock<EpisodeDao>(),
            categoryRepository = mock<CategoryRepository>(),
            recordingScheduleDao = mock<RecordingScheduleDao>(),
            recordingManager = mock<RecordingManager>(),
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = gson,
            channelDao = mock(),
            seriesDao = mock()
        )

        val result = manager.importConfig(
            uriString = "content://backup-home-dashboard",
            plan = BackupImportPlan(
                importPreferences = true,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
                conflictStrategy = BackupConflictStrategy.KEEP_EXISTING
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(preferencesRepository).setAppHomeDashboardShelves(
            listOf(
                AppHomeDashboardShelf.FAVORITE_CHANNELS,
                AppHomeDashboardShelf.RECOMMENDED_MOVIES,
                AppHomeDashboardShelf.TOP_RATED_MOVIES
            )
        )
    }

    @Test
    fun `toScheduledRecordingBackup stores requested window and padding separately`() {
        val provider = Provider(
            id = 7L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user",
            stalkerMacAddress = ""
        )
        val item = RecordingItem(
            id = "scheduled-1",
            scheduleId = 21L,
            providerId = 7L,
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            programTitle = "World News",
            recurrence = RecordingRecurrence.DAILY,
            status = RecordingStatus.SCHEDULED
        )
        val schedule = RecordingScheduleEntity(
            id = 21L,
            providerId = 7L,
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            programTitle = "World News",
            requestedStartMs = 1_700_000_120_000L,
            requestedEndMs = 1_700_000_480_000L,
            recurrence = RecordingRecurrence.DAILY
        )

        val backup = item.toScheduledRecordingBackup(provider, schedule)

        assertThat(backup.scheduledStartMs).isEqualTo(item.scheduledStartMs)
        assertThat(backup.scheduledEndMs).isEqualTo(item.scheduledEndMs)
        assertThat(backup.requestedStartMs).isEqualTo(schedule.requestedStartMs)
        assertThat(backup.requestedEndMs).isEqualTo(schedule.requestedEndMs)
        assertThat(backup.paddingBeforeMs).isEqualTo(120_000L)
        assertThat(backup.paddingAfterMs).isEqualTo(60_000L)
        assertThat(backup.recurringRuleId).isEqualTo(schedule.recurringRuleId)
    }

    @Test
    fun `toRecordingRequest preserves legacy effective backup windows`() {
        val backup = ScheduledRecordingBackup(
            providerServerUrl = "https://example.com",
            providerUsername = "user",
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            programTitle = "World News",
            recurrence = RecordingRecurrence.NONE
        )

        val request = backup.toRecordingRequest(providerId = 7L)

        assertThat(request.scheduledStartMs).isEqualTo(backup.scheduledStartMs)
        assertThat(request.scheduledEndMs).isEqualTo(backup.scheduledEndMs)
        assertThat(request.paddingBeforeMs).isEqualTo(0L)
        assertThat(request.paddingAfterMs).isEqualTo(0L)
    }

    @Test
    fun `toRecordingRequest restores requested window and explicit padding from new backups`() {
        val backup = ScheduledRecordingBackup(
            providerServerUrl = "https://example.com",
            providerUsername = "user",
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            requestedStartMs = 1_700_000_120_000L,
            requestedEndMs = 1_700_000_480_000L,
            paddingBeforeMs = 120_000L,
            paddingAfterMs = 60_000L,
            programTitle = "World News",
            recurrence = RecordingRecurrence.WEEKLY,
            recurringRuleId = "rule-1"
        )

        val request = backup.toRecordingRequest(providerId = 7L)

        assertThat(request.scheduledStartMs).isEqualTo(backup.requestedStartMs)
        assertThat(request.scheduledEndMs).isEqualTo(backup.requestedEndMs)
        assertThat(request.paddingBeforeMs).isEqualTo(backup.paddingBeforeMs)
        assertThat(request.paddingAfterMs).isEqualTo(backup.paddingAfterMs)
        assertThat(request.recurrence).isEqualTo(RecordingRecurrence.WEEKLY)
        assertThat(request.recurringRuleId).isEqualTo("rule-1")
    }

    @Test
    fun `normalizedRecurringBackups collapses duplicate occurrences for the same recurring rule`() {
        val recurringFirst = ScheduledRecordingBackup(
            providerServerUrl = "https://example.com",
            providerUsername = "user",
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            requestedStartMs = 1_700_000_120_000L,
            requestedEndMs = 1_700_000_480_000L,
            programTitle = "World News",
            recurrence = RecordingRecurrence.DAILY,
            recurringRuleId = "rule-1"
        )
        val recurringSecond = recurringFirst.copy(
            scheduledStartMs = 1_700_086_400_000L,
            scheduledEndMs = 1_700_086_940_000L,
            requestedStartMs = 1_700_086_520_000L,
            requestedEndMs = 1_700_086_880_000L
        )
        val oneShot = ScheduledRecordingBackup(
            providerServerUrl = "https://example.com",
            providerUsername = "user",
            channelId = 101L,
            channelName = "BBC Two",
            streamUrl = "https://example.com/other.ts",
            scheduledStartMs = 1_700_010_000_000L,
            scheduledEndMs = 1_700_010_540_000L,
            programTitle = "Documentary",
            recurrence = RecordingRecurrence.NONE
        )

        val normalized = listOf(recurringSecond, oneShot, recurringFirst).normalizedRecurringBackups()

        assertThat(normalized).hasSize(2)
        assertThat(normalized).contains(recurringFirst)
        assertThat(normalized).contains(oneShot)
    }

    @Test
    fun `normalizedRecurringBackups keeps recurring entries without stable identity`() {
        val first = ScheduledRecordingBackup(
            providerServerUrl = "https://example.com",
            providerUsername = "user",
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            programTitle = "World News",
            recurrence = RecordingRecurrence.DAILY
        )
        val second = first.copy(
            scheduledStartMs = 1_700_086_400_000L,
            scheduledEndMs = 1_700_086_940_000L
        )

        val normalized = listOf(first, second).normalizedRecurringBackups()

        assertThat(normalized).containsExactly(first, second)
    }

    @Test
    fun `importScheduledRecordingBackups reports skipped and failed outcomes`() {
        val recordingManager: RecordingManager = mock()
        val provider = Provider(
            id = 7L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user"
        )
        val existingSchedule = RecordingItem(
            id = "existing-1",
            providerId = 7L,
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            status = RecordingStatus.SCHEDULED
        )
        val keepExisting = ScheduledRecordingBackup(
            providerServerUrl = provider.serverUrl,
            providerUsername = provider.username,
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = existingSchedule.scheduledStartMs,
            scheduledEndMs = existingSchedule.scheduledEndMs,
            programTitle = "World News"
        )
        val validationFailure = keepExisting.copy(
            channelId = 101L,
            channelName = "BBC Two",
            streamUrl = "https://example.com/other.ts",
            scheduledStartMs = 1_700_001_000_000L,
            scheduledEndMs = 1_700_001_540_000L
        )

        runBlocking {
            whenever(recordingManager.scheduleRecording(any()))
                .thenReturn(Result.error("Recording conflicts with an existing active recording for World News."))
        }

        val summary = kotlinx.coroutines.runBlocking {
            importScheduledRecordingBackups(
                recordings = listOf(keepExisting, validationFailure),
                storedProviders = listOf(provider),
                existingSchedules = mutableListOf(existingSchedule),
                conflictStrategy = BackupConflictStrategy.KEEP_EXISTING,
                recordingManager = recordingManager,
                nowMs = 1_699_999_000_000L
            )
        }

        assertThat(summary.importedCount).isEqualTo(0)
        assertThat(summary.skippedCount).isEqualTo(1)
        assertThat(summary.failedCount).isEqualTo(1)
        assertThat(summary.outcomes.map { it.disposition }).containsExactly(
            RecordingScheduleImportDisposition.SKIPPED_EXISTING,
            RecordingScheduleImportDisposition.FAILED
        )
        assertThat(summary.outcomes.last().reason).contains("conflicts")
        runBlocking {
            verify(recordingManager).scheduleRecording(any())
        }
    }

    @Test
    fun `importScheduledRecordingBackups reports replaced existing schedules`() {
        val recordingManager: RecordingManager = mock()
        val provider = Provider(
            id = 7L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user"
        )
        val existingSchedule = RecordingItem(
            id = "existing-1",
            providerId = 7L,
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            status = RecordingStatus.SCHEDULED
        )
        val imported = ScheduledRecordingBackup(
            providerServerUrl = provider.serverUrl,
            providerUsername = provider.username,
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = existingSchedule.scheduledStartMs,
            scheduledEndMs = existingSchedule.scheduledEndMs,
            programTitle = "World News"
        )
        val importedItem = existingSchedule.copy(id = "imported-1")

        runBlocking {
            whenever(recordingManager.cancelRecording(existingSchedule.id)).thenReturn(Result.success(Unit))
            whenever(recordingManager.scheduleRecording(any())).thenReturn(Result.success(importedItem))
        }

        val summary = kotlinx.coroutines.runBlocking {
            importScheduledRecordingBackups(
                recordings = listOf(imported),
                storedProviders = listOf(provider),
                existingSchedules = mutableListOf(existingSchedule),
                conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING,
                recordingManager = recordingManager,
                nowMs = 1_699_999_000_000L
            )
        }

        assertThat(summary.importedCount).isEqualTo(1)
        assertThat(summary.failedCount).isEqualTo(0)
        assertThat(summary.outcomes.single().disposition).isEqualTo(RecordingScheduleImportDisposition.REPLACED_EXISTING)
        runBlocking {
            verify(recordingManager).cancelRecording(existingSchedule.id)
            verify(recordingManager).scheduleRecording(any())
        }
    }

    @Test
    fun `importScheduledRecordingBackups keeps old schedule when replacement cancellation fails`() = runBlocking {
        val recordingManager: RecordingManager = mock()
        val provider = Provider(
            id = 7L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            username = "user"
        )
        val existingSchedule = RecordingItem(
            id = "existing-1",
            providerId = 7L,
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = 1_700_000_000_000L,
            scheduledEndMs = 1_700_000_540_000L,
            status = RecordingStatus.SCHEDULED
        )
        val imported = ScheduledRecordingBackup(
            providerServerUrl = provider.serverUrl,
            providerUsername = provider.username,
            channelId = 100L,
            channelName = "BBC One",
            streamUrl = "https://example.com/live.ts",
            scheduledStartMs = existingSchedule.scheduledStartMs,
            scheduledEndMs = existingSchedule.scheduledEndMs
        )
        val replacement = existingSchedule.copy(id = "replacement-1")
        whenever(recordingManager.scheduleRecording(any())).thenReturn(Result.success(replacement))
        whenever(recordingManager.cancelRecording(existingSchedule.id)).thenReturn(
            Result.error("old alarm cancellation failed")
        )
        whenever(recordingManager.cancelRecording(replacement.id)).thenReturn(Result.success(Unit))

        val schedules = mutableListOf(existingSchedule)
        val summary = importScheduledRecordingBackups(
            recordings = listOf(imported),
            storedProviders = listOf(provider),
            existingSchedules = schedules,
            conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING,
            recordingManager = recordingManager,
            nowMs = 1_699_999_000_000L
        )

        assertThat(summary.importedCount).isEqualTo(0)
        assertThat(summary.failedCount).isEqualTo(1)
        assertThat(summary.outcomes.single().reason).contains("Could not replace existing schedule")
        assertThat(schedules).containsExactly(existingSchedule)
        verify(recordingManager).cancelRecording(replacement.id)
        Unit
    }

    private class RecordingTransactionRunner : DatabaseTransactionRunner {
        var calls: Int = 0
        private var depth: Int = 0

        val isInTransaction: Boolean
            get() = depth > 0

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            calls += 1
            depth += 1
            return try {
                block()
            } finally {
                depth -= 1
            }
        }
    }

    private suspend fun inspectAdmissionFailure(name: String, json: String): BackupAdmissionException {
        val context: Context = mock()
        val contentResolver: ContentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(contentResolver.openInputStream(Uri.parse("content://$name"))).thenReturn(
            ByteArrayInputStream(json.toByteArray())
        )
        val result = backupManagerForValidation(context).inspectBackup("content://$name")
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(BackupAdmissionException::class.java)
        return error.exception as BackupAdmissionException
    }

    private class PrefixThenPaddingInputStream(
        private val prefix: ByteArray,
        private val totalBytes: Int
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= totalBytes) return -1
            val value = if (position < prefix.size) prefix[position].toInt() and 0xff else ' '.code
            position += 1
            return value
        }
    }

    private class CancellingInputStream(
        private val prefix: ByteArray
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= prefix.size) throw CancellationException("cancelled during read")
            return prefix[position++].toInt() and 0xff
        }
    }

    private fun Provider.backupIdentityForTest(): Triple<String, String, String> =
        Triple(serverUrl, username, stalkerMacAddress)

    private fun backupManagerForValidation(
        context: Context,
        preferencesRepository: PreferencesRepository = mock(),
        providerDao: ProviderDao = mock(),
        favoriteDao: FavoriteDao = mock(),
        checkpointDao: BackupRestoreCheckpointDao? = null,
        restoreLedgerDao: BackupRestoreLedgerDao? = null,
        channelDao: ChannelDao = mock(),
        movieDao: MovieDao = mock(),
        categoryRepository: CategoryRepository = mock(),
        virtualGroupDao: VirtualGroupDao = mock(),
        credentialCrypto: CredentialCrypto = mock(),
        epgSourceDao: EpgSourceDao? = null,
        recordingManager: RecordingManager = mock(),
        storedProviders: List<Provider> = emptyList(),
    ): BackupManagerImpl {
        whenever(preferencesRepository.defaultCategoryId).thenReturn(flowOf(null))
        whenever(recordingManager.observeRecordingItems()).thenReturn(flowOf(emptyList()))
        whenever(recordingManager.observeStorageState()).thenReturn(flowOf(RecordingStorageState()))
        return BackupManagerImpl(
            context = context,
            preferencesRepository = preferencesRepository,
            credentialCrypto = credentialCrypto,
            providerDao = providerDao,
            favoriteDao = favoriteDao,
            virtualGroupDao = virtualGroupDao,
            playbackHistoryDao = mock<PlaybackHistoryDao>(),
            movieDao = movieDao,
            episodeDao = mock<EpisodeDao>(),
            categoryRepository = categoryRepository,
            recordingScheduleDao = mock<RecordingScheduleDao>(),
            recordingManager = recordingManager,
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            gson = Gson(),
            providerSnapshotRepository = object : ProviderSnapshotRepository {
            override suspend fun getSnapshot(providerId: Long) =
                storedProviders.firstOrNull { it.id == providerId }?.toProviderSnapshot()

            override suspend fun compareAndSetStalkerLearning(
                providerId: Long,
                learning: com.streamvault.domain.model.StalkerPortalLearning
            ): Boolean = false

            override suspend fun updateCatalogLayout(
                providerId: Long,
                layout: com.streamvault.domain.model.CatalogLayout,
                detectionVersion: Int
            ) = Unit
            },
            providerSnapshotDao = acceptingSnapshotDao(),
            providerConfigurationCodec = ProviderConfigurationCodec(Gson(), credentialCrypto),
            backupRestoreCheckpointDao = checkpointDao,
            backupRestoreLedgerDao = restoreLedgerDao,
            channelDao = channelDao,
            seriesDao = mock(),
            epgSourceDao = epgSourceDao
        )
    }

    private fun preferencesOnlyPlan() = BackupImportPlan(
        importPreferences = true,
        importProviders = false,
        importSavedLibrary = false,
        importPlaybackHistory = false,
        importMultiViewPresets = false,
        importRecordingSchedules = false
    )

    private fun snapshotRepositoryFor(vararg providers: Provider) = object : ProviderSnapshotRepository {
        override suspend fun getSnapshot(providerId: Long) =
            checkNotNull(providers.firstOrNull { it.id == providerId }) {
                "No typed provider fixture for id=$providerId; available=${providers.map { it.id }}"
            }.toProviderSnapshot()

        override suspend fun compareAndSetStalkerLearning(
            providerId: Long,
            learning: com.streamvault.domain.model.StalkerPortalLearning
        ): Boolean = false

        override suspend fun updateCatalogLayout(
            providerId: Long,
            layout: com.streamvault.domain.model.CatalogLayout,
            detectionVersion: Int
        ) = Unit
    }

    private fun acceptingSnapshotDao(): ProviderSnapshotDao = mock<ProviderSnapshotDao>().also { dao ->
        runBlocking {
            whenever(dao.getConfig(any())).thenReturn(null)
            whenever(dao.commitConfiguration(any())).thenReturn(true)
        }
    }
}
