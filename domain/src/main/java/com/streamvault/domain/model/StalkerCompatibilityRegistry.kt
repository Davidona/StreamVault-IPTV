package com.streamvault.domain.model

import java.util.Locale

enum class StalkerBrowserEngine { QT_WEBKIT, EKIOH, ANDROID }
enum class StalkerIdentityStrategy { MAC_ONLY, MANUAL_FIELDS_REQUIRED, API_V3_CREDENTIALS }

/**
 * A registry entry is an indivisible portal fingerprint. Experimental siblings intentionally
 * inherit a captured family fingerprint as a unit; individual firmware/metrics fields are never
 * mixed. They remain manual-only until a trace fixture promotes them to VERIFIED.
 */
data class StalkerCompatibilityProfile(
    val id: String,
    val revision: Int,
    val displayName: String,
    val generation: String,
    val model: String,
    val aliases: Set<String>,
    val protocolFamily: StalkerProtocolFamily,
    val verification: StalkerProfileVerification,
    val browserEngine: StalkerBrowserEngine,
    val identityStrategy: StalkerIdentityStrategy,
    val automaticRepresentative: Boolean,
    val baseFingerprintId: String? = null,
    val preset: StalkerMagPreset? = null,
    val bootstrapRecipe: StalkerBootstrapRecipe? = null
)

object StalkerCompatibilityRegistry {
    const val REVISION = 1
    const val MAX_AUTOMATIC_ATTEMPTS = 6

    private fun verified(
        id: String,
        name: String,
        generation: String,
        model: String,
        aliases: Set<String>,
        preset: StalkerMagPreset,
        recipe: StalkerBootstrapRecipe,
        automatic: Boolean = true
    ) = StalkerCompatibilityProfile(
        id, REVISION, name, generation, model, aliases,
        StalkerProtocolFamily.CLASSIC_MAG, StalkerProfileVerification.VERIFIED,
        StalkerBrowserEngine.QT_WEBKIT, StalkerIdentityStrategy.MAC_ONLY,
        automatic, preset = preset, bootstrapRecipe = recipe
    )

    private fun experimental(
        model: String,
        generation: String,
        base: String,
        aliases: Set<String> = emptySet(),
        engine: StalkerBrowserEngine = StalkerBrowserEngine.QT_WEBKIT
    ) = StalkerCompatibilityProfile(
        id = "classic.${model.lowercase()}",
        revision = REVISION,
        displayName = model,
        generation = generation,
        model = model,
        aliases = aliases,
        protocolFamily = StalkerProtocolFamily.CLASSIC_MAG,
        verification = StalkerProfileVerification.EXPERIMENTAL,
        browserEngine = engine,
        identityStrategy = StalkerIdentityStrategy.MANUAL_FIELDS_REQUIRED,
        automaticRepresentative = false,
        baseFingerprintId = base
    )

    private fun android(model: String, aliases: Set<String> = emptySet()) = StalkerCompatibilityProfile(
        id = "api-v3.${model.lowercase()}",
        revision = REVISION,
        displayName = "$model (Ministra API v3)",
        generation = "Android MAG",
        model = model,
        aliases = aliases,
        protocolFamily = StalkerProtocolFamily.MINISTRA_API_V3,
        verification = StalkerProfileVerification.EXPERIMENTAL,
        browserEngine = StalkerBrowserEngine.ANDROID,
        identityStrategy = StalkerIdentityStrategy.API_V3_CREDENTIALS,
        automaticRepresentative = false
    )

