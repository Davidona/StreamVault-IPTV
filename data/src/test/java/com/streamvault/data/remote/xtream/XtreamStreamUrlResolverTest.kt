package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.provider.DefaultProviderCapabilityRegistry
import com.streamvault.data.provider.JellyfinCapabilityFactory
import com.streamvault.data.provider.M3uCapabilityFactory
import com.streamvault.data.provider.ProviderCapabilityResolver
import com.streamvault.data.provider.StalkerCapabilityFactory
import com.streamvault.data.provider.StalkerPlaybackCapabilityCache
import com.streamvault.data.provider.TypedProviderClientFactory
import com.streamvault.data.provider.XtreamCapabilityFactory
import com.streamvault.data.provider.toProviderSnapshot
import com.streamvault.data.remote.jellyfin.JellyfinProvider
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerPortalStateStore
import com.streamvault.data.remote.stalker.StalkerRemoteIdentityResolver
import com.streamvault.data.remote.stalker.StalkerCommandVariant
import com.streamvault.data.remote.stalker.StalkerCategoryRecord
import com.streamvault.data.remote.stalker.StalkerDeviceProfile
import com.streamvault.data.remote.stalker.StalkerEpisodeRecord
import com.streamvault.data.remote.stalker.StalkerItemRecord
import com.streamvault.data.remote.stalker.StalkerPagedItems
import com.streamvault.data.remote.stalker.StalkerPlaybackDescriptor
import com.streamvault.data.remote.stalker.StalkerPlaybackMode
import com.streamvault.data.remote.stalker.StalkerProgramRecord
import com.streamvault.data.remote.stalker.StalkerProviderProfile
import com.streamvault.data.remote.stalker.StalkerPortalCapabilities
import com.streamvault.data.remote.stalker.StalkerSeasonRecord
import com.streamvault.data.remote.stalker.StalkerSeriesDetails
import com.streamvault.data.remote.stalker.StalkerSession
import com.streamvault.data.remote.stalker.StalkerStreamKind
import com.streamvault.data.remote.stalker.StalkerUrlFactory
import com.streamvault.data.remote.xtream.PlaybackObservationSink
import com.streamvault.domain.model.ContentType
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.StalkerPortalLearning
import com.streamvault.domain.provider.PlaybackObservation
import com.streamvault.domain.provider.StalkerPlaybackObservation
import com.streamvault.domain.repository.ProviderSnapshotRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class XtreamStreamUrlResolverTest {
    private val credentialCrypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String = value
        override fun decryptIfNeeded(value: String): String = value
    }

    private val stalkerApiService = FakeStalkerApiService()

    private fun preferencesRepository(
        liveStreamFormatMode: LiveStreamFormatMode = LiveStreamFormatMode.AUTO
    ): PreferencesRepository = mock<PreferencesRepository>().apply {
        whenever(playerLiveStreamFormatMode).thenReturn(flowOf(liveStreamFormatMode))
    }

    private fun xtreamResolver(
        liveStreamFormatMode: LiveStreamFormatMode = LiveStreamFormatMode.AUTO
    ): XtreamStreamUrlResolver = XtreamStreamUrlResolver(
        providerDao = FakeProviderDao(xtreamProvider()),
        credentialCrypto = credentialCrypto,
        stalkerApiService = stalkerApiService,
        preferencesRepository = preferencesRepository(liveStreamFormatMode)
    )

    private fun xtreamProvider(): Provider = Provider(
        id = 9,
        name = "Xtream",
        type = ProviderType.XTREAM_CODES,
        serverUrl = "https://portal.example.com",
        username = "alice",
        password = "secret"
    )


    @Test
    fun buildPlaybackUrl_uses_live_container_extension_when_present() {
        val url = XtreamUrlFactory.buildPlaybackUrl(
            serverUrl = "https://stream.example.com",
            username = "alice",
            password = "secret",
            kind = XtreamStreamKind.LIVE,
            streamId = 123,
            containerExtension = ".M3U8"
        )

        assertThat(url).isEqualTo("https://stream.example.com/live/alice/secret/123.m3u8")
    }

    @Test
    fun buildInternalStreamUrl_normalizes_container_extension() {
        val url = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.LIVE,
            streamId = 456,
            containerExtension = ".TS",
            directSource = "https://edge.example.com/live/456/index.ts"
        )

        assertThat(url).isEqualTo("xtream://9/live/456?ext=ts&src=https%3A%2F%2Fedge.example.com%2Flive%2F456%2Findex.ts")
        assertThat(XtreamUrlFactory.parseInternalStreamUrl(url)?.containerExtension).isEqualTo("ts")
        assertThat(XtreamUrlFactory.parseInternalStreamUrl(url)?.directSource).isEqualTo("https://edge.example.com/live/456/index.ts")
    }

    @Test
    fun resolveWithMetadata_overrides_live_internal_url_to_mpegTs_when_preferred() {
        runBlocking {
        val resolver = xtreamResolver(LiveStreamFormatMode.MPEG_TS)
        val url = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.LIVE,
            streamId = 456,
            containerExtension = "m3u8"
        )

        val resolved = resolver.resolveWithMetadata(url)

        assertThat(resolved?.url).isEqualTo("https://portal.example.com/live/alice/secret/456.ts")
        assertThat(resolved?.containerExtension).isEqualTo("ts")
        }
    }

    @Test
    fun resolveWithMetadata_overrides_live_internal_url_to_hls_when_preferred() {
        runBlocking {
        val resolver = xtreamResolver(LiveStreamFormatMode.HLS)
        val url = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.LIVE,
            streamId = 456,
            containerExtension = "ts"
        )

        val resolved = resolver.resolveWithMetadata(url)

        assertThat(resolved?.url).isEqualTo("https://portal.example.com/live/alice/secret/456.m3u8")
        assertThat(resolved?.containerExtension).isEqualTo("m3u8")
        }
    }

    @Test
    fun resolveWithMetadata_keeps_live_internal_url_extension_in_auto_mode() {
        runBlocking {
        val resolver = xtreamResolver(LiveStreamFormatMode.AUTO)
        val url = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.LIVE,
            streamId = 456,
            containerExtension = "m3u8"
        )

        val resolved = resolver.resolveWithMetadata(url)

        assertThat(resolved?.url).isEqualTo("https://portal.example.com/live/alice/secret/456.m3u8")
        assertThat(resolved?.containerExtension).isEqualTo("m3u8")
        }
    }

    @Test
    fun resolveWithMetadata_does_not_apply_live_format_preference_to_vod_or_series() {
        runBlocking {
        val resolver = xtreamResolver(LiveStreamFormatMode.MPEG_TS)
        val movieUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.MOVIE,
            streamId = 456,
            containerExtension = "mp4"
        )
        val seriesUrl = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.SERIES,
            streamId = 789,
            containerExtension = "mkv"
        )

        val movie = resolver.resolveWithMetadata(movieUrl)
        val series = resolver.resolveWithMetadata(seriesUrl)

        assertThat(movie?.url).isEqualTo("https://portal.example.com/movie/alice/secret/456.mp4")
        assertThat(movie?.containerExtension).isEqualTo("mp4")
        assertThat(series?.url).isEqualTo("https://portal.example.com/series/alice/secret/789.mkv")
        assertThat(series?.containerExtension).isEqualTo("mkv")
        }
    }

    @Test
    fun resolveWithMetadata_prefers_allowed_direct_source_for_vod() {
        runBlocking {
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                Provider(
                    id = 9,
                    name = "Xtream",
                    type = ProviderType.XTREAM_CODES,
                    serverUrl = "https://portal.example.com",
                    username = "alice",
                    password = "secret"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = stalkerApiService,
            preferencesRepository = preferencesRepository()
        )
        val url = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.MOVIE,
            streamId = 456,
            containerExtension = "mp4",
            directSource = "http://edge.example.com/movie/456/index.mp4?exp=1774017000"
        )

        val resolved = resolver.resolveWithMetadata(url)

        assertThat(resolved?.url).isEqualTo("http://edge.example.com/movie/456/index.mp4?exp=1774017000")
        assertThat(resolved?.expirationTime).isEqualTo(1_774_017_000_000L)
        }
    }

    @Test
    fun resolveWithMetadata_ignores_live_direct_source_and_uses_portal_url() {
        runBlocking {
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                Provider(
                    id = 9,
                    name = "Xtream",
                    type = ProviderType.XTREAM_CODES,
                    serverUrl = "https://portal.example.com",
                    username = "alice",
                    password = "secret",
                    httpUserAgent = "ProviderAgent/2.0",
                    httpHeaders = "Referer: https://portal.example.com/player"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = stalkerApiService,
            preferencesRepository = preferencesRepository()
        )
        val url = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.LIVE,
            streamId = 456,
            containerExtension = "ts",
            directSource = "http://edge.example.com/live/456/index.ts?exp=1774017000"
        )

        val resolved = resolver.resolveWithMetadata(url)

        assertThat(resolved?.url).isEqualTo("https://portal.example.com/live/alice/secret/456.ts")
        assertThat(resolved?.expirationTime).isNull()
        assertThat(resolved?.userAgent).isEqualTo("ProviderAgent/2.0")
        assertThat(resolved?.headers).containsEntry("Referer", "https://portal.example.com/player")
        }
    }

    @Test
    fun resolveWithMetadata_rebuilds_direct_credentialed_live_url_from_provider() {
        runBlocking {
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                Provider(
                    id = 9,
                    name = "Xtream",
                    type = ProviderType.XTREAM_CODES,
                    serverUrl = "https://portal.example.com",
                    username = "alice",
                    password = "fresh-secret",
                    httpUserAgent = "ProviderAgent/2.0"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = stalkerApiService,
            preferencesRepository = preferencesRepository()
        )

        val resolved = resolver.resolveWithMetadata(
            url = "https://old-edge.example.com/live/old-user/old-pass/456.m3u8?token=stale",
            fallbackProviderId = 9,
            fallbackContentType = ContentType.LIVE
        )

        assertThat(resolved?.url).isEqualTo("https://portal.example.com/live/alice/fresh-secret/456.m3u8")
        assertThat(resolved?.expirationTime).isNull()
        assertThat(resolved?.containerExtension).isEqualTo("m3u8")
        assertThat(resolved?.userAgent).isEqualTo("ProviderAgent/2.0")
        }
    }

    @Test
    fun resolveWithMetadata_ignores_unsupported_direct_source_scheme() {
        runBlocking {
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                Provider(
                    id = 9,
                    name = "Xtream",
                    type = ProviderType.XTREAM_CODES,
                    serverUrl = "https://portal.example.com",
                    username = "alice",
                    password = "secret"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = stalkerApiService,
            preferencesRepository = preferencesRepository()
        )
        val url = XtreamUrlFactory.buildInternalStreamUrl(
            providerId = 9,
            kind = XtreamStreamKind.LIVE,
            streamId = 456,
            containerExtension = "m3u8",
            directSource = "ftp://edge.example.com/live/456/index.m3u8"
        )

        val resolved = resolver.resolveWithMetadata(url)

        assertThat(resolved?.url).isEqualTo("https://portal.example.com/live/alice/secret/456.m3u8")
        }
    }

    @Test
    fun resolveWithMetadata_applies_provider_request_profile_to_direct_m3u_urls() {
        runBlocking {
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                Provider(
                    id = 21,
                    name = "Playlist",
                    type = ProviderType.M3U,
                    serverUrl = "https://playlist.example.com",
                    httpUserAgent = "PlaylistAgent/5.0",
                    httpHeaders = "Referer: https://playlist.example.com/app | Origin: https://playlist.example.com"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = stalkerApiService,
            preferencesRepository = preferencesRepository()
        )

        val resolved = resolver.resolveWithMetadata(
            url = "https://cdn.example.com/live/news/index.m3u8",
            fallbackProviderId = 21
        )

        assertThat(resolved?.url).isEqualTo("https://cdn.example.com/live/news/index.m3u8")
        assertThat(resolved?.userAgent).isEqualTo("PlaylistAgent/5.0")
        assertThat(resolved?.headers).containsExactly(
            "Referer", "https://playlist.example.com/app",
            "Origin", "https://playlist.example.com"
        )
        }
    }

    @Test
    fun extractStreamExpirationTime_reads_unix_seconds_query_parameter() {
        val expirationTime = extractStreamExpirationTime(
            "https://stream.example.com/live.m3u8?token=abc123&expire=1774017000"
        )

        assertThat(expirationTime).isEqualTo(1_774_017_000_000L)
    }

    @Test
    fun extractStreamExpirationTime_reads_iso_encoded_query_parameter() {
        val expirationTime = extractStreamExpirationTime(
            "https://stream.example.com/live.m3u8?expires_at=2026-03-20T14%3A30%3A00Z"
        )

        assertThat(expirationTime).isEqualTo(1_774_017_000_000L)
    }

    @Test
    fun extractStreamExpirationTime_returns_null_when_missing() {
        val expirationTime = extractStreamExpirationTime(
            "https://stream.example.com/live.m3u8?token=abc123"
        )

        assertThat(expirationTime).isNull()
    }

    @Test
    fun resolveWithMetadata_resolves_stalker_internal_url_via_cached_provider() {
        runBlocking {
        val fakeStalkerApiService = FakeStalkerApiService()
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                Provider(
                    id = 14,
                    name = "Stalker",
                    type = ProviderType.STALKER_PORTAL,
                    serverUrl = "https://portal.example.com",
                    stalkerMacAddress = "00:1A:79:12:34:56",
                    stalkerDeviceProfile = "MAG250",
                    stalkerDeviceTimezone = "UTC",
                    stalkerDeviceLocale = "en"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = fakeStalkerApiService,
            preferencesRepository = preferencesRepository()
        )
        val internalUrl = StalkerUrlFactory.buildInternalStreamUrl(
            providerId = 14,
            kind = StalkerStreamKind.LIVE,
            itemId = 77,
            cmd = "ffrt http://edge.example.com/live/77.m3u8",
            containerExtension = "m3u8"
        )

        val firstResolved = resolver.resolveWithMetadata(internalUrl)
        val secondResolved = resolver.resolveWithMetadata(internalUrl)

        assertThat(firstResolved?.url).isEqualTo("http://edge.example.com/live/77.m3u8")
        assertThat(firstResolved?.expirationTime).isNull()
        assertThat(firstResolved?.headers?.get("Referer")).isEqualTo("https://portal.example.com/c/")
        assertThat(firstResolved?.headers?.get("Cookie")).contains("mac=00%3A1A%3A79%3A12%3A34%3A56")
        assertThat(firstResolved?.headers?.get("Cookie")).contains("stb_lang=en")
        assertThat(firstResolved?.headers?.get("Cookie")).contains("timezone=UTC")
        assertThat(firstResolved?.headers?.get("Cookie")).doesNotContain("sn=")
        assertThat(firstResolved?.headers?.get("Cookie")).doesNotContain("device_id=")
        assertThat(firstResolved?.headers?.get("Cookie")).doesNotContain("device_id2=")
        assertThat(firstResolved?.headers?.get("Cookie")).doesNotContain("signature=")
        assertThat(firstResolved?.headers?.get("Authorization")).isEqualTo("Bearer token")
        assertThat(firstResolved?.headers?.get("X-User-Agent")).isEqualTo("Model: MAG250; Link: Ethernet")
        assertThat(firstResolved?.userAgent).contains("MAG250 stbapp")
        assertThat(secondResolved?.url).isEqualTo("http://edge.example.com/live/77.m3u8")
        assertThat(fakeStalkerApiService.authenticateCalls).isEqualTo(1)
        assertThat(fakeStalkerApiService.createLinkCalls).isEqualTo(0)
        }
    }

    @Test
    fun resolveWithMetadata_applies_stalker_custom_headers_through_cached_provider() {
        runBlocking {
        val fakeStalkerApiService = FakeStalkerApiService()
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                Provider(
                    id = 14,
                    name = "Stalker",
                    type = ProviderType.STALKER_PORTAL,
                    serverUrl = "https://portal.example.com",
                    httpHeaders = "X-Test: enabled | Referer: https://custom.example.com/ref",
                    stalkerMacAddress = "00:1A:79:12:34:56",
                    stalkerDeviceProfile = "MAG250",
                    stalkerDeviceTimezone = "UTC",
                    stalkerDeviceLocale = "en"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = fakeStalkerApiService,
            preferencesRepository = preferencesRepository()
        )
        val internalUrl = StalkerUrlFactory.buildInternalStreamUrl(
            providerId = 14,
            kind = StalkerStreamKind.LIVE,
            itemId = 77,
            cmd = "ffrt http://edge.example.com/live/77.m3u8",
            containerExtension = "m3u8"
        )

        val resolved = resolver.resolveWithMetadata(internalUrl)

        assertThat(resolved?.headers).containsEntry("X-Test", "enabled")
        assertThat(resolved?.headers).containsEntry("Referer", "https://custom.example.com/ref")
        assertThat(fakeStalkerApiService.lastAuthenticateProfile?.httpHeaders)
            .contains("X-Test: enabled")
        }
    }

    @Test
    fun resolveWithMetadata_uses_direct_stalker_absolute_cmd_without_create_link() {
        runBlocking {
        val fakeStalkerApiService = FakeStalkerApiService()
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                Provider(
                    id = 14,
                    name = "Stalker",
                    type = ProviderType.STALKER_PORTAL,
                    serverUrl = "https://portal.example.com",
                    stalkerMacAddress = "00:1A:79:12:34:56",
                    stalkerDeviceProfile = "MAG250",
                    stalkerDeviceTimezone = "UTC",
                    stalkerDeviceLocale = "en"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = fakeStalkerApiService,
            preferencesRepository = preferencesRepository()
        )
        val directCmd = "http://0connect.top:8080/9UTXtQcxuxkk/9fa4ed5x07/443?play_token=iwdgLK23Yl"
        val internalUrl = StalkerUrlFactory.buildInternalStreamUrl(
            providerId = 14,
            kind = StalkerStreamKind.LIVE,
            itemId = 77,
            cmd = directCmd,
            containerExtension = "ts"
        )

        val resolved = resolver.resolveWithMetadata(internalUrl)

        assertThat(resolved?.url).isEqualTo(directCmd)
        assertThat(resolved?.headers?.get("Referer")).isEqualTo("https://portal.example.com/c/")
        assertThat(resolved?.headers?.get("Authorization")).isEqualTo("Bearer token")
        assertThat(resolved?.userAgent).contains("MAG250 stbapp")
        assertThat(fakeStalkerApiService.authenticateCalls).isEqualTo(1)
        assertThat(fakeStalkerApiService.createLinkCalls).isEqualTo(0)
        }
    }

    @Test
    fun resolveWithMetadata_uses_wrapped_direct_stalker_live_cmd_without_create_link() {
        runBlocking {
        val fakeStalkerApiService = FakeStalkerApiService().apply {
            createLinkResponse = "http://line.trxdnscloud.ru/play/live.php?mac=00:1A:79:40:8B:D7&stream=&extension=ts&play_token=broken"
        }
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                Provider(
                    id = 14,
                    name = "Stalker",
                    type = ProviderType.STALKER_PORTAL,
                    serverUrl = "http://line.trxdnscloud.ru/c/",
                    stalkerMacAddress = "00:1A:79:40:8B:D7",
                    stalkerDeviceProfile = "MAG250",
                    stalkerDeviceTimezone = "UTC",
                    stalkerDeviceLocale = "en"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = fakeStalkerApiService,
            preferencesRepository = preferencesRepository()
        )
        val internalUrl = StalkerUrlFactory.buildInternalStreamUrl(
            providerId = 14,
            kind = StalkerStreamKind.LIVE,
            itemId = 978715,
            cmd = "ffmpeg http://line.trxdnscloud.ru/play/live.php?mac=00:1A:79:40:8B:D7&stream=978715&extension=ts&play_token=R7KbxtDJj3",
            containerExtension = "ts"
        )

        val resolved = resolver.resolveWithMetadata(internalUrl)

        assertThat(resolved?.url).isEqualTo(
            "http://line.trxdnscloud.ru/play/live.php?mac=00:1A:79:40:8B:D7&stream=978715&extension=ts&play_token=R7KbxtDJj3"
        )
        assertThat(resolved?.headers?.get("Referer")).isEqualTo("https://portal.example.com/c/")
        assertThat(resolved?.headers?.get("Authorization")).isNull()
        assertThat(resolved?.userAgent).contains("MAG250 stbapp")
        assertThat(fakeStalkerApiService.authenticateCalls).isEqualTo(1)
        assertThat(fakeStalkerApiService.createLinkCalls).isEqualTo(0)
        }
    }

    @Test
    fun resolveWithMetadata_passes_stalker_episode_series_selector_to_create_link() {
        runBlocking {
        val fakeStalkerApiService = FakeStalkerApiService().apply {
            createLinkResponse = "http://portal.example.com/play/movie.php?stream=1672828.mkv"
        }
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                Provider(
                    id = 14,
                    name = "Stalker",
                    type = ProviderType.STALKER_PORTAL,
                    serverUrl = "https://portal.example.com",
                    stalkerMacAddress = "00:1A:79:12:34:56",
                    stalkerDeviceProfile = "MAG250",
                    stalkerDeviceTimezone = "UTC",
                    stalkerDeviceLocale = "en"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = fakeStalkerApiService,
            preferencesRepository = preferencesRepository()
        )
        val internalUrl = StalkerUrlFactory.buildInternalStreamUrl(
            providerId = 14,
            kind = StalkerStreamKind.EPISODE,
            itemId = 77,
            cmd = "eyJzZXJpZXNfaWQiOjUzOTk5LCJzZWFzb25fbnVtIjoxLCJ0eXBlIjoic2VyaWVzIn0=",
            containerExtension = "mkv",
            seriesNumber = 11
        )

        val resolved = resolver.resolveWithMetadata(internalUrl)

        assertThat(resolved?.url).isEqualTo("http://portal.example.com/play/movie.php?stream=1672828.mkv")
        assertThat(fakeStalkerApiService.authenticateCalls).isEqualTo(1)
        assertThat(fakeStalkerApiService.createLinkCalls).isEqualTo(1)
        assertThat(fakeStalkerApiService.lastCreateLinkSeriesNumber).isEqualTo(11)
        }
    }

    @Test
    fun resolveWithMetadata_uses_ranked_stalker_multi_command_candidates() {
        runBlocking {
        val fakeStalkerApiService = FakeStalkerApiService()
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                Provider(
                    id = 14,
                    name = "Stalker",
                    type = ProviderType.STALKER_PORTAL,
                    serverUrl = "https://portal.example.com",
                    stalkerMacAddress = "00:1A:79:12:34:56",
                    stalkerDeviceProfile = "MAG250",
                    stalkerDeviceTimezone = "UTC",
                    stalkerDeviceLocale = "en"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = fakeStalkerApiService,
            preferencesRepository = preferencesRepository()
        )
        val descriptor = StalkerPlaybackDescriptor(
            primaryMode = StalkerPlaybackMode.MULTI_CMD,
            candidates = listOf(
                StalkerCommandVariant(
                    cmd = "ffmpeg http://localhost/ch/77_",
                    playbackMode = StalkerPlaybackMode.LOCALHOST_CMD,
                    sourceKey = "cmd",
                    priority = 2
                ),
                StalkerCommandVariant(
                    cmd = "http://edge.example.com/live/77.m3u8",
                    playbackMode = StalkerPlaybackMode.DIRECT_URL,
                    sourceKey = "cmd_1",
                    priority = 1
                )
            ),
            capabilities = StalkerPortalCapabilities()
        )
        val internalUrl = StalkerUrlFactory.buildInternalStreamUrl(
            providerId = 14,
            kind = StalkerStreamKind.LIVE,
            itemId = 77,
            cmd = "ffmpeg http://localhost/ch/77_",
            containerExtension = "ts",
            playbackDescriptor = descriptor
        )

        val resolved = resolver.resolveWithMetadata(internalUrl)

        assertThat(resolved?.url).isEqualTo("http://edge.example.com/live/77.m3u8")
        assertThat(fakeStalkerApiService.createLinkCalls).isEqualTo(0)
        }
    }

    @Test
    fun resolveWithMetadata_repairs_stale_direct_stalker_live_url_and_applies_headers() {
        runBlocking {
        val fakeStalkerApiService = FakeStalkerApiService()
        val resolver = XtreamStreamUrlResolver(
            providerDao = FakeProviderDao(
                Provider(
                    id = 14,
                    name = "Stalker",
                    type = ProviderType.STALKER_PORTAL,
                    serverUrl = "https://portal.example.com",
                    stalkerMacAddress = "00:1A:79:12:34:56",
                    stalkerDeviceProfile = "MAG250",
                    stalkerDeviceTimezone = "UTC",
                    stalkerDeviceLocale = "en"
                )
            ),
            credentialCrypto = credentialCrypto,
            stalkerApiService = fakeStalkerApiService,
            preferencesRepository = preferencesRepository()
        )

        val resolved = resolver.resolveWithMetadata(
            url = "http://portal.example.com/play/live.php?mac=00:1A:79:12:34:56&stream=&extension=ts&play_token=abc123",
            fallbackProviderId = 14,
            fallbackStreamId = 978715,
            fallbackContentType = ContentType.LIVE,
            fallbackContainerExtension = "ts"
        )

        assertThat(resolved?.url).isEqualTo(
            "http://portal.example.com/play/live.php?mac=00:1A:79:12:34:56&stream=978715&extension=ts&play_token=abc123"
        )
        assertThat(resolved?.headers?.get("Referer")).isEqualTo("https://portal.example.com/c/")
        assertThat(resolved?.headers?.get("Authorization")).isNull()
        assertThat(resolved?.userAgent).contains("MAG250 stbapp")
        assertThat(fakeStalkerApiService.authenticateCalls).isEqualTo(1)
        assertThat(fakeStalkerApiService.createLinkCalls).isEqualTo(0)
        }
    }

    @Test
    fun resolveAndCommitMetadata_commits_learned_playback_observations() {
        runBlocking {
            val sink = RecordingPlaybackObservationSink()
            val resolver = XtreamStreamUrlResolver(
                providerDao = FakeProviderDao(
                    Provider(
                        id = 14,
                        name = "Stalker",
                        type = ProviderType.STALKER_PORTAL,
                        serverUrl = "https://portal.example.com",
                        stalkerMacAddress = "00:1A:79:12:34:56",
                        stalkerDeviceProfile = "MAG250",
                        stalkerDeviceTimezone = "UTC",
                        stalkerDeviceLocale = "en"
                    )
                ),
                credentialCrypto = credentialCrypto,
                stalkerApiService = FakeStalkerApiService(),
                preferencesRepository = preferencesRepository(),
                observationSink = sink
            )
            val internalUrl = StalkerUrlFactory.buildInternalStreamUrl(
                providerId = 14,
                kind = StalkerStreamKind.LIVE,
                itemId = 77,
                cmd = "http://edge.example.com/live/77.m3u8",
                containerExtension = "m3u8"
            )

            resolver.resolveAndCommitMetadata(internalUrl)

            assertThat(sink.observations).hasSize(1)
            assertThat(sink.observations.single()).isInstanceOf(StalkerPlaybackObservation::class.java)
        }
    }

    @Test
    fun resolveAndCommitMetadata_requires_an_observation_sink() {
        runBlocking {
            val resolver = xtreamResolver()

            val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                runBlocking { resolver.resolveAndCommitMetadata("https://edge.example.com/live.m3u8") }
            }

            assertThat(error).hasMessageThat().contains("observation coordinator")
        }
    }

    private class FakeProviderDao(
        override val fixtureProvider: Provider?
    ) : ProviderDao(), ProviderFixtureSource {
        private val provider = fixtureProvider?.toEntity()
        override fun getAll() = flowOf(listOfNotNull(provider))
        override suspend fun getAllSync(): List<ProviderEntity> = listOfNotNull(provider)
        override fun getActive() = flowOf(provider)
        override suspend fun getById(id: Long): ProviderEntity? = provider?.takeIf { it.id == id }
        override fun getByIdSync(id: Long): ProviderEntity? = provider?.takeIf { it.id == id }
        override suspend fun getByIds(ids: List<Long>): List<ProviderEntity> =
            listOfNotNull(provider).filter { it.id in ids }
        override fun getByTypeSync(type: ProviderType): List<ProviderEntity> =
            listOfNotNull(provider).filter { it.type == type }
        override suspend fun insertDirect(provider: ProviderEntity): Long = provider.id
        override suspend fun updateDirect(provider: ProviderEntity) = Unit
        override suspend fun insert(provider: ProviderEntity): Long = provider.id
        override suspend fun update(provider: ProviderEntity) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun deactivateAll() = Unit
        override suspend fun activate(id: Long) = Unit
        override suspend fun updateSyncTime(id: Long, timestamp: Long) = Unit
    }

    private class FakeStalkerApiService : StalkerApiService {
        var authenticateCalls: Int = 0
        var createLinkCalls: Int = 0
        var lastCreateLinkSeriesNumber: Int? = null
        var lastAuthenticateProfile: StalkerDeviceProfile? = null
        var createLinkResponse: String = "http://edge.example.com/live/77.m3u8?exp=1774017000"

        override suspend fun authenticate(profile: StalkerDeviceProfile): Result<Pair<StalkerSession, StalkerProviderProfile>> {
            authenticateCalls += 1
            lastAuthenticateProfile = profile
            return Result.success(
                StalkerSession(
                    loadUrl = "https://portal.example.com/server/load.php",
                    portalReferer = "https://portal.example.com/c/",
                    token = "token"
                ) to StalkerProviderProfile(accountName = "Test")
            )
        }

        override suspend fun getLiveCategories(
            session: StalkerSession,
            profile: StalkerDeviceProfile
        ): Result<List<StalkerCategoryRecord>> = Result.success(emptyList())

        override suspend fun getLiveStreams(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?
        ): Result<List<StalkerItemRecord>> = Result.success(emptyList())

        override suspend fun streamLiveStreams(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            onItem: suspend (StalkerItemRecord) -> Unit
        ): Result<Int> = Result.success(0)

        override suspend fun getVodCategories(
            session: StalkerSession,
            profile: StalkerDeviceProfile
        ): Result<List<StalkerCategoryRecord>> = Result.success(emptyList())

        override suspend fun getVodStreams(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?
        ): Result<List<StalkerItemRecord>> = Result.success(emptyList())

        override suspend fun getVodStreamsPage(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?,
            page: Int,
            searchQuery: String?
        ): Result<StalkerPagedItems> = Result.success(
            StalkerPagedItems(items = emptyList(), page = page, totalPages = page, pageSize = 0)
        )

        override suspend fun getSeriesCategories(
            session: StalkerSession,
            profile: StalkerDeviceProfile
        ): Result<List<StalkerCategoryRecord>> = Result.success(emptyList())

        override suspend fun getSeries(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?
        ): Result<List<StalkerItemRecord>> = Result.success(emptyList())

        override suspend fun getSeriesPage(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?,
            page: Int
        ): Result<StalkerPagedItems> = Result.success(
            StalkerPagedItems(items = emptyList(), page = page, totalPages = page, pageSize = 0)
        )

        override suspend fun getSeriesDetails(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            seriesId: String
        ): Result<StalkerSeriesDetails> = Result.success(
            StalkerSeriesDetails(
                series = StalkerItemRecord(id = seriesId, name = "Series"),
                seasons = listOf(StalkerSeasonRecord(seasonNumber = 1, name = "Season 1", episodes = listOf(StalkerEpisodeRecord(id = "1", title = "Episode", episodeNumber = 1, seasonNumber = 1))))
            )
        )

        override suspend fun getShortEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            channelId: String,
            limit: Int
        ): Result<List<StalkerProgramRecord>> = Result.success(emptyList())

        override suspend fun getEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            channelId: String
        ): Result<List<StalkerProgramRecord>> = Result.success(emptyList())

        override suspend fun getBulkEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            periodHours: Int
        ): Result<List<StalkerProgramRecord>> = Result.success(emptyList())

        override suspend fun streamBulkEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            periodHours: Int,
            onProgram: suspend (StalkerProgramRecord) -> Unit
        ): Result<Int> = Result.success(0)

        override suspend fun streamEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            channelId: String,
            periodHours: Int,
            onProgram: suspend (StalkerProgramRecord) -> Unit
        ): Result<Int> = Result.success(0)

        override suspend fun createLink(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            kind: StalkerStreamKind,
            cmd: String,
            seriesNumber: Int?,
            archiveStartSeconds: Long?,
            archiveEndSeconds: Long?
        ): Result<String> {
            createLinkCalls += 1
            lastCreateLinkSeriesNumber = seriesNumber
            return Result.success(createLinkResponse)
        }
    }
}

