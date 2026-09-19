package com.streamvault.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import org.junit.Test

class PlayerPreloadWindowPolicyTest {

    @Test
    fun `three item window remains ordered around current item`() {
        val window = PlayerPreloadWindow(
            items = listOf(
                item("previous", "https://example.com/previous.mp4"),
                item("current", "https://example.com/current.mp4"),
                item("next", "https://example.com/next.mp4")
            ),
            currentIndex = 1
        )

        val normalized = normalizePlayerPreloadWindow(window)

        assertThat(normalized.items.map(PlayerPreloadItem::key))
            .containsExactly("previous", "current", "next")
            .inOrder()
        assertThat(normalized.currentIndex).isEqualTo(1)
    }

    @Test
    fun `window is capped to closest previous and next candidates`() {
        val window = PlayerPreloadWindow(
            items = (0..4).map { index -> item("item-$index", "https://example.com/$index.mp4") },
            currentIndex = 2
        )

        val normalized = normalizePlayerPreloadWindow(window)

        assertThat(normalized.items.map(PlayerPreloadItem::key))
            .containsExactly("item-1", "item-2", "item-3")
            .inOrder()
        assertThat(normalized.currentIndex).isEqualTo(1)
    }

    @Test
    fun `window at queue edge keeps only one adjacent candidate`() {
        val window = PlayerPreloadWindow(
            items = (0..4).map { index -> item("item-$index", "https://example.com/$index.mp4") },
            currentIndex = 0
        )

        val normalized = normalizePlayerPreloadWindow(window)

        assertThat(normalized.items.map(PlayerPreloadItem::key))
            .containsExactly("item-0", "item-1")
            .inOrder()
        assertThat(normalized.currentIndex).isEqualTo(0)
    }

    @Test
    fun `duplicate keys retain first item and adjust current index`() {
        val firstCurrent = item("current", "https://example.com/first.mp4")
        val duplicateCurrent = item(" current ", "https://example.com/duplicate.mp4")
        val window = PlayerPreloadWindow(
            items = listOf(
                item("previous", "https://example.com/previous.mp4"),
                firstCurrent,
                duplicateCurrent,
                item("next", "https://example.com/next.mp4")
            ),
            currentIndex = 2
        )

        val normalized = normalizePlayerPreloadWindow(window)

        assertThat(normalized.items.map(PlayerPreloadItem::key))
            .containsExactly("previous", "current", "next")
            .inOrder()
        assertThat(normalized.items[1].streamInfo.url).isEqualTo(firstCurrent.streamInfo.url)
        assertThat(normalized.currentIndex).isEqualTo(1)
    }

    @Test
    fun `live blank key and malformed url are filtered`() {
        val window = PlayerPreloadWindow(
            items = listOf(
                PlayerPreloadItem(
                    key = "live",
                    streamInfo = StreamInfo(
                        url = "https://example.com/live.m3u8",
                        streamType = StreamType.HLS
                    ),
                    contentType = PlayerPreloadContentType.LIVE
                ),
                item("   ", "https://example.com/blank-key.mp4"),
                item("malformed", "not-a-url"),
                item("current", "https://example.com/current.mp4")
            ),
            currentIndex = 3
        )

        val normalized = normalizePlayerPreloadWindow(window)

        assertThat(normalized.items.map(PlayerPreloadItem::key)).containsExactly("current")
        assertThat(normalized.currentIndex).isEqualTo(0)
    }

    @Test
    fun `invalid current index produces an empty window`() {
        val window = PlayerPreloadWindow(
            items = listOf(item("current", "https://example.com/current.mp4")),
            currentIndex = 4
        )

        val normalized = normalizePlayerPreloadWindow(window)

        assertThat(normalized.items).isEmpty()
        assertThat(normalized.currentIndex).isEqualTo(-1)
    }

    @Test
    fun `vod and catch up HLS or DASH items remain eligible`() {
        val window = PlayerPreloadWindow(
            items = listOf(
                item(
                    key = "vod",
                    url = "https://example.com/movie.m3u8",
                    contentType = PlayerPreloadContentType.VOD,
                    streamType = StreamType.HLS
                ),
                item(
                    key = "catch-up",
                    url = "https://example.com/program.mpd",
                    contentType = PlayerPreloadContentType.CATCH_UP,
                    streamType = StreamType.DASH
                )
            ),
            currentIndex = 0
        )

        val normalized = normalizePlayerPreloadWindow(window)

        assertThat(normalized.items.map(PlayerPreloadItem::key))
            .containsExactly("vod", "catch-up")
            .inOrder()
    }

    private fun item(
        key: String,
        url: String,
        contentType: PlayerPreloadContentType = PlayerPreloadContentType.VOD,
        streamType: StreamType = StreamType.PROGRESSIVE
    ): PlayerPreloadItem = PlayerPreloadItem(
        key = key,
        streamInfo = StreamInfo(url = url, streamType = streamType),
        contentType = contentType
    )
}
