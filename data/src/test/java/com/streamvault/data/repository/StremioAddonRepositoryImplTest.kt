package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.StremioAddonDao
import com.streamvault.data.local.entity.StremioAddonEntity
import com.streamvault.data.remote.stremio.StremioAddonClient
import com.streamvault.domain.model.stremio.StremioCatalogQuery
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.repository.StremioResourceResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

class StremioAddonRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var dao: FakeStremioAddonDao
    private lateinit var repository: StremioAddonRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dao = FakeStremioAddonDao()
        repository = StremioAddonRepositoryImpl(
            dao = dao,
            client = StremioAddonClient(
                okHttpClient = OkHttpClient(),
                json = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    explicitNulls = false
                    coerceInputValues = true
                }
            ),
            resourceResolver = StremioResourceResolver()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `install stores the normalized manifest snapshot`() = runTest {
        server.enqueue(manifestResponse("com.linvo.cinemeta", "Cinemeta"))
        val baseUrl = "${baseUrl()}/addon-a"

        val result = repository.installAddon(baseUrl)

        assertThat(result.isSuccess).isTrue()
        val addon = result.getOrNull()!!
        assertThat(addon.installUrl).isEqualTo("$baseUrl/manifest.json")
        assertThat(addon.manifestId).isEqualTo("com.linvo.cinemeta")
        assertThat(addon.name).isEqualTo("Cinemeta")
        assertThat(addon.version).isEqualTo("1.0.0")
        assertThat(addon.enabled).isTrue()
        assertThat(addon.sortOrder).isEqualTo(0)
        assertThat(addon.manifestJson).contains("com.linvo.cinemeta")
        assertThat(addon.isCatalogEnabled("movie:top")).isTrue()
        assertThat(dao.getAll()).hasSize(1)
    }

    @Test
    fun `duplicate installs are rejected`() = runTest {
        server.enqueue(manifestResponse("com.linvo.cinemeta", "Cinemeta"))
        server.enqueue(manifestResponse("com.linvo.cinemeta", "Cinemeta"))
        val baseUrl = "${baseUrl()}/addon-a"

        assertThat(repository.installAddon(baseUrl).isSuccess).isTrue()
        val duplicate = repository.installAddon(baseUrl)

        assertThat(duplicate.isError).isTrue()
        assertThat(duplicate.errorMessageOrNull()).contains("already installed")
        assertThat(dao.getAll()).hasSize(1)
    }

    @Test
    fun `installs append in sort order`() = runTest {
        server.enqueue(manifestResponse("com.one", "One"))
        server.enqueue(manifestResponse("com.two", "Two"))

        repository.installAddon("${baseUrl()}/one")
        repository.installAddon("${baseUrl()}/two")

        val addons = dao.getAll()
        assertThat(addons.map { it.sortOrder }).containsExactly(0, 1).inOrder()
        assertThat(addons.map { it.name }).containsExactly("One", "Two").inOrder()
    }

    @Test
    fun `refresh updates manifest while preserving install choices and ordering`() = runTest {
        server.enqueue(manifestResponse("com.one", "One"))
        val installed = repository.installAddon("${baseUrl()}/one").getOrNull()!!
        dao.setEnabled(installed.id, false, 250)
        dao.setSortOrder(installed.id, 4, 250)
        dao.setCatalogSettings(installed.id, "{\"movie:top\":false}", 250)
        server.enqueue(manifestResponse("com.one", "Renamed One"))

        val refreshed = repository.refreshAddon(installed.id).getOrNull()!!

        assertThat(refreshed.id).isEqualTo(installed.id)
        assertThat(refreshed.installUrl).isEqualTo(installed.installUrl)
        assertThat(refreshed.name).isEqualTo("Renamed One")
        assertThat(refreshed.enabled).isFalse()
        assertThat(refreshed.sortOrder).isEqualTo(4)
        assertThat(refreshed.isCatalogEnabled("movie:top")).isFalse()
        assertThat(refreshed.createdAt).isEqualTo(installed.createdAt)
    }

    @Test
    fun `refresh failure keeps the saved manifest`() = runTest {
        server.enqueue(manifestResponse("com.one", "One"))
        val installed = repository.installAddon("${baseUrl()}/one").getOrNull()!!
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporary outage"))

        val refreshed = repository.refreshAddon(installed.id)

        assertThat(refreshed.isError).isTrue()
        assertThat(dao.getById(installed.id)?.manifestJson).isEqualTo(installed.manifestJson)
        assertThat(dao.getById(installed.id)?.name).isEqualTo(installed.name)
    }

    @Test
    fun `manifest failures surface the addon error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = repository.installAddon("${baseUrl()}/broken")

        assertThat(result.isError).isTrue()
        assertThat(result.errorMessageOrNull()).contains("500")
    }

    @Test
    fun `catalog fetch sends zero skip when supported`() = runTest {
        server.enqueue(manifestResponse("com.one", "One"))
        val addon = repository.installAddon("${baseUrl()}/one").getOrNull()!!
        server.enqueue(MockResponse().setBody("""{"metas":[]}"""))

        val result = repository.fetchCatalog(
            addonId = addon.id,
            catalog = repository.getManifest(addon.id)!!.catalogs.single(),
            query = StremioCatalogQuery(skip = 0)
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/one/manifest.json")
        assertThat(server.takeRequest().path).isEqualTo("/one/catalog/movie/top/skip=0.json")
    }

    @Test
    fun `cross addon resolution keeps catalog origin and partial stream results`() = runTest {
        server.enqueue(
            manifestWithResources(
                "catalog",
                "Catalog",
                "\"catalog\",{\"name\":\"meta\",\"types\":[\"movie\"],\"idPrefixes\":[\"tt\"]}",
                catalog = true
            )
        )
        server.enqueue(
            manifestWithResources(
                "streamer",
                "Streamer",
                "{\"name\":\"stream\",\"types\":[\"movie\"],\"idPrefixes\":[\"tt\"]}"
            )
        )
        server.enqueue(
            manifestWithResources(
                "broken-streamer",
                "Broken Streamer",
                "{\"name\":\"stream\",\"types\":[\"movie\"],\"idPrefixes\":[\"tt\"]}"
            )
        )
        server.enqueue(
            manifestWithResources(
                "unrelated",
                "Unrelated",
                "{\"name\":\"stream\",\"types\":[\"movie\"],\"idPrefixes\":[\"kitsu\"]}"
            )
        )
        server.enqueue(
            manifestWithResources(
                "disabled",
                "Disabled",
                "{\"name\":\"stream\",\"types\":[\"movie\"],\"idPrefixes\":[\"tt\"]}"
            )
        )

        val catalog = repository.installAddon("${baseUrl()}/catalog").getOrNull()!!
        val streamer = repository.installAddon("${baseUrl()}/streamer").getOrNull()!!
        val brokenStreamer = repository.installAddon("${baseUrl()}/broken-streamer").getOrNull()!!
        repository.installAddon("${baseUrl()}/unrelated")
        val disabled = repository.installAddon("${baseUrl()}/disabled").getOrNull()!!
        repository.setAddonEnabled(disabled.id, false)
        repository.setCatalogEnabled(catalog.id, "movie:top", false)

        val requestPaths = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestPaths += request.path
                return when (request.path) {
                    "/catalog/meta/movie/tt123.json" -> MockResponse().setBody(
                        """{"meta":{"id":"tt123","type":"movie","name":"Catalog metadata"}}"""
                    )
                    "/streamer/stream/movie/tt123.json" -> MockResponse().setBody(
                        """{"streams":[{"name":"Direct","url":"https://media.example/video.mp4"}]}"""
                    )
                    "/broken-streamer/stream/movie/tt123.json" -> MockResponse().setResponseCode(503)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val metadata = repository.resolveMeta(catalog.id, StremioMediaType.MOVIE, "tt123")
        val streams = repository.resolveStreams(StremioMediaType.MOVIE, "tt123")

        assertThat(metadata.result?.source?.addonId).isEqualTo(catalog.id)
        assertThat(metadata.result?.resource?.name).isEqualTo("Catalog metadata")
        assertThat(streams.candidateCount).isEqualTo(2)
        assertThat(streams.results.map { it.source.addonId }).containsExactly(streamer.id)
        assertThat(streams.failures.map { it.source.addonId }).containsExactly(brokenStreamer.id)
        assertThat(requestPaths).containsExactly(
            "/catalog/meta/movie/tt123.json",
            "/streamer/stream/movie/tt123.json",
            "/broken-streamer/stream/movie/tt123.json"
        )
    }

    @Test
    fun `subtitle routing uses the exact episode id and video extras`() = runTest {
        server.enqueue(
            manifestWithResources(
                "subtitle-addon",
                "Subtitle Addon",
                "{\"name\":\"subtitles\",\"types\":[\"series\"],\"idPrefixes\":[\"series:\"]}"
            )
        )
        val subtitleAddon = repository.installAddon("${baseUrl()}/subtitle-addon").getOrNull()!!
        server.takeRequest() // Consume the install request before inspecting the subtitle endpoint.
        val exactVideoId = "series:custom:episode-04"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(
                """{"subtitles":[{"id":"eng-1","url":"https://subs.example/episode.vtt","lang":"eng"}]}"""
            )
        }

        val result = repository.resolveSubtitles(
            type = StremioMediaType.SERIES,
            exactVideoId = exactVideoId,
            videoHash = "hash-1",
            videoSize = 99L,
            filename = "Episode One.mkv"
        )

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo(
            "/subtitle-addon/subtitles/series/series:custom:episode-04/videoHash=hash-1&videoSize=99&filename=Episode%20One.mkv.json"
        )
        assertThat(result.results.single().source.addonId).isEqualTo(subtitleAddon.id)
        assertThat(result.results.single().resource.lang).isEqualTo("eng")
    }

    @Test
    fun `catalog fetch rejects missing required extras before network request`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "id":"com.search.only","version":"1","name":"Search Only",
                  "resources":["catalog"],"types":["movie"],
                  "catalogs":[{"type":"movie","id":"search","extra":[{"name":"search","isRequired":true}]}]
                }
                """.trimIndent()
            )
        )
        val addon = repository.installAddon("${baseUrl()}/search").getOrNull()!!
        val catalog = repository.getManifest(addon.id)!!.catalogs.single()

        val result = repository.fetchCatalog(addon.id, catalog, StremioCatalogQuery())

        assertThat(result.isError).isTrue()
        assertThat(result.errorMessageOrNull()).contains("Enter a search")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `catalog fetch rejects unsupported required extras before network request`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "id":"com.date.only","version":"1","name":"Date Only",
                  "resources":["catalog"],"types":["movie"],
                  "catalogs":[{"type":"movie","id":"date","extra":[{"name":"date","isRequired":true}]}]
                }
                """.trimIndent()
            )
        )
        val addon = repository.installAddon("${baseUrl()}/date").getOrNull()!!
        val catalog = repository.getManifest(addon.id)!!.catalogs.single()

        val result = repository.fetchCatalog(addon.id, catalog, StremioCatalogQuery())

        assertThat(result.isError).isTrue()
        assertThat(result.errorMessageOrNull()).contains("unsupported 'date'")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `disabled addons cannot fetch catalogs`() = runTest {
        server.enqueue(manifestResponse("com.one", "One"))
        val addon = repository.installAddon("${baseUrl()}/one").getOrNull()!!
        val catalog = repository.getManifest(addon.id)!!.catalogs.single()
        repository.setAddonEnabled(addon.id, false)

        val result = repository.fetchCatalog(
            addon.id,
            catalog,
            StremioCatalogQuery(search = "film", skip = 0)
        )

        assertThat(result.isError).isTrue()
        assertThat(result.errorMessageOrNull()).contains("disabled")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `manifest without an id is rejected`() = runTest {
        server.enqueue(MockResponse().setBody("""{"version":"1.0.0","name":"No Id","resources":["catalog"]}"""))

        val result = repository.installAddon("${baseUrl()}/no-id")

        assertThat(result.isError).isTrue()
        assertThat(result.errorMessageOrNull()).contains("id")
        assertThat(dao.getAll()).isEmpty()
    }

    @Test
    fun `addons that require configuration are rejected`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "id": "com.configured.addon",
                  "version": "1.0.0",
                  "name": "Configured Addon",
                  "resources": ["stream"],
                  "behaviorHints": {"configurationRequired": true}
                }
                """.trimIndent()
            )
        )

        val result = repository.installAddon("${baseUrl()}/configured")

        assertThat(result.isError).isTrue()
        assertThat(result.errorMessageOrNull()).contains("requires configuration")
        assertThat(dao.getAll()).isEmpty()
    }

    @Test
    fun `invalid install urls never reach the network`() = runTest {
        val result = repository.installAddon("not a url")

        assertThat(result.isError).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `enable remove and catalog settings persist`() = runTest {
        server.enqueue(manifestResponse("com.one", "One"))
        val addon = repository.installAddon("${baseUrl()}/one").getOrNull()!!

        assertThat(repository.setAddonEnabled(addon.id, false).isSuccess).isTrue()
        assertThat(dao.getById(addon.id)!!.enabled).isFalse()

        assertThat(repository.setCatalogEnabled(addon.id, "movie:top", false).isSuccess).isTrue()
        val updated = repository.getAddon(addon.id)!!
        assertThat(updated.isCatalogEnabled("movie:top")).isFalse()
        assertThat(updated.isCatalogEnabled("series:top")).isTrue()

        assertThat(repository.setCatalogEnabled(addon.id, "movie:top", true).isSuccess).isTrue()
        assertThat(repository.getAddon(addon.id)!!.isCatalogEnabled("movie:top")).isTrue()

        assertThat(repository.removeAddon(addon.id).isSuccess).isTrue()
        assertThat(repository.getAddon(addon.id)).isNull()
    }

    @Test
    fun `move reorders addons by offset and clamps at the edges`() = runTest {
        server.enqueue(manifestResponse("com.one", "One"))
        server.enqueue(manifestResponse("com.two", "Two"))
        server.enqueue(manifestResponse("com.three", "Three"))
        val one = repository.installAddon("${baseUrl()}/one").getOrNull()!!
        repository.installAddon("${baseUrl()}/two")
        repository.installAddon("${baseUrl()}/three")

        assertThat(repository.moveAddon(one.id, 1).isSuccess).isTrue()
        assertThat(dao.getAll().map { it.name }).containsExactly("Two", "One", "Three").inOrder()
        assertThat(dao.getAll().map { it.sortOrder }).containsExactly(0, 1, 2).inOrder()

        assertThat(repository.moveAddon(one.id, -5).isSuccess).isTrue()
        assertThat(dao.getAll().map { it.name }).containsExactly("One", "Two", "Three").inOrder()

        assertThat(repository.moveAddon(9999L, 1).isError).isTrue()
    }

    @Test
    fun `catalog settings codec ignores junk and round-trips`() {
        assertThat(StremioCatalogSettingsCodec.decode("")).isEmpty()
        assertThat(StremioCatalogSettingsCodec.decode("not json")).isEmpty()
        assertThat(StremioCatalogSettingsCodec.encode(emptyMap())).isEmpty()
        val encoded = StremioCatalogSettingsCodec.encode(mapOf("movie:top" to false, "series:top" to true))
        assertThat(StremioCatalogSettingsCodec.decode(encoded))
            .containsExactly("movie:top", false, "series:top", true)
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun manifestResponse(id: String, name: String): MockResponse =
        MockResponse().setBody(
            """
            {
              "id": "$id",
              "version": "1.0.0",
              "name": "$name",
              "resources": ["catalog", "meta", "stream"],
              "types": ["movie", "series"],
              "catalogs": [
                {"type": "movie", "id": "top", "name": "Popular", "extra": ["search", "skip"]}
              ]
            }
            """.trimIndent()
        )

    private fun manifestWithResources(
        id: String,
        name: String,
        resourcesJson: String,
        catalog: Boolean = false
    ): MockResponse = MockResponse().setBody(
        """
        {
          "id":"$id",
          "name":"$name",
          "version":"1.0.0",
          "types":["movie","series"],
          "resources":[$resourcesJson],
          "catalogs":${if (catalog) "[{\"type\":\"movie\",\"id\":\"top\",\"name\":\"Popular\"}]" else "[]"}
        }
        """.trimIndent()
    )
}

private class FakeStremioAddonDao : StremioAddonDao {
    private val addons = MutableStateFlow<List<StremioAddonEntity>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<StremioAddonEntity>> = addons.asStateFlow()

    override suspend fun getAll(): List<StremioAddonEntity> =
        addons.value.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun getById(id: Long): StremioAddonEntity? =
        addons.value.firstOrNull { it.id == id }

    override suspend fun getByInstallUrl(installUrl: String): StremioAddonEntity? =
        addons.value.firstOrNull { it.installUrl == installUrl }

    override suspend fun maxSortOrder(): Int = addons.value.maxOfOrNull { it.sortOrder } ?: -1

    override suspend fun insert(entity: StremioAddonEntity): Long {
        val id = nextId++
        addons.value = addons.value + entity.copy(id = id)
        return id
    }

    override suspend fun updateManifest(
        id: Long,
        manifestId: String,
        name: String,
        version: String,
        manifestJson: String,
        updatedAt: Long
    ) {
        addons.value = addons.value.map { entity ->
            if (entity.id == id) entity.copy(
                manifestId = manifestId,
                name = name,
                version = version,
                manifestJson = manifestJson,
                updatedAt = updatedAt
            ) else entity
        }
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long) {
        addons.value = addons.value.map {
            if (it.id == id) it.copy(enabled = enabled, updatedAt = updatedAt) else it
        }
    }

    override suspend fun setSortOrder(id: Long, sortOrder: Int, updatedAt: Long) {
        addons.value = addons.value.map {
            if (it.id == id) it.copy(sortOrder = sortOrder, updatedAt = updatedAt) else it
        }
    }

    override suspend fun setCatalogSettings(id: Long, catalogSettingsJson: String, updatedAt: Long) {
        addons.value = addons.value.map {
            if (it.id == id) it.copy(catalogSettingsJson = catalogSettingsJson, updatedAt = updatedAt) else it
        }
    }

    override suspend fun delete(id: Long) {
        addons.value = addons.value.filterNot { it.id == id }
    }

    override suspend fun applyOrder(positions: List<Pair<Long, Int>>, updatedAt: Long) {
        val byId = positions.toMap()
        addons.value = addons.value.map { entity ->
            byId[entity.id]?.let { position -> entity.copy(sortOrder = position, updatedAt = updatedAt) }
                ?: entity
        }
    }
}
