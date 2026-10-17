package com.streamvault.feature.live.sports

import com.streamvault.domain.model.Program
import java.util.Locale

/** Parses the event-shaped parts of an EPG programme without assuming team sports. */
internal object SportsEventParser {
    private val matchupPattern = Regex(
        """(?i)(.+?)\s+(?:vs\.?|versus|v\.?|@|at|x)\s+(.+)$""",
    )
    private val hyphenMatchupPattern = Regex("\\s+[–—-]\\s+")
    private val compactHyphenMatchupPattern = Regex("(?<=\\S)-(?=\\S)")
    private val etapaSuffixPattern = Regex("\\s+etapa\\s*\\d+\\s*$", RegexOption.IGNORE_CASE)
    private val competitorHeaderSeparatorPattern = Regex("\\s+[-–—|]\\s+")
    private val broadcastTimePattern = Regex(
        """\b(?:[01]?\d|2[0-3]):[0-5]\d\s*(?:[ap](?:\.?m\.?)?)?\b|\b(?:1[0-2]|[1-9])\s*[ap]\.?(?:m\.?)?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val competitionPattern = Regex(
        """\b(?:live|match|game|finals?|semi[ -]?finals?|quarter[ -]?finals?|grand\s+prix|""" +
            """qualifying|practice|race|championship|tournament|round|fight\s+night|main\s+card|""" +
            """stage|playoffs?|world\s+series|premier\s+league|champions\s+league|la\s+liga|""" +
            """serie\s+a|bundesliga|ligue\s+1|fa\s+cup|europa\s+league|eredivisie|""" +
            """primeira\s+liga|copa\s+del\s+rey|copa\s+libertadores|copa\s+america|""" +
            """nations\s+league|carabao\s+cup|efl\s+cup|saudi\s+pro\s+league|""" +
            """liga\s+mx|scottish\s+premiership|spfl\s+premiership)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val qualitySuffixPattern = Regex(
        """\b(?:hd|fhd|uhd|4k|8k|hevc|h\.?265|backup|vip|raw|live)\b.*$""",
        RegexOption.IGNORE_CASE,
    )
    private val competitorPrefixPattern = Regex(
        "(?i)^\\s*(?:football|soccer|basketball|nba|nfl|laliga|premier league)\\s*[:–—-]\\s*",
    )
    private val competitorDecorationPattern = Regex("\\s*[\\[(].*?[\\])](?=\\s*$)")
    private val displayQualitySuffixPattern = Regex(
        "(?i)\\s+\\b(?:hd|fhd|uhd|4k|8k|hevc|h\\.?265|backup|vip|raw)\\b.*$",
    )
    private val eventNoisePattern = Regex(
        "\\b(?:live|hd|fhd|uhd|4k|8k|football|soccer|basketball|match|game)\\b",
    )
    private val whitespacePattern = Regex("\\s+")
    private val hyphenatedYearPattern = Regex("\\b(?:19|20)\\d{2}(?:\\s*[-/]\\s*(?:\\d{2}|20\\d{2}))?\\b")
    private val numericOnlyPattern = Regex("^\\d+$")
    private val explicitHeaderPrefixes = setOf(
        "football", "soccer", "basketball", "tennis", "golf", "motorsport", "f1", "nba", "nfl", "laliga",
    )

    private val replayPattern = Regex(
        """\b(?:replay|re-run|rerun|repeat|encore|classic\s+match)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val highlightsPattern = Regex(
        """\b(?:highlights?|best\s+of)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val previewPattern = Regex("\\bpreview\\b", RegexOption.IGNORE_CASE)
    private val preGamePattern = Regex("\\bpre[ -]?game\\b|\\bpregame\\b", RegexOption.IGNORE_CASE)
    private val postGamePattern = Regex("\\bpost[ -]?(?:game|match)\\b", RegexOption.IGNORE_CASE)
    private val sportsNewsPattern = Regex("\\b(?:sports?\\s*news|sportscenter|newscast|weather|traffic)\\b", RegexOption.IGNORE_CASE)
    private val sportsShowPattern = Regex(
        """\b(?:match\s+of\s+the\s+day|analysis|review|magazine|weekly|inside|coverage|exclusive|""" +
            """home\s+of|studio|roundup|wrap\s*up|intermission|break|prestavka|morning\s+edition|""" +
            """evening\s+edition|sur\s+canal|talk\s*show|documentary)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val explicitLivePattern = Regex("\\blive\\b", RegexOption.IGNORE_CASE)

    private val sprintQualifyingPattern = Regex("\\bsprint\\s+qualifying\\b", RegexOption.IGNORE_CASE)
    private val practiceNumberPattern = Regex("\\b(?:fp|practice\\s*)([1-3])\\b", RegexOption.IGNORE_CASE)
    private val golfRoundPattern = Regex("\\bround\\s*([1-4])\\b", RegexOption.IGNORE_CASE)
    private val golfDayPattern = Regex("\\bday\\s*([1-4])\\b", RegexOption.IGNORE_CASE)
    private val tennisRoundOfPattern = Regex("\\bround\\s+of\\s+(16|32|64|128)\\b", RegexOption.IGNORE_CASE)
    private val tennisQuarterfinalRoundPattern = Regex("\\bqf\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)
    private val tennisQuarterfinalPattern = Regex("\\bquarter[ -]?finals?\\b", RegexOption.IGNORE_CASE)
    private val tennisSemifinalPattern = Regex("\\bsemi[ -]?finals?\\b", RegexOption.IGNORE_CASE)
    private val numberedStagePattern = Regex("\\bstage\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)
    private val numberedRoundPattern = Regex("\\bround\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)
    private val numberedDayPattern = Regex("\\bday\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)
    private val etapaSessionPattern = Regex("\\betapa\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)

    fun extractMatchup(title: String): Pair<String, String>? {
        val explicitMatch = matchupPattern.find(title)
        if (explicitMatch != null) {
            val first = cleanCompetitorName(explicitMatch.groupValues[1].substringAfterLast(':').trim())
            val second = cleanCompetitorName(explicitMatch.groupValues[2])
            if (broadcastTimePattern.containsMatchIn(first) || broadcastTimePattern.containsMatchIn(second)) {
                return null
            }
            return first.takeIf(String::isNotBlank)?.let { cleanedFirst ->
                second.takeIf(String::isNotBlank)?.let { cleanedSecond -> cleanedFirst to cleanedSecond }
            }
        }

        val colonBody = title.substringAfterLast(':').trim().takeIf { ':' in title }
        val titleBodies = buildList {
            add(stripCompetitionTitleHeader(title) to false)
            colonBody?.let { add(it to true) }
        }.distinct()
        val matchingBody = titleBodies.firstNotNullOfOrNull { (body, isColonBody) ->
            val withoutRoundSuffix = etapaSuffixPattern.replace(body, "").trim()
            when {
                hyphenMatchupPattern.findAll(withoutRoundSuffix).count() == 1 ->
                    withoutRoundSuffix to false
                isColonBody && compactHyphenMatchupPattern.findAll(withoutRoundSuffix).count() == 1 ->
                    withoutRoundSuffix to true
                else -> null
            }
        } ?: return null
        val titleBody = matchingBody.first
        val sides = if (matchingBody.second) {
            compactHyphenMatchupPattern.split(titleBody, limit = 2)
        } else {
            hyphenMatchupPattern.split(titleBody, limit = 2)
        }
        if (sides.size != 2) return null
        val first = cleanCompetitorName(sides[0])
        val second = cleanCompetitorName(sides[1])
        if (first.length < 2 || second.length < 2) return null
        val normalizedFirst = normalizeText(first)
        val normalizedSecond = normalizeText(second)
        if (listOf(normalizedFirst, normalizedSecond).any { side ->
                competitionPattern.containsMatchIn(side) ||
                    hyphenatedYearPattern.containsMatchIn(side) ||
                    numericOnlyPattern.matches(side)
            }) return null
        if (normalizeTeamName(first) == normalizeTeamName(second)) return null
        return first to second
    }

    fun classifyEventType(program: Program, hasCompetitiveSignal: Boolean): SportsEventType {
        val title = program.title.trim()
        val signals = listOf(title, program.category.orEmpty(), program.genre.orEmpty())
        return when {
            signals.any(replayPattern::containsMatchIn) -> SportsEventType.REPLAY
            signals.any(highlightsPattern::containsMatchIn) -> SportsEventType.HIGHLIGHTS
            signals.any(preGamePattern::containsMatchIn) -> SportsEventType.PRE_GAME
            signals.any(postGamePattern::containsMatchIn) -> SportsEventType.POST_GAME
            signals.any(previewPattern::containsMatchIn) -> SportsEventType.PREVIEW
            signals.any(sportsNewsPattern::containsMatchIn) -> SportsEventType.SPORTS_NEWS
            signals.any(sportsShowPattern::containsMatchIn) -> SportsEventType.SPORTS_SHOW
            hasCompetitiveSignal && explicitLivePattern.containsMatchIn(title) -> SportsEventType.LIVE_EVENT
            hasCompetitiveSignal -> SportsEventType.SCHEDULED_EVENT
            else -> SportsEventType.UNKNOWN
        }
    }

    fun isGenericEventTitle(title: String): Boolean = normalizeText(title) in genericEventTitles

    fun hasCompetitionTerm(title: String): Boolean = competitionPattern.containsMatchIn(normalizeText(title))

    fun extractSession(title: String, sport: SportType): String? {
        val normalized = normalizeText(title)
        return when (sport) {
            SportType.MOTORSPORT -> motorsportSession(normalized)
            SportType.GOLF -> golfSession(normalized)
            SportType.TENNIS -> tennisSession(normalized)
            SportType.MMA, SportType.BOXING -> combatSession(normalized)
            else -> genericSession(normalized)
        }
    }

    fun displayTitle(title: String): String = title
        .replace(displayQualitySuffixPattern, "")
        .trim()
        .ifBlank { title.trim() }

    fun eventIdentity(classification: SportsClassification, sourceKey: String): String {
        val normalizedCompetitors = classification.competitors
            .map(::normalizeTeamName)
            .filter(String::isNotBlank)
        val hasCompetitorIdentity = normalizedCompetitors.size >= 2
        val eventName = if (hasCompetitorIdentity) {
            normalizedCompetitors.sorted().joinToString("|")
        } else {
            normalizeEventTitle(classification.displayTitle)
                .ifBlank { normalizeText(classification.categoryLabel.orEmpty()) }
        }
        val typeIdentity = classification.eventType.identityFamily
        val sessionIdentity = if (hasCompetitorIdentity) "" else {
            classification.session
                ?.let(::normalizeText)
                ?.takeIf(String::isNotBlank)
                .orEmpty()
        }
        val sourceDisambiguator = if (
            !hasCompetitorIdentity && (eventName.length < 4 || eventName in genericEventTitles)
        ) "|source:$sourceKey" else ""
        return "${classification.sport.name.lowercase(Locale.ROOT)}|$typeIdentity|$eventName|session:$sessionIdentity$sourceDisambiguator"
    }

    fun canonicalEventTitle(value: String): String = normalizeEventTitle(value)

    fun canonicalCompetitor(value: String): String = normalizeTeamName(value)

    private fun motorsportSession(normalized: String): String? = when {
        sprintQualifyingPattern.containsMatchIn(normalized) -> "Sprint Qualifying"
        practiceNumberPattern.find(normalized) != null ->
            "Practice ${practiceNumberPattern.find(normalized)!!.groupValues[1]}"
        containsWord(normalized, "sprint") -> "Sprint"
        containsWord(normalized, "qualifying") -> "Qualifying"
        containsWord(normalized, "race") -> "Race"
        containsWord(normalized, "practice") -> "Practice"
        else -> genericSession(normalized)
    }

    private fun golfSession(normalized: String): String? = when {
        containsPhrase(normalized, "final round") -> "Final Round"
        golfRoundPattern.find(normalized) != null -> "Round ${golfRoundPattern.find(normalized)!!.groupValues[1]}"
        golfDayPattern.find(normalized) != null -> "Day ${golfDayPattern.find(normalized)!!.groupValues[1]}"
        containsPhrase(normalized, "final day") -> "Final Day"
        else -> genericSession(normalized)
    }

    private fun tennisSession(normalized: String): String? = when {
        tennisRoundOfPattern.find(normalized) != null -> "Round of ${tennisRoundOfPattern.find(normalized)!!.groupValues[1]}"
        tennisQuarterfinalRoundPattern.find(normalized) != null ->
            "Quarterfinal ${tennisQuarterfinalRoundPattern.find(normalized)!!.groupValues[1]}"
        tennisQuarterfinalPattern.containsMatchIn(normalized) -> "Quarterfinals"
        tennisSemifinalPattern.containsMatchIn(normalized) -> "Semifinals"
        containsWord(normalized, "final") || containsWord(normalized, "finals") -> "Final"
        numberedRoundPattern.find(normalized) != null -> "Round ${numberedRoundPattern.find(normalized)!!.groupValues[1]}"
        else -> genericSession(normalized)
    }

    private fun combatSession(normalized: String): String? = when {
        containsPhrase(normalized, "early prelims") -> "Early Prelims"
        containsPhrase(normalized, "main card") -> "Main Card"
        containsPhrase(normalized, "preliminary card") || containsWord(normalized, "prelims") -> "Prelims"
        else -> genericSession(normalized)
    }

    private fun genericSession(normalized: String): String? = when {
        etapaSessionPattern.find(normalized) != null ->
            "Etapa ${etapaSessionPattern.find(normalized)!!.groupValues[1]}"
        numberedStagePattern.find(normalized) != null -> "Stage ${numberedStagePattern.find(normalized)!!.groupValues[1]}"
        numberedRoundPattern.find(normalized) != null -> "Round ${numberedRoundPattern.find(normalized)!!.groupValues[1]}"
        numberedDayPattern.find(normalized) != null -> "Day ${numberedDayPattern.find(normalized)!!.groupValues[1]}"
        containsPhrase(normalized, "group stage") -> "Group Stage"
        containsWord(normalized, "race") -> "Race"
        containsWord(normalized, "qualifying") -> "Qualifying"
        containsPhrase(normalized, "main card") -> "Main Card"
        else -> null
    }

    private fun containsWord(value: String, word: String): Boolean = " $value ".contains(" $word ")

    private fun containsPhrase(value: String, phrase: String): Boolean = " $value ".contains(" $phrase ")

    private fun cleanCompetitorName(value: String): String = value
        .let(::stripCompetitionHeader)
        .replace(competitorPrefixPattern, "")
        .replace(competitorDecorationPattern, "")
        .replace(qualitySuffixPattern, "")
        .trim()

    private fun stripCompetitionHeader(value: String): String {
        val separators = competitorHeaderSeparatorPattern.findAll(value).toList()
        val trailingHeaderCompetitor = separators.firstNotNullOfOrNull { separator ->
            val competitor = value.substring(0, separator.range.first).trim()
            val header = value.substring(separator.range.last + 1).trim()
            competitor.takeIf {
                it.isNotBlank() && SportsLeagueRegistry.detectNormalized(normalizeText(header)) != null
            }
        }
        if (trailingHeaderCompetitor != null) return trailingHeaderCompetitor

        return separators.lastOrNull { separator ->
            SportsLeagueRegistry.detectNormalized(normalizeText(value.substring(0, separator.range.first))) != null
        }?.let { separator ->
            value.substring(separator.range.last + 1).trim().ifBlank { value }
        } ?: value
    }

    private fun stripCompetitionTitleHeader(value: String): String {
        val prefix = value.substringBefore(':').trim()
        val normalizedPrefix = normalizeText(prefix)
        if (normalizedPrefix in explicitHeaderPrefixes || SportsLeagueRegistry.detectNormalized(normalizedPrefix) != null) {
            return value.substringAfter(':').trim()
        }
        return value.trim()
    }

    private fun normalizeTeamName(value: String): String = SportsAliasRegistry.normalizeTeamName(value)

    private fun normalizeEventTitle(value: String): String {
        val normalizedTitle = normalizeText(stripCompetitionTitleHeader(value))
            .replace(eventNoisePattern, " ")
            .replace(whitespacePattern, " ")
            .trim()
        return normalizedTitle.ifBlank { normalizeText(value) }
    }

    private fun normalizeText(value: String): String = SportsTextNormalizer.normalize(value)

    private val genericEventTitles = setOf(
        "sports", "live sports", "sports live", "live", "match", "the match", "live match", "game", "the game", "the final", "live game",
        "final", "race", "qualifying", "practice", "football", "live football", "football live",
        "soccer", "live soccer", "soccer live", "basketball", "live basketball", "basketball live",
        "baseball", "hockey", "tennis", "cricket", "rugby", "golf", "motorsport",
    )
}

private val SportsEventType.identityFamily: String
    get() = when (this) {
        SportsEventType.REPLAY -> "replay"
        SportsEventType.HIGHLIGHTS -> "highlights"
        SportsEventType.PREVIEW -> "preview"
        SportsEventType.PRE_GAME -> "pre-game"
        SportsEventType.POST_GAME -> "post-game"
        SportsEventType.SPORTS_NEWS -> "sports-news"
        SportsEventType.SPORTS_SHOW -> "sports-show"
        SportsEventType.LIVE_EVENT,
        SportsEventType.SCHEDULED_EVENT,
        SportsEventType.UNKNOWN,
        -> "competition"
    }