@Suppress("FunctionName")
private fun XtreamStreamUrlResolver(
    providerDao: ProviderDao,
    credentialCrypto: CredentialCrypto,
    stalkerApiService: StalkerApiService,
    preferencesRepository: PreferencesRepository,
    observationSink: PlaybackObservationSink? = null
): XtreamStreamUrlResolver {
    val provider = requireNotNull((providerDao as? ProviderFixtureSource)?.fixtureProvider)
    val snapshots = object : ProviderSnapshotRepository {
        override suspend fun getSnapshot(providerId: Long) =
            provider.takeIf { it.id == providerId }?.toProviderSnapshot()

        override suspend fun compareAndSetStalkerLearning(
            providerId: Long,
            learning: StalkerPortalLearning
        ): Boolean = false

        override suspend fun updateCatalogLayout(
            providerId: Long,
            layout: com.streamvault.domain.model.CatalogLayout,
            detectionVersion: Int
        ) = Unit
    }
    val clients = TypedProviderClientFactory(
        xtreamApiService = mock(),
        stalkerApiService = stalkerApiService,
        jellyfinProvider = mock<JellyfinProvider>(),
        preferencesRepository = preferencesRepository,
        stalkerRemoteIdentityResolver = mock<StalkerRemoteIdentityResolver>(),
        stalkerPortalStateStore = mock<StalkerPortalStateStore>()
    )
    val stalkerPlaybackCache = StalkerPlaybackCapabilityCache(clients)
    val registry = DefaultProviderCapabilityRegistry(
        listOf(
            XtreamCapabilityFactory(clients),
            StalkerCapabilityFactory(clients, stalkerPlaybackCache),
            M3uCapabilityFactory(
                categoryDao = mock(),
                channelDao = mock(),
                movieDao = mock(),
                programDao = mock()
            ),
            JellyfinCapabilityFactory(clients)
        )
    )
    return XtreamStreamUrlResolver(ProviderCapabilityResolver(snapshots, registry), observationSink)
}

private class RecordingPlaybackObservationSink : PlaybackObservationSink {
    val observations = mutableListOf<PlaybackObservation>()

    override suspend fun persist(observations: List<PlaybackObservation>) {
        this.observations += observations
    }
}

private interface ProviderFixtureSource {
    val fixtureProvider: Provider?
}
