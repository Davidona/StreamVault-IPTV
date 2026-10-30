package com.streamvault.data.provider

import com.streamvault.domain.model.Provider as StableProvider

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.remote.jellyfin.JellyfinProvider
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.domain.model.CapabilityState
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.StalkerDeviceIdentity
import com.streamvault.domain.model.StalkerObservation
import com.streamvault.domain.model.StalkerObservationSource
import com.streamvault.domain.model.StalkerPortalLearning
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.provider.CapabilityResolution
import com.streamvault.domain.provider.CatchUpRequest
import com.streamvault.domain.provider.PlaybackResolver
import com.streamvault.domain.provider.PlaybackRequest
import com.streamvault.domain.provider.ProviderContentReference
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.data.remote.xtream.XtreamStreamKind
import org.junit.Test
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ProductionProviderCapabilityFactoriesTest {
    private val clients: TypedProviderClientFactory = mock()
    private val playbackCache: StalkerPlaybackCapabilityCache = mock()
    private val categoryDao: CategoryDao = mock()
    private val channelDao: ChannelDao = mock()
    private val movieDao: MovieDao = mock()
    private val programDao: ProgramDao = mock()

    @Test
    fun `M3U adapter builds catch-up URL from channel metadata and configured fallback`() = runTest {
        val provider = snapshot(ProviderType.M3U, M3uConfig("https://playlist.test/channels.m3u"))
        val capabilitySet = M3uCapabilityFactory(categoryDao, channelDao, movieDao, programDao).create(provider)
        val catchUp = (capabilitySet.catchUp() as CapabilityResolution.Available).capability

        val urls = catchUp.buildCatchUpUrls(
            CatchUpRequest(
                streamId = 42L,
                start = 1_710_000_000L,
                end = 1_710_003_600L,
                sourceStreamUrl = "https://live.test/channel.ts?token=abc",
                sourceCatchUpType = "default",
                catchUpCorrectionSeconds = 3_600,
                fallbackCatchUpTemplate = "&from={utc}&to={utcend}&minutes={duration:60}"
            )
        )

        assertThat(urls).containsExactly(
            "https://live.test/channel.ts?token=abc&from=1710003600&to=1710007200&minutes=60"
        )
    }

    @Test fun `Xtream live identity preserves every generated archive time range even on stable renewal`() = runTest {
        val config = XtreamConfig("https://archive.test/base", "user name", "pass/word",
            httpUserAgent = "ArchiveTest/1", httpHeaders = "X-Archive: validation")
        val provider = snapshot(ProviderType.XTREAM_CODES, config)
        whenever(clients.resolveLiveContainerExtension(provider, XtreamStreamKind.LIVE, "m3u8")).thenReturn("m3u8")
        val resolver = (availableSet(registry().resolve(provider)).playback() as CapabilityResolution.Available).capability
        val urls = listOf("m3u8", "ts").flatMap { extension ->
            XtreamUrlFactory.buildCatchUpUrls(config.serverUrl, config.username, config.password,
                4, "2026-10-03:07-55", 1001, listOf(extension)).also { assertThat(it).hasSize(6) }
        }
        for (url in urls) for (stable in listOf(false, true)) {
            val resolved = resolver.resolve(PlaybackRequest(url, ProviderContentReference(provider.provider.id, streamId = 1001),
                ContentType.LIVE, "m3u8", preferStableUrl = stable)).getOrNull()!!
            assertThat(resolved.url).isEqualTo(url)
            assertThat(resolved.userAgent).isEqualTo("ArchiveTest/1")
            assertThat(resolved.headers).containsEntry("X-Archive", "validation")
            assertThat(resolved.containerExtension).isEqualTo("m3u8")
        }
    }

    @Test fun `Xtream ordinary live URLs still normalize to current provider credentials`() = runTest {
        val config = XtreamConfig("https://archive.test", "current", "secret")
        val provider = snapshot(ProviderType.XTREAM_CODES, config)
        whenever(clients.resolveLiveContainerExtension(provider, XtreamStreamKind.LIVE, "m3u8")).thenReturn("m3u8")
        val resolver = (availableSet(registry().resolve(provider)).playback() as CapabilityResolution.Available).capability
        val inputs = listOf("https://archive.test/live/old/old/1001.m3u8",
            XtreamUrlFactory.buildInternalStreamUrl(provider.provider.id, XtreamStreamKind.LIVE, 1001, "m3u8"),
            "https://other.test/timeshift/current/secret/4/2026-10-03%3A07-55/1001.m3u8",
            "https://archive.test/timeshift/old/old/4/2026-10-03%3A07-55/1001.m3u8",
            "https://archive.test/timeshift/current/secret/4/2026-10-03%3A07-55/999.m3u8",
            "https://archive.test/timeshift/current/secret/0/2026-10-03%3A07-55/1001.m3u8",
            "https://archive.test/timeshift.php?username=current&password=secret&stream=1001&start=bad&duration=4")
        for (url in inputs) for (stable in listOf(false, true)) {
            val resolved = resolver.resolve(PlaybackRequest(url, ProviderContentReference(provider.provider.id, streamId = 1001),
                ContentType.LIVE, "m3u8", preferStableUrl = stable)).getOrNull()!!
            assertThat(resolved.url).isEqualTo("https://archive.test/live/current/secret/1001.m3u8")
        }
    }

    @Test
    fun `production factories register every provider type exactly once`() {
        val stalker = snapshot(
            ProviderType.STALKER_PORTAL,
            StalkerConfig("https://portal.test", StalkerDeviceIdentity("00:11:22:33:44:55"))
        )
        whenever(clients.stalker(stalker)).thenReturn(CapabilityResolution.Available(mock<StalkerProvider>()))
        whenever(playbackCache.resolve(stalker)).thenReturn(CapabilityResolution.Available(mock<PlaybackResolver>()))
        val jellyfin = snapshot(
            ProviderType.JELLYFIN,
            JellyfinConfig("https://media.test", "alice", "token")
        )
        whenever(clients.jellyfin(jellyfin)).thenReturn(
            CapabilityResolution.Available(
                JellyfinClientContext(
                    mock<JellyfinProvider>(),
                    Provider(
                        id = 4L,
                        name = "Jellyfin",
                        type = ProviderType.JELLYFIN,
                        serverUrl = "https://media.test",
                        username = "alice",
                        password = "token"
                    )
                )
            )
        )
        val registry = registry()

        assertThat(registry.resolve(snapshot(ProviderType.XTREAM_CODES, XtreamConfig("https://x.test", "u", "p"))))
            .isInstanceOf(CapabilityResolution.Available::class.java)
        assertThat(registry.resolve(snapshot(ProviderType.M3U, M3uConfig("https://m.test/list.m3u"))))
            .isInstanceOf(CapabilityResolution.Available::class.java)
        assertThat(registry.resolve(stalker)).isInstanceOf(CapabilityResolution.Available::class.java)
        assertThat(registry.resolve(jellyfin)).isInstanceOf(CapabilityResolution.Available::class.java)
    }

    @Test
    fun `m3u matrix exposes configured execution and typed native-guide absence`() {
        val set = availableSet(
            registry().resolve(snapshot(ProviderType.M3U, M3uConfig("https://m.test/list.m3u")))
        )

        assertThat(set.authentication()).isInstanceOf(CapabilityResolution.Unsupported::class.java)
        assertThat(set.liveCatalog()).isInstanceOf(CapabilityResolution.Available::class.java)
        assertThat(set.vodCatalog()).isInstanceOf(CapabilityResolution.Available::class.java)
        assertThat(set.seriesCatalog()).isInstanceOf(CapabilityResolution.Unsupported::class.java)
        assertThat(set.guide()).isInstanceOf(CapabilityResolution.Unsupported::class.java)
        assertThat(set.playback()).isInstanceOf(CapabilityResolution.Available::class.java)
        assertThat(set.catchUp()).isInstanceOf(CapabilityResolution.Available::class.java)
    }

    @Test
    fun `m3u configured XMLTV exposes guide capability`() {
        val set = availableSet(
            registry().resolve(
                snapshot(
                    ProviderType.M3U,
                    M3uConfig("https://m.test/list.m3u", epgUrl = "https://m.test/guide.xml")
                )
            )
        )

        assertThat(set.guide()).isInstanceOf(CapabilityResolution.Available::class.java)
    }

    @Test
    fun `m3u catalog capabilities expose the imported local catalog`() = runTest {
        whenever(categoryDao.getByProviderAndTypeSync(2L, ContentType.LIVE.name)).thenReturn(
            listOf(CategoryEntity(categoryId = 11L, name = "News", type = ContentType.LIVE, providerId = 2L))
        )
        whenever(categoryDao.getByProviderAndTypeSync(2L, ContentType.MOVIE.name)).thenReturn(
            listOf(CategoryEntity(categoryId = 12L, name = "Movies", type = ContentType.MOVIE, providerId = 2L))
        )
        val set = availableSet(
            registry().resolve(snapshot(ProviderType.M3U, M3uConfig("https://m.test/list.m3u")))
        )
        val live = (set.liveCatalog() as CapabilityResolution.Available).capability
        val vod = (set.vodCatalog() as CapabilityResolution.Available).capability

        assertThat((live.getLiveCategories() as com.streamvault.domain.model.Result.Success).data.single().name)
            .isEqualTo("News")
        assertThat((vod.getVodCategories() as com.streamvault.domain.model.Result.Success).data.single().name)
            .isEqualTo("Movies")
    }

    @Test
    fun `stalker learning dynamically restricts validated capabilities`() {
        val generation = 7L
        val observation = StalkerObservation(
            CapabilityState.RESTRICTED,
            generation,
            StalkerObservationSource.DISCOVERY,
            100L
        )
        val stalker = snapshot(
            ProviderType.STALKER_PORTAL,
            StalkerConfig("https://portal.test", StalkerDeviceIdentity("00:11:22:33:44:55")),
            generation,
            StalkerPortalLearning(generation, capabilities = mapOf("series" to observation))
        )
        whenever(clients.stalker(stalker)).thenReturn(CapabilityResolution.Available(mock<StalkerProvider>()))
        whenever(playbackCache.resolve(stalker)).thenReturn(CapabilityResolution.Available(mock<PlaybackResolver>()))

        val set = availableSet(registry().resolve(stalker))

        assertThat(set.liveCatalog()).isInstanceOf(CapabilityResolution.Available::class.java)
        assertThat(set.seriesCatalog()).isInstanceOf(CapabilityResolution.Restricted::class.java)
    }

    private fun registry() = DefaultProviderCapabilityRegistry(
        listOf(
            XtreamCapabilityFactory(clients),
            StalkerCapabilityFactory(clients, playbackCache),
            M3uCapabilityFactory(categoryDao, channelDao, movieDao, programDao),
            JellyfinCapabilityFactory(clients)
        )
    )

    private fun availableSet(resolution: CapabilityResolution<com.streamvault.domain.provider.ProviderCapabilitySet>) =
        (resolution as CapabilityResolution.Available).capability

    private fun snapshot(
        type: ProviderType,
        configuration: com.streamvault.domain.model.ProviderConfiguration,
        generation: Long = 1L,
        learning: StalkerPortalLearning? = null
    ) = ProviderSnapshot(
        provider = StableProvider(id = type.ordinal.toLong() + 1L, name = type.name, type = type),
        configuration = configuration,
        configurationGeneration = generation,
        stalkerLearning = learning
    )
}
