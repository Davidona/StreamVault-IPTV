package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LivePlaybackIdentity
import org.junit.Test

class LivePlaybackByteTapTest {
    private val tap = LivePlaybackByteTap()
    private val identity = LivePlaybackIdentity(1, 2)
    private val playlistUrl = "https://fixture.invalid/live/index.m3u8"

    private fun publisher(url: String, playlist: Boolean = false, start: Long = 0, length: Long = -1) =
        tap.beginHlsResource(identity, "fingerprint", url, url, playlist, start, length)

    private fun publish(url: String, bytes: ByteArray, playlist: Boolean = false) {
        publisher(url, playlist).use { resource ->
            resource.publish(bytes, 0, bytes.size)
            resource.complete()
        }
    }

    private fun openCapture(): LivePlaybackByteTap.HlsCapture =
        requireNotNull(tap.attachHls(identity, "fingerprint"))

    @Test fun `late recording can read warm playlists and encryption or initialization resources`() {
        val playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXT-X-MAP:URI=\"init\"\n".toByteArray()
        val key = ByteArray(16) { it.toByte() }
        val init = byteArrayOf(4, 5, 6)
        publish(playlistUrl, playlist, playlist = true)
        publish("https://fixture.invalid/live/key", key)
        publish("https://fixture.invalid/live/init", init)
        openCapture().use { capture ->
            assertThat(capture.resource(playlistUrl)?.bytes).isEqualTo(playlist)
            assertThat(capture.resource("https://fixture.invalid/live/key")?.bytes).isEqualTo(key)
            assertThat(capture.resource("https://fixture.invalid/live/init")?.bytes).isEqualTo(init)
            assertThat(tap.attachHls(identity, "different")).isNull()
        }
    }

