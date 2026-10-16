package com.streamvault.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnetLinkParserTest {
    @Test
    fun `parses btih file selection and public tracker`() {
        val link = MagnetLinkParser.parse(
            "magnet:?xt=urn%3Abtih%3A0123456789abcdef0123456789abcdef01234567" +
                "&dn=Sample%20Film&so=3&tr=udp%3A%2F%2Ftracker.example%3A6969%2Fannounce"
        )

        assertNotNull(link)
        assertEquals("0123456789ABCDEF0123456789ABCDEF01234567", link!!.infoHash)
        assertEquals("Sample Film", link.displayName)
        assertEquals(3, link.selectedFileIndex)
        assertEquals(listOf("udp://tracker.example:6969/announce"), link.trackers)
        assertTrue(link.toEngineUri().startsWith("magnet:?xt=urn%3Abtih%3A"))
    }

    @Test
    fun `accepts base32 btih hashes`() {
        val link = MagnetLinkParser.parse("magnet:?xt=urn:btih:ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
        assertNotNull(link)
        assertEquals(40, link!!.infoHash.length)
    }

    @Test
    fun `sanitizes unknown sources and private trackers before passing to engine`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val link = MagnetLinkParser.parse(
            "magnet:?xt=urn:btih:$hash" +
                "&dn=Safe%20Name&tr=http%3A%2F%2F127.0.0.1%3A8080%2Fannounce" +
                "&tr=udp%3A%2F%2Ftracker.example%3A6969%2Fannounce" +
                "&xs=http%3A%2F%2F127.0.0.1%2Fsecret&ws=http%3A%2F%2Fexample.test%2Ffile"
        )

        assertNotNull(link)
        assertEquals(listOf("udp://tracker.example:6969/announce"), link!!.trackers)
        assertFalse(link.toEngineUri().contains("xs="))
        assertFalse(link.toEngineUri().contains("ws="))
        assertFalse(link.toEngineUri().contains("127.0.0.1"))
    }

    @Test
    fun `rejects conflicting file selections`() {
        assertNull(MagnetLinkParser.parse(
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&so=1&so=2"
        ))
    }

    @Test
    fun `rejects malformed or non magnet links`() {
        assertNull(MagnetLinkParser.parse("https://example.test/video.mp4"))
        assertNull(MagnetLinkParser.parse("magnet:?dn=missing-hash"))
        assertNull(MagnetLinkParser.parse("magnet:?xt=urn:btih:not-a-valid-hash"))
        assertNull(MagnetLinkParser.parse("magnet://example.test/?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
    }
}
