package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderRepositoryCatchUpTest {

    @Test
    fun `buildM3uCatchUpUrls expands common replay placeholders`() {
        val urls = buildM3uCatchUpUrls(
            source = "http://catchup.example.com/archive/{utc}/{utcend}/{Y}/{m}/{d}/{H}/{M}/{S}/{duration_minutes}.m3u8",
            start = 1_710_000_000L,
            end = 1_710_003_600L
        )

        assertThat(urls).containsExactly(
            "http://catchup.example.com/archive/1710000000/1710003600/2024/03/09/16/00/00/60.m3u8"
        )
    }

    @Test
    fun `append mode adds its expanded query to the live URL and applies correction`() {
        val nowBefore = System.currentTimeMillis() / 1_000L
        val urls = buildM3uCatchUpUrls(
            source = "&from={utc}&to={utcend}&now={lutc}",
            streamUrl = "https://live.example.com/channel.ts?token=abc",
            catchUpType = "append",
            start = 1_710_000_000L,
            end = 1_710_003_600L,
            correctionSeconds = 1_800
        )
        val nowAfter = System.currentTimeMillis() / 1_000L

        assertThat(urls).hasSize(1)
        assertThat(urls.single()).startsWith("https://live.example.com/channel.ts?token=abc&from=1710001800&to=1710005400&now=")
        val generatedNow = urls.single().substringAfter("&now=").toLong()
        assertThat(generatedNow).isAtLeast(nowBefore + 1_800L)
        assertThat(generatedNow).isAtMost(nowAfter + 1_800L)
    }

    @Test
    fun `default mode uses the configured query format when source is missing`() {
        val nowBefore = System.currentTimeMillis() / 1_000L
        val start = nowBefore - 7_200L
        val urls = buildM3uCatchUpUrls(
            source = null,
            streamUrl = "https://live.example.com/channel.ts",
            catchUpType = "default",
            start = start,
            end = start + 3_600L,
            correctionSeconds = 3_600,
            fallbackTemplate = "?from={utc}&minutes={duration:60}&offset={offset:60}&now={lutc}"
        )
        val nowAfter = System.currentTimeMillis() / 1_000L

        assertThat(urls).hasSize(1)
        val query = urls.single().substringAfter('?').split('&').associate {
            it.substringBefore('=') to it.substringAfter('=')
        }
        assertThat(query["from"]).isEqualTo((start + 3_600L).toString())
        assertThat(query["minutes"]).isEqualTo("60")
        assertThat(query["offset"]!!.toLong()).isAtLeast((nowBefore - start) / 60L)
        assertThat(query["offset"]!!.toLong()).isAtMost((nowAfter - start) / 60L)
        assertThat(query["now"]!!.toLong()).isAtLeast(nowBefore + 3_600L)
        assertThat(query["now"]!!.toLong()).isAtMost(nowAfter + 3_600L)
    }

    @Test
    fun `empty configured query format disables default and append fallback`() {
        for (mode in listOf("default", "append")) {
            val urls = buildM3uCatchUpUrls(
                source = null,
                streamUrl = "https://live.example.com/channel.ts",
                catchUpType = mode,
                start = 1_710_000_000L,
                end = 1_710_003_600L,
                correctionSeconds = 0,
                fallbackTemplate = ""
            )

            assertThat(urls).isEmpty()
        }
    }

    @Test
    fun `shift mode synthesizes SIPTV parameters on the live URL`() {
        val urls = buildM3uCatchUpUrls(
            source = null,
            streamUrl = "https://live.example.com/channel.ts",
            catchUpType = "shift",
            start = 1_710_000_000L,
            end = 1_710_003_600L,
            correctionSeconds = 0
        )

        assertThat(urls).hasSize(1)
        assertThat(urls.single()).startsWith("https://live.example.com/channel.ts?utc=1710000000&lutc=")
    }

    @Test
    fun `Flussonic HLS mode builds a relative timeshift path`() {
        val now = System.currentTimeMillis() / 1_000L
        val urls = buildM3uCatchUpUrls(
            source = null,
            streamUrl = "https://flussonic.example.com/news/index.m3u8?token=abc",
            catchUpType = "fs",
            start = now - 3_600L,
            end = now - 1_800L,
            correctionSeconds = 0
        )

        assertThat(urls).hasSize(1)
        assertThat(urls.single()).contains("/news/timeshift_rel-")
        assertThat(urls.single()).endsWith(".m3u8?token=abc")
    }

    @Test
    fun `Xtream mode builds its timeshift endpoint from the live URL`() {
        val urls = buildM3uCatchUpUrls(
            source = null,
            streamUrl = "https://xc.example.com:8080/live/user/pass/42.ts",
            catchUpType = "xc",
            start = 1_710_000_000L,
            end = 1_710_003_600L,
            correctionSeconds = 0
        )

        assertThat(urls).containsExactly(
            "https://xc.example.com:8080/timeshift/user/pass/60/2024-03-09:16-00/42.ts"
        )
    }

    @Test
    fun `unknown mode without a template does not create a candidate`() {
        val urls = buildM3uCatchUpUrls(
            source = null,
            streamUrl = "https://live.example.com/channel.ts",
            catchUpType = "unknown-mode",
            start = 1_710_000_000L,
            end = 1_710_003_600L,
            correctionSeconds = 0
        )

        assertThat(urls).isEmpty()
    }

    @Test
    fun `catchup id templates do not return unresolved replay URLs`() {
        val urls = buildM3uCatchUpUrls(
            source = "https://archive.example.com/program/{catchup-id}.m3u8",
            streamUrl = "https://live.example.com/channel.ts",
            catchUpType = "vod",
            start = 1_710_000_000L,
            end = 1_710_003_600L,
            correctionSeconds = 0
        )

        assertThat(urls).isEmpty()
    }
}
