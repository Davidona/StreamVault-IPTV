package com.streamvault.domain.util

import com.streamvault.domain.model.LiveChannelVariantAttributes
import java.text.Normalizer
import java.util.Locale

data class ChannelClassification(
    val logicalGroupId: String,
    val canonicalName: String,
    val attributes: LiveChannelVariantAttributes
)

object ChannelNormalizer {
    private val bracketRegex = Regex("""\[(.*?)]|\((.*?)\)|\|(.*?)\|""")
    private val leadingRegionRegex = Regex("""^\s*([a-z]{2,3})\s*[:\-]\s*""", RegexOption.IGNORE_CASE)
    private val separatorRegex = Regex("""[\s_\-./]+""")
    private val collapseWhitespaceRegex = Regex("""\s+""")
    private val nonAlphaNumericRegex = Regex("""[^a-z0-9 ]""")
    private val frameRateRegex = Regex("""(?<!\d)(24|25|30|50|60)\s*fps(?!\d)""", RegexOption.IGNORE_CASE)
    private val heightRegex = Regex("""(?<!\d)(4320|2160|1440|1080|720|576|540|480|360|240)\s*p?(?!\d)""", RegexOption.IGNORE_CASE)
    private val plusRegex = Regex("""\+""")
    private val colonOrPipeRegex = Regex("""[:|]""")

    private val resolutionTags = linkedMapOf(
        "8k" to 4320,
        "4320p" to 4320,
        "uhd" to 2160,
        "ultra hd" to 2160,
        "ultrahd" to 2160,
        "4k" to 2160,
        "2160p" to 2160,
        "2k" to 1440,
        "qhd" to 1440,
        "1440p" to 1440,
        "full hd" to 1080,
        "fullhd" to 1080,
        "fhd" to 1080,
        "1080p" to 1080,
        "1080i" to 1080,
        "hd" to 720,
        "720p" to 720,
        "576p" to 576,
        "540p" to 540,
        "hq" to 576,
        "sd" to 576
    )
    private val codecTags = linkedMapOf(
        "dolby vision" to "Dolby Vision",
        "dv" to "Dolby Vision",
        "hdr10" to "HDR10",
        "hdr" to "HDR",
        "hevc" to "HEVC",
        "h265" to "HEVC",
        "x265" to "HEVC",
        "av1" to "AV1",
        "h264" to "H.264",
        "x264" to "H.264"
    )
    private val transportTags = linkedMapOf(
        "mpeg ts" to "MPEG-TS",
        "mpeg-ts" to "MPEG-TS",
        "ts" to "MPEG-TS",
        "hls" to "HLS",
        "m3u8" to "HLS"
    )
    private val sourceHintTags = linkedMapOf(
        "backup" to "Backup",
        "alt" to "Alternate",
        "alternate" to "Alternate",
        "raw" to "Raw",
        "lite" to "Lite",
        "mobile" to "Mobile",
        "test" to "Test",
        "low" to "Low",
        "vip" to "VIP",
        "premium" to "Premium",
        "pro" to "Pro"
    )
    private val languageTags = linkedMapOf(
        "en" to "EN",
        "eng" to "EN",
        "english" to "EN",
        "fr" to "FR",
        "fre" to "FR",
        "french" to "FR",
        "de" to "DE",
        "ger" to "DE",
        "german" to "DE",
        "it" to "IT",
        "ita" to "IT",
        "italian" to "IT",
        "es" to "ES",
        "esp" to "ES",
        "spanish" to "ES",
        "pt" to "PT",
        "por" to "PT",
        "portuguese" to "PT",
        "ar" to "AR",
        "ara" to "AR",
        "arabic" to "AR"
    )

    private val removableCanonicalPhrases = (
        resolutionTags.keys +
            codecTags.keys +
            transportTags.keys +
            sourceHintTags.keys +
            listOf("fps")
        )
        .sortedByDescending { it.length }
        .map { phrase -> Regex("""(?<![a-z0-9])${Regex.escape(phrase)}(?![a-z0-9])""", RegexOption.IGNORE_CASE) }

