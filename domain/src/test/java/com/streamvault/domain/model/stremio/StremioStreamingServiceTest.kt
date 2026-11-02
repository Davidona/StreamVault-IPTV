package com.streamvault.domain.model.stremio

import org.junit.Assert.*
import org.junit.Test

class StremioStreamingServiceTest {
    @Test fun matchesOfficialLzStringUnicodeAndPayloadVectors() {
        assertEquals("Q",StremioLzUri.compress(""))
        assertEquals("BYUwNmD2Q",StremioLzUri.compress("hello"))
        assertEquals("BYUwNmD2AEoTcpA",StremioLzUri.compress("hello hello hello"))
        assertEquals("N4IgrgTgNgziBcBtRIAWAXdAHeB6XAjAEwDsAdAAyVkG4CGEAxqgJYBuApmQF4tYgAaACxEAuqIEgA9lnQspAOzjxQAMxZQOASQAmADwQVJ6zQFkwMdFoWMoYHRwSJRAXxdA",StremioLzUri.compress("{\"urls\":[[\"http://127.0.0.1/archive.zip\",42]],\"options\":{\"fileIdx\":0,\"fileMustInclude\":[]}}"))
        assertEquals("pegh3QSroIu6AASGhygyrlQPBuBr9oA",StremioLzUri.compress("\u05e9\u05dc\u05d5\u05dd \u4e16\u754c \ud83c\udfac"))
    }
    @Test fun ipfsGatewayPreservesCaseAndRejectsTraversal() {
        assertEquals("https://ipfs.io/ipfs/QmCase/manifest.json?x=1",stremioGatewayUrl("ipfs://QmCase/manifest.json?x=1"))
        assertEquals("https://ipfs.io/ipns/example.com/video.mp4",stremioGatewayUrl("ipns://example.com/video.mp4"))
        assertNull(stremioGatewayUrl("ipfs://user@QmCase/file"))
        assertNull(stremioGatewayUrl("ipfs://QmCase/%2e%2e/file"))
    }
    @Test fun enginesRequireServiceAndSecretsNeverAppearInStreamDescription() {
        val archive=StremioStream(archive=StremioArchiveSource("zip",listOf(StremioArchiveUrl("https://example.com/a.zip?token=secret"))))
        assertNull(DisabledStremioStreamingService.prepare(archive))
        assertFalse(archive.toString().contains("secret"))
        val usenet=StremioStream(nzbUrls=listOf("https://example.com/a.nzb"),servers=listOf("nntps://user:secret@example.com:563"))
        assertNull(DisabledStremioStreamingService.prepare(usenet))
        assertFalse(usenet.toString().contains("secret"))
    }
}
