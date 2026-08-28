package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.ChannelEpgMappingDao
import com.streamvault.data.local.dao.EpgChannelDao
import com.streamvault.data.local.dao.EpgProgrammeDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.dao.ProviderEpgSourceDao
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ChannelEpgMappingEntity
import com.streamvault.data.local.entity.ChannelGuideLookupEntity
import com.streamvault.data.local.entity.EpgChannelEntity
import com.streamvault.data.local.entity.EpgProgrammeEntity
import com.streamvault.data.local.entity.ProgramBrowseEntity
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.ProviderEpgSourceEntity
import com.streamvault.domain.model.EpgMatchType
import com.streamvault.domain.model.EpgSourceType
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class EpgResolutionEngineTest {

    private val channelDao: ChannelDao = mock()
    private val channelEpgMappingDao: ChannelEpgMappingDao = mock()
    private val providerEpgSourceDao: ProviderEpgSourceDao = mock()
    private val epgChannelDao: EpgChannelDao = mock()
    private val epgProgrammeDao: EpgProgrammeDao = mock()
    private val programDao: ProgramDao = mock()
    private val providerSnapshotDao: ProviderSnapshotDao = mock()

    private lateinit var engine: EpgResolutionEngine

    companion object {
        private const val PROVIDER_ID = 1L
        private const val SOURCE_1 = 10L
        private const val SOURCE_2 = 20L
    }

    @Before
    fun setup() {
        runTest {
            whenever(programDao.getChannelIdsWithPrograms(any(), any())).thenReturn(emptyList())
            whenever(providerSnapshotDao.getConfig(any())).thenReturn(makeProviderConfig())
        }
        engine = EpgResolutionEngine(
            channelDao = channelDao,
            channelEpgMappingDao = channelEpgMappingDao,
            providerEpgSourceDao = providerEpgSourceDao,
            epgChannelDao = epgChannelDao,
            epgProgrammeDao = epgProgrammeDao,
            programDao = programDao,
            providerSnapshotDao = providerSnapshotDao
        )
    }

    // ── resolveForProvider ─────────────────────────────────────────

    @Test
    fun `resolveForProvider_exactIdMatch_resolvesAsExternalExactId`() = runTest {
        val channel = makeChannel(id = 1, name = "BBC One", epgChannelId = "bbc1.uk")
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(listOf(channel))
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(
            listOf(ProviderEpgSourceEntity(id = 1, providerId = PROVIDER_ID, epgSourceId = SOURCE_1, priority = 0))
        )
        whenever(epgChannelDao.getBySources(listOf(SOURCE_1))).thenReturn(
            listOf(EpgChannelEntity(id = 1, epgSourceId = SOURCE_1, xmltvChannelId = "bbc1.uk", displayName = "BBC One", normalizedName = "bbcone"))
        )
        whenever(channelEpgMappingDao.getForProvider(PROVIDER_ID)).thenReturn(emptyList())

        val summary = engine.resolveForProvider(PROVIDER_ID)

        assertThat(summary.exactIdMatches).isEqualTo(1)
        assertThat(summary.totalChannels).isEqualTo(1)

        val captor = argumentCaptor<List<ChannelEpgMappingEntity>>()
        verify(channelEpgMappingDao).replaceForProvider(any(), captor.capture())
        val mapping = captor.firstValue.single()
        assertThat(mapping.sourceType).isEqualTo(EpgSourceType.EXTERNAL.name)
        assertThat(mapping.matchType).isEqualTo(EpgMatchType.EXACT_ID.name)
        assertThat(mapping.xmltvChannelId).isEqualTo("bbc1.uk")
        assertThat(mapping.confidence).isEqualTo(1.0f)
    }

    @Test
    fun `resolveForProvider_accentedExactId_remainsAnExactMatch`() = runTest {
        val channel = makeChannel(id = 1, name = "Chaîne Été", epgChannelId = "chaîne-é")
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(listOf(channel))
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(
            listOf(ProviderEpgSourceEntity(id = 1, providerId = PROVIDER_ID, epgSourceId = SOURCE_1, priority = 0))
        )
        whenever(epgChannelDao.getBySources(listOf(SOURCE_1))).thenReturn(
            listOf(
                EpgChannelEntity(
                    id = 1,
                    epgSourceId = SOURCE_1,
                    xmltvChannelId = "chaîne-é",
                    displayName = "Chaîne Été",
                    normalizedName = EpgNameNormalizer.normalize("Chaîne Été")
                )
            )
        )
        whenever(channelEpgMappingDao.getForProvider(PROVIDER_ID)).thenReturn(emptyList())

        val summary = engine.resolveForProvider(PROVIDER_ID)

        assertThat(summary.exactIdMatches).isEqualTo(1)
        val captor = argumentCaptor<List<ChannelEpgMappingEntity>>()
        verify(channelEpgMappingDao).replaceForProvider(any(), captor.capture())
        assertThat(captor.firstValue.single().xmltvChannelId).isEqualTo("chaîne-é")
        assertThat(captor.firstValue.single().matchType).isEqualTo(EpgMatchType.EXACT_ID.name)
    }

    @Test
    fun `resolveForProvider_normalizedNameFallback_resolvesAsNormalizedName`() = runTest {
        // Channel has no matching epgChannelId but name matches after normalization
        val channel = makeChannel(id = 1, name = "CNN International", epgChannelId = null)
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(listOf(channel))
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(
            listOf(ProviderEpgSourceEntity(id = 1, providerId = PROVIDER_ID, epgSourceId = SOURCE_1, priority = 0))
        )
        whenever(epgChannelDao.getBySources(listOf(SOURCE_1))).thenReturn(
            listOf(EpgChannelEntity(id = 1, epgSourceId = SOURCE_1, xmltvChannelId = "cnn.intl", displayName = "CNN International", normalizedName = EpgNameNormalizer.normalize("CNN International")))
        )
        whenever(channelEpgMappingDao.getForProvider(PROVIDER_ID)).thenReturn(emptyList())

        val summary = engine.resolveForProvider(PROVIDER_ID)

        assertThat(summary.normalizedNameMatches).isEqualTo(1)

        val captor = argumentCaptor<List<ChannelEpgMappingEntity>>()
        verify(channelEpgMappingDao).replaceForProvider(any(), captor.capture())
        val mapping = captor.firstValue.single()
        assertThat(mapping.matchType).isEqualTo(EpgMatchType.NORMALIZED_NAME.name)
        assertThat(mapping.xmltvChannelId).isEqualTo("cnn.intl")
        assertThat(mapping.confidence).isEqualTo(0.7f)
    }

    @Test
    fun `resolveForProvider_providerNativeFallback_resolvesAsProvider`() = runTest {
        // Channel has epgChannelId but no external source matches — falls back to provider native
        val channel = makeChannel(id = 1, name = "Local Channel", epgChannelId = "local.ch1")
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(listOf(channel))
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(emptyList())
        whenever(channelEpgMappingDao.getForProvider(PROVIDER_ID)).thenReturn(emptyList())
        whenever(programDao.getChannelIdsWithPrograms(PROVIDER_ID, listOf("local.ch1"))).thenReturn(listOf("local.ch1"))

        val summary = engine.resolveForProvider(PROVIDER_ID)

        assertThat(summary.providerNativeMatches).isEqualTo(1)

        val captor = argumentCaptor<List<ChannelEpgMappingEntity>>()
        verify(channelEpgMappingDao).replaceForProvider(any(), captor.capture())
        val mapping = captor.firstValue.single()
        assertThat(mapping.sourceType).isEqualTo(EpgSourceType.PROVIDER.name)
        assertThat(mapping.matchType).isEqualTo(EpgMatchType.PROVIDER_NATIVE.name)
    }

    @Test
    fun `resolveForProvider_unresolvedChannel_noEpg`() = runTest {
        val channel = makeChannel(id = 1, name = "Mystery Channel", epgChannelId = null)
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(listOf(channel))
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(emptyList())
        whenever(channelEpgMappingDao.getForProvider(PROVIDER_ID)).thenReturn(emptyList())

        val summary = engine.resolveForProvider(PROVIDER_ID)

        assertThat(summary.unresolvedChannels).isEqualTo(1)

        val captor = argumentCaptor<List<ChannelEpgMappingEntity>>()
        verify(channelEpgMappingDao).replaceForProvider(any(), captor.capture())
        val mapping = captor.firstValue.single()
        assertThat(mapping.sourceType).isEqualTo(EpgSourceType.NONE.name)
        assertThat(mapping.matchType).isNull()
        assertThat(mapping.failedAttempts).isEqualTo(1)
    }

    @Test
    fun `resolveForProvider_providerNativeCountsAsLowConfidenceRematchCandidate`() = runTest {
        val channel = makeChannel(id = 1, name = "Local Channel", epgChannelId = "local.ch1")
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(listOf(channel))
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(emptyList())
        whenever(channelEpgMappingDao.getForProvider(PROVIDER_ID)).thenReturn(emptyList())
        whenever(programDao.getChannelIdsWithPrograms(PROVIDER_ID, listOf("local.ch1"))).thenReturn(listOf("local.ch1"))

        val summary = engine.resolveForProvider(PROVIDER_ID)

        assertThat(summary.providerNativeMatches).isEqualTo(1)
        assertThat(summary.lowConfidenceChannels).isEqualTo(1)
        assertThat(summary.rematchCandidateChannels).isEqualTo(1)
    }

    @Test
    fun `resolveForProvider_higherPrioritySourceWins`() = runTest {
        // Both sources match the same channel by exact ID — source with lower priority index wins
        val channel = makeChannel(id = 1, name = "BBC One", epgChannelId = "bbc1")
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(listOf(channel))
        // assignment order = priority order (source_1 first)
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(listOf(
            ProviderEpgSourceEntity(id = 1, providerId = PROVIDER_ID, epgSourceId = SOURCE_1, priority = 0),
            ProviderEpgSourceEntity(id = 2, providerId = PROVIDER_ID, epgSourceId = SOURCE_2, priority = 1)
        ))
        whenever(epgChannelDao.getBySources(listOf(SOURCE_1, SOURCE_2))).thenReturn(listOf(
            EpgChannelEntity(id = 1, epgSourceId = SOURCE_1, xmltvChannelId = "bbc1", displayName = "BBC One", normalizedName = "bbcone"),
            EpgChannelEntity(id = 2, epgSourceId = SOURCE_2, xmltvChannelId = "bbc1", displayName = "BBC 1", normalizedName = "bbc1")
        ))
        whenever(channelEpgMappingDao.getForProvider(PROVIDER_ID)).thenReturn(emptyList())

        val summary = engine.resolveForProvider(PROVIDER_ID)

        assertThat(summary.exactIdMatches).isEqualTo(1)

        val captor = argumentCaptor<List<ChannelEpgMappingEntity>>()
        verify(channelEpgMappingDao).replaceForProvider(any(), captor.capture())
        val mapping = captor.firstValue.single()
        // SOURCE_1 should win because it appears first in the priority-ordered list
        assertThat(mapping.epgSourceId).isEqualTo(SOURCE_1)
    }

    @Test
    fun `resolveForProvider_manualOverridePreserved`() = runTest {
        val channel = makeChannel(id = 1, name = "BBC One", epgChannelId = "bbc1")
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(listOf(channel))
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(
            listOf(ProviderEpgSourceEntity(id = 1, providerId = PROVIDER_ID, epgSourceId = SOURCE_1, priority = 0))
        )
        whenever(epgChannelDao.getBySources(listOf(SOURCE_1))).thenReturn(
            listOf(EpgChannelEntity(id = 1, epgSourceId = SOURCE_1, xmltvChannelId = "bbc1", displayName = "BBC One", normalizedName = "bbcone"))
        )
        // Existing manual override
        val manualMapping = ChannelEpgMappingEntity(
            id = 99, providerChannelId = 1, providerId = PROVIDER_ID,
            sourceType = EpgSourceType.EXTERNAL.name, epgSourceId = SOURCE_2,
            xmltvChannelId = "bbc1.manual", matchType = EpgMatchType.MANUAL.name,
            confidence = 1.0f, isManualOverride = true, updatedAt = 1000L
        )
        whenever(channelEpgMappingDao.getForProvider(PROVIDER_ID)).thenReturn(listOf(manualMapping))

        engine.resolveForProvider(PROVIDER_ID)

        val captor = argumentCaptor<List<ChannelEpgMappingEntity>>()
        verify(channelEpgMappingDao).replaceForProvider(any(), captor.capture())
        val mapping = captor.firstValue.single()
        // Manual override should be preserved with updated timestamp
        assertThat(mapping.isManualOverride).isTrue()
        assertThat(mapping.xmltvChannelId).isEqualTo("bbc1.manual")
        assertThat(mapping.epgSourceId).isEqualTo(SOURCE_2)
    }

    @Test
    fun `resolveForProvider_emptyChannelList_clearsAndReturnsEmpty`() = runTest {
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(emptyList())

        val summary = engine.resolveForProvider(PROVIDER_ID)

        assertThat(summary.totalChannels).isEqualTo(0)
        verify(channelEpgMappingDao).deleteByProvider(PROVIDER_ID)
    }

    @Test
    fun `resolveForProvider_exactIdBeatsNormalizedName`() = runTest {
        // Channel has both exact ID and name match — exact ID should win
        val channel = makeChannel(id = 1, name = "BBC One HD", epgChannelId = "bbc.one.hd")
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(listOf(channel))
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(
            listOf(ProviderEpgSourceEntity(id = 1, providerId = PROVIDER_ID, epgSourceId = SOURCE_1, priority = 0))
        )
        whenever(epgChannelDao.getBySources(listOf(SOURCE_1))).thenReturn(listOf(
            // Has exact ID match
            EpgChannelEntity(id = 1, epgSourceId = SOURCE_1, xmltvChannelId = "bbc.one.hd", displayName = "BBC 1 HD", normalizedName = "bbc1hd"),
            // Also has a name match (normalizes to same thing)
            EpgChannelEntity(id = 2, epgSourceId = SOURCE_1, xmltvChannelId = "bbc.one.sd", displayName = "BBC One HD", normalizedName = EpgNameNormalizer.normalize("BBC One HD"))
        ))
        whenever(channelEpgMappingDao.getForProvider(PROVIDER_ID)).thenReturn(emptyList())

        val summary = engine.resolveForProvider(PROVIDER_ID)

        assertThat(summary.exactIdMatches).isEqualTo(1)
        assertThat(summary.normalizedNameMatches).isEqualTo(0)

        val captor = argumentCaptor<List<ChannelEpgMappingEntity>>()
        verify(channelEpgMappingDao).replaceForProvider(any(), captor.capture())
        val mapping = captor.firstValue.single()
        assertThat(mapping.matchType).isEqualTo(EpgMatchType.EXACT_ID.name)
        assertThat(mapping.xmltvChannelId).isEqualTo("bbc.one.hd")
    }

    @Test
    fun `resolveForProvider_duplicateSourceDoesNotCreateDuplicateMappings`() = runTest {
        // Two channels that map to the same source — each gets its own mapping
        val ch1 = makeChannel(id = 1, name = "Channel A", epgChannelId = "ch-a")
        val ch2 = makeChannel(id = 2, name = "Channel B", epgChannelId = "ch-b")
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(listOf(ch1, ch2))
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(
            listOf(ProviderEpgSourceEntity(id = 1, providerId = PROVIDER_ID, epgSourceId = SOURCE_1, priority = 0))
        )
        whenever(epgChannelDao.getBySources(listOf(SOURCE_1))).thenReturn(listOf(
            EpgChannelEntity(id = 1, epgSourceId = SOURCE_1, xmltvChannelId = "ch-a", displayName = "Channel A", normalizedName = "channela"),
            EpgChannelEntity(id = 2, epgSourceId = SOURCE_1, xmltvChannelId = "ch-b", displayName = "Channel B", normalizedName = "channelb")
        ))
        whenever(channelEpgMappingDao.getForProvider(PROVIDER_ID)).thenReturn(emptyList())

        val summary = engine.resolveForProvider(PROVIDER_ID)

        assertThat(summary.exactIdMatches).isEqualTo(2)
        assertThat(summary.totalChannels).isEqualTo(2)

        val captor = argumentCaptor<List<ChannelEpgMappingEntity>>()
        verify(channelEpgMappingDao).replaceForProvider(any(), captor.capture())
        val mappings = captor.firstValue
        assertThat(mappings).hasSize(2)
        // Each channel gets exactly one mapping — no duplicates
        assertThat(mappings.map { it.providerChannelId }.toSet()).containsExactly(1L, 2L)
    }

    @Test
    fun `resolveForProvider_providerNativeFallback_requiresCoverageForEachChannel`() = runTest {
        val coveredChannel = makeChannel(id = 1, name = "Local One", epgChannelId = "local.one")
        val uncoveredChannel = makeChannel(id = 2, name = "Local Two", epgChannelId = "local.two")
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(listOf(coveredChannel, uncoveredChannel))
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(emptyList())
        whenever(channelEpgMappingDao.getForProvider(PROVIDER_ID)).thenReturn(emptyList())
        whenever(programDao.getChannelIdsWithPrograms(PROVIDER_ID, listOf("local.one", "local.two"))).thenReturn(listOf("local.one"))

        val summary = engine.resolveForProvider(PROVIDER_ID)

        assertThat(summary.providerNativeMatches).isEqualTo(1)
        assertThat(summary.unresolvedChannels).isEqualTo(1)

        val captor = argumentCaptor<List<ChannelEpgMappingEntity>>()
        verify(channelEpgMappingDao).replaceForProvider(any(), captor.capture())
        val mappings = captor.firstValue.associateBy { it.providerChannelId }
        assertThat(mappings.getValue(1L).sourceType).isEqualTo(EpgSourceType.PROVIDER.name)
        assertThat(mappings.getValue(2L).sourceType).isEqualTo(EpgSourceType.NONE.name)
    }

    @Test
    fun `resolveForProvider_providerOnly_replaces_stale_external_mapping_with_provider_mapping`() = runTest {
        whenever(providerSnapshotDao.getConfig(PROVIDER_ID)).thenReturn(
            makeProviderConfig(GuideSourcePolicy.PROVIDER_ONLY)
        )
        val channel = makeChannel(id = 1, name = "Local One", epgChannelId = "local.one")
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(listOf(channel))
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(emptyList())
        whenever(channelEpgMappingDao.getForProvider(PROVIDER_ID)).thenReturn(
            listOf(
                ChannelEpgMappingEntity(
                    id = 1,
                    providerChannelId = 1,
                    providerId = PROVIDER_ID,
                    sourceType = EpgSourceType.EXTERNAL.name,
                    epgSourceId = SOURCE_1,
                    xmltvChannelId = "external.one",
                    matchType = EpgMatchType.MANUAL.name,
                    confidence = 1.0f,
                    isManualOverride = true,
                    updatedAt = 1000L
                )
            )
        )
        whenever(programDao.getChannelIdsWithPrograms(PROVIDER_ID, listOf("local.one"))).thenReturn(listOf("local.one"))

        engine.resolveForProvider(PROVIDER_ID)

        val captor = argumentCaptor<List<ChannelEpgMappingEntity>>()
        verify(channelEpgMappingDao).replaceForProvider(any(), captor.capture())
        val mapping = captor.firstValue.single()
        assertThat(mapping.sourceType).isEqualTo(EpgSourceType.PROVIDER.name)
        assertThat(mapping.xmltvChannelId).isEqualTo("local.one")
    }

    // ── getResolvedProgrammes ──────────────────────────────────────

    @Test
    fun `getResolvedProgrammes_externalSource_returnsProgrammes`() = runTest {
        val now = System.currentTimeMillis()
        val startTime = now - 3600_000
        val endTime = now + 3600_000

        whenever(channelEpgMappingDao.getForChannels(PROVIDER_ID, listOf(1L))).thenReturn(listOf(
            ChannelEpgMappingEntity(
                id = 1, providerChannelId = 1, providerId = PROVIDER_ID,
                sourceType = EpgSourceType.EXTERNAL.name, epgSourceId = SOURCE_1,
                xmltvChannelId = "bbc1", matchType = EpgMatchType.EXACT_ID.name,
                confidence = 1.0f, updatedAt = now
            )
        ))
        whenever(channelDao.getGuideLookupsByIds(listOf(1L))).thenReturn(listOf(
            makeGuideLookup(id = 1, epgChannelId = "bbc1")
        ))
        whenever(epgProgrammeDao.getForChannels(SOURCE_1, listOf("bbc1"), startTime, endTime)).thenReturn(listOf(
            EpgProgrammeEntity(id = 1, epgSourceId = SOURCE_1, xmltvChannelId = "bbc1", startTime = startTime, endTime = endTime, title = "News")
        ))

        val result = engine.getResolvedProgrammes(PROVIDER_ID, listOf(1L), startTime, endTime)

        assertThat(result).containsKey("bbc1")
        assertThat(result["bbc1"]).hasSize(1)
        assertThat(result["bbc1"]!![0].title).isEqualTo("News")
    }

    @Test
    fun `getResolvedProgrammes_providerNative_returnsProgrammes`() = runTest {
        val now = System.currentTimeMillis()
        val startTime = now - 3600_000
        val endTime = now + 3600_000

        whenever(channelEpgMappingDao.getForChannels(PROVIDER_ID, listOf(1L))).thenReturn(listOf(
            ChannelEpgMappingEntity(
                id = 1, providerChannelId = 1, providerId = PROVIDER_ID,
                sourceType = EpgSourceType.PROVIDER.name, epgSourceId = null,
                xmltvChannelId = "local.ch1", matchType = EpgMatchType.PROVIDER_NATIVE.name,
                confidence = 0.5f, updatedAt = now
            )
        ))
        whenever(channelDao.getGuideLookupsByIds(listOf(1L))).thenReturn(listOf(
            makeGuideLookup(id = 1, epgChannelId = "local.ch1")
        ))
        whenever(programDao.getForChannelsSync(PROVIDER_ID, listOf("local.ch1"), startTime, endTime)).thenReturn(listOf(
            ProgramBrowseEntity(id = 1, providerId = PROVIDER_ID, channelId = "local.ch1", title = "Local News", startTime = startTime, endTime = endTime)
        ))

        val result = engine.getResolvedProgrammes(PROVIDER_ID, listOf(1L), startTime, endTime)

        assertThat(result).containsKey("local.ch1")
        assertThat(result["local.ch1"]!!.first().title).isEqualTo("Local News")
    }

    @Test
    fun `getResolvedProgrammes_unmappedChannel_returnsEmpty`() = runTest {
        val now = System.currentTimeMillis()
        whenever(channelEpgMappingDao.getForChannels(PROVIDER_ID, listOf(1L))).thenReturn(listOf(
            ChannelEpgMappingEntity(
                id = 1, providerChannelId = 1, providerId = PROVIDER_ID,
                sourceType = EpgSourceType.NONE.name, updatedAt = now
            )
        ))
        whenever(channelDao.getGuideLookupsByIds(listOf(1L))).thenReturn(listOf(
            makeGuideLookup(id = 1, epgChannelId = null)
        ))

        val result = engine.getResolvedProgrammes(PROVIDER_ID, listOf(1L), now - 3600_000, now + 3600_000)

        assertThat(result).isEmpty()
    }

    @Test
    fun `getResolvedProgrammes_providerOnly_ignores_stale_external_mapping`() = runTest {
        val now = System.currentTimeMillis()
        whenever(providerSnapshotDao.getConfig(PROVIDER_ID)).thenReturn(
            makeProviderConfig(GuideSourcePolicy.PROVIDER_ONLY)
        )
        whenever(channelEpgMappingDao.getForChannels(PROVIDER_ID, listOf(1L))).thenReturn(
            listOf(
                ChannelEpgMappingEntity(
                    id = 1,
                    providerChannelId = 1,
                    providerId = PROVIDER_ID,
                    sourceType = EpgSourceType.EXTERNAL.name,
                    epgSourceId = SOURCE_1,
                    xmltvChannelId = "external.one",
                    matchType = EpgMatchType.EXACT_ID.name,
                    confidence = 1.0f,
                    updatedAt = now
                )
            )
        )

        val result = engine.getResolvedProgrammes(PROVIDER_ID, listOf(1L), now - 3600_000, now + 3600_000)

        assertThat(result).isEmpty()
    }

    @Test
    fun `getResolvedProgrammes_disabled_ignores_all_mappings`() = runTest {
        val now = System.currentTimeMillis()
        whenever(providerSnapshotDao.getConfig(PROVIDER_ID)).thenReturn(
            makeProviderConfig(GuideSourcePolicy.DISABLED)
        )

        val result = engine.getResolvedProgrammes(PROVIDER_ID, listOf(1L), now - 3600_000, now + 3600_000)

        assertThat(result).isEmpty()
    }

    @Test
    fun `resolveForProvider_overFiveHundredChannels_chunks_native_program_lookup`() = runTest {
        val channels = (1..600).map { i ->
            makeChannel(id = i.toLong(), name = "Channel $i", epgChannelId = "ch.$i")
        }
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(channels)
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(emptyList())
        whenever(channelEpgMappingDao.getForProvider(PROVIDER_ID)).thenReturn(emptyList())

        engine.resolveForProvider(PROVIDER_ID)

        val captor = argumentCaptor<List<String>>()
        verify(programDao, times(2)).getChannelIdsWithPrograms(eq(PROVIDER_ID), captor.capture())
        assertThat(captor.allValues.map { it.size }).isEqualTo(listOf(500, 100))
        assertThat(captor.allValues.flatten().distinct().size).isEqualTo(600)
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun makeChannel(
        id: Long,
        name: String,
        epgChannelId: String? = null,
        streamId: Long = 0L
    ) = ChannelEntity(
        id = id,
        name = name,
        epgChannelId = epgChannelId,
        streamId = streamId,
        providerId = PROVIDER_ID
    )

    private fun makeGuideLookup(
        id: Long,
        epgChannelId: String? = null,
        streamId: Long = 0L
    ) = ChannelGuideLookupEntity(
        id = id,
        streamId = streamId,
        epgChannelId = epgChannelId
    )

    private fun makeProviderConfig(
        guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO
    ) = ProviderConfigEntity(
        providerId = PROVIDER_ID,
        type = ProviderType.XTREAM_CODES,
        schemaVersion = 1,
        configurationGeneration = 1L,
        identityKey = "identity",
        encryptedConfigJson = "{}",
        guideSourcePolicy = guideSourcePolicy,
        updatedAt = 1L
    )
}
