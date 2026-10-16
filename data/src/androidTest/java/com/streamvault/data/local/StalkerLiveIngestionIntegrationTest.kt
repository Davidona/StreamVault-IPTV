package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.remote.stalker.*
import com.streamvault.data.sync.FallbackCategoryCollector
import com.streamvault.data.sync.SyncCatalogStore
import com.streamvault.data.sync.SyncManagerCatalogStager
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StalkerLiveIngestionIntegrationTest {
    @Test fun decodedPipelineRowsPromoteWithStableIdsAndSearch() = runTest {
        withFixture { db, provider, import, setPayload ->
            setPayload(payload(1..250))
            assertThat(import()).isInstanceOf(Result.Success::class.java)
            val original = db.channelDao().getByProviderSync(provider.providerId).first { it.streamId == 2L }
            db.favoriteDao().insert(FavoriteEntity(providerId = provider.providerId, contentId = original.id, contentType = ContentType.LIVE))
            assertThat(original.catchUpSupported).isTrue()
            assertThat(original.epgChannelId).isEqualTo("guide.2")
            setPayload(payload(2..251, renamed = true))
            assertThat(import()).isInstanceOf(Result.Success::class.java)
            val rows = db.channelDao().getByProviderSync(provider.providerId)
            assertThat(rows).hasSize(250)
            assertThat(rows.any { it.streamId == 1L }).isFalse()
            val refreshed = rows.first { it.streamId == 2L }
            assertThat(refreshed.id).isEqualTo(original.id)
            assertThat(refreshed.name).isEqualTo("Renamedtoken")
            assertThat(refreshed.streamUrl).isEqualTo(original.streamUrl)
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM channels_fts WHERE channels_fts MATCH 'renamedtoken'").use {
                it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(1)
            }
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM favorites WHERE content_id = ?", arrayOf(original.id)).use {
                it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(1)
            }
        }
    }

    @Test fun trailingOrTruncatedDocumentCannotReplaceVerifiedCatalog() = runTest {
        withFixture { db, provider, import, setPayload ->
            setPayload(payload(1..250))
            assertThat(import()).isInstanceOf(Result.Success::class.java)
            val original = db.channelDao().getByProviderSync(provider.providerId)
            for (broken in listOf(payload(300..550) + " {}", payload(300..550).dropLast(3))) {
                setPayload(broken)
                assertThat(import()).isInstanceOf(Result.Error::class.java)
                assertThat(db.channelDao().getByProviderSync(provider.providerId)).isEqualTo(original)
                db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM channel_import_stage").use {
                    it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(0)
                }
            }
        }
    }

    @Test fun partialCategoryCoveragePrunesOnlyCompletedCategoryRows() = runTest {
        withFixture { db, provider, _, _ ->
            val runner = RoomDatabaseTransactionRunner(db)
            val store = SyncCatalogStore(
                db.channelDao(), db.movieDao(), db.seriesDao(), db.categoryDao(),
                db.catalogSyncDao(), db.tmdbIdentityDao(), runner
            )
            val categories = listOf(
                CategoryEntity(categoryId = 10L, name = "News", parentId = 0, type = ContentType.LIVE, providerId = provider.providerId),
                CategoryEntity(categoryId = 20L, name = "Sports", parentId = 0, type = ContentType.LIVE, providerId = provider.providerId)
            )
            val existing = listOf(
                Channel(id = 1L, streamId = 1L, providerId = provider.providerId, name = "News 1", categoryId = 10L),
                Channel(id = 4L, streamId = 4L, providerId = provider.providerId, name = "News stale", categoryId = 10L),
                Channel(id = 2L, streamId = 2L, providerId = provider.providerId, name = "Sports verified", categoryId = 20L)
            ).map(Channel::toEntity)
            store.replaceLiveCatalog(provider.providerId, categories, existing)

            val sessionId = store.newSessionId()
            store.stageChannelBatch(
                provider.providerId,
                sessionId,
                listOf(Channel(
                    id = 1L,
                    streamId = 1L,
                    providerId = provider.providerId,
                    name = "News refreshed",
                    categoryId = 10L
                ).toEntity())
            )
            store.applyStagedLiveCatalogWithCoverage(
                providerId = provider.providerId,
                sessionId = sessionId,
                categories = categories,
                completeCategoryIds = setOf(10L),
                pruneCategories = false
            )

            val rows = db.channelDao().getByProviderSync(provider.providerId).associateBy { it.streamId }
            assertThat(rows.keys).containsExactly(1L, 2L)
            assertThat(rows.getValue(1L).name).isEqualTo("News refreshed")
            assertThat(rows.getValue(2L).name).isEqualTo("Sports verified")
        }
    }

    @Test fun categoryFallbackStreamsValidatedPagesInBoundedBatches() = runTest {
        withFixture { _, provider, _, setPayload ->
            setPayload(payload(1..250))
            assertThat(provider.getLiveCategories()).isInstanceOf(Result.Success::class.java)
            val batches = mutableListOf<List<Channel>>()
            val result = provider.streamLiveCategory(
                categoryId = 10L,
                batchRows = 50,
                batchBytes = 256L * 1024L
            ) { channels -> batches += channels }

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat((result as Result.Success).data).isEqualTo(250)
            assertThat(batches.sumOf { it.size }).isEqualTo(250)
            assertThat(batches).isNotEmpty()
            assertThat(batches.maxOf { it.size }).isAtMost(50)
        }
    }

    private fun payload(ids: IntRange, renamed: Boolean = false): String =
        """{"js":{"data":[""" + ids.joinToString(",") { id ->
            """{"id":"$id","name":"${if (renamed && id == 2) "Renamedtoken" else "Channel $id"}","tv_genre_id":"10","xmltv_id":"guide.$id","archive":true,"cmd":"ffmpeg http://localhost/ch/${id}_","cmd_1":"https://cdn.example/$id.m3u8","use_http_tmp_link":1,"unknown":{"ignored":[1,2,3]}}"""
        } + "]}}"

    private suspend fun withFixture(
        block: suspend (StreamVaultDatabase, StalkerProvider, suspend () -> Result<Int>, (String) -> Unit) -> Unit
    ) {
        StalkerProvider.clearSharedAuthCacheForTests()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).build()
        try {
            val id = db.providerDao().insert(ProviderEntity(name = "Fixture", type = ProviderType.STALKER_PORTAL))
            var body = ""
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val requestUrl = chain.request().url
                val responseBody = when {
                    requestUrl.queryParameter("action") == "get_genres" ->
                        """{"js":[{"id":"10","title":"News"}]}"""
                    requestUrl.queryParameter("action") == "get_ordered_list" &&
                        requestUrl.queryParameter("p") != "1" -> """{"js":{"data":[]}}"""
                    else -> body
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val service = OkHttpStalkerApiService(client, Json { ignoreUnknownKeys = true })
            val api = object : StalkerApiService by service {
                override suspend fun authenticate(profile: StalkerDeviceProfile) = Result.success(
                    StalkerSession("https://portal.example/server/load.php", "https://portal.example/c/", "token") to
                        StalkerProviderProfile(accountName = "Fixture")
                )
            }
            val runner = RoomDatabaseTransactionRunner(db)
            val provider = StalkerProvider(id, api, "https://portal.example/c/", "00:1A:79:12:34:56",
                deviceProfile = "MAG250", timezone = "UTC", locale = "en",
                identityResolver = StalkerRemoteIdentityResolver(db.stalkerRemoteIdentityDao(), runner, db.categoryDao()))
            val categories = (provider.getLiveCategories() as Result.Success).data.map { it.toEntity(id) }
            val store = SyncCatalogStore(db.channelDao(), db.movieDao(), db.seriesDao(), db.categoryDao(), db.catalogSyncDao(), db.tmdbIdentityDao(), runner)
            val stager = SyncManagerCatalogStager(store, 25)
            suspend fun import(): Result<Int> {
                var session: Long? = null
                var collector = FallbackCategoryCollector(id, ContentType.LIVE)
                val seen = HashSet<Long>()
                val batch = ArrayList<Channel>()
                suspend fun flush() {
                    if (batch.isEmpty()) return
                    val staged = stager.stageChannelItems(id, batch, seen, collector, session, useLiveAdmissionCount = true)
                    session = staged.sessionId
                    batch.clear()
                }
                try {
                    val result = provider.streamLiveStreams(batchRows = 100, batchBytes = 256L * 1024L,
                        onAttemptStart = {
                            session?.let { store.discardStagedImport(id, it) }
                            session = null; seen.clear(); batch.clear(); collector = FallbackCategoryCollector(id, ContentType.LIVE)
                        }) { channel -> batch += channel; if (batch.size == 25) flush() }
                    if (result is Result.Success) { flush(); store.applyStagedLiveCatalog(id, requireNotNull(session), categories) }
                    return result
                } finally { session?.let { store.discardStagedImport(id, it) } }
            }
            block(db, provider, ::import, { body = it })
        } finally {
            db.close()
            StalkerProvider.clearSharedAuthCacheForTests()
        }
    }
}
