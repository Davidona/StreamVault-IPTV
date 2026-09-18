package com.streamvault.data.parser

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.GZIPOutputStream

/**
 * Unit tests for [M3uParser].
 *
 * All tests run on the host JVM — no Android framework needed.
 */
class M3uParserTest {

    private lateinit var parser: M3uParser

    @Before
    fun setUp() {
        parser = M3uParser()
    }

    // ── Happy path ─────────────────────────────────────────────────

    @Test
    fun `parse_validPlaylist_returnsEntries`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cnn" tvg-name="CNN" tvg-logo="http://logo.png" group-title="News",CNN International
            http://stream.example.com/cnn.m3u8
            #EXTINF:-1 tvg-id="bbc" tvg-name="BBC" group-title="News",BBC World
            http://stream.example.com/bbc.ts
            #EXTINF:-1 group-title="Sports",Sports Channel
            http://stream.example.com/sports.ts
        """.trimIndent()

        val entries = parseEntries(m3u)

        assertThat(entries).hasSize(3)

        val cnn = entries[0]
        assertThat(cnn.name).isEqualTo("CNN International")
        assertThat(cnn.tvgId).isEqualTo("cnn")
        assertThat(cnn.tvgName).isEqualTo("CNN")
        assertThat(cnn.tvgLogo).isEqualTo("http://logo.png")
        assertThat(cnn.groupTitle).isEqualTo("News")
        assertThat(cnn.url).isEqualTo("http://stream.example.com/cnn.m3u8")

        val bbc = entries[1]
        assertThat(bbc.name).isEqualTo("BBC World")
        assertThat(bbc.tvgId).isEqualTo("bbc")
        assertThat(bbc.tvgLogo).isNull()

        val sports = entries[2]
        assertThat(sports.name).isEqualTo("Sports Channel")
        assertThat(sports.groupTitle).isEqualTo("Sports")
    }

    @Test
    fun `parse_kodiAdaptiveDirectives_attachesPlaybackMetadataToEntry`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="DRM",Protected channel
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=https://license.example/key
            #KODIPROP:inputstream.adaptive.license_headers=Authorization=Bearer%20token
            #EXTVLCOPT:http-user-agent=Mozilla/5.0
            https://stream.example.com/channel.mpd
        """.trimIndent()

        val entry = parseEntries(m3u).single()

