package com.streamvault.data.util

import com.google.common.truth.Truth.assertThat
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ProviderUrlProtocolResolverTest {
    @Test
    fun `resolve keeps explicit scheme unchanged`() = runBlocking {
        assertThat(ProviderUrlProtocolResolver.resolve("https://example.com:8080"))
            .isEqualTo("https://example.com:8080")
    }

    @Test
    fun `resolve falls back to http for bare host when https probe fails`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val host = "127.0.0.1:$port"

        assertThat(ProviderUrlProtocolResolver.resolve(host)).isEqualTo("http://$host")
    }

    @Test
    fun `resolve keeps non-http schemes unchanged without probing`() = runBlocking {
        assertThat(ProviderUrlProtocolResolver.resolve("file:///storage/emulated/0/playlist.m3u"))
            .isEqualTo("file:///storage/emulated/0/playlist.m3u")
        assertThat(ProviderUrlProtocolResolver.resolve("content://downloads/public_downloads/1"))
            .isEqualTo("content://downloads/public_downloads/1")
    }

    @Test
    fun `httpsProbeAccepts only accepts 2xx responses`() {
        assertThat(ProviderUrlProtocolResolver.httpsProbeAccepts(200)).isTrue()
        assertThat(ProviderUrlProtocolResolver.httpsProbeAccepts(204)).isTrue()
        assertThat(ProviderUrlProtocolResolver.httpsProbeAccepts(299)).isTrue()
        assertThat(ProviderUrlProtocolResolver.httpsProbeAccepts(301)).isFalse()
        assertThat(ProviderUrlProtocolResolver.httpsProbeAccepts(302)).isFalse()
        assertThat(ProviderUrlProtocolResolver.httpsProbeAccepts(401)).isFalse()
        assertThat(ProviderUrlProtocolResolver.httpsProbeAccepts(404)).isFalse()
        assertThat(ProviderUrlProtocolResolver.httpsProbeAccepts(500)).isFalse()
    }
}