    @Test fun `consumed map and warm playlist dependencies survive for the next recording`() {
        val base = "https://fixture.invalid/live/"
        val mapUrl = base + "init.mp4"
        val keyUrl = base + "key"
        val segmentUrl = base + "segment.mp4"
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXT-X-KEY:METHOD=AES-128,URI="key"
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="4@0"
            #EXTINF:2,
            segment.mp4
        """.trimIndent().toByteArray()
        publish(playlistUrl, playlist, playlist = true)
        publish(keyUrl, ByteArray(16) { it.toByte() })

        openCapture().use { first ->
            publisher(mapUrl, start = 0, length = 4).use { resource ->
                resource.publish(byteArrayOf(4, 3, 2, 1), 0, 4)
                resource.complete()
            }
            publish(segmentUrl, byteArrayOf(8, 9))
            val map = requireNotNull(first.resource(mapUrl, rangeStart = 0, rangeLength = 4))
            val segment = requireNotNull(first.recordingResource(segmentUrl))
            first.consume(map)
            first.consume(segment)

            assertThat(first.resource(mapUrl, rangeStart = 0, rangeLength = 4)).isNotNull()
            assertThat(first.resource(segmentUrl)).isNull()
        }

        openCapture().use { second ->
            assertThat(second.resource(playlistUrl)?.bytes).isEqualTo(playlist)
            assertThat(second.resource(keyUrl)?.bytes).isEqualTo(ByteArray(16) { it.toByte() })
            assertThat(second.resource(mapUrl, rangeStart = 0, rangeLength = 4)?.bytes)
                .isEqualTo(byteArrayOf(4, 3, 2, 1))
            // A resource from the first capture is never eligible as newly recorded media.
            assertThat(second.recordingResource(segmentUrl)).isNull()
            val nextSegmentUrl = base + "next.mp4"
            publish(nextSegmentUrl, byteArrayOf(10, 11))
            assertThat(second.recordingResource(nextSegmentUrl)?.bytes).isEqualTo(byteArrayOf(10, 11))
        }
    }

    @Test fun `closing capture prunes rolled media but keeps current window and its dependencies`() {
        val base = "https://fixture.invalid/live/"
        val oldSegment = base + "old.ts"
        val currentSegment = base + "current.ts"
        val currentKey = base + "current-key"
        val currentMap = base + "current-init"
        publish(
            playlistUrl,
            "#EXTM3U\n#EXTINF:2,\nold.ts\n".toByteArray(),
            playlist = true
        )
        publish(oldSegment, byteArrayOf(1, 2, 3))
        publish(
            playlistUrl,
            ("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"current-key\"\n" +
                "#EXT-X-MAP:URI=\"current-init\"\n#EXTINF:2,\ncurrent.ts\n").toByteArray(),
            playlist = true
        )
        publish(currentKey, ByteArray(16) { 7 })
        publish(currentMap, byteArrayOf(5, 6, 7))
        publish(currentSegment, byteArrayOf(8, 9, 10))

        openCapture().close()

        openCapture().use { capture ->
            assertThat(capture.resource(playlistUrl)).isNotNull()
            assertThat(capture.resource(oldSegment)).isNull()
            assertThat(capture.resource(currentSegment)?.bytes).isEqualTo(byteArrayOf(8, 9, 10))
            assertThat(capture.resource(currentKey)?.bytes).isEqualTo(ByteArray(16) { 7 })
            assertThat(capture.resource(currentMap)?.bytes).isEqualTo(byteArrayOf(5, 6, 7))
        }
    }

    @Test fun `idle playlist roll prunes obsolete media and retains current resources`() {
        val base = "https://fixture.invalid/live/"
        val oldSegment = base + "old.ts"
        val currentSegment = base + "current.ts"
        val currentKey = base + "current-key"
        val currentMap = base + "current-init"
        publish(
            playlistUrl,
            "#EXTM3U\n#EXTINF:2,\nold.ts\n#EXTINF:2,\ncurrent.ts\n".toByteArray(),
            playlist = true
        )
        publish(oldSegment, byteArrayOf(1, 2, 3))
        publish(
            playlistUrl,
            ("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"current-key\"\n" +
                "#EXT-X-MAP:URI=\"current-init\"\n#EXTINF:2,\ncurrent.ts\n").toByteArray(),
            playlist = true
        )
        publish(currentKey, ByteArray(16) { 7 })
        publish(currentMap, byteArrayOf(5, 6, 7))
        publish(currentSegment, byteArrayOf(8, 9, 10))

        openCapture().use { capture ->
            assertThat(capture.resource(oldSegment)).isNull()
            assertThat(capture.resource(playlistUrl)).isNotNull()
            assertThat(capture.resource(currentSegment)?.bytes).isEqualTo(byteArrayOf(8, 9, 10))
            assertThat(capture.resource(currentKey)?.bytes).isEqualTo(ByteArray(16) { 7 })
            assertThat(capture.resource(currentMap)?.bytes).isEqualTo(byteArrayOf(5, 6, 7))
        }
    }

    @Test fun `detaching one capture does not prune a segment another capture still owes`() {
        val base = "https://fixture.invalid/live/"
        val firstSegment = base + "first.ts"
        val secondSegment = base + "second.ts"
        publish(
            playlistUrl,
            "#EXTM3U\n#EXTINF:2,\nfirst.ts\n#EXTINF:2,\nsecond.ts\n".toByteArray(),
            playlist = true
        )
        val first = openCapture()
        val second = openCapture()
        publish(firstSegment, byteArrayOf(1, 2))
        publish(secondSegment, byteArrayOf(3, 4))

        first.consume(requireNotNull(first.recordingResource(firstSegment)))
        first.close()

        assertThat(second.recordingResource(firstSegment)?.bytes).isEqualTo(byteArrayOf(1, 2))
        assertThat(second.recordingResource(secondSegment)?.bytes).isEqualTo(byteArrayOf(3, 4))
        second.close()
    }

    @Test fun `consumed rotated maps can be reclaimed under recording cache pressure`() {
        val base = "https://fixture.invalid/live/"
        publish(playlistUrl, "#EXTM3U\n".toByteArray(), playlist = true)
        openCapture().use { capture ->
            repeat(4) { index ->
                publish(playlistUrl, "#EXTM3U\n#EXT-X-MAP:URI=\"init-$index\"\n".toByteArray(), true)
                publish(base + "init-$index", ByteArray(12 * 1024 * 1024) { index.toByte() })
                capture.throwIfFailed()
                val map = requireNotNull(capture.resource(base + "init-$index"))
                capture.consume(map)
                assertThat(capture.resource(base + "init-$index")).isNotNull()
            }
        }
    }

    @Test fun `a warm map not consumed by another recording remains protected under pressure`() {
        val base = "https://fixture.invalid/live/"
        publish(playlistUrl, "#EXTM3U\n#EXT-X-MAP:URI=\"init-0\"\n".toByteArray(), true)
        publish(base + "init-0", ByteArray(12 * 1024 * 1024))
        openCapture().use { first ->
            openCapture().use { second ->
                first.consume(requireNotNull(first.resource(base + "init-0")))
                publish(playlistUrl, "#EXTM3U\n#EXT-X-MAP:URI=\"init-1\"\n".toByteArray(), true)
                publish(base + "init-1", ByteArray(12 * 1024 * 1024))
                first.consume(requireNotNull(first.resource(base + "init-1")))
                publish(playlistUrl, "#EXTM3U\n#EXT-X-MAP:URI=\"init-2\"\n".toByteArray(), true)
                publish(base + "init-2", ByteArray(12 * 1024 * 1024))
                org.junit.Assert.assertThrows(LivePlaybackCaptureBackpressureException::class.java) {
                    second.throwIfFailed()
                }
                assertThat(second.resource(base + "init-0")).isNotNull()
                assertThat(second.resource(base + "init-1")).isNotNull()
            }
        }
    }

    @Test fun `removed media does not keep an unbounded recording consumption history`() {
        val url = "https://fixture.invalid/live/media.ts"
        publish(playlistUrl, "#EXTM3U\n#EXTINF:2,\nmedia.ts\n".toByteArray(), true)
        openCapture().use { capture ->
            repeat(1_000) {
                publish(url, byteArrayOf(1, 2))
                capture.consume(requireNotNull(capture.recordingResource(url)))
            }
            val history = capture.javaClass.getDeclaredField("consumedResourceIds").apply {
                isAccessible = true
            }.get(capture) as Set<*>
            assertThat(history.size).isLessThan(16)
        }
    }

    @Test fun `resource that completes after attachment is available without a second request`() {
        publish(playlistUrl, "#EXTM3U\n".toByteArray(), playlist = true)
        val url = "https://fixture.invalid/live/segment.ts"
        publisher(url).use { resource ->
            val bytes = ByteArray(65_537) { (it % 251).toByte() }
            resource.publish(bytes, 0, 50_000)
            openCapture().use { capture ->
                resource.publish(bytes, 50_000, bytes.size - 50_000)
                resource.complete()
                assertThat(capture.recordingResource(url)?.bytes).isEqualTo(bytes)
            }
        }
    }

    @Test fun `recording starts with new resources and one reader cannot discard another reader's segment`() {
        val old = "https://fixture.invalid/old.ts"
        val fresh = "https://fixture.invalid/new.ts"
        publish(old, byteArrayOf(1))
        openCapture().use { first ->
            openCapture().use { second ->
                assertThat(first.recordingResource(old)).isNull()
                publish(fresh, byteArrayOf(2, 3))
                first.consume(requireNotNull(first.recordingResource(fresh)))
                assertThat(second.recordingResource(fresh)?.bytes).isEqualTo(byteArrayOf(2, 3))
                second.consume(requireNotNull(second.recordingResource(fresh)))
                assertThat(second.recordingResource(fresh)).isNull()
            }
        }
    }

    @Test fun `range keys distinguish adjacent initialization or segment byte ranges`() {
        val url = "https://fixture.invalid/live/file.mp4"
        publisher(url, start = 20, length = 3).use { resource ->
            openCapture().use { capture ->
                resource.publish(byteArrayOf(9, 8, 7), 0, 3)
                resource.complete()
                assertThat(capture.recordingResource(url, 20, 3)?.bytes).isEqualTo(byteArrayOf(9, 8, 7))
                assertThat(capture.recordingResource(url, 21, 3)).isNull()
                assertThat(capture.recordingResource(url)).isNull()
            }
        }
    }

    @Test fun `incomplete reads never become captured resources`() {
        val url = "https://fixture.invalid/live/truncated.ts"
        publisher(url).use { resource -> resource.publish(byteArrayOf(1, 2), 0, 2) }
        openCapture().use { capture -> assertThat(capture.resource(url)).isNull() }
    }

    @Test fun `chunked response preserves offset reads and owns published bytes`() {
        val url = "https://fixture.invalid/live/large.ts"
        val expected = ByteArray(3 * 65536 + 31) { (it % 251).toByte() }
        val scratch = ByteArray(4107)
        publisher(url).use { resource ->
            var position = 0
            while (position < expected.size) {
                val count = minOf(4093, expected.size - position)
                expected.copyInto(scratch, 7, position, position + count)
                resource.publish(scratch, 7, count)
                scratch.fill(0)
                position += count
            }
            resource.complete()
            resource.complete()
        }
        openCapture().use { capture -> assertThat(capture.resource(url)?.bytes).isEqualTo(expected) }
    }

    @Test fun `response at the byte limit is retained intact`() {
        val url = "https://fixture.invalid/live/limit.ts"
        val chunk = ByteArray(65536) { (it % 239).toByte() }
        publisher(url).use { resource ->
            repeat(512) { resource.publish(chunk, 0, chunk.size) }
            resource.complete()
        }
        openCapture().use { capture ->
            val bytes = requireNotNull(capture.resource(url)).bytes
            assertThat(bytes.size).isEqualTo(32 * 1024 * 1024)
            assertThat(bytes.take(31)).containsExactlyElementsIn(chunk.take(31)).inOrder()
            assertThat(bytes.takeLast(31)).containsExactlyElementsIn(chunk.takeLast(31)).inOrder()
        }
    }

    @Test fun `oversized response fails recording explicitly without retaining partial bytes`() {
        publish(playlistUrl, "#EXTM3U\n".toByteArray(), playlist = true)
        openCapture().use { capture ->
            val url = "https://fixture.invalid/live/oversized.ts"
            publisher(url).use { resource ->
                val chunk = ByteArray(65536)
                repeat(512) { resource.publish(chunk, 0, chunk.size) }
                resource.publish(byteArrayOf(1), 0, 1)
                resource.complete()
            }
            assertThat(capture.resource(url)).isNull()
            org.junit.Assert.assertThrows(LivePlaybackCaptureBackpressureException::class.java) {
                capture.throwIfFailed()
            }
        }
    }

    @Test fun `recording can reclaim old media after its playlist window rolls while preserving dependencies`() {
        val base = "https://fixture.invalid/live/"
        val oldSegments = (1..5).map { "old-$it.ts" }
        val header = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXT-X-MAP:URI=\"init\"\n"
        publish(playlistUrl, (header + oldSegments.joinToString("") { "#EXTINF:2,\n$it\n" }).toByteArray(), true)
        val key = ByteArray(16) { 7 }
        val init = ByteArray(128) { 9 }
        publish(base + "key", key)
        publish(base + "init", init)
        oldSegments.forEach { publish(base + it, ByteArray(8 * 1024 * 1024)) }
        publish(playlistUrl, (header + "#EXTINF:2,\nnew.ts\n").toByteArray(), true)
        openCapture().use { capture ->
            publish(base + "new.ts", ByteArray(8 * 1024 * 1024) { 1 })
            capture.throwIfFailed()
            assertThat(capture.recordingResource(base + "new.ts")?.bytes?.size).isEqualTo(8 * 1024 * 1024)
            assertThat(capture.resource(base + oldSegments.first())).isNull()
            assertThat(capture.resource(base + oldSegments[2])).isNull()
            assertThat(capture.resource(base + "key")?.bytes).isEqualTo(key)
            assertThat(capture.resource(base + "init")?.bytes).isEqualTo(init)
        }
    }

    @Test fun `cache pressure cannot evict segments still owed to another recording`() {
        val base = "https://fixture.invalid/live/"
        publish(playlistUrl, "#EXTM3U\n#EXTINF:2,\na.ts\n#EXTINF:2,\nb.ts\n#EXTINF:2,\nc.ts\n".toByteArray(), true)
        openCapture().use { first ->
            openCapture().use { second ->
                listOf("a.ts", "b.ts").forEach { name ->
                    publish(base + name, ByteArray(12 * 1024 * 1024))
                    first.consume(requireNotNull(first.recordingResource(base + name)))
                }
                publish(base + "c.ts", ByteArray(12 * 1024 * 1024))
                org.junit.Assert.assertThrows(LivePlaybackCaptureBackpressureException::class.java) {
                    second.throwIfFailed()
                }
                assertThat(second.recordingResource(base + "a.ts")).isNotNull()
                assertThat(second.recordingResource(base + "b.ts")).isNotNull()
            }
        }
    }

    @Test fun `map sharing a URI with media and unresolved variable dependencies remain protected`() {
        val url = "https://fixture.invalid/live/shared.mp4"
        val headers = listOf(
            "#EXT-X-MAP:URI=\"shared.mp4\",BYTERANGE=\"32@0\"",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"{\$key}\""
        )
        headers.forEach { header ->
            publish(playlistUrl, "#EXTM3U\n#EXTINF:2,\nshared.mp4\n".toByteArray(), true)
            publish(url, ByteArray(32 * 1024 * 1024))
            publish(playlistUrl, "#EXTM3U\n$header\n#EXTINF:2,\nshared.mp4\n".toByteArray(), true)
            openCapture().use { capture ->
                publish("https://fixture.invalid/live/new.ts", byteArrayOf(1))
                org.junit.Assert.assertThrows(LivePlaybackCaptureBackpressureException::class.java) {
                    capture.throwIfFailed()
                }
                assertThat(capture.resource(url)).isNotNull()
            }
        }
    }

    @Test fun `idle eviction removes obsolete rotated keys while retaining the current key`() {
        val base = "https://fixture.invalid/live/"
        fun manifest(key: String, segment: String) =
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"$key\"\n#EXTINF:2,\n$segment\n".toByteArray()
        publish(playlistUrl, manifest("old-key", "old.ts"), true)
        publish(base + "old-key", ByteArray(16))
        publish(base + "old.ts", ByteArray(16 * 1024 * 1024))
        publish(playlistUrl, manifest("current-key", "new.ts"), true)
        val currentKey = ByteArray(16) { 1 }
        publish(base + "current-key", currentKey)
        publish(base + "new.ts", ByteArray(16 * 1024 * 1024))
        openCapture().use { capture ->
            assertThat(capture.resource(base + "old-key")).isNull()
            assertThat(capture.resource(base + "current-key")?.bytes).isEqualTo(currentKey)
            assertThat(capture.resource(base + "new.ts")?.bytes?.size).isEqualTo(16 * 1024 * 1024)
        }
    }
}