        assertThat(entry.playbackMetadata?.manifestType).isEqualTo(com.streamvault.domain.model.StreamType.DASH)
        assertThat(entry.playbackMetadata?.drmScheme).isEqualTo(com.streamvault.domain.model.DrmScheme.WIDEVINE)
        assertThat(entry.playbackMetadata?.licenseUrl).isEqualTo("https://license.example/key")
        assertThat(entry.playbackMetadata?.licenseHeaders?.get("Authorization")).isEqualTo("Bearer token")
        assertThat(entry.userAgent).isEqualTo("Mozilla/5.0")
    }

    @Test
    fun `parse_malformedEntry_skipsGracefully`() {
        // Second entry has no URL line — should be skipped
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="News",Good Channel
            http://stream.example.com/good.ts
            #EXTINF:-1 group-title="News",Bad Channel
            #EXTINF:-1 group-title="News",Another Good Channel
            http://stream.example.com/another.ts
        """.trimIndent()

        val entries = parseEntries(m3u)

        // "Bad Channel" is skipped since its "URL" line is another #EXTINF
        assertThat(entries.map { it.name }).containsExactly("Good Channel", "Another Good Channel")
    }

    @Test
    fun `parse_emptyInputStream_returnsEmptyList`() {
        val entries = parseEntries("")
        assertThat(entries).isEmpty()
    }

    @Test
    fun `parse_noExtinfHeader_skipsLines`() {
        // Bare URLs without #EXTINF preamble should be ignored
        val m3u = """
            http://stream.example.com/channel1.ts
            http://stream.example.com/channel2.ts
        """.trimIndent()

        val entries = parseEntries(m3u)
        assertThat(entries).isEmpty()
    }

    // ── Attribute parsing ─────────────────────────────────────────

    @Test
    fun `parse_quotedAttributes_extractsCorrectly`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Movies HD" tvg-id="movie1",Action Movie
            http://vod.example.com/movie1.mp4
        """.trimIndent()

        val entries = parseEntries(m3u)

        assertThat(entries).hasSize(1)
        assertThat(entries[0].groupTitle).isEqualTo("Movies HD")
    }

    @Test
    fun `parse_quotedAttributes_withSpacesOrSpecialChars`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Al Jazeera | Arabic" tvg-name="Al Jazeera",Al Jazeera Arabic
            http://stream.example.com/aljazeera.ts
        """.trimIndent()

        val entries = parseEntries(m3u)

        assertThat(entries).hasSize(1)
        assertThat(entries[0].groupTitle).isEqualTo("Al Jazeera | Arabic")
    }

    @Test
    fun `parse_extgrpDirective_between_extinf_and_url_setsGroupTitle`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cnn",CNN International
            #EXTGRP:News
            http://stream.example.com/cnn.m3u8
        """.trimIndent()

        val entry = parseEntries(m3u).single()

        assertThat(entry.groupTitle).isEqualTo("News")
    }

    @Test
    fun `parse_extgrpDirective_overrides_missing_groupTitle_even_with_other_directives`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="espn",ESPN
            #EXTVLCOPT:http-user-agent=CustomAgent
            #EXTGRP="Sports"
            http://stream.example.com/espn.m3u8
        """.trimIndent()

        val entry = parseEntries(m3u).single()

        assertThat(entry.groupTitle).isEqualTo("Sports")
    }

    @Test
    fun `parse_headerUrlTvg_extractsGuideUrl`() {
        val result = parser.parse(
            """
            #EXTM3U url-tvg="https://epg.example.com/guide.xml.gz"
            #EXTINF:-1 tvg-id="cnn" group-title="News",CNN
            http://stream.example.com/cnn.m3u8
            """.trimIndent().byteInputStream()
        )

        assertThat(result.header.tvgUrl).isEqualTo("https://epg.example.com/guide.xml.gz")
        assertThat(result.entries.single().tvgId).isEqualTo("cnn")
    }

    @Test
    fun `parse_headerAliases_extractGuideUrl`() {
        val variants = listOf("x-tvg-url", "tvg-url", "url-xml")

        variants.forEach { attribute ->
            val result = parser.parse(
                """
                #EXTM3U $attribute="https://epg.example.com/$attribute.xml.gz"
                #EXTINF:-1 group-title="News",CNN
                http://stream.example.com/cnn.m3u8
                """.trimIndent().byteInputStream()
            )

            assertThat(result.header.tvgUrl).isEqualTo("https://epg.example.com/$attribute.xml.gz")
        }
    }

    @Test
    fun `parse_headerWithUtf8Bom_extractsGuideUrl`() {
        val result = parser.parse(
            (
                "\uFEFF#EXTM3U x-tvg-url=\"https://epg.example.com/guide.xml\"\n" +
                    "#EXTINF:-1 tvg-id=\"foxlivenow_foxlivenow_US\" group-title=\"News\",LiveNOW from FOX\n" +
                    "https://stream.example.com/live.m3u8\n"
                ).byteInputStream()
        )

        assertThat(result.header.tvgUrl).isEqualTo("https://epg.example.com/guide.xml")
        assertThat(result.entries.single().tvgId).isEqualTo("foxlivenow_foxlivenow_US")
    }

    @Test
    fun `parse_headerWithMultipleEpgUrls_retainsDistinctUrlsInOrder`() {
        val result = parser.parse(
            """
            #EXTM3U x-tvg-url="https://epg.example.com/guide.xml.gz, https://backup.example.com/guide.xml"
            #EXTINF:-1 group-title="News",CNN
            http://stream.example.com/cnn.m3u8
            """.trimIndent().byteInputStream()
        )

        assertThat(result.header.tvgUrls).containsExactly(
            "https://epg.example.com/guide.xml.gz",
            "https://backup.example.com/guide.xml"
        ).inOrder()
    }

    @Test
    fun `parseStreaming_reportsMalformedCandidate`() = runBlocking {
        var invalidEntries = 0

        parser.parseStreaming(
            inputStream = """
                #EXTM3U
                #EXTINF:-1,Missing URL
                not-a-stream-url
            """.trimIndent().byteInputStream(),
            onEntry = { error("Expected malformed entry to be rejected") },
            onInvalidEntry = { invalidEntries++ }
        )

        assertThat(invalidEntries).isEqualTo(1)
    }

    @Test
    fun `parseStreaming_reportsReplacedAndUnterminatedCandidates`() = runBlocking {
        val entries = mutableListOf<M3uParser.M3uEntry>()
        var invalidEntries = 0

        parser.parseStreaming(
            inputStream = """
                #EXTM3U
                #EXTINF:-1,Replaced candidate
                #EXTINF:-1,Valid candidate
                https://stream.example.com/valid.ts
                #EXTINF:-1,Unterminated candidate
            """.trimIndent().byteInputStream(),
            onEntry = entries::add,
            onInvalidEntry = { invalidEntries++ }
        )

        assertThat(entries.map { it.name }).containsExactly("Valid candidate")
        assertThat(invalidEntries).isEqualTo(2)
    }

    @Test
    fun `parse_utf16Bom_decodesPlaylist`() {
        val playlist = "#EXTM3U\n#EXTINF:-1 tvg-id=\"cafe\" group-title=\"Café\",Café\nhttps://stream.example.com/cafe.m3u8\n"
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + playlist.toByteArray(Charsets.UTF_16LE)

        val result = parser.parse(utf16.inputStream())

        assertThat(result.entries.single().name).isEqualTo("Café")
        assertThat(result.entries.single().groupTitle).isEqualTo("Café")
    }

    @Test
    fun `parse_utf16BigEndianBom_decodesPlaylist`() {
        val playlist = "#EXTM3U\n#EXTINF:-1 tvg-id=\"cafe\" group-title=\"Café\",Café\nhttps://stream.example.com/cafe.m3u8\n"
        val utf16 = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + playlist.toByteArray(Charsets.UTF_16BE)

        val result = parser.parse(utf16.inputStream())

        assertThat(result.entries.single().name).isEqualTo("Café")
        assertThat(result.entries.single().groupTitle).isEqualTo("Café")
    }

    @Test
    fun `parse_windows1252WithoutCharset_usesLegacyFallback`() {
        val windows1252 = Charset.forName("windows-1252")
        val playlist = "#EXTM3U\n#EXTINF:-1 tvg-id=\"café-tv\" group-title=\"Ciné – FR\",Café Télévision\nhttps://stream.example.com/cafe.ts\n"

        val result = parser.parse(playlist.toByteArray(windows1252).inputStream())

        val entry = result.entries.single()
        assertThat(entry.name).isEqualTo("Café Télévision")
        assertThat(entry.groupTitle).isEqualTo("Ciné – FR")
        assertThat(entry.tvgId).isEqualTo("café-tv")
    }

    @Test
    fun `parse_honorsValidatedIso88591Charset`() {
        val iso88591 = Charsets.ISO_8859_1
        val playlist = "#EXTM3U\n#EXTINF:-1 group-title=\"Télévision\",Café\nhttps://stream.example.com/cafe.ts\n"

        val result = parser.parse(
            playlist.toByteArray(iso88591).inputStream(),
            declaredCharset = iso88591
        )

        assertThat(result.entries.single().name).isEqualTo("Café")
        assertThat(result.entries.single().groupTitle).isEqualTo("Télévision")
    }

    @Test
    fun `parse_bomTakesPrecedenceOverDeclaredCharset`() {
        val playlist = "#EXTM3U\n#EXTINF:-1,Café\nhttps://stream.example.com/cafe.ts\n"
        val utf8WithBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            playlist.toByteArray(Charsets.UTF_8)

        val result = parser.parse(
            utf8WithBom.inputStream(),
            declaredCharset = Charset.forName("windows-1252")
        )

        assertThat(result.entries.single().name).isEqualTo("Café")
    }

    @Test
    fun `parse_ignoresUnsupportedDeclaredCharsetAndUsesUtf8Policy`() {
        val playlist = "#EXTM3U\n#EXTINF:-1,Chaîne Été\nhttps://stream.example.com/ete.ts\n"

        val result = parser.parse(
            playlist.toByteArray(Charsets.UTF_8).inputStream(),
            declaredCharset = Charset.forName("UTF-32BE")
        )

        assertThat(result.entries.single().name).isEqualTo("Chaîne Été")
    }

    @Test
    fun `parse_invalidUtf8Sequence_fallsBackWithoutReplacementCharacters`() {
        val prefix = "#EXTM3U\n#EXTINF:-1 group-title=\"Legacy\",".toByteArray()
        val malformedName = byteArrayOf(0xC3.toByte(), 0x28)
        val suffix = "\nhttps://stream.example.com/legacy.ts\n".toByteArray()

        val result = parser.parse((prefix + malformedName + suffix).inputStream())

        assertThat(result.entries.single().name).isEqualTo("Ã(")
        assertThat(result.entries.single().name).doesNotContain("�")
    }

    @Test
    fun `parseStreaming_windows1252Fallback_preservesAccentedEpgIdentity`() = runBlocking {
        val entries = mutableListOf<M3uParser.M3uEntry>()
        val playlist = "#EXTM3U\n#EXTINF:-1 tvg-id=\"chaîne-é\" group-title=\"Télé\",Chaîne Été\nhttps://stream.example.com/ete.ts\n"

        parser.parseStreaming(
            inputStream = playlist.toByteArray(Charset.forName("windows-1252")).inputStream(),
            onEntry = entries::add
        )

        assertThat(entries.single().tvgId).isEqualTo("chaîne-é")
        assertThat(entries.single().name).isEqualTo("Chaîne Été")
    }

    @Test
    fun `parse_headerGuideUrls_deduplicatesAndCapsAtEightInSourceOrder`() {
        val urls = (1..10).map { "https://epg.example.com/$it.xml" }
        val headerUrls = (urls.take(2) + urls[0] + urls.drop(2)).joinToString(", ")
        val result = parser.parse(
            """
                #EXTM3U x-tvg-url="$headerUrls"
                #EXTINF:-1,News
                https://stream.example.com/news.ts
            """.trimIndent().byteInputStream()
        )

        assertThat(result.header.tvgUrls).containsExactlyElementsIn(urls.take(8)).inOrder()
    }

    @Test
    fun `parse_catchUpAttributes_extracted`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 catchup="xc" catchup-source="http://catchup.example.com/ch1/{utc}-{utcend}.ts" group-title="Live",Live Channel
            http://stream.example.com/ch1.ts
        """.trimIndent()

        val entries = parseEntries(m3u)

        assertThat(entries).hasSize(1)
        with(entries[0]) {
            assertThat(catchUp).isEqualTo("xc")
            assertThat(catchUpSource).isEqualTo("http://catchup.example.com/ch1/{utc}-{utcend}.ts")
        }
    }

    @Test
    fun `parseToChannels_marksCatchUpSupported_whenOnlyCatchUpSourceExists`() {
        val channels = parser.parseToChannels(
            """
            #EXTM3U
            #EXTINF:-1 catchup-source="http://catchup.example.com/ch1/{utc}-{utcend}.ts" group-title="Live",Live Channel
            http://stream.example.com/ch1.ts
            """.trimIndent().byteInputStream(),
            providerId = 99L
        )

        assertThat(channels).hasSize(1)
        assertThat(channels.single().catchUpSupported).isTrue()
    }

    @Test
    fun `parse_unknownAttributes_ignoredGracefully`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Live" tvg-language="en" custom-attr="custom" another="x",Live Channel
            http://stream.example.com/ch1.ts
        """.trimIndent()

        val entry = parseEntries(m3u).single()

        assertThat(entry.tvgLanguage).isEqualTo("en")
        assertThat(entry.name).isEqualTo("Live Channel")
    }

    @Test
    fun `parse_gzipPlaylist_returnsEntries`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Live",Compressed
            http://stream.example.com/compressed.ts
        """.trimIndent()

        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(m3u)
        }

        val entries = parseEntries(String(java.util.zip.GZIPInputStream(output.toByteArray().inputStream()).readBytes(), Charsets.UTF_8))
        assertThat(entries).hasSize(1)
        assertThat(entries.single().name).isEqualTo("Compressed")
    }

    @Test
    fun `parse_largeSyntheticPlaylist_returnsAllEntries`() {
        val count = 100_000
        val builder = StringBuilder("#EXTM3U\n")
        repeat(count) { index ->
            builder.append("#EXTINF:-1 group-title=\"Group ${index % 10}\",Channel ${index + 1}\n")
            builder.append("http://stream.example.com/ch${index + 1}.ts\n")
        }

        val entries = parseEntries(builder.toString())

        assertThat(entries).hasSize(count)
        assertThat(entries.first().name).isEqualTo("Channel 1")
        assertThat(entries.last().name).isEqualTo("Channel 100000")
    }

    @Test
    fun `parse_unicodeName_preserved`() {
        val m3u = "#EXTM3U\n" +
                "#EXTINF:-1 group-title=\"Arabic\",قناة الأولى\n" +
                "http://stream.example.com/arabic.ts\n" +
                "#EXTINF:-1 group-title=\"Hebrew\",ערוץ ראשון\n" +
                "http://stream.example.com/hebrew.ts"

        val entries = parseEntries(m3u)

        assertThat(entries).hasSize(2)
        assertThat(entries[0].name).isEqualTo("قناة الأولى")
        assertThat(entries[1].name).isEqualTo("ערוץ ראשון")
    }

    @Test
    fun `parse_tokenizedUrl_preserved`() {
        val tokenizedUrl = "http://stream.example.com/ch1.ts?token=abc123&expire=9999999999&uid=user42"
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Live",Tokenized Channel
            $tokenizedUrl
        """.trimIndent()

        val entries = parseEntries(m3u)

        assertThat(entries).hasSize(1)
        assertThat(entries[0].url).isEqualTo(tokenizedUrl)
    }

    // ── VOD detection (via SyncManager — canonical isVodEntry) ────

    @Test
    fun `isVodEntry_movieExtension_returnsTrue`() {
        val entries = parseEntries(buildPlaylist(
            "http://vod.example.com/avatar.mp4" to "Movies",
            "http://vod.example.com/inception.mkv" to "VOD",
            "http://vod.example.com/oldfilm.avi" to "Films"
        ))

        assertThat(entries).hasSize(3)
        entries.forEach { entry -> assertThat(M3uParser.isVodEntry(entry)).isTrue() }
    }

    @Test
    fun `isVodEntry_liveStream_returnsFalse`() {
        val entries = parseEntries(buildPlaylist(
            "http://live.example.com/ch1.ts" to "Live",
            "http://live.example.com/ch2.m3u8" to "Sports"
        ))

        assertThat(entries).hasSize(2)
        entries.forEach { entry -> assertThat(M3uParser.isVodEntry(entry)).isFalse() }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun buildPlaylist(vararg entries: Pair<String, String>): String {
        val sb = StringBuilder("#EXTM3U\n")
        entries.forEachIndexed { index, (url, group) ->
            sb.append("#EXTINF:-1 group-title=\"$group\",Channel ${index + 1}\n")
            sb.append("$url\n")
        }
        return sb.toString()
    }

    private fun parseEntries(m3u: String): List<M3uParser.M3uEntry> {
        return parser.parse(m3u.byteInputStream()).entries
    }
}
