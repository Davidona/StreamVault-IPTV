package com.streamvault.data.remote.stremio

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.stremio.StremioCatalogQuery
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioResourceKind
import com.streamvault.domain.model.stremio.StremioStreamKind
import java.io.InputStreamReader
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class StremioAddonClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: StremioAddonClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = StremioAddonClient(
            okHttpClient = OkHttpClient(),
            json = Json {
                ignoreUnknownKeys = true
                isLenient = true
                explicitNulls = false
                coerceInputValues = true
            }
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `manifest parses resources catalogs and behavior hints`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("stremio/fixtures/cinemeta_manifest.json")))

        val result = client.fetchManifest(baseUrl())

        assertThat(result.isSuccess).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/manifest.json")
        val manifest = result.getOrNull()!!
        assertThat(manifest.id).isEqualTo("com.linvo.cinemeta")
        assertThat(manifest.name).isEqualTo("Cinemeta")
        assertThat(manifest.version).isEqualTo("3.0.14")
        assertThat(manifest.types)
            .containsExactly(StremioMediaType.MOVIE, StremioMediaType.SERIES)
            .inOrder()
        assertThat(manifest.resources.map { it.kind }).containsExactly(
            StremioResourceKind.CATALOG,
            StremioResourceKind.META,
            StremioResourceKind.STREAM,
            StremioResourceKind.SUBTITLES
        ).inOrder()
        val metaResource = manifest.resources.first { it.kind == StremioResourceKind.META }
        assertThat(metaResource.idPrefixes).containsExactly("tt")
        assertThat(metaResource.types)
            .containsExactly(StremioMediaType.MOVIE, StremioMediaType.SERIES)
        val catalogResource = manifest.resources.first { it.kind == StremioResourceKind.CATALOG }
        assertThat(catalogResource.types)
            .containsExactly(StremioMediaType.MOVIE, StremioMediaType.SERIES)
        assertThat(manifest.supportsResource(StremioResourceKind.STREAM, StremioMediaType.SERIES)).isTrue()
        assertThat(manifest.supportsResource(StremioResourceKind.STREAM, StremioMediaType.CHANNEL)).isFalse()
        assertThat(manifest.idPrefixes).containsExactly("tt")
        assertThat(manifest.behaviorHints.configurable).isFalse()
        assertThat(manifest.catalogs).hasSize(2)
        val movieCatalog = manifest.catalogs.first { it.type == StremioMediaType.MOVIE }
        assertThat(movieCatalog.id).isEqualTo("top")
        assertThat(movieCatalog.name).isEqualTo("Popular")
        assertThat(movieCatalog.genres).containsExactly("Action", "Comedy")
        assertThat(movieCatalog.supportsSearch).isTrue()
        assertThat(movieCatalog.supportsSkip).isTrue()
        assertThat(movieCatalog.supportsGenre).isTrue()
        assertThat(movieCatalog.extras.first { it.name == "search" }.isRequired).isFalse()
        assertThat(movieCatalog.extras.first { it.name == "genre" }.options)
            .containsExactly("Action", "Comedy", "Drama")
            .inOrder()
        val skipExtra = movieCatalog.extras.first { it.name == "skip" }
        assertThat(skipExtra.isRequired).isFalse()
        assertThat(skipExtra.options).isEmpty()
        val seriesCatalog = manifest.catalogs.first { it.type == StremioMediaType.SERIES }
        assertThat(seriesCatalog.extras.map { it.name }).containsExactly("search", "skip").inOrder()
    }

    @Test
    fun `catalog page parses metas and hasMore`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("stremio/fixtures/catalog_movies_top.json")))

        val result = client.fetchCatalog(baseUrl(), StremioMediaType.MOVIE, "top")

        assertThat(result.isSuccess).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/catalog/movie/top.json")
        val page = result.getOrNull()!!
        assertThat(page.hasMore).isTrue()
        assertThat(page.metas).hasSize(2)
        val first = page.metas.first()
        assertThat(first.id).isEqualTo("tt0111161")
        assertThat(first.name).isEqualTo("The Shawshank Redemption")
        assertThat(first.poster).isEqualTo("https://images.metahub.space/poster/medium/tt0111161/img")
        assertThat(first.releaseInfo).isEqualTo("1994")
        assertThat(first.imdbRating).isEqualTo("9.3")
        assertThat(first.genres).containsExactly("Drama")
    }

    @Test
    fun `catalog request encodes search genre and skip extras`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("stremio/fixtures/catalog_search.json")))

        val result = client.fetchCatalog(
            baseUrl(),
            StremioMediaType.MOVIE,
            "top",
            StremioCatalogQuery(search = "guardians of the galaxy", genre = "Sci-Fi & Fantasy", skip = 100)
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(server.takeRequest().path).isEqualTo(
            "/catalog/movie/top/search=guardians%20of%20the%20galaxy&genre=Sci-Fi%20%26%20Fantasy&skip=100.json"
        )
        assertThat(result.getOrNull()!!.metas.single().name).isEqualTo("Guardians of the Galaxy Vol. 2")
    }

    @Test
    fun `meta parses movie details`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("stremio/fixtures/meta_movie.json")))

        val result = client.fetchMeta(baseUrl(), StremioMediaType.MOVIE, "tt0111161")

        assertThat(result.isSuccess).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/meta/movie/tt0111161.json")
        val meta = result.getOrNull()!!
        assertThat(meta.name).isEqualTo("The Shawshank Redemption")
        assertThat(meta.director).containsExactly("Frank Darabont")
        assertThat(meta.cast).hasSize(3)
        assertThat(meta.genres).containsExactly("Drama", "Crime").inOrder()
        assertThat(meta.videos).isEmpty()
    }

    @Test
    fun `meta parses series videos`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("stremio/fixtures/meta_series.json")))

        val result = client.fetchMeta(baseUrl(), StremioMediaType.SERIES, "tt0903747")

        assertThat(result.isSuccess).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/meta/series/tt0903747.json")
        val meta = result.getOrNull()!!
        assertThat(meta.videos).hasSize(3)
        assertThat(meta.seasons()).containsExactly(1, 2).inOrder()
        assertThat(meta.episodes(1)).hasSize(2)
        assertThat(meta.episodes(1)[1].title).isEqualTo("Cat's in the Bag...")
        assertThat(meta.episodeVideoId(1, 2)).isEqualTo("tt0903747:1:2")
        assertThat(meta.director).containsExactly("Vince Gilligan")
    }

    @Test
    fun `streams parse direct torrent youtube and external kinds`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("stremio/fixtures/streams_movie.json")))

        val result = client.fetchStreams(baseUrl(), StremioMediaType.MOVIE, "tt0111161")

        assertThat(result.isSuccess).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/stream/movie/tt0111161.json")
        val streams = result.getOrNull()!!
        assertThat(streams).hasSize(4)
        assertThat(streams.map { it.kind }).containsExactly(
            StremioStreamKind.TORRENT,
            StremioStreamKind.DIRECT,
            StremioStreamKind.YOUTUBE,
            StremioStreamKind.EXTERNAL
        ).inOrder()
        val torrent = streams[0]
        assertThat(torrent.infoHash).isEqualTo("abcdef1234567890abcdef1234567890abcdef12")
        assertThat(torrent.fileIdx).isEqualTo(3)
        assertThat(torrent.sources).hasSize(2)
        assertThat(torrent.behaviorHints?.filename)
            .isEqualTo("The.Shawshank.Redemption.1994.1080p.BluRay.x264.mkv")
        assertThat(torrent.behaviorHints?.videoSize).isEqualTo(2198453013L)
        assertThat(torrent.behaviorHints?.bingeGroup).isEqualTo("torrentio-1080p")
        val direct = streams[1]
        assertThat(direct.url).isEqualTo("https://cdn.example.test/movies/shawshank/1080p.mp4")
        assertThat(direct.behaviorHints?.proxyHeaders?.request?.get("Authorization"))
            .isEqualTo("Bearer token123")
        assertThat(streams[2].ytId).isEqualTo("6hB3S9bIaco")
        assertThat(streams[3].externalUrl).isEqualTo("https://example.test/watch/tt0111161")
    }

    @Test
    fun `episode stream requests keep colon ids`() = runTest {
        server.enqueue(MockResponse().setBody("""{"streams":[]}"""))

        val result = client.fetchStreams(baseUrl(), StremioMediaType.SERIES, "tt0903747:1:2")

        assertThat(result.isSuccess).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/stream/series/tt0903747:1:2.json")
        assertThat(result.getOrNull()).isEmpty()
    }

    @Test
    fun `subtitles parse and request extras`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("stremio/fixtures/subtitles.json")))

        val result = client.fetchSubtitles(
            baseUrl(),
            StremioMediaType.MOVIE,
            "tt0111161",
            videoHash = "abc123",
            filename = "The Shawshank Redemption.mkv"
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(server.takeRequest().path).isEqualTo(
            "/subtitles/movie/tt0111161/videoHash=abc123&filename=The%20Shawshank%20Redemption.mkv.json"
        )
        val subtitles = result.getOrNull()!!
        assertThat(subtitles).hasSize(2)
        assertThat(subtitles.map { it.lang }).containsExactly("eng", "ger").inOrder()
    }

    @Test
    fun `http errors surface the status code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

        val result = client.fetchManifest(baseUrl())

        assertThat(result.isError).isTrue()
        assertThat(result.errorMessageOrNull()).contains("503")
    }

    @Test
    fun `unparseable responses surface a parse error`() = runTest {
        server.enqueue(MockResponse().setBody("{ not json"))

        val result = client.fetchManifest(baseUrl())

        assertThat(result.isError).isTrue()
        assertThat(result.errorMessageOrNull()).contains("parsed")
    }

    @Test
    fun `manifest without an id is rejected`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"version":"1.0.0","name":"No Id Addon","resources":["catalog"]}""")
        )

        val result = client.fetchManifest(baseUrl())

        assertThat(result.isError).isTrue()
        assertThat(result.errorMessageOrNull()).contains("id")
    }

    @Test
    fun `oversized responses are rejected`() = runTest {
        server.enqueue(
            MockResponse().setBody("x".repeat((StremioAddonClient.MAX_RESPONSE_BYTES + 1).toInt()))
        )

        val result = client.fetchManifest(baseUrl())

        assertThat(result.isError).isTrue()
        assertThat(result.errorMessageOrNull()).contains("too large")
    }

    @Test
    fun `invalid addon urls are rejected before any request`() = runTest {
        val result = client.fetchManifest("not a url")

        assertThat(result.isError).isTrue()
        assertThat(result.errorMessageOrNull()).contains("not a valid")
        assertThat(server.requestCount).isEqualTo(0)
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun fixture(path: String): String {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing fixture $path"
        }
        return stream.use { InputStreamReader(it).readText() }
    }
}