    fun getLogicalGroupId(channelName: String, providerId: Long): String {
        val originalName = channelName.trim().ifBlank { "Channel" }
        return buildLogicalGroupId(originalName, buildCanonicalName(originalName), providerId)
    }

    /**
     * Returns true when the channel name is wrapped in hash markers,
     * e.g. "#### GENERAL HD/4K ####". These are category-header entries
     * from some providers (like Strong8k) that are not actual playable channels.
     */
    fun isHashWrappedHeader(channelName: String): Boolean {
        val trimmed = channelName.trim()
        return trimmed.startsWith("##") && trimmed.endsWith("##")
    }

    fun classify(
        channelName: String,
        providerId: Long,
        streamUrl: String = ""
    ): ChannelClassification {
        val originalName = channelName.trim().ifBlank { "Channel" }
        val lowerName = originalName.lowercase(Locale.ROOT)
        val lowerUrl = streamUrl.lowercase(Locale.ROOT)

        val extractedTags = buildList {
            bracketRegex.findAll(originalName).forEach { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.let(::add)
            }
        }
        val regionHint = resolveRegionHint(originalName, extractedTags)
        val languageHint = resolveLanguageHint(lowerName, extractedTags)
        val declaredHeight = resolveDeclaredHeight(lowerName, lowerUrl)
        val frameRate = frameRateRegex.find(lowerName)?.groupValues?.get(1)?.toIntOrNull()
        val codecLabel = resolveCodecLabel(lowerName, lowerUrl)
        val transportLabel = resolveTransportLabel(lowerName, lowerUrl)
        val sourceHint = resolveSourceHint(lowerName)
        val rawTags = buildRawTags(
            declaredHeight = declaredHeight,
            frameRate = frameRate,
            codecLabel = codecLabel,
            transportLabel = transportLabel,
            sourceHint = sourceHint,
            regionHint = regionHint,
            languageHint = languageHint,
            lowerName = lowerName
        )

        val canonicalName = buildCanonicalName(originalName)
        return ChannelClassification(
            logicalGroupId = buildLogicalGroupId(originalName, canonicalName, providerId),
            canonicalName = canonicalName,
            attributes = LiveChannelVariantAttributes(
                resolutionLabel = declaredHeight?.let(::heightToResolutionLabel),
                declaredHeight = declaredHeight,
                qualityTier = declaredHeight?.let(::heightToQualityTier) ?: 0,
                codecLabel = codecLabel,
                transportLabel = transportLabel,
                frameRate = frameRate,
                isHdr = lowerName.contains("hdr") || lowerName.contains("dolby vision") || Regex("""(?<![a-z0-9])dv(?![a-z0-9])""").containsMatchIn(lowerName),
                sourceHint = sourceHint,
                regionHint = regionHint,
                languageHint = languageHint,
                rawTags = rawTags
            )
        )
    }

    private fun buildLogicalGroupId(originalName: String, canonicalName: String, providerId: Long): String {
        val logicalKey = canonicalName
            .stripAccents()
            .lowercase(Locale.ROOT)
            .replace(nonAlphaNumericRegex, " ")
            .replace(collapseWhitespaceRegex, " ")
            .trim()
            .replace(" ", "")
            .ifEmpty {
                originalName.stripAccents()
                    .lowercase(Locale.ROOT)
                    .replace(nonAlphaNumericRegex, "")
                    .ifBlank { "channel${providerId}" }
            }

        return "${providerId}_$logicalKey"
    }

    private fun buildCanonicalName(originalName: String): String {
        var cleaned = originalName
            .replace(bracketRegex, " ")
            .replace(leadingRegionRegex, " ")

        removableCanonicalPhrases.forEach { regex ->
            cleaned = cleaned.replace(regex, " ")
        }
        cleaned = heightRegex.replace(cleaned, " ")
        cleaned = frameRateRegex.replace(cleaned, " ")

        cleaned = cleaned
            .replace(plusRegex, " + ")
            .replace(colonOrPipeRegex, " ")
            .replace(separatorRegex, " ")
            .replace(collapseWhitespaceRegex, " ")
            .trim()

        return cleaned.ifBlank { originalName.trim() }
    }

