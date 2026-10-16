package com.streamvault.data.remote.stremio

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.stremio.StremioMediaType
import org.junit.Test

class StremioAddonUrlTest {

    @Test
    fun `stremio scheme is upgraded and manifest appended`() {
        val location = StremioAddonUrl.parse("stremio://v3-cinemeta.strem.io")
        assertThat(location).isNotNull()
        assertThat(location!!.baseUrl).isEqualTo("https://v3-cinemeta.strem.io")
        assertThat(location.manifestUrl).isEqualTo("https://v3-cinemeta.strem.io/manifest.json")
    }

    @Test
    fun `cinemeta manifest url is accepted`() {
        val location = StremioAddonUrl.parse("https://v3-cinemeta.strem.io/manifest.json")

        assertThat(location).isNotNull()
        assertThat(location!!.manifestUrl).isEqualTo("https://v3-cinemeta.strem.io/manifest.json")
    }

    @Test
    fun `configured manifest path is preserved`() {
        val location = StremioAddonUrl.parse("https://torrentio.strem.fun/qualityfilter=720p,av1/manifest.json")
        assertThat(location).isNotNull()
        assertThat(location!!.baseUrl).isEqualTo("https://torrentio.strem.fun/qualityfilter=720p,av1")
    }

    @Test
    fun `clipboard direction marks around manifest url are ignored`() {
        val location = StremioAddonUrl.parse(
            "\u200F\uFEFF https://thepiratebay-plus.strem.fun/manifest.json\u200E\u200F "
        )

        assertThat(location).isNotNull()
        assertThat(location!!.manifestUrl)
            .isEqualTo("https://thepiratebay-plus.strem.fun/manifest.json")
    }

    @Test
    fun `encoded configured path bytes round trip unchanged`() {
        val location = StremioAddonUrl.parse(
            "https://example.test/tenant%2Fprivate/quality%3D720p/manifest.json?token=secret"
        )

        assertThat(location).isNotNull()
        assertThat(location!!.manifestUrl)
            .isEqualTo("https://example.test/tenant%2Fprivate/quality%3D720p/manifest.json?token=secret")
        assertThat(location.endpointUrl("/catalog/movie/top.json"))
            .isEqualTo("https://example.test/tenant%2Fprivate/quality%3D720p/catalog/movie/top.json?token=secret")
    }

    @Test
    fun `trailing slash and manifest suffix normalize to the same base`() {
        assertThat(StremioAddonUrl.parse("https://example.test/addon/")!!.baseUrl)
            .isEqualTo("https://example.test/addon")
        assertThat(StremioAddonUrl.parse("https://example.test/addon/manifest.json")!!.baseUrl)
            .isEqualTo("https://example.test/addon")
    }

    @Test
    fun `query string is preserved on manifest and endpoint urls`() {
        val location = StremioAddonUrl.parse("https://example.test/addon/manifest.json?token=abc123")!!
        assertThat(location.query).isEqualTo("token=abc123")
        assertThat(location.manifestUrl).isEqualTo("https://example.test/addon/manifest.json?token=abc123")
        assertThat(location.endpointUrl("/catalog/movie/top.json"))
            .isEqualTo("https://example.test/addon/catalog/movie/top.json?token=abc123")
    }

    @Test
    fun `port is preserved`() {
        val location = StremioAddonUrl.parse("http://192.168.1.5:7000/manifest.json")
        assertThat(location).isNotNull()
        assertThat(location!!.baseUrl).isEqualTo("http://192.168.1.5:7000")
    }

    @Test
    fun `invalid urls are rejected`() {
        assertThat(StremioAddonUrl.parse("")).isNull()
        assertThat(StremioAddonUrl.parse("v3-cinemeta.strem.io")).isNull()
        assertThat(StremioAddonUrl.parse("ftp://example.test/manifest.json")).isNull()
        assertThat(StremioAddonUrl.parse("https:///manifest.json")).isNull()
    }

    @Test
    fun `catalog path encodes extra values`() {
        val path = StremioAddonUrl.catalogPath(
            StremioMediaType.MOVIE,
            "top",
            listOf("genre" to "Sci-Fi & Fantasy", "skip" to "100")
        )
        assertThat(path).isEqualTo("/catalog/movie/top/genre=Sci-Fi%20%26%20Fantasy&skip=100.json")
    }

    @Test
    fun `meta and stream paths keep colon ids`() {
        assertThat(StremioAddonUrl.metaPath(StremioMediaType.SERIES, "tt0903747:1:2"))
            .isEqualTo("/meta/series/tt0903747:1:2.json")
        assertThat(StremioAddonUrl.streamPath(StremioMediaType.SERIES, "tt0903747:1:2"))
            .isEqualTo("/stream/series/tt0903747:1:2.json")
    }

    @Test
    fun `subtitle path encodes filename extras`() {
        val path = StremioAddonUrl.subtitlePath(
            StremioMediaType.MOVIE,
            "tt0111161",
            listOf("videoHash" to "abc", "filename" to "The Shawshank Redemption.mkv")
        )
        assertThat(path)
            .isEqualTo("/subtitles/movie/tt0111161/videoHash=abc&filename=The%20Shawshank%20Redemption.mkv.json")
    }
}
