package com.streamvault.feature.live.sports

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program

internal object SportsProgrammeClassifier {
    private val eventActionPattern = Regex(
        """\b(?:qualifying|practice|race|championship|tournament|round|fight\s+night|main\s+card|""" +
            """stage|playoffs?|grand\s+prix|world\s+series|finals?|semi[ -]?finals?|quarter[ -]?finals?|""" +
            """matchday|md\s*\d+|week\s+\d+|day\s+\d+|qf\s*\d+|open|asian\s+games|""" +
            """friday\s+fights|pro\s+portugal)\b|\bpremier\s+padel\b.*\bp[1-4]\b""",
        RegexOption.IGNORE_CASE,
    )
    private val tennisTourIdPattern = Regex("\\batp\\s*\\d{3,4}\\b", RegexOption.IGNORE_CASE)
    private val genericCategoryNames = setOf("sport", "sports", "live tv", "tv")

    private val sportsTerms = mapOf(
        SportType.AMERICAN_FOOTBALL to listOf("american football", "gridiron", "nfl"),
        SportType.FOOTBALL to listOf(
            "football", "soccer", "futbol", "fútbol", "futebol", "fußball", "fussball", "fudbal",
            "رياضة كرة القدم", "كرة القدم", "premier league", "champions league", "la liga",
            "laliga", "serie a", "bundesliga", "ligue 1", "mls", "fa cup", "europa league",
            "eredivisie", "primeira liga", "copa del rey", "copa libertadores", "copa america",
            "uefa nations league", "carabao cup", "efl cup", "saudi pro league",
            "roshn saudi league", "liga mx", "scottish premiership", "spfl premiership",
        ),
        SportType.BASKETBALL to listOf("basketball", "basket-ball", "nba", "wnba", "euroleague"),
        SportType.BASEBALL to listOf("baseball", "mlb"),
        SportType.HOCKEY to listOf("ice hockey", "hockey", "nhl"),
        SportType.TENNIS to listOf("tennis", "tenis", "atp", "wta", "grand slam"),
        SportType.MOTORSPORT to listOf("motorsport", "formula 1", "formula one", "f1", "grand prix", "nascar", "motogp", "rally"),
        SportType.MMA to listOf("mma", "ufc", "mixed martial arts", "one friday fights"),
        SportType.BOXING to listOf("boxing", "boxeo", "boxe"),
        SportType.CRICKET to listOf("cricket", "ipl"),
        SportType.RUGBY to listOf("rugby"),
        SportType.GOLF to listOf("golf", "pga tour", "lpga"),
        SportType.VOLLEYBALL to listOf("volleyball", "volleball"),
        SportType.HANDBALL to listOf("handball"),
        SportType.SNOOKER to listOf("snooker"),
        SportType.DARTS to listOf("darts"),
        SportType.CYCLING to listOf("cycling", "cyclisme", "ciclismo", "tour de france"),
        SportType.OTHER to listOf("gymnastics", "padel", "surfing", "world surf league", "wsl"),
    )

    private val sportsChannelTerms = listOf(
        "espn", "bein sports", "sky sports", "supersport", "sportsnet", "fox sports",
        "tnt sports", "dazn", "arena sport", "canal+ sport", "eurosport", "tennis channel",
        "golf channel", "nfl network", "nba tv", "mlb network", "nhl network",
    )
    private val normalizedSportsTerms = sportsTerms.mapValues { (_, terms) ->
        terms.map { " " + normalizeText(it) + " " }
    }
    private val normalizedSportsChannelTerms = sportsChannelTerms.map { " " + normalizeText(it) + " " }
    private val normalizedGenericSportsTerms = listOf(
        "sport", "sports", "رياضة", "спорт", "deportes", "desporto",
    ).map { " " + normalizeText(it) + " " }

    class ChannelContext internal constructor(
        val channel: Channel,
        val detectedSport: SportType,
        val isSportsChannel: Boolean,
    )

    fun prepareChannel(channel: Channel): ChannelContext {
        val normalizedText = normalizeText(listOfNotNull(channel.name, channel.groupTitle).joinToString(" "))
        val paddedChannelText = " $normalizedText "
        val isSportsChannel = normalizedSportsChannelTerms.any { term ->
            containsNormalizedTerm(paddedChannelText, term)
        } || containsNormalizedTerm(paddedChannelText, " sport ") ||
            containsNormalizedTerm(paddedChannelText, " sports ")
        return ChannelContext(
            channel = channel,
            detectedSport = detectNormalizedSport(normalizedText),
            isSportsChannel = isSportsChannel,
        )
    }

    fun classify(program: Program, channel: Channel): SportsClassification =
        classify(program, prepareChannel(channel))

