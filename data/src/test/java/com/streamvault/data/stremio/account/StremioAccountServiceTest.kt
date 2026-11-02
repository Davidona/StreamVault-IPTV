package com.streamvault.data.stremio.account

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.*
import com.streamvault.domain.repository.*
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.mockito.kotlin.*

class StremioAccountServiceTest {
    private val now=1_800_000_000_000L
    private val addon=StremioAddon(1,"https://metadata.test/manifest.json","fixture","Fixture","1")
    private val addonFlow=MutableStateFlow(listOf(addon))
    private val history=MutableStateFlow<List<StremioPlaybackHistory>>(emptyList())
    private val addons=mock<StremioAddonRepository>()
    private val histories=mock<StremioPlaybackHistoryRepository>()
    private val server=MockWebServer()
    private val store=MemoryStore()
    private var cloud=JsonArray()
    private var collection=JsonArray()
    private var uploadFailure=false
    private var privateMode=false
    private var accountId="account-one"
    private val uploaded=mutableListOf<JsonObject>()
    private lateinit var service:StremioAccountService

    private class MemoryStore:StremioAccountStore {
        var value=JsonObject()
        override fun read()=value.deepCopy()
        override fun write(value:JsonObject) { this.value=value.deepCopy() }
        override fun clear() { value=JsonObject() }
    }

