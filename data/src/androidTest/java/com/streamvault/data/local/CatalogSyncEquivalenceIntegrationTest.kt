package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.sync.SyncCatalogStore
import com.streamvault.data.sync.SyncManagerM3uImporter
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Verifies that full and section-repair activation converge through the production Room DAOs. */
@RunWith(AndroidJUnit4::class)
class CatalogSyncEquivalenceIntegrationTest {
    private lateinit var db: StreamVaultDatabase
    private lateinit var store: SyncCatalogStore

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).build()
        store = SyncCatalogStore(
            channelDao = db.channelDao(),
            movieDao = db.movieDao(),
            seriesDao = db.seriesDao(),
            categoryDao = db.categoryDao(),
            catalogSyncDao = db.catalogSyncDao(),
            tmdbIdentityDao = db.tmdbIdentityDao(),
            transactionRunner = RoomDatabaseTransactionRunner(db)
        )
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun fullImportAndSectionRepairsConvergeToEquivalentActiveCatalogs() = runTest {
        val fullProviderId = insertProvider("Full")
        val repairProviderId = insertProvider("Repair")
        seedStaleCatalog(fullProviderId)
        seedStaleCatalog(repairProviderId)

        val importer = playlistImporter()
        importer.importPlaylist(provider(fullProviderId), onProgress = null)
        importer.importPlaylist(
            provider(repairProviderId),
            onProgress = null,
            includeLive = true,
            includeMovies = false
        )
        importer.importPlaylist(
            provider(repairProviderId),
            onProgress = null,
            includeLive = false,
            includeMovies = true
        )

        assertThat(channelSnapshot(fullProviderId)).isEqualTo(channelSnapshot(repairProviderId))
        assertThat(movieSnapshot(fullProviderId)).isEqualTo(movieSnapshot(repairProviderId))
        assertThat(categorySnapshot(fullProviderId)).isEqualTo(categorySnapshot(repairProviderId))
        assertThat(db.channelDao().getByProviderSync(fullProviderId).map { it.streamId }).doesNotContain(999L)
        assertThat(db.movieDao().getByProviderSync(fullProviderId).map { it.streamId }).doesNotContain(999L)
    }

    @Test
    fun liveIndexProjectionPreservesFieldsAndScopesReplacementToOneProvider() = runTest {
        val providerId = insertProvider("Projection")
        val otherId = insertProvider("Other")
        store.replaceLiveCatalog(providerId, emptyList(), listOf(
            ChannelEntity(streamId = 11, name = "News", providerId = providerId,
                groupTitle = "Fallback", categoryId = 8, logoUrl = "https://example.test/logo", isAdult = true)
        ))
        store.replaceLiveCatalog(otherId, emptyList(), listOf(
            ChannelEntity(streamId = 12, name = "Other", providerId = otherId)
        ))
        val dao = db.xtreamContentIndexDao()
        assertThat(dao.replaceLiveIndexFromChannels(otherId, 100)).isEqualTo(1)
        assertThat(dao.replaceLiveIndexFromChannels(providerId, 200)).isEqualTo(1)
        val channel = db.channelDao().getByProviderSync(providerId).single()
        val row = requireNotNull(dao.getByRemoteId(providerId, "LIVE", "11"))
        assertThat(row.localContentId).isEqualTo(channel.id)
        assertThat(row.name).isEqualTo(channel.name)
        assertThat(row.categoryId).isEqualTo(8L)
        assertThat(row.categoryName).isEqualTo("Fallback")
        assertThat(row.imageUrl).isEqualTo(channel.logoUrl)
        assertThat(row.isAdult).isTrue()
        assertThat(row.indexedAt).isEqualTo(200L)
        assertThat(row.detailHydratedAt).isEqualTo(200L)
        assertThat(row.syncFingerprint).isEqualTo(channel.syncFingerprint)
        assertThat(dao.getByRemoteId(otherId, "LIVE", "12")?.indexedAt).isEqualTo(100L)
        db.channelDao().deleteByProvider(providerId)
        assertThat(dao.replaceLiveIndexFromChannels(providerId, 300)).isEqualTo(0)
        assertThat(dao.getByRemoteId(providerId, "LIVE", "11")).isNull()
        assertThat(dao.getByRemoteId(otherId, "LIVE", "12")).isNotNull()
    }

    private suspend fun insertProvider(name: String): Long = db.providerDao().insert(
        ProviderEntity(name = name, type = ProviderType.M3U)
    )

    private suspend fun seedStaleCatalog(providerId: Long) {
        store.replaceLiveCatalog(
            providerId,
            categories = listOf(CategoryEntity(categoryId = 99L, name = "Stale", providerId = providerId)),
            channels = listOf(ChannelEntity(streamId = 999L, name = "Stale", providerId = providerId))
        )
        store.replaceMovieCatalog(
            providerId,
            categories = listOf(
                CategoryEntity(
                    categoryId = 99L,
                    name = "Stale",
                    type = ContentType.MOVIE,
                    providerId = providerId
                )
            ),
            movies = sequenceOf(MovieEntity(streamId = 999L, name = "Stale", providerId = providerId))
        )
    }

    private fun playlistImporter(): SyncManagerM3uImporter {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(PLAYLIST.toResponseBody("application/x-mpegURL".toMediaType()))
                .build()
        }.build()
        return SyncManagerM3uImporter(
            m3uParser = M3uParser(),
            okHttpClient = client,
            syncCatalogStore = store,
            retryTransient = { block -> block() },
            progress = { _, _, _ -> },
            emitProgress = { _, _ -> }
        )
    }

    private fun provider(providerId: Long) = LegacyProvider(
        id = providerId,
        name = "Provider $providerId",
        type = ProviderType.M3U,
        serverUrl = PLAYLIST_URL,
        m3uUrl = PLAYLIST_URL,
        m3uVodClassificationEnabled = true
    )

    private suspend fun channelSnapshot(providerId: Long) = db.channelDao().getByProviderSync(providerId)
        .sortedBy { it.streamUrl }
        // Synthetic M3U identities deliberately include the provider ID.
        .map { it.copy(id = 0L, streamId = 0L, categoryId = null, providerId = 0L, syncFingerprint = "") }

    private suspend fun movieSnapshot(providerId: Long) = db.movieDao().getByProviderSync(providerId)
        .sortedBy { it.streamUrl }
        .map { it.copy(id = 0L, streamId = 0L, categoryId = null, providerId = 0L, syncFingerprint = "") }

    private suspend fun categorySnapshot(providerId: Long) = ContentType.entries
        .flatMap { type -> db.categoryDao().getByProviderAndTypeSync(providerId, type.name) }
        .sortedWith(compareBy(CategoryEntity::type, CategoryEntity::name))
        .map { it.copy(id = 0L, categoryId = 0L, providerId = 0L, syncFingerprint = "") }

    private companion object {
        const val PLAYLIST_URL = "https://catalog.example.test/playlist.m3u"
        val PLAYLIST = """
            #EXTM3U
            #EXTINF:-1 tvg-id="news" group-title="Live",News
            https://stream.example.test/live/10.m3u8
            #EXTINF:-1 tvg-id="sports" group-title="Live",Sports
            https://stream.example.test/live/20.m3u8
            #EXTINF:-1 group-title="Movies",Feature
            https://stream.example.test/movie/30.mp4
        """.trimIndent()
    }
}