    private fun resolveDeclaredHeight(lowerName: String, lowerUrl: String): Int? {
        val directHeight = heightRegex.find(lowerName)?.groupValues?.get(1)?.toIntOrNull()
        if (directHeight != null) {
            return directHeight
        }
        resolutionTags.forEach { (tag, height) ->
            val regex = Regex("""(?<![a-z0-9])${Regex.escape(tag)}(?![a-z0-9])""", RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(lowerName)) {
                return height
            }
        }
        return heightRegex.find(lowerUrl)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun resolveCodecLabel(lowerName: String, lowerUrl: String): String? =
        codecTags.entries.firstOrNull { (token, _) ->
            containsStandalone(lowerName, token) || containsStandalone(lowerUrl, token)
        }?.value

    private fun resolveTransportLabel(lowerName: String, lowerUrl: String): String? = when {
        lowerUrl.endsWith(".m3u8") || containsStandalone(lowerName, "m3u8") || containsStandalone(lowerName, "hls") -> "HLS"
        lowerUrl.endsWith(".ts") || containsStandalone(lowerName, "ts") || containsStandalone(lowerName, "mpeg ts") || containsStandalone(lowerName, "mpeg-ts") -> "MPEG-TS"
        else -> transportTags.entries.firstOrNull { (token, _) ->
            containsStandalone(lowerName, token) || containsStandalone(lowerUrl, token)
        }?.value
    }

    private fun resolveSourceHint(lowerName: String): String? =
        sourceHintTags.entries.firstOrNull { (token, _) -> containsStandalone(lowerName, token) }?.value

    private fun resolveRegionHint(originalName: String, extractedTags: List<String>): String? {
        val prefixMatch = leadingRegionRegex.find(originalName)
        val prefixCode = prefixMatch?.groupValues?.getOrNull(1)
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.length in 2..3 }
        if (prefixCode != null) {
            return prefixCode
        }
        return extractedTags.firstNotNullOfOrNull { tag ->
            val normalized = tag.trim().uppercase(Locale.ROOT)
            normalized.takeIf { it.length in 2..3 && it.all(Char::isLetter) }
        }
    }

    private fun resolveLanguageHint(lowerName: String, extractedTags: List<String>): String? {
        val extractedMatch = extractedTags.firstNotNullOfOrNull { tag ->
            languageTags[tag.trim().lowercase(Locale.ROOT)]
        }
        if (extractedMatch != null) {
            return extractedMatch
        }
        return languageTags.entries.firstOrNull { (token, _) -> containsStandalone(lowerName, token) }?.value
    }

    private fun buildRawTags(
        declaredHeight: Int?,
        frameRate: Int?,
        codecLabel: String?,
        transportLabel: String?,
        sourceHint: String?,
        regionHint: String?,
        languageHint: String?,
        lowerName: String
    ): List<String> = buildList {
        declaredHeight?.let { add(heightToResolutionLabel(it)) }
        frameRate?.let { add("${it}fps") }
        codecLabel?.let { add(it) }
        transportLabel?.let { add(it) }
        sourceHint?.let { add(it) }
        regionHint?.let { add(it) }
        languageHint?.let { if (it != regionHint) add(it) }
        if (lowerName.contains("hdr")) add("HDR")
        if (lowerName.contains("dolby vision") || Regex("""(?<![a-z0-9])dv(?![a-z0-9])""").containsMatchIn(lowerName)) {
            add("Dolby Vision")
        }
    }.distinct()

    private fun containsStandalone(text: String, token: String): Boolean {
        val regex = Regex("""(?<![a-z0-9])${Regex.escape(token)}(?![a-z0-9])""", RegexOption.IGNORE_CASE)
        return regex.containsMatchIn(text)
    }

    private fun heightToResolutionLabel(height: Int): String = when {
        height >= 4320 -> "8K"
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height > 0 -> "${height}p"
        else -> "Unknown"
    }

    private fun heightToQualityTier(height: Int): Int = when {
        height >= 4320 -> 7
        height >= 2160 -> 6
        height >= 1440 -> 5
        height >= 1080 -> 4
        height >= 720 -> 3
        height >= 576 -> 2
        else -> 1
    }

    private fun String.stripAccents(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
}
