package com.streamvault.data.stremio.account

import com.google.gson.JsonObject
import com.streamvault.domain.model.stremio.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class StremioStreamingServiceImplTest {
    private class Store:StremioAccountStore {
        var data=JsonObject()
        override fun read()=data.deepCopy()
        override fun write(value:JsonObject) { data=value.deepCopy() }
        override fun clear() { data=JsonObject() }
    }
    @Test fun verifiesSettingsPersistsAndBuildsOfficialArchiveEndpoint()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"values":{"serverVersion":"4.21.0"}}"""))
            val store=Store();val service=StremioStreamingServiceImpl(store)
            service.configure(server.url("/").toString())
            assertTrue(service.state.value.connected)
            assertEquals("/settings",server.takeRequest().path)
            val original=StremioStream(fileIdx=0,archive=StremioArchiveSource("zip",listOf(StremioArchiveUrl("https://example.com/a.zip",42))),
                behaviorHints=StremioStreamBehaviorHints(bingeGroup="same",proxyHeaders=StremioProxyHeaders(request=mapOf("Authorization" to "secret"))))
            val prepared=service.prepare(original)!!
            assertEquals(StremioStreamKind.DIRECT,prepared.kind)
            val payload="""{"urls":[["https://example.com/a.zip",42]],"options":{"fileIdx":0,"fileMustInclude":[]}}"""
            assertEquals(server.url("/zip/create").toString()+"?lz="+java.net.URLEncoder.encode(StremioLzUri.compress(payload),"UTF-8"),prepared.url)
            assertNull(prepared.behaviorHints?.proxyHeaders)
            assertEquals("same",prepared.behaviorHints?.bingeGroup)
            val restored=StremioStreamingServiceImpl(store);restored.load()
            assertNotNull(restored.prepare(original))
            restored.clear();assertNull(restored.prepare(original))
        }
    }
    @Test fun ftpAndUsenetUseServiceWithoutLeakingCredentials()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"values":{"serverVersion":"4.21.0"}}"""))
            val service=StremioStreamingServiceImpl(Store());service.configure(server.url("/").toString())
            val ftp=service.prepare(StremioStream(url="ftp://user:secret@example.com/video.mp4"))!!
            assertTrue(ftp.url!!.contains("/ftp/video.mp4?lz="));assertFalse(ftp.url!!.contains("secret"))
            val nzb=service.prepare(StremioStream(nzbUrls=listOf("https://example.com/a.nzb"),servers=listOf("nntps://user:secret@example.com:563")))!!
            assertTrue(nzb.url!!.contains("/nzb/create?lz="));assertFalse(nzb.url!!.contains("secret"))
            assertNull(service.prepare(StremioStream(nzbUrls=listOf("https://example.com/a.nzb"))))
        }
    }
    @Test fun rejectsCredentialBearingServiceAddressAndRetainsDirectHeaders()=runBlocking {
        val service=StremioStreamingServiceImpl(Store());service.configure("http://user:secret@example.com/")
        assertTrue(service.state.value.error);assertNull(service.state.value.url)
        val direct=StremioStream(url="https://example.com/video.mp4",behaviorHints=StremioStreamBehaviorHints(proxyHeaders=StremioProxyHeaders(request=mapOf("X-Required" to "yes"))))
        assertEquals(direct,service.prepare(direct))
    }
}
