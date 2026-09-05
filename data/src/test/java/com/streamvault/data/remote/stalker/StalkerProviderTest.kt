package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.StalkerPortalStateDao
import com.streamvault.data.local.dao.StalkerRemoteIdentityDao
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.data.local.entity.StalkerRemoteIdentityEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.PlaybackTransportMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerTransportGrant
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.StalkerTransportOrigin
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class StalkerProviderTest {

@Before
    fun clearSharedAuthCache() {
        StalkerProvider.clearSharedAuthCacheForTests()
        StalkerProvider.clearResolvedStreamUrlCacheForTests()
    }

    @Test
    fun truncated_page_is_never_reported_complete() {
        val page = StalkerPagedResult(
            items = listOf("cached"),
            page = 200,
            totalPages = 200,
            advertisedTotalPages = 250,
            isTruncated = true,
            terminationReason = "page_limit",
            pageSize = 14
        )

        assertThat(page.isComplete).isFalse()
    }

    @Test
    fun authenticate_treats_status_zero_as_partial_not_expired() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(
                    accountId = "758423",
                    accountName = "Room",
                    statusLabel = "0",
                    authAccess = false
                )
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.authenticate()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.status).isEqualTo(ProviderStatus.PARTIAL)
    }

    @Test
    fun authenticate_detects_unifiedVod_when_nativeSeriesIsEmpty_andVodExists() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                vodCategories = listOf(StalkerCategoryRecord("165", "Movies")),
                seriesCategories = emptyList()
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.authenticate() as Result.Success

        assertThat(result.data.catalogLayout).isEqualTo(CatalogLayout.UNIFIED_VOD)
        assertThat(result.data.catalogLayoutDetectionVersion)
            .isEqualTo(StalkerProvider.CATALOG_LAYOUT_DETECTION_VERSION)
    }

    @Test
    fun authenticate_detects_split_when_nativeSeriesCategoriesExist() = runTest {
        val provider = StalkerProvider(
            providerId = 8,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                vodCategories = listOf(StalkerCategoryRecord("10", "Movies")),
                seriesCategories = listOf(StalkerCategoryRecord("20", "Series"))
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:57",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.authenticate() as Result.Success

        assertThat(result.data.catalogLayout).isEqualTo(CatalogLayout.SPLIT)
    }

    @Test
    fun authenticate_retainsLastLayout_whenSeriesProbeFails() = runTest {
        val provider = StalkerProvider(
            providerId = 10,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                seriesCategoriesResult = Result.error("timeout")
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:59",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en",
            catalogLayoutHint = CatalogLayout.UNIFIED_VOD,
            catalogLayoutDetectionVersionHint = 0
        )

        val result = provider.authenticate() as Result.Success

        assertThat(result.data.catalogLayout).isEqualTo(CatalogLayout.UNIFIED_VOD)
        assertThat(result.data.catalogLayoutDetectionVersion).isEqualTo(0)
    }

    @Test
    fun authenticate_treatsHttp200EmptyBody_asNoNativeSeriesSupport() = runTest {
        val provider = StalkerProvider(
            providerId = 13,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                vodCategories = listOf(StalkerCategoryRecord("10", "VOD")),
                seriesCategoriesResult = Result.error(
                    "Failed to load series categories",
                    StalkerApiError.EmptyBody("Portal returned an empty response for get_categories.")
                )
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:63",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.authenticate() as Result.Success

        assertThat(result.data.catalogLayout).isEqualTo(CatalogLayout.UNIFIED_VOD)
        assertThat(result.data.catalogLayoutDetectionVersion)
            .isEqualTo(StalkerProvider.CATALOG_LAYOUT_DETECTION_VERSION)
    }

    @Test
    fun authenticate_keepsProvidersIsolated_whenTheirCatalogCapabilitiesDiffer() = runTest {
        fun provider(id: Long, seriesCategories: List<StalkerCategoryRecord>) = StalkerProvider(
            providerId = id,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                vodCategories = listOf(StalkerCategoryRecord("10", "VOD")),
                seriesCategories = seriesCategories
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:${id.toString().padStart(2, '0')}",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val unified = provider(11, emptyList()).authenticate() as Result.Success
        val split = provider(12, listOf(StalkerCategoryRecord("20", "Series"))).authenticate() as Result.Success

        assertThat(unified.data.catalogLayout).isEqualTo(CatalogLayout.UNIFIED_VOD)
        assertThat(split.data.catalogLayout).isEqualTo(CatalogLayout.SPLIT)
    }

    @Test
    fun vodPaging_classifiesSeriesMarker_withoutLeakingSeriesIntoMovies() = runTest {
        val api = FakeStalkerApiService(
            profile = StalkerProviderProfile(accountName = "Room"),
            vodCategories = listOf(StalkerCategoryRecord("124", "Anime Movies/Series")),
            vodPageItems = listOf(
                StalkerItemRecord(
                    id = "100",
                    name = "Movie",
                    streamUrl = "https://cdn.example.com/movie.mp4",
                    isSeries = false
                ),
                StalkerItemRecord(
                    id = "200",
                    name = "Series",
                    streamUrl = "https://cdn.example.com/series.mp4",
                    isSeries = true
                )
            )
        )
        val provider = StalkerProvider(
            providerId = 9,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:58",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val movies = provider.getVodStreamsPage(null, 1) as Result.Success
        val series = provider.getSeriesListPage(null, 1) as Result.Success

        assertThat(movies.data.items.map { it.name }).containsExactly("Movie")
        assertThat(series.data.items.map { it.name }).containsExactly("Series")
        assertThat(movies.data.totalPages).isEqualTo(1)
        assertThat(series.data.totalPages).isEqualTo(1)
    }

    @Test
    fun unifiedVodPage_keepsMixedProviderOrder_andDefaultsMissingMarkerToMovie() = runTest {
        val api = FakeStalkerApiService(
            profile = StalkerProviderProfile(accountName = "Room"),
            vodCategories = listOf(StalkerCategoryRecord("124", "Mixed")),
            vodPageItems = listOf(
                StalkerItemRecord(id = "200", name = "Series A", isSeries = true),
                StalkerItemRecord(id = "100", name = "Unmarked Movie", streamUrl = "https://cdn.example.com/movie.mp4"),
                StalkerItemRecord(id = "201", name = "Series B", isSeries = true)
            )
        )
        val provider = StalkerProvider(
            providerId = 14,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:61",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        val category = (provider.getUnifiedVodCategories() as Result.Success).data.single()

        val page = (provider.getUnifiedVodPage(category.id, 1) as Result.Success).data

        assertThat(page.items.map { entry ->
            when (entry.item) {
                is com.streamvault.domain.model.VodCatalogItem.MovieItem -> "movie"
                is com.streamvault.domain.model.VodCatalogItem.SeriesItem -> "series"
            }
        }).containsExactly("series", "movie", "series").inOrder()
        assertThat(page.items.map { it.rawItemId }).containsExactly("200", "100", "201").inOrder()
        assertThat(page.items.map { entry ->
            when (val item = entry.item) {
                is com.streamvault.domain.model.VodCatalogItem.MovieItem -> item.movie.categoryId
                is com.streamvault.domain.model.VodCatalogItem.SeriesItem -> item.series.categoryId
            }
        }).containsExactly(category.id, category.id, category.id)
        assertThat(page.page).isEqualTo(1)
        assertThat(page.isComplete).isTrue()
    }

    @Test
    fun authenticate_maps_expired_date_to_expired() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(
                    accountName = "Room",
                    expirationDate = 1L
                )
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.authenticate()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.status).isEqualTo(ProviderStatus.EXPIRED)
    }

    @Test
    fun authenticate_leaves_optional_identity_fields_empty_when_not_configured() = runTest {
        val api = FakeStalkerApiService(profile = StalkerProviderProfile(accountName = "Room"))
        val provider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.authenticate()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val authProfile = checkNotNull(api.lastAuthenticateProfile)
        assertThat(authProfile.serialNumber).isEmpty()
        assertThat(authProfile.deviceId).isEmpty()
        assertThat(authProfile.deviceId2).isEmpty()
        assertThat(authProfile.signature).isEmpty()
    }

    @Test
    fun authenticate_persistedProvider_disablesCompatibilityDiscovery() = runTest {
        val api = FakeStalkerApiService(profile = StalkerProviderProfile(accountName = "Room"))
        val provider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        assertThat(provider.authenticate()).isInstanceOf(Result.Success::class.java)

        assertThat(checkNotNull(api.lastAuthenticateProfile).allowCompatibilityDiscovery).isFalse()
    }

    @Test
    fun authenticate_recovers_when_saved_endpoint_and_recipe_are_cooling_down() = runTest {
        val dao = FakePortalStateDao()
        val stateStore = StalkerPortalStateStore(dao)
        dao.upsert(
            StalkerPortalStateEntity(
                providerId = 7L,
                workingEndpoint = "https://portal.example.com/server/load.php",
                bootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE.name,
                validatedAt = System.currentTimeMillis()
            )
        )
        stateStore.markEndpointUnhealthy(
            providerId = 7L,
            endpoint = "https://portal.example.com/server/load.php"
        )
        stateStore.markRecipeUnhealthy(
            providerId = 7L,
            recipe = StalkerBootstrapRecipe.GENERIC_SAFE.name
        )
        val api = FakeStalkerApiService(
            profile = StalkerProviderProfile(accountName = "Room"),
            authenticationFailuresBeforeSuccess = 1
        )
        val provider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en",
            portalStateStore = stateStore
        )

        assertThat(provider.authenticate()).isInstanceOf(Result.Success::class.java)
        assertThat(api.authenticateCalls).isEqualTo(2)
        assertThat(checkNotNull(api.lastAuthenticateProfile).allowCompatibilityDiscovery).isTrue()
    }

    @Test
    fun authenticate_unsavedProvider_allowsExplicitCompatibilityDiscovery() = runTest {
        val api = FakeStalkerApiService(profile = StalkerProviderProfile(accountName = "Room"))
        val provider = StalkerProvider(
            providerId = 0,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        assertThat(provider.authenticate()).isInstanceOf(Result.Success::class.java)

        assertThat(checkNotNull(api.lastAuthenticateProfile).allowCompatibilityDiscovery).isTrue()
    }

    @Test
    fun authenticate_reusesSessionAcrossEquivalentProviderInstances() = runTest {
        val api = FakeStalkerApiService(profile = StalkerProviderProfile(accountName = "Room"))
        val firstProvider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        val secondProvider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        assertThat(firstProvider.authenticate()).isInstanceOf(Result.Success::class.java)
        assertThat(secondProvider.authenticate()).isInstanceOf(Result.Success::class.java)

        assertThat(api.authenticateCalls).isEqualTo(1)
    }

    @Test
    fun invalidateAuthenticationClearsSharedSessionForEquivalentProviderInstances() = runTest {
        val api = FakeStalkerApiService(profile = StalkerProviderProfile(accountName = "Room"))
        val firstProvider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        val secondProvider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        assertThat(firstProvider.authenticate()).isInstanceOf(Result.Success::class.java)
        firstProvider.invalidateAuthentication()
        assertThat(secondProvider.authenticate()).isInstanceOf(Result.Success::class.java)

        assertThat(api.authenticateCalls).isEqualTo(2)
    }

    @Test
    fun getLiveCategories_reauthenticates_onceAfterTypedAuthorizationFailure() = runTest {
        val api = FakeStalkerApiService(
            profile = StalkerProviderProfile(accountName = "Room"),
            liveCategoryResults = ArrayDeque(
                listOf(
                    Result.error(
                        "Portal token is invalid",
                        StalkerApiError.Authorization("Portal token is invalid", portalReason = "not_valid_token")
                    ),
                    Result.success(emptyList())
                )
            )
        )
        val provider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.getLiveCategories()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(api.authenticateCalls).isEqualTo(2)
    }

    @Test
    fun getLiveCategories_doesNotReauthenticate_for_non_authorization_errors() = runTest {
        val api = FakeStalkerApiService(
            profile = StalkerProviderProfile(accountName = "Room"),
            liveCategoryResults = ArrayDeque(
                listOf(
                    Result.error("Temporary playback link missing")
                )
            )
        )
        val provider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.getLiveCategories()

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(api.authenticateCalls).isEqualTo(1)
    }

    @Test
    fun getSeriesInfo_resolvesCollidingEpisodeRemoteIdsBeforeMapping() = runTest {
        val identityDao = FakeIdentityDao()
        val resolver = StalkerRemoteIdentityResolver(identityDao, DirectTransactionRunner)
        val api = FakeStalkerApiService(
            profile = StalkerProviderProfile(accountName = "Room"),
            seriesDetails = StalkerSeriesDetails(
                series = StalkerItemRecord(id = "series-alpha", name = "Series"),
                seasons = listOf(
                    StalkerSeasonRecord(
                        seasonNumber = 1,
                        name = "Season 1",
                        episodes = listOf(
                            StalkerEpisodeRecord("ba", "Episode A", 1, 1),
                            StalkerEpisodeRecord("a\u0080", "Episode B", 2, 1)
                        )
                    )
                )
            )
        )
        val provider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en",
            identityResolver = resolver
        )

        val result = provider.getSeriesInfo("series-alpha")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val episodeIds = (result as Result.Success).data.seasons.single().episodes.map { episode -> episode.id }
        assertThat(episodeIds.toSet()).hasSize(2)
        assertThat(resolver.reverse(7L, ContentType.SERIES_EPISODE, episodeIds[0])).isEqualTo("ba")
        assertThat(resolver.reverse(7L, ContentType.SERIES_EPISODE, episodeIds[1])).isEqualTo("a\u0080")
    }

    @Test
    fun getVodStreamsPage_preservesRequestedCategoryWhenOnlyReverseIdentityIsCached() = runTest {
        val resolver = StalkerRemoteIdentityResolver(FakeIdentityDao(), DirectTransactionRunner)
        resolver.resolveAll(7L, ContentType.MOVIE, listOf("20"))
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                vodPageItems = listOf(
                    StalkerItemRecord(
                        id = "movie-1",
                        name = "Movie",
                        categoryName = "Action",
                        cmd = "ffmpeg http://cdn.example.com/movie.ts"
                    )
                )
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en",
            identityResolver = resolver
        )

        val result = provider.getVodStreamsPage(categoryId = 20L, page = 1)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val movie = (result as Result.Success).data.items.single()
        assertThat(movie.categoryId).isEqualTo(20L)
        assertThat(movie.categoryName).isEqualTo("Action")
    }

    @Test
    fun getSeriesListPage_preservesRequestedCategoryWhenOnlyReverseIdentityIsCached() = runTest {
        val resolver = StalkerRemoteIdentityResolver(FakeIdentityDao(), DirectTransactionRunner)
        resolver.resolveAll(7L, ContentType.SERIES, listOf("30"))
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                seriesPageItems = listOf(
                    StalkerItemRecord(
                        id = "series-1",
                        name = "Series",
                        categoryName = "Drama"
                    )
                )
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en",
            identityResolver = resolver
        )

        val result = provider.getSeriesListPage(categoryId = 30L, page = 1)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val series = (result as Result.Success).data.items.single()
        assertThat(series.categoryId).isEqualTo(30L)
        assertThat(series.categoryName).isEqualTo("Drama")
    }

    @Test
    fun streamLiveStreams_resolves_large_catalog_identities_in_batches() = runTest {
        val transactionRunner = CountingTransactionRunner()
        val resolver = StalkerRemoteIdentityResolver(FakeIdentityDao(), transactionRunner)
        val liveStreams = (1..1_001).map { id ->
            StalkerItemRecord(
                id = id.toString(),
                name = "Channel $id",
                categoryId = "10",
                cmd = "ffmpeg http://cdn.example.com/$id.ts"
            )
        }
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                liveStreams = liveStreams
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en",
            identityResolver = resolver
        )
        val emitted = mutableListOf<Long>()

        val result = provider.streamLiveStreams { channel -> emitted += channel.streamId }

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(emitted).hasSize(1_001)
        assertThat(transactionRunner.transactionCount).isEqualTo(3)
    }

    @Test
    fun getLiveStreams_maps_archive_capabilities_to_catch_up_fields() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                liveStreams = listOf(
                    StalkerItemRecord(
                        id = "1200",
                        name = "Archive TV",
                        cmd = "ffmpeg http://localhost/ch/1200_",
                        streamUrl = "http://localhost/ch/1200_",
                        portalCapabilities = StalkerPortalCapabilities(
                            archiveAvailable = true,
                            allowLocalTimeshift = true
                        ),
                        archiveAvailable = true
                    )
                )
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.getLiveStreams()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        val channel = success.data.single()
        assertThat(channel.catchUpSupported).isTrue()
        assertThat(channel.catchUpSource).isNotEmpty()
    }

    @Test
    fun buildCatchUpUrls_returns_internal_archive_candidates_for_live_token() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(profile = StalkerProviderProfile(accountName = "Room")),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            playbackBackendHint = com.streamvault.domain.model.StalkerPlaybackBackendHint.PLAY_LIVE,
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        val start = Instant.parse("2024-01-01T10:00:00Z").epochSecond
        val end = Instant.parse("2024-01-01T11:00:00Z").epochSecond
        val sourceStreamUrl = StalkerUrlFactory.buildInternalStreamUrl(
            providerId = 7,
            kind = StalkerStreamKind.LIVE,
            itemId = 1200L,
            cmd = "ffmpeg http://localhost/ch/1200_",
            playbackDescriptor = checkNotNull(
                buildStalkerPlaybackDescriptor(
                    primaryCmd = "ffmpeg http://localhost/ch/1200_",
                    capabilities = StalkerPortalCapabilities(archiveAvailable = true)
                )
            ),
        )

        val urls = provider.buildCatchUpUrls(
            streamId = 1200L,
            start = start,
            end = end,
            sourceStreamUrl = sourceStreamUrl,
            sourceCatchUpSource = sourceStreamUrl
        )

        assertThat(urls).isNotEmpty()
        val token = StalkerUrlFactory.parseInternalStreamUrl(urls.first())
        assertThat(token?.kind).isEqualTo(StalkerStreamKind.ARCHIVE)
        assertThat(token?.archiveStartSeconds).isEqualTo(start)
        assertThat(token?.archiveEndSeconds).isEqualTo(end)
    }

    @Test
    fun buildCatchUpUrls_ignores_tokens_from_other_providers() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(profile = StalkerProviderProfile(accountName = "Room")),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        val foreignToken = StalkerUrlFactory.buildInternalStreamUrl(
            providerId = 99,
            kind = StalkerStreamKind.LIVE,
            itemId = 1200L,
            cmd = "ffmpeg http://localhost/ch/1200_"
        )

        val urls = provider.buildCatchUpUrls(
            streamId = 1200L,
            start = Instant.parse("2024-01-01T10:00:00Z").epochSecond,
            end = Instant.parse("2024-01-01T11:00:00Z").epochSecond,
            sourceStreamUrl = foreignToken,
            sourceCatchUpSource = foreignToken
        )

        assertThat(urls).isEmpty()
    }

    @Test
    fun resolvePlaybackInfo_rejects_invalid_archive_window() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(profile = StalkerProviderProfile(accountName = "Room")),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.ARCHIVE,
            descriptor = buildStalkerPlaybackDescriptor("ffmpeg http://localhost/ch/1200_"),
            archiveStartSeconds = 1_000L,
            archiveEndSeconds = 999L
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.message).contains("end time after the start time")
    }

    @Test
    fun resolvePlaybackInfo_omits_authorization_for_play_live_temp_links() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                createLinkUrl = "http://fdox.org:8080/play/live.php?mac=00:1A:79:BA:73:FA&stream=228556&extension=ts&play_token=abc123"
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            playbackBackendHint = com.streamvault.domain.model.StalkerPlaybackBackendHint.TEMP_LINK_STRICT,
            cookieModeHint = com.streamvault.domain.model.StalkerCookieMode.CREATE_LINK,
            deviceProfile = "MAG322",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = checkNotNull(
                buildStalkerPlaybackDescriptor(
                    primaryCmd = "ffmpeg http://localhost/ch/1200_",
                    capabilities = StalkerPortalCapabilities(
                        useHttpTemporaryLink = true,
                        nginxSecureLink = true
                    )
                )
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.headers).doesNotContainKey("Authorization")
        assertThat(success.data.headers).containsKey("Referer")
        assertThat(success.data.headers).containsKey("Cookie")
        assertThat(success.data.headers).containsKey("X-User-Agent")
        assertThat(success.data.cookieMode).isNotEqualTo(com.streamvault.domain.model.StalkerCookieMode.NONE)
    }

    @Test
    fun resolvePlaybackInfo_uses_latest_api_cookie_snapshot_for_playback_headers() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                createLinkUrl = "http://fdox.org:8080/play/live.php?stream=228556&play_token=abc123",
                currentCookieHeader = "PHPSESSID=fresh-session"
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            playbackBackendHint = com.streamvault.domain.model.StalkerPlaybackBackendHint.TEMP_LINK_STRICT,
            cookieModeHint = com.streamvault.domain.model.StalkerCookieMode.CREATE_LINK,
            deviceProfile = "MAG322",
            timezone = "Europe/Amsterdam",
            locale = "en us",
            serialNumber = "serial-123",
            deviceId = "device-123",
            deviceId2 = "device-456",
            signature = "signature-789"
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = checkNotNull(
                buildStalkerPlaybackDescriptor(
                    primaryCmd = "ffmpeg http://localhost/ch/1200_",
                    capabilities = StalkerPortalCapabilities(useHttpTemporaryLink = true)
                )
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.headers["Cookie"]).contains("PHPSESSID=fresh-session")
        assertThat(success.data.headers["Cookie"]).contains("mac=00%3A1A%3A79%3A12%3A34%3A56")
        assertThat(success.data.headers["Cookie"]).contains("stb_lang=en%20us")
        assertThat(success.data.headers["Cookie"]).contains("timezone=Europe%2FAmsterdam")
        assertThat(success.data.headers["Cookie"]).doesNotContain("sn=")
        assertThat(success.data.headers["Cookie"]).doesNotContain("device_id=")
        assertThat(success.data.headers["Cookie"]).doesNotContain("device_id2=")
        assertThat(success.data.headers["Cookie"]).doesNotContain("signature=")
        assertThat(success.data.headers["Accept"]).isEqualTo("*/*")
        assertThat(success.data.headers["Connection"]).isEqualTo("keep-alive")
        assertThat(success.data.headers["Host"]).isEqualTo("fdox.org:8080")
        assertThat(success.data.userAgent).isEqualTo("Lavf53.32.100")
        assertThat(success.data.allowInvalidSsl).isFalse()
        assertThat(success.data.transportPolicy).isNull()
    }

    @Test
    fun resolvePlaybackInfo_uses_mag_style_default_player_headers() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                createLinkUrl = "http://cdn.example.com/live/stream.ts"
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            playbackBackendHint = com.streamvault.domain.model.StalkerPlaybackBackendHint.TEMP_LINK_STRICT,
            cookieModeHint = com.streamvault.domain.model.StalkerCookieMode.CREATE_LINK,
            deviceProfile = "MAG322",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = checkNotNull(
                buildStalkerPlaybackDescriptor(
                    primaryCmd = "ffmpeg http://localhost/ch/1200_",
                    capabilities = StalkerPortalCapabilities(useHttpTemporaryLink = true)
                )
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.userAgent).isEqualTo("Lavf53.32.100")
        assertThat(success.data.headers["Accept"]).isEqualTo("*/*")
        assertThat(success.data.headers["Connection"]).isEqualTo("keep-alive")
        assertThat(success.data.headers["Host"]).isEqualTo("cdn.example.com")
    }

    @Test
    fun resolvePlaybackInfo_applies_custom_header_overrides_and_user_agent_removal() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                createLinkUrl = "http://fdox.org:8080/play/live.php?stream=228556&play_token=abc123"
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            httpHeaders = "User-Agent: | Referer: | X-Test: enabled",
            stalkerAdvancedOptionsJson = StalkerAdvancedOptionsCodec.encode(
                StalkerAdvancedOptions(
                    proxyEnabled = true,
                    proxyHost = "127.0.0.1",
                    proxyPort = 8080
                )
            ),
            playbackBackendHint = com.streamvault.domain.model.StalkerPlaybackBackendHint.TEMP_LINK_STRICT,
            cookieModeHint = com.streamvault.domain.model.StalkerCookieMode.CREATE_LINK,
            deviceProfile = "MAG322",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = checkNotNull(
                buildStalkerPlaybackDescriptor(
                    primaryCmd = "ffmpeg http://localhost/ch/1200_",
                    capabilities = StalkerPortalCapabilities(useHttpTemporaryLink = true)
                )
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.userAgent).isNull()
        assertThat(success.data.allowInvalidSsl).isFalse()
        assertThat(success.data.transportPolicy).isNull()
        assertThat(success.data.proxyHost).isEqualTo("127.0.0.1")
        assertThat(success.data.proxyPort).isEqualTo(8080)
        assertThat(success.data.headers).doesNotContainKey("Referer")
        assertThat(success.data.headers["X-Test"]).isEqualTo("enabled")
    }

    @Test
    fun resolvePlaybackInfo_repairs_blank_live_stream_from_localhost_channel_command() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                createLinkUrl = "http://fdox.org:8080/play/live.php?mac=00:1A:79:BA:73:FA&stream=&extension=ts&play_token=abc123"
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            playbackBackendHint = com.streamvault.domain.model.StalkerPlaybackBackendHint.TEMP_LINK_STRICT,
            cookieModeHint = com.streamvault.domain.model.StalkerCookieMode.CREATE_LINK,
            deviceProfile = "MAG322",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = checkNotNull(
                buildStalkerPlaybackDescriptor(
                    primaryCmd = "ffmpeg http://localhost/ch/61523_",
                    capabilities = StalkerPortalCapabilities(useHttpTemporaryLink = true)
                )
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.url).isEqualTo(
            "http://fdox.org:8080/play/live.php?mac=00:1A:79:BA:73:FA&stream=61523&extension=ts&play_token=abc123"
        )
    }

    @Test
    fun resolvePlaybackInfo_sends_absolute_portal_channel_commands_through_create_link() = runTest {
        val api = FakeStalkerApiService(
            profile = StalkerProviderProfile(accountName = "Room"),
            createLinkUrl = "http://fdox.org:8080/play/live.php?mac=00:1A:79:BA:73:FA&stream=390414&extension=ts&play_token=abc123"
        )
        val provider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG322",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = checkNotNull(
                buildStalkerPlaybackDescriptor(
                    primaryCmd = "ffmpeg http://portal.example.com/ch/390414_"
                )
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.url).contains("/play/live.php")
        assertThat(api.createLinkCalls).isEqualTo(1)
    }

    @Test
    fun resolvePlaybackInfo_corrects_stored_direct_mode_for_absolute_portal_channel_commands() = runTest {
        val api = FakeStalkerApiService(
            profile = StalkerProviderProfile(accountName = "Room"),
            createLinkUrl = "http://fdox.org:8080/play/live.php?mac=00:1A:79:BA:73:FA&stream=390414&extension=ts&play_token=abc123"
        )
        val provider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG322",
            timezone = "UTC",
            locale = "en"
        )
        val descriptor = StalkerPlaybackDescriptor(
            primaryMode = StalkerPlaybackMode.DIRECT_URL,
            candidates = listOf(
                StalkerCommandVariant(
                    cmd = "ffmpeg http://portal.example.com/ch/390414_",
                    playbackMode = StalkerPlaybackMode.DIRECT_URL
                )
            )
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = descriptor
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(api.createLinkCalls).isEqualTo(1)
    }

    @Test
    fun authenticate_persists_effective_learned_mag_identity() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(
                    accountName = "Room",
                    effectiveAuthMode = com.streamvault.domain.model.StalkerAuthMode.MAC_ONLY,
                    portalProfile = com.streamvault.domain.model.StalkerPortalProfile.MODULE_GATED,
                    portalFingerprint = com.streamvault.domain.model.StalkerPortalFingerprint.MODULE_GATED,
                    magPreset = com.streamvault.domain.model.StalkerMagPreset.MINISTRA_MODERN,
                    bootstrapRecipe = com.streamvault.domain.model.StalkerBootstrapRecipe.MODULE_GATED,
                    fingerprintEvidence = StalkerFingerprintEvidence(
                        endpointPreference = com.streamvault.domain.model.StalkerEndpointPreference.PORTAL,
                        cookieMode = com.streamvault.domain.model.StalkerCookieMode.BOTH,
                        playbackBackendHint = com.streamvault.domain.model.StalkerPlaybackBackendHint.TEMP_LINK_STRICT
                    )
                )
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.authenticate()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.stalkerMagPreset).isEqualTo(com.streamvault.domain.model.StalkerMagPreset.MINISTRA_MODERN)
        assertThat(success.data.stalkerDeviceProfile).isEqualTo("MAG322")
        assertThat(success.data.stalkerDeviceId).isEmpty()
        assertThat(success.data.stalkerSignature).isEmpty()
    }

    @Test
    fun resolvePlaybackInfo_dedicatedPlayerUserAgentOverridesCustomHeaderUserAgent() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                createLinkUrl = "http://cdn.example.com/live.ts"
            ),
            portalUrl = "https://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            httpHeaders = "User-Agent: Header Agent/2.0",
            stalkerAdvancedOptionsJson = StalkerAdvancedOptionsCodec.encode(
                StalkerAdvancedOptions(
                    apiUserAgent = "API Agent/9.0",
                    playerUserAgent = "Player Agent/10.0"
                )
            ),
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = buildStalkerPlaybackDescriptor(
                primaryCmd = "ffmpeg http://portal.example.com/ch/390414_"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.userAgent).isEqualTo("Player Agent/10.0")
        assertThat(success.data.headers).doesNotContainKey("User-Agent")
    }

    private class FakeStalkerApiService(
        private val profile: StalkerProviderProfile,
        private val liveStreams: List<StalkerItemRecord> = emptyList(),
        private val createLinkUrl: String = "http://cdn.example.com/stream.ts",
        private val currentCookieHeader: String = "",
        private val liveCategoryResults: ArrayDeque<Result<List<StalkerCategoryRecord>>> = ArrayDeque(),
        private val seriesDetails: StalkerSeriesDetails? = null,
        private val vodCategories: List<StalkerCategoryRecord> = emptyList(),
        private val seriesCategories: List<StalkerCategoryRecord> = emptyList(),
        private val vodCategoriesResult: Result<List<StalkerCategoryRecord>>? = null,
        private val seriesCategoriesResult: Result<List<StalkerCategoryRecord>>? = null,
        private val vodPageItems: List<StalkerItemRecord> = emptyList(),
        private val seriesPageItems: List<StalkerItemRecord> = emptyList(),
        private var authenticationFailuresBeforeSuccess: Int = 0,
        private var authenticationError: Throwable? = null,
        private val shortEpgByChannel: Map<String, List<StalkerProgramRecord>> = emptyMap(),
        private val epgByChannel: Map<String, List<StalkerProgramRecord>> = emptyMap(),
        val shortEpgCalls: MutableList<String> = mutableListOf(),
        val epgCalls: MutableList<String> = mutableListOf()
    ) : StalkerApiService {
        var createLinkCalls: Int = 0
            private set
        var authenticateCalls: Int = 0
            private set
        var lastAuthenticateProfile: StalkerDeviceProfile? = null
            private set

        override suspend fun authenticate(profile: StalkerDeviceProfile): Result<Pair<StalkerSession, StalkerProviderProfile>> {
            authenticateCalls += 1
            lastAuthenticateProfile = profile
            if (authenticationFailuresBeforeSuccess > 0) {
                authenticationFailuresBeforeSuccess -= 1
                return Result.error("authentication failed")
            }
            return Result.success(
                StalkerSession(
                    loadUrl = "https://portal.example.com/server/load.php",
                    portalReferer = "https://portal.example.com/c/",
                    token = "token"
                ) to this.profile
            )
        }

        override suspend fun getLiveCategories(
            session: StalkerSession,
            profile: StalkerDeviceProfile
        ) = liveCategoryResults.removeFirstOrNull() ?: Result.success(emptyList<StalkerCategoryRecord>())

        override suspend fun getLiveStreams(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?
        ) = Result.success(liveStreams)

        override suspend fun streamLiveStreams(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            onItem: suspend (StalkerItemRecord) -> Unit
        ): Result<Int> {
            liveStreams.forEach { item -> onItem(item) }
            return Result.success(liveStreams.size)
        }

        override suspend fun getVodCategories(
            session: StalkerSession,
            profile: StalkerDeviceProfile
        ) = vodCategoriesResult ?: Result.success(vodCategories)

        override suspend fun getVodStreams(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?
        ) = Result.success(emptyList<StalkerItemRecord>())

        override suspend fun getVodStreamsPage(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?,
            page: Int
        ) = Result.success(StalkerPagedItems(vodPageItems, page, page, vodPageItems.size))

        override suspend fun getSeriesCategories(
            session: StalkerSession,
            profile: StalkerDeviceProfile
        ) = seriesCategoriesResult ?: Result.success(seriesCategories)

        override suspend fun getSeries(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?
        ) = Result.success(emptyList<StalkerItemRecord>())

        override suspend fun getSeriesPage(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            categoryId: String?,
            page: Int
        ) = Result.success(StalkerPagedItems(seriesPageItems, page, page, seriesPageItems.size))

        override suspend fun getSeriesDetails(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            seriesId: String
        ) = Result.success(
            seriesDetails ?: StalkerSeriesDetails(
                series = StalkerItemRecord(id = seriesId, name = "Series"),
                seasons = emptyList()
            )
        )

        override suspend fun getShortEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            channelId: String,
            limit: Int
        ): Result<List<StalkerProgramRecord>> {
            shortEpgCalls += channelId
            return Result.success(shortEpgByChannel[channelId].orEmpty())
        }

        override suspend fun getEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            channelId: String
        ): Result<List<StalkerProgramRecord>> {
            epgCalls += channelId
            return Result.success(epgByChannel[channelId].orEmpty())
        }

        override suspend fun getBulkEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            periodHours: Int
        ) = Result.success(emptyList<StalkerProgramRecord>())

        override suspend fun streamBulkEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            periodHours: Int,
            onProgram: suspend (StalkerProgramRecord) -> Unit
        ) = Result.success(0)

        override suspend fun streamEpg(
            session: StalkerSession,
            profile: StalkerDeviceProfile,
            channelId: String,
            periodHours: Int,
            onProgram: suspend (StalkerProgramRecord) -> Unit
        ) = Result.success(0)

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
            return Result.success(createLinkUrl)
        }

        override fun currentCookieHeader(session: StalkerSession): String = currentCookieHeader
    }

    private class FakePortalStateDao : StalkerPortalStateDao {
        private val rows = mutableMapOf<Long, StalkerPortalStateEntity>()

        override suspend fun get(providerId: Long): StalkerPortalStateEntity? = rows[providerId]

        override suspend fun upsert(entity: StalkerPortalStateEntity) {
            rows[entity.providerId] = entity
        }

        override suspend fun invalidate(providerId: Long): Int = if (rows.remove(providerId) != null) 1 else 0
    }

    @Test
    fun resolvePlaybackInfo_applies_accepted_http_only_to_the_granted_origin() = runTest {
        val grant = StalkerTransportGrant(
            mode = StalkerTransportMode.USER_ACCEPTED_HTTP,
            origin = StalkerTransportOrigin("http", "portal.example.com", 8080),
            consentedAt = 42L
        )
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                createLinkUrl = "http://portal.example.com:8080/live/stream.ts"
            ),
            portalUrl = "http://portal.example.com:8080/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG322",
            timezone = "Europe/Amsterdam",
            locale = "en",
            transportGrant = grant
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = checkNotNull(
                buildStalkerPlaybackDescriptor(
                    primaryCmd = "ffmpeg http://localhost/ch/1200_",
                    capabilities = StalkerPortalCapabilities(useHttpTemporaryLink = true)
                )
            )
        ) as Result.Success

        assertThat(result.data.allowInvalidSsl).isFalse()
        assertThat(result.data.transportPolicy?.mode)
            .isEqualTo(PlaybackTransportMode.USER_ACCEPTED_HTTP)
        assertThat(result.data.transportPolicy?.origin).isEqualTo(grant.origin)
    }

