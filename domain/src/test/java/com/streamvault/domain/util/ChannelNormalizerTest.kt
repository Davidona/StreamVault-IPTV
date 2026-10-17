package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelNormalizerTest {

    @Test
    fun `import group ids remain identical to full playback classification`() {
        val names = listOf("", "   ", "BBC One", "Téléfilm", "שלום חדשות", "العربية", "😀", "+++", "1080p", "US: HBO Max",
            "CNN (US)", "|UK| Sky Sports 1", "Canal+", "[HD] News", "News &amp; Movies", "CHANNEL 60fps")
        val variants = listOf("", " HD", " FHD HEVC", " 4K HDR10 50fps", " [EN] Backup", " |FR| HLS VIP", " (8K Dolby Vision)")
        names.forEach { name -> variants.forEach { variant -> listOf(0L, 1L, 42L, Long.MAX_VALUE).forEach { provider ->
            val value = name + variant
            assertThat(ChannelNormalizer.getLogicalGroupId(value, provider))
                .isEqualTo(ChannelNormalizer.classify(value, provider, "https://stream.invalid/hls/1080.m3u8").logicalGroupId)
        } } }
    }

    @Test
    fun `tag detection preserves boundaries and case in names and stream URLs`() {
        val embedded = ChannelNormalizer.classify("Shadow HDSports Englishman", 1L)
        assertThat(embedded.canonicalName).isEqualTo("Shadow HDSports Englishman")
        assertThat(embedded.attributes.declaredHeight).isNull()
        assertThat(embedded.attributes.languageHint).isNull()

        val decorated = ChannelNormalizer.classify("Téléfilm [FR] fHd HeVc", 1L, "https://example.test/LIVE.M3U8")
        assertThat(decorated.canonicalName).isEqualTo("Téléfilm")
        assertThat(decorated.attributes.declaredHeight).isEqualTo(1080)
        assertThat(decorated.attributes.codecLabel).isEqualTo("HEVC")
        assertThat(decorated.attributes.transportLabel).isEqualTo("HLS")

        val url = ChannelNormalizer.classify("Cinema", 1L, "https://example.test/HEVC/2160p/channel.ts")
        assertThat(url.attributes.declaredHeight).isEqualTo(2160)
        assertThat(url.attributes.codecLabel).isEqualTo("HEVC")
        assertThat(url.attributes.transportLabel).isEqualTo("MPEG-TS")
    }

    @Test
    fun `strips HD quality tag`() {
        val id = ChannelNormalizer.getLogicalGroupId("BBC One HD", 1L)
        assertThat(id).isEqualTo("1_bbcone")
    }

    @Test
    fun `strips FHD tag and country prefix`() {
        val id = ChannelNormalizer.getLogicalGroupId("US: HBO Max FHD", 1L)
        assertThat(id).isEqualTo("1_hbomax")
    }

    @Test
    fun `strips pipe-delimited country code`() {
        val id = ChannelNormalizer.getLogicalGroupId("|UK| Sky Sports 1", 1L)
        assertThat(id).isEqualTo("1_skysports1")
    }

    @Test
    fun `strips parenthesized content`() {
        val id = ChannelNormalizer.getLogicalGroupId("CNN (US)", 1L)
        assertThat(id).isEqualTo("1_cnn")
    }

    @Test
    fun `strips bracketed content`() {
        val id = ChannelNormalizer.getLogicalGroupId("[HD] ESPN", 1L)
        assertThat(id).isEqualTo("1_espn")
    }

    @Test
    fun `removes accents`() {
        val id = ChannelNormalizer.getLogicalGroupId("Téléfilm", 1L)
        assertThat(id).isEqualTo("1_telefilm")
    }

    @Test
    fun `same channel different qualities normalize to same id`() {
        val hdId = ChannelNormalizer.getLogicalGroupId("BBC One HD", 1L)
        val sdId = ChannelNormalizer.getLogicalGroupId("BBC One SD", 1L)
        val fhdId = ChannelNormalizer.getLogicalGroupId("BBC One FHD", 1L)
        assertThat(hdId).isEqualTo(sdId)
        assertThat(sdId).isEqualTo(fhdId)
    }

    @Test
    fun `different providers have different ids`() {
        val id1 = ChannelNormalizer.getLogicalGroupId("BBC One", 1L)
        val id2 = ChannelNormalizer.getLogicalGroupId("BBC One", 2L)
        assertThat(id1).isNotEqualTo(id2)
    }

    @Test
    fun `strips multiple quality tags`() {
        val id = ChannelNormalizer.getLogicalGroupId("ESPN 4K HEVC", 1L)
        assertThat(id).isEqualTo("1_espn")
    }

    @Test
    fun `preserves channel numbers`() {
        val id = ChannelNormalizer.getLogicalGroupId("Sky Sports 1", 1L)
        assertThat(id).isEqualTo("1_skysports1")
    }

    @Test
    fun `strips colon-delimited country prefix`() {
        val id = ChannelNormalizer.getLogicalGroupId("FR: Canal+", 1L)
        assertThat(id).isEqualTo("1_canal")
    }

    @Test
    fun `classify extracts canonical name and variant metadata`() {
        val classification = ChannelNormalizer.classify("FR: Canal+ FHD HEVC 50FPS RAW", 9L)

        assertThat(classification.logicalGroupId).isEqualTo("9_canal")
        assertThat(classification.canonicalName).isEqualTo("Canal +")
        assertThat(classification.attributes.resolutionLabel).isEqualTo("1080p")
        assertThat(classification.attributes.declaredHeight).isEqualTo(1080)
        assertThat(classification.attributes.codecLabel).isEqualTo("HEVC")
        assertThat(classification.attributes.frameRate).isEqualTo(50)
        assertThat(classification.attributes.sourceHint).isEqualTo("Raw")
        assertThat(classification.attributes.regionHint).isEqualTo("FR")
        assertThat(classification.attributes.rawTags).containsAtLeast("1080p", "50fps", "HEVC", "Raw", "FR")
    }

    @Test
    fun `classify preserves region and language hints from bracketed tags`() {
        val classification = ChannelNormalizer.classify("|UK| BBC One (English) HD", 7L)

        assertThat(classification.logicalGroupId).isEqualTo("7_bbcone")
        assertThat(classification.canonicalName).isEqualTo("BBC One")
        assertThat(classification.attributes.regionHint).isEqualTo("UK")
        assertThat(classification.attributes.languageHint).isEqualTo("EN")
        assertThat(classification.attributes.resolutionLabel).isEqualTo("720p")
        assertThat(classification.attributes.rawTags).containsAtLeast("UK", "EN", "720p")
    }

    @Test
    fun `classify recognizes fullhd ultrahd 2k and numeric mid-tier tags`() {
        val fullHd = ChannelNormalizer.classify("Sports One fullhd", 1L)
        val ultraHd = ChannelNormalizer.classify("Cinema Ultra HD", 1L)
        val twoK = ChannelNormalizer.classify("Arena 2k", 1L)
        val fiveSeventySix = ChannelNormalizer.classify("News 576p", 1L)
        val fiveForty = ChannelNormalizer.classify("Docs 540p", 1L)

        assertThat(fullHd.attributes.declaredHeight).isEqualTo(1080)
        assertThat(ultraHd.attributes.declaredHeight).isEqualTo(2160)
        assertThat(twoK.attributes.declaredHeight).isEqualTo(1440)
        assertThat(fiveSeventySix.attributes.declaredHeight).isEqualTo(576)
        assertThat(fiveForty.attributes.declaredHeight).isEqualTo(540)
    }

    @Test
    fun `detects hash wrapped provider headers`() {
        assertThat(ChannelNormalizer.isHashWrappedHeader("#### GENERAL HD/4K ####")).isTrue()
        assertThat(ChannelNormalizer.isHashWrappedHeader("  ## SPORTS ##  ")).isTrue()
        assertThat(ChannelNormalizer.isHashWrappedHeader("#EXTINF:-1")).isFalse()
        assertThat(ChannelNormalizer.isHashWrappedHeader("Channel #1")).isFalse()
    }
}