    val profiles: List<StalkerCompatibilityProfile> = listOf(
        verified(StalkerCompatibilityProfileIds.CLASSIC_MAG250_GENERIC, "MAG250 (Generic)", "MAG2xx", "MAG250", setOf("MAG245"), StalkerMagPreset.GENERIC_SAFE, StalkerBootstrapRecipe.GENERIC_SAFE),
        verified(StalkerCompatibilityProfileIds.CLASSIC_MAG250_LEGACY, "MAG250 (Legacy)", "MAG2xx", "MAG250", setOf("MAG245"), StalkerMagPreset.MAG250_LEGACY, StalkerBootstrapRecipe.LEGACY_MAG),
        verified(StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT, "MAG254 (Strict)", "MAG2xx", "MAG254", setOf("MAG255"), StalkerMagPreset.MAG254_STRICT, StalkerBootstrapRecipe.STRICT_MAG),
        verified(StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN, "MAG322 (Modern)", "MAG3xx", "MAG322", setOf("MAG323"), StalkerMagPreset.MINISTRA_MODERN, StalkerBootstrapRecipe.LOCALIZATION_STRICT),

        experimental("MAG200", "MAG2xx", StalkerCompatibilityProfileIds.CLASSIC_MAG250_LEGACY),
        experimental("MAG245", "MAG2xx", StalkerCompatibilityProfileIds.CLASSIC_MAG250_GENERIC),
        experimental("MAG255", "MAG2xx", StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT),
        experimental("MAG256", "MAG2xx", StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT, setOf("MAG257")),
        experimental("MAG257", "MAG2xx", StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT),
        experimental("MAG270", "MAG2xx", StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT, setOf("MAG275")),
        experimental("MAG275", "MAG2xx", StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT),
        experimental("MAG323", "MAG3xx", StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN),
        experimental("MAG324", "MAG3xx", StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN, setOf("MAG325")),
        experimental("MAG325", "MAG3xx", StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN),
        experimental("MAG349", "MAG3xx", StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN, setOf("MAG351")),
        experimental("MAG351", "MAG3xx", StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN),
        experimental("MAG420", "MAG4xx", StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN, setOf("MAG424")),
        experimental("MAG424", "MAG4xx", StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN),
        experimental("MAG520", "MAG5xx", StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN, setOf("MAG522", "MAG524")),
        experimental("MAG522", "MAG5xx", StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN),
        experimental("MAG524", "MAG5xx", StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN),
        experimental("MAG540", "MAG5xx", StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN, setOf("MAG544")),
        experimental("MAG544", "MAG5xx", StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN),
        experimental("MAG250-EKIOH", "MAG2xx Ekioh", StalkerCompatibilityProfileIds.CLASSIC_MAG250_GENERIC, engine = StalkerBrowserEngine.EKIOH),
        experimental("MAG255-EKIOH", "MAG2xx Ekioh", StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT, engine = StalkerBrowserEngine.EKIOH),

        android("MAG260"), android("MAG410"), android("MAG424A"), android("MAG425A"), android("MAG500A")
    )

    private val byId = profiles.associateBy { it.id }

    fun find(id: String?): StalkerCompatibilityProfile? = id?.trim()?.let(byId::get)

    fun findVerifiedByModelSignal(value: String?): StalkerCompatibilityProfile? {
        val normalized = value?.normalizeModelSignal()?.takeIf { it.isNotBlank() } ?: return null
        return profiles
            .asSequence()
            .filter {
                it.protocolFamily == StalkerProtocolFamily.CLASSIC_MAG &&
                    it.verification == StalkerProfileVerification.VERIFIED
            }
            .firstOrNull { profile ->
                profile.model.normalizeModelSignal() == normalized ||
                    profile.aliases.any { alias -> alias.normalizeModelSignal() == normalized }
            }
    }

    fun baseFingerprint(profile: StalkerCompatibilityProfile): StalkerCompatibilityProfile =
        profile.baseFingerprintId?.let(byId::get) ?: profile

    fun idForLegacyPreset(preset: StalkerMagPreset): String = when (preset) {
        StalkerMagPreset.GENERIC_SAFE -> StalkerCompatibilityProfileIds.CLASSIC_MAG250_GENERIC
        StalkerMagPreset.MAG250_LEGACY -> StalkerCompatibilityProfileIds.CLASSIC_MAG250_LEGACY
        StalkerMagPreset.MAG254_STRICT -> StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT
        StalkerMagPreset.MINISTRA_MODERN -> StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN
    }

    fun classicAutomaticOrder(preferredId: String?): List<StalkerCompatibilityProfile> = buildList {
        find(preferredId)?.takeIf { it.protocolFamily == StalkerProtocolFamily.CLASSIC_MAG }?.let(::add)
        listOf(
            StalkerCompatibilityProfileIds.CLASSIC_MAG250_GENERIC,
            StalkerCompatibilityProfileIds.CLASSIC_MAG250_LEGACY,
            StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT,
            StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN
        ).mapNotNull(::find).forEach(::add)
    }.distinctBy { it.id }.take(MAX_AUTOMATIC_ATTEMPTS)

    private fun String.normalizeModelSignal(): String =
        trim().uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}
