package com.streamvault.player.playback

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class StremioServiceConnectionPolicyTest {
    @Test fun ordinaryAndUnscopedSourcesRetainTheirClient() {
        val client=OkHttpClient()
        assertSame(client,StremioServiceConnectionPolicy.apply(client,"https://example.com/live.m3u8",true))
        assertSame(client,StremioServiceConnectionPolicy.apply(client,"https://example.com/zip/create?lz=value",false))
        assertFalse(StremioServiceConnectionPolicy.isEngineUrl("https://example.com/zip/create?token=value"))
        assertTrue(StremioServiceConnectionPolicy.isEngineUrl("http://localhost:11470/base/tar/create?lz=value"))
    }
    @Test fun excessServiceResponseByteCannotPoisonTheNextSeek() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("videoP").setHeader("Content-Length",5))
            server.enqueue(MockResponse().setBody("seek"))
            val url=server.url("/zip/create?lz=fixture").toString()
            val client=StremioServiceConnectionPolicy.apply(OkHttpClient(),url,true)
            client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals("video",it.body!!.string()) }
            client.newCall(Request.Builder().url(url).header("Range","bytes=1-4").build()).execute().use { assertEquals("seek",it.body!!.string()) }
            assertEquals(0,server.takeRequest().sequenceNumber)
            assertEquals(0,server.takeRequest().sequenceNumber)
        }
    }
}