    @Before fun before()=runBlocking {
        whenever(addons.observeAddons()).thenReturn(addonFlow)
        whenever(histories.observeHistory()).thenReturn(history)
        whenever(addons.getAddon(any())).thenAnswer { addonFlow.value.firstOrNull { a -> a.id==it.getArgument<Long>(0) } }
        whenever(addons.getManifest(any())).thenReturn(StremioManifest("fixture","Fixture","1",
            resources=listOf(StremioManifestResource(StremioResourceKind.META))))
        whenever(addons.setCatalogEnabled(any(),any(),any())).thenAnswer { call ->
            val key=call.getArgument<String>(1);val enabled=call.getArgument<Boolean>(2)
            addonFlow.value=addonFlow.value.map { it.copy(catalogSettings=it.catalogSettings+(key to enabled)) };Result.success(Unit)
        }
        whenever(histories.get(any())).thenAnswer { call -> history.value.firstOrNull { it.contentKey==call.getArgument<String>(0) } }
        whenever(histories.saveAll(any())).thenAnswer { call ->
            val rows=call.getArgument<List<StremioPlaybackHistory>>(0)
            history.value=(history.value+rows).associateBy { it.contentKey }.values.toList();Result.success(Unit)
        }
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                return when(request.requestUrl!!.encodedPath.substringAfterLast('/')) {
                    "create" -> ok(jsonObject("code" to "device-code","link" to "https://link.stremio.com/?code=device-code"))
                    "read" -> ok(jsonObject("authKey" to "transfer-token"))
                    "loginWithToken" -> ok(jsonObject("authKey" to "own-token","user" to jsonObject("_id" to accountId,"email" to "fixture@example.test")))
                    "addonCollectionGet" -> ok(jsonObject("addons" to collection))
                    "datastoreGet" -> ok(cloud)
                    "datastorePut" -> {
                        val body=JsonParser.parseString(request.body.readUtf8()).asJsonObject
                        uploaded+=body.getAsJsonArray("changes").map { it.asJsonObject }
                        if(uploadFailure) MockResponse().setResponseCode(503) else ok(jsonObject("success" to true))
                    }
                    "logout" -> ok(jsonObject("success" to true))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        service=StremioAccountService(StremioAccountApi(OkHttpClient(),server.url("/api/").toString(),server.url("/link/").toString()),store,addons,histories,{now},{!privateMode})
    }
    @After fun after() { server.shutdown() }
    private fun ok(value:com.google.gson.JsonElement)=MockResponse().setBody(jsonObject("result" to value).toString())
    private fun session(baseline:JsonObject=JsonObject(),remote:JsonObject=JsonObject()) {
        store.value=jsonObject("authKey" to "own-token","userId" to "account-one","connectedAt" to now-10_000,"baseline" to baseline,"remote" to remote)
    }
    private fun movie(position:Long=15_000)=StremioPlaybackHistory("stremio:global:movie:tt123","movie","tt123",title="Movie",
        resumePositionMs=position,totalDurationMs=60_000,lastWatchedAt=now,catalogOriginKey=addon.installFingerprint())
    private fun cloudMovie(modified:Long=now-20_000)=jsonObject("_id" to "tt123","name" to "Cloud movie","type" to "movie",
        "removed" to false,"temp" to false,"_mtime" to Instant.ofEpochMilli(modified).toString(),"serverExtra" to "keep",
        "state" to jsonObject("video_id" to "tt123","timeOffset" to 40_000,"duration" to 60_000,
            "lastWatched" to Instant.ofEpochMilli(modified).toString(),"timesWatched" to 0,"flaggedWatched" to 0,"unknownState" to "keep"))

    @Test fun linkingExchangesTheTransferTokenAndDoesNotUploadUnrelatedExistingLocalData()=runBlocking {
        history.value=listOf(movie())
        service.beginLink();assertEquals("device-code",service.state.value.linkCode)
        service.pollLink()
        assertTrue(service.state.value.connected)
        assertEquals("own-token",store.value.text("authKey"))
        assertFalse(store.value.toString().contains("transfer-token"))
        assertTrue(uploaded.isEmpty())
        val requests=(0 until server.requestCount).map { server.takeRequest() }
        val exchange=requests.single { it.path=="/api/loginWithToken" }
        val body=JsonParser.parseString(exchange.body.readUtf8()).asJsonObject
        assertEquals("LoginWithToken",body.text("type"));assertEquals("transfer-token",body.text("token"))
    }
    @Test fun cloudLibraryAndProgressImportWithCanonicalIdentity()=runBlocking {
        session();cloud.add(cloudMovie())
        assertTrue(service.sync())
        assertEquals(40_000L,history.value.single().resumePositionMs)
        assertEquals("stremio:global:movie:tt123",history.value.single().contentKey)
        assertTrue(addonFlow.value.single().catalogSettings[stremioLibraryKey(StremioMediaType.MOVIE,"tt123")]!!)
        assertTrue(uploaded.isEmpty())
    }
    @Test fun unavailableAccountAddonDoesNotBlockOtherLibraryProgress()=runBlocking {
        session();cloud.add(cloudMovie())
        collection.add(jsonObject("transportName" to "http","transportUrl" to "https://unavailable.test/manifest.json"))
        whenever(addons.installAddon(any())).thenReturn(Result.error("Unavailable"))
        assertFalse(service.sync())
        assertEquals(40_000L,history.value.single().resumePositionMs)
        assertEquals(StremioAccountError.SYNC,service.state.value.error)
    }
    @Test fun seriesBitmapImportPreservesOtherEpisodeResumeAndDismissal()=runBlocking {
        val first="tt123:1:1";val second="tt123:1:2"
        val metadata=StremioMetaDetail("tt123",StremioMediaType.SERIES,"Series",videos=listOf(
            StremioVideo(first,season=1,episode=1),StremioVideo(second,season=1,episode=2)))
        whenever(addons.resolveMeta(any(),any(),any())).thenReturn(StremioMetaResolution(
            StremioAttributedResource(StremioAddonSource(1,"Fixture","fixture",addon.installFingerprint()),metadata)))
        val previous=StremioPlaybackHistory(stremioPlaybackContentKey("series",first,"tt123",addon.installFingerprint())!!,
            "series",first,"tt123","Series",resumePositionMs=12_000,totalDurationMs=60_000,lastWatchedAt=now-50_000,
            catalogOriginKey=addon.installFingerprint(),dismissedAtMs=now-20_000)
        history.value=listOf(previous)
        session();cloud.add(jsonObject("_id" to "tt123","type" to "series","name" to "Series","_mtime" to Instant.ofEpochMilli(now+1).toString(),
            "state" to jsonObject("video_id" to second,"timeOffset" to 30_000,"duration" to 60_000,
                "lastWatched" to Instant.ofEpochMilli(now).toString(),"watched" to StremioWatchedField.encode(listOf(first,second),emptySet()))))
        assertTrue(service.sync())
        assertEquals(12_000L,history.value.single { it.exactVideoId==first }.resumePositionMs)
        assertEquals(previous.dismissedAtMs,history.value.single { it.exactVideoId==first }.dismissedAtMs)
        assertEquals(30_000L,history.value.single { it.exactVideoId==second }.resumePositionMs)
    }
    @Test fun ambiguousCustomCloudIdsNeverChooseAnArbitraryProvider()=runBlocking {
        addonFlow.value=listOf(addon,addon.copy(id=2,installUrl="https://other.test/manifest.json"))
        session();cloud.add(jsonObject("_id" to "custom:1","type" to "movie","name" to "Custom",
            "state" to jsonObject("video_id" to "custom:1","timeOffset" to 12_000)))
        assertFalse(service.sync())
        assertTrue(history.value.isEmpty())
        assertTrue(addonFlow.value.all { it.catalogSettings.isEmpty() })
    }
    @Test fun localProgressUploadPreservesUnknownCloudFields()=runBlocking {
        val remote=cloudMovie();cloud.add(remote)
        session(jsonObject("tt123" to "older"),jsonObject("tt123" to remote))
        history.value=listOf(movie())
        assertTrue(service.sync())
        val change=uploaded.single()
        assertEquals("keep",change.text("serverExtra"))
        assertEquals("keep",change.getAsJsonObject("state").text("unknownState"))
        assertEquals(15_000L,change.getAsJsonObject("state").number("timeOffset"))
    }
    @Test fun newerCloudEditWinsWithoutUploadingStaleProgress()=runBlocking {
        session(jsonObject("tt123" to "older"));history.value=listOf(movie())
        cloud.add(cloudMovie(now+5_000))
        assertTrue(service.sync());assertTrue(uploaded.isEmpty())
        assertEquals(40_000L,history.value.single().resumePositionMs)
    }
    @Test fun dismissalClearsCloudContinueWatchingWithoutDeletingLocalResume()=runBlocking {
        session(jsonObject("tt123" to "older"));history.value=listOf(movie().copy(dismissedAtMs=now))
        assertTrue(service.sync())
        assertEquals(0L,uploaded.single().getAsJsonObject("state").number("timeOffset"))
        assertEquals(15_000L,history.value.single().resumePositionMs)
    }
    @Test fun failedUploadsRemainDirtyForRetry()=runBlocking {
        session(jsonObject("tt123" to "older"));history.value=listOf(movie());uploadFailure=true
        assertFalse(service.sync());assertEquals("older",store.value.getAsJsonObject("baseline").text("tt123"))
        uploadFailure=false;assertTrue(service.sync())
        assertNotEquals("older",store.value.getAsJsonObject("baseline").text("tt123"))
        assertEquals(2,uploaded.size)
    }
    @Test fun privateModeDoesNotContactTheAccountApi()=runBlocking {
        session();privateMode=true
        assertTrue(service.sync());assertEquals(0,server.requestCount)
        assertEquals(StremioAccountError.INCOGNITO,service.state.value.error)
    }
    @Test fun disconnectRetainsLocalDataAndDropsTheOldAccountLedger()=runBlocking {
        session(jsonObject("tt123" to "old-account"));history.value=listOf(movie())
        service.load();service.disconnect()
        assertFalse(service.state.value.connected);assertEquals(1,history.value.size);assertEquals(0,store.value.size())
        accountId="account-two";service.beginLink();service.pollLink();assertTrue(uploaded.isEmpty())
        assertEquals("account-two",store.value.text("userId"))
    }
}
