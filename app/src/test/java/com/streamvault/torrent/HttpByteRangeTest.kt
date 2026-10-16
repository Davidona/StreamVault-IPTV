package com.streamvault.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class HttpByteRangeTest {
    @Test
    fun `parses bounded open and suffix ranges`() {
        assertEquals(ByteRange(2, 5), HttpByteRange.parse("bytes=2-5", 10))
        assertEquals(ByteRange(7, 9), HttpByteRange.parse("bytes=7-", 10))
        assertEquals(ByteRange(7, 9), HttpByteRange.parse("bytes=-3", 10))
    }

    @Test
    fun `clamps the end to file length and accepts case insensitive units`() {
        assertEquals(ByteRange(0, 9), HttpByteRange.parse("Bytes=0-99", 10))
    }

    @Test
    fun `missing range requests the whole resource`() {
        assertNull(HttpByteRange.parse(null, 10))
        assertNull(HttpByteRange.parse("", 10))
    }

    @Test
    fun `rejects malformed multiple and unsatisfiable ranges`() {
        listOf("items=0-1", "bytes=1-0", "bytes=10-", "bytes=-0", "bytes=0-1,3-4").forEach {
            assertRejected(it)
        }
    }

    private fun assertRejected(header: String) {
        try {
            HttpByteRange.parse(header, 10)
            fail("Expected range to be rejected: $header")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