    fun classify(program: Program, channelContext: ChannelContext): SportsClassification {
        val channel = channelContext.channel
        val title = program.title.trim()
        val titleText = normalizeText(title)
        val programText = normalizeText(
            listOfNotNull(program.title, program.description, program.genre, program.category)
                .joinToString(" "),
        )
        val league = SportsLeagueRegistry.detectNormalized(titleText)
            ?: SportsLeagueRegistry.detectNormalized(
                normalizeText(listOfNotNull(program.genre, program.category).joinToString(" ")),
            )
            ?: SportsLeagueRegistry.detectNormalized(normalizeText(program.description))
            ?: SportsLeagueRegistry.detectNormalized(programText)
        val sport = detectNormalizedSport(programText).takeUnless { it == SportType.UNKNOWN }
            ?: if (tennisTourIdPattern.containsMatchIn(programText)) SportType.TENNIS else null
            ?: league?.sport
            ?: channelContext.detectedSport
        val matchup = SportsEventParser.extractMatchup(title)
        val hasSportsCategory = listOfNotNull(program.genre, program.category)
            .any { isSportsCategory(it) || detectSport(it) != SportType.UNKNOWN }
        val hasSportsChannel = channelContext.isSportsChannel
        val hasSportTerm = sport != SportType.UNKNOWN || containsNormalizedGenericSportsTerm(programText)
        val isSports = hasSportsCategory || hasSportsChannel || hasSportTerm
        val isGenericTitle = SportsEventParser.isGenericEventTitle(titleText)
        val hasCompetitionSignal = !isGenericTitle && (
            SportsEventParser.hasCompetitionTerm(titleText) ||
                matchup != null || titleText.contains("grand prix")
            )
        val hasCompetitiveEventSignal = !isGenericTitle &&
            (matchup != null || eventActionPattern.containsMatchIn(titleText))
        val eventType = SportsEventParser.classifyEventType(program, hasCompetitiveEventSignal)
        val isProgrammeShow = eventType in setOf(
            SportsEventType.REPLAY,
            SportsEventType.HIGHLIGHTS,
            SportsEventType.PREVIEW,
            SportsEventType.PRE_GAME,
            SportsEventType.POST_GAME,
            SportsEventType.SPORTS_NEWS,
            SportsEventType.SPORTS_SHOW,
        )
        val isCompetitiveEvent = isSports && !isProgrammeShow && hasCompetitiveEventSignal &&
            (matchup != null || hasSportTerm || hasSportsCategory || hasSportsChannel)
        val reasons = buildSet {
            if (hasSportsCategory) add(SportsClassificationReason.SPORTS_CATEGORY)
            if (hasSportTerm) add(SportsClassificationReason.SPORT_TERM)
            if (hasSportsChannel) add(SportsClassificationReason.SPORTS_CHANNEL)
            if (matchup != null) add(SportsClassificationReason.MATCHUP)
            if (league != null) add(SportsClassificationReason.LEAGUE)
            if (hasCompetitionSignal) add(SportsClassificationReason.COMPETITION_TERM)
            if (isProgrammeShow) add(SportsClassificationReason.PROGRAMME_SHOW)
        }
        val categoryLabel = sequenceOf(program.category, program.genre, channel.groupTitle)
            .filterNotNull()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() && normalizeText(it) !in genericCategoryNames }

        return SportsClassification(
            isSports = isSports,
            isCompetitiveEvent = isCompetitiveEvent,
            sport = sport,
            displayTitle = matchup?.let { "${it.first} vs ${it.second}" } ?: SportsEventParser.displayTitle(title),
            competitors = matchup?.toList().orEmpty(),
            league = league?.displayName,
            categoryLabel = categoryLabel,
            confidence = when {
                matchup != null && league != null -> 1.0f
                matchup != null && hasSportsCategory -> 0.98f
                matchup != null -> 0.88f
                league != null && hasCompetitiveEventSignal -> 0.82f
                hasSportsCategory && hasSportTerm -> 0.84f
                hasSportsCategory -> 0.70f
                hasSportsChannel && hasCompetitiveEventSignal -> 0.66f
                hasSportTerm && hasCompetitiveEventSignal -> 0.62f
                else -> 0.45f
            },
            reasons = reasons,
            eventType = eventType,
            session = SportsEventParser.extractSession(title, sport),
            leagueInfo = league,
        )
    }

    private fun containsNormalizedTerm(searchText: String, paddedTerm: String): Boolean =
        paddedTerm.isNotBlank() && searchText.contains(paddedTerm)

    fun eventIdentity(classification: SportsClassification, sourceKey: String): String =
        SportsEventParser.eventIdentity(classification, sourceKey)

    /** Uses Plan 2's sport aliases for provider names; unknown values remain unknown. */
    fun sportForProvider(value: String?): SportType = value?.let(::detectSport) ?: SportType.UNKNOWN

    fun canonicalEventTitle(value: String): String = SportsEventParser.canonicalEventTitle(value)

    fun isGenericEventTitle(value: String): Boolean =
        SportsEventParser.isGenericEventTitle(SportsEventParser.canonicalEventTitle(value))

    private fun isSportsCategory(value: String): Boolean {
        val normalized = normalizeText(value)
        return normalized.contains("sport") || normalized in setOf(
            "رياضة", "رياضة عامة", "deportes", "desporto", "sporto", "спорт", "спортивные",
            "urheilu", "sports", "athletics",
        )
    }

    private fun detectSport(text: String): SportType =
        detectNormalizedSport(normalizeText(text))

    private fun detectNormalizedSport(normalizedText: String): SportType {
        val normalized = " " + normalizedText + " "
        return normalizedSportsTerms.entries.firstOrNull { (_, terms) ->
            terms.any { term -> containsNormalizedTerm(normalized, term) }
        }?.key ?: SportType.UNKNOWN
    }

    private fun containsNormalizedGenericSportsTerm(normalizedText: String): Boolean {
        val normalized = " " + normalizedText + " "
        return normalizedGenericSportsTerms.any { term -> containsNormalizedTerm(normalized, term) }
    }

    private fun normalizeText(value: String): String = SportsTextNormalizer.normalize(value)
}
