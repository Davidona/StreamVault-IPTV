package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.StremioAddonDao
import com.streamvault.data.local.entity.StremioAddonEntity
import com.streamvault.data.remote.stremio.StremioAddonClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
            )
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
    fun `manifest failures surface the addon error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = repository.installAddon("${baseUrl()}/broken")

        assertThat(result.isError).isTrue()
        assertThat(result.errorMessageOrNull()).contains("500")
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
