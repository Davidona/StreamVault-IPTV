package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamLiveStreamRow
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.dto.XtreamVodMovieData
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test

class XtreamDtoParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `xtream category decodes is_adult from provider payload`() {
        val category = json.decodeFromString<XtreamCategory>(
            """
            {
              "category_id": "28",
              "category_name": "|+18| ADULTS LIVE",
              "is_adult": "1"
            }
            """.trimIndent()
        )

        assertThat(category.isAdult).isTrue()
    }

    @Test
    fun `xtream stream and vod payloads decode nullable is_adult`() {
        val stream = json.decodeFromString<XtreamStream>(
            """
            {
              "name": "Adult Channel",
              "stream_id": 99,
              "is_adult": 1
            }
            """.trimIndent()
        )
        val vod = json.decodeFromString<XtreamVodMovieData>(
            """
            {
              "stream_id": 123,
              "name": "Adult Movie",
              "is_adult": "true"
            }
            """.trimIndent()
        )

        assertThat(stream.isAdult).isTrue()
        assertThat(vod.isAdult).isTrue()
    }

      @Test
      fun `xtream thin live row ignores rich provider fields while decoding sync fields`() {
        val row = json.decodeFromString<XtreamLiveStreamRow>(
          """
          {
            "num": "12",
            "name": "Live Channel",
            "stream_id": "777",
            "stream_icon": "https://img.example.test/live.png",
            "epg_channel_id": "live.us",
            "category_id": "0",
            "category_name": "News",
            "category_ids": ["123", "456"],
            "tv_archive": "1",
            "tv_archive_duration": "3",
            "container_extension": ".m3u8",
            "is_adult": "0",
            "direct_source": "https://cdn.example.test/live/777/master.m3u8?token=abc",
            "cover_big": "https://img.example.test/cover.jpg",
            "rating": "9.1",
            "tmdb": "999",
            "youtube_trailer": "abc"
          }
          """.trimIndent()
        )

        assertThat(row.num).isEqualTo(12)
        assertThat(row.name).isEqualTo("Live Channel")
        assertThat(row.streamId).isEqualTo(777L)
        assertThat(row.streamIcon).isEqualTo("https://img.example.test/live.png")
        assertThat(row.epgChannelId).isEqualTo("live.us")
        assertThat(row.categoryId).isEqualTo("0")
        assertThat(row.categoryName).isEqualTo("News")
        assertThat(row.categoryIds).containsExactly("123", "456").inOrder()
        assertThat(row.tvArchive).isEqualTo(1)
        assertThat(row.tvArchiveDuration).isEqualTo(3)
        assertThat(row.containerExtension).isEqualTo(".m3u8")
        assertThat(row.isAdult).isFalse()
      }

    @Test
    fun `xtream series payload decodes false and missing is_adult safely`() {
        val explicitFalse = json.decodeFromString<XtreamSeriesItem>(
            """
            {
              "series_id": 77,
              "name": "Series",
              "is_adult": "0"
            }
            """.trimIndent()
        )
        val missing = json.decodeFromString<XtreamSeriesItem>(
            """
            {
              "series_id": 78,
              "name": "Series Two"
            }
            """.trimIndent()
        )

        assertThat(explicitFalse.isAdult).isFalse()
        assertThat(missing.isAdult).isNull()
    }

    @Test
    fun `series info reads nested episode arrays as seasons`() {
        val response = json.decodeFromString<XtreamSeriesInfoResponse>(
            """
            {
              "info": { "series_id": 42, "name": "Specials Show" },
              "episodes": [
                [
                  { "id": "100", "episode_num": 1, "title": "Special One" },
                  { "id": "101", "episode_num": 2, "title": "Special Two" }
                ],
                [
                  { "id": "200", "episode_num": 1, "title": "Season One Pilot" }
                ]
              ]
            }
            """.trimIndent()
        )

        assertThat(response.episodes.keys).containsExactly("0", "1").inOrder()
        assertThat(response.episodes["0"]?.map { it.title })
            .containsExactly("Special One", "Special Two").inOrder()
        assertThat(response.episodes["1"]?.single()?.season).isEqualTo(1)
    }

    @Test
    fun `series info keeps episodes whose info block has an unexpected shape`() {
        val response = json.decodeFromString<XtreamSeriesInfoResponse>(
            """
            {
              "episodes": {
                "1": [
                  { "id": "1", "episode_num": 1, "title": "First", "season": 1, "info": "not-an-object" },
                  { "id": "2", "episode_num": 2, "title": "Second", "season": 1, "info": [1, 2] },
                  { "id": "3", "episode_num": 3, "title": "Third", "season": 1, "info": { "plot": "kept" } }
                ]
              }
            }
            """.trimIndent()
        )

        assertThat(response.episodes["1"]?.map { it.title })
            .containsExactly("First", "Second", "Third").inOrder()
        assertThat(response.episodes["1"]?.map { it.info?.plot })
            .containsExactly(null, null, "kept").inOrder()
    }

    @Test
    fun `series info applies season map key when episode rows omit season`() {
        val response = json.decodeFromString<XtreamSeriesInfoResponse>(
            """
            {
              "episodes": {
                "2": [
                  { "id": "21", "episode_num": 1, "title": "S2E1" }
                ]
              }
            }
            """.trimIndent()
        )

        assertThat(response.episodes["2"]?.single()?.season).isEqualTo(2)
    }

    @Test
    fun `series info reads a season whose episodes are keyed by index`() {
        val response = json.decodeFromString<XtreamSeriesInfoResponse>(
            """
            {
              "episodes": {
                "3": {
                  "0": { "id": "301", "episode_num": 1, "title": "Keyed Pilot" },
                  "2": { "id": "303", "episode_num": 3, "title": "Keyed Third" }
                }
              }
            }
            """.trimIndent()
        )

        assertThat(response.episodes["3"]?.map { it.title })
            .containsExactly("Keyed Pilot", "Keyed Third").inOrder()
        assertThat(response.episodes["3"]?.map { it.season }).containsExactly(3, 3).inOrder()
    }
}