@Test
    fun resolvePlaybackInfo_plays_cleartext_cdn_stream_on_a_different_origin_than_the_portal_consent() = runTest {
        val provider = StalkerProvider(
            providerId = 7,
            api = FakeStalkerApiService(
                profile = StalkerProviderProfile(accountName = "Room"),
                createLinkUrl = "http://cdn.example.com/live/stream.ts"
            ),
            portalUrl = "http://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG322",
            timezone = "Europe/Amsterdam",
            locale = "en",
            transportGrant = StalkerTransportGrant(
                mode = StalkerTransportMode.USER_ACCEPTED_HTTP,
                origin = StalkerTransportOrigin("http", "portal.example.com", 80),
                consentedAt = 42L
            )
        )

        val result = provider.resolvePlaybackInfo(
            kind = StalkerStreamKind.LIVE,
            descriptor = checkNotNull(
                buildStalkerPlaybackDescriptor(
                    primaryCmd = "ffmpeg http://localhost/ch/1200_",
                    capabilities = StalkerPortalCapabilities(useHttpTemporaryLink = true)
                )
            )
        )

        // Cleartext IPTV streams commonly come from a different origin than the (often HTTPS)
        // portal, and the portal already authenticated the resolved URL via create_link, so a
        // matching portal consent is no longer required for playback.
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.url).isEqualTo("http://cdn.example.com/live/stream.ts")
    }

    @Test
    fun getShortEpg_request_prefers_numeric_portal_id_over_xmltv_key() = runTest {
        val api = FakeStalkerApiService(
            profile = StalkerProviderProfile(accountName = "Room"),
            shortEpgByChannel = mapOf(
                "66" to listOf(
                    StalkerProgramRecord(
                        id = "p1",
                        channelId = "66",
                        title = "Christ in Prophecy",
                        description = "Prophecy",
                        startTimeMillis = 1_788_573_600_000L,
                        endTimeMillis = 1_788_575_400_000L
                    )
                )
            )
        )
        val provider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "http://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG322",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.getShortEpg(
            com.streamvault.domain.provider.GuideRequest(streamId = 66L, epgChannelId = "daystar.us")
        ) as Result.Success

        assertThat(result.data.map { it.title }).containsExactly("Christ in Prophecy")
        assertThat(api.shortEpgCalls).containsExactly("66")
    }

    @Test
    fun getShortEpg_request_falls_back_to_xmltv_key_when_numeric_id_is_empty() = runTest {
        val api = FakeStalkerApiService(
            profile = StalkerProviderProfile(accountName = "Room"),
            shortEpgByChannel = mapOf(
                "daystar.us" to listOf(
                    StalkerProgramRecord(
                        id = "p1",
                        channelId = "daystar.us",
                        title = "Christ in Prophecy",
                        description = "Prophecy",
                        startTimeMillis = 1_788_573_600_000L,
                        endTimeMillis = 1_788_575_400_000L
                    )
                )
            )
        )
        val provider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "http://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG322",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.getShortEpg(
            com.streamvault.domain.provider.GuideRequest(streamId = 66L, epgChannelId = "daystar.us")
        ) as Result.Success

        assertThat(result.data.map { it.title }).containsExactly("Christ in Prophecy")
        assertThat(api.shortEpgCalls).containsExactly("66", "daystar.us")
    }

    @Test
    fun getEpg_request_uses_numeric_portal_id() = runTest {
        val api = FakeStalkerApiService(
            profile = StalkerProviderProfile(accountName = "Room"),
            epgByChannel = mapOf(
                "66" to listOf(
                    StalkerProgramRecord(
                        id = "p1",
                        channelId = "66",
                        title = "Evening News",
                        description = "News",
                        startTimeMillis = 1_788_573_600_000L,
                        endTimeMillis = 1_788_575_400_000L
                    )
                )
            )
        )
        val provider = StalkerProvider(
            providerId = 7,
            api = api,
            portalUrl = "http://portal.example.com/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG322",
            timezone = "UTC",
            locale = "en"
        )

        val result = provider.getEpg(
            com.streamvault.domain.provider.GuideRequest(streamId = 66L, epgChannelId = "daystar.us")
        ) as Result.Success

        assertThat(result.data.map { it.title }).containsExactly("Evening News")
        assertThat(api.epgCalls).containsExactly("66")
    }

    private object DirectTransactionRunner : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private class CountingTransactionRunner : DatabaseTransactionRunner {
        var transactionCount = 0
            private set

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            transactionCount += 1
            return block()
        }
    }

    private class FakeIdentityDao : StalkerRemoteIdentityDao {
        private val rows = mutableListOf<StalkerRemoteIdentityEntity>()

        override suspend fun getByRawId(providerId: Long, contentType: String, rawId: String) =
            rows.firstOrNull { row ->
                row.providerId == providerId && row.contentType.name == contentType && row.rawId == rawId
            }

        override suspend fun getBySurrogateId(providerId: Long, contentType: String, surrogateId: Long) =
            rows.firstOrNull { row ->
                row.providerId == providerId && row.contentType.name == contentType && row.surrogateId == surrogateId
            }

        override suspend fun maxAllocatedSurrogate(providerId: Long, contentType: String, floor: Long): Long? =
            rows.filter { row ->
                row.providerId == providerId && row.contentType.name == contentType && row.surrogateId >= floor
            }.maxOfOrNull(StalkerRemoteIdentityEntity::surrogateId)

        override suspend fun insert(entity: StalkerRemoteIdentityEntity) {
            check(rows.none { row ->
                row.providerId == entity.providerId && row.contentType == entity.contentType &&
                    (row.rawId == entity.rawId || row.surrogateId == entity.surrogateId)
            })
            rows += entity
        }
    }
}
