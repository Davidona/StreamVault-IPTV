package com.streamvault.domain.settings

/** Product profile used as the global starting point for adaptive video selection. */
enum class PlaybackQualityProfile {
    AUTO,
    DATA_SAVER,
    BALANCED,
    CUSTOM
}

/** A maximum video height and advertised video bitrate. Null means no limit in that dimension. */
data class PlaybackQualityLimit(
    val maxVideoHeight: Int? = null,
    val maxVideoBitrateBps: Long? = null
) {
    fun normalized(): PlaybackQualityLimit = copy(
        maxVideoHeight = maxVideoHeight?.takeIf { it > 0 },
        maxVideoBitrateBps = maxVideoBitrateBps?.takeIf { it > 0L }
    )

    companion object {
        val NONE = PlaybackQualityLimit()
    }
}

/**
 * Persisted global quality controls. The existing Wi-Fi and Ethernet height values remain the
 * source of truth for those limits so upgrading does not duplicate or lose their settings.
 */
data class PlaybackQualityPreferences(
    val profile: PlaybackQualityProfile = PlaybackQualityProfile.AUTO,
    val customLimit: PlaybackQualityLimit = PlaybackQualityLimit.NONE,
    val wifiLimit: PlaybackQualityLimit = PlaybackQualityLimit.NONE,
    val ethernetLimit: PlaybackQualityLimit = PlaybackQualityLimit.NONE
) {
    fun normalized(): PlaybackQualityPreferences = copy(
        customLimit = customLimit.normalized(),
        wifiLimit = wifiLimit.normalized(),
        ethernetLimit = ethernetLimit.normalized()
    )

    fun profileLimit(): PlaybackQualityLimit = when (profile) {
        PlaybackQualityProfile.AUTO -> PlaybackQualityLimit.NONE
        PlaybackQualityProfile.DATA_SAVER -> PlaybackQualityLimit(480, 2_000_000L)
        PlaybackQualityProfile.BALANCED -> PlaybackQualityLimit(720, 4_000_000L)
        PlaybackQualityProfile.CUSTOM -> customLimit.normalized()
    }

    fun connectionLimit(connection: PlaybackConnectionType): PlaybackQualityLimit? = when (connection) {
        PlaybackConnectionType.WIFI -> wifiLimit.normalized()
        PlaybackConnectionType.ETHERNET -> ethernetLimit.normalized()
        PlaybackConnectionType.UNCLASSIFIED,
        PlaybackConnectionType.OFFLINE -> null
    }
}

enum class PlaybackConnectionType {
    WIFI,
    ETHERNET,
    UNCLASSIFIED,
    OFFLINE
}

/** Inherit keeps the parent field, NoAdditionalLimit removes it, and Maximum replaces it. */
sealed interface QualityLimitOverride<out T> {
    data object Inherit : QualityLimitOverride<Nothing>
    data object NoAdditionalLimit : QualityLimitOverride<Nothing>
    data class Maximum<T>(val value: T) : QualityLimitOverride<T>
}

data class ScopedPlaybackQualityPreference(
    val maxVideoHeight: QualityLimitOverride<Int> = QualityLimitOverride.Inherit,
    val maxVideoBitrateBps: QualityLimitOverride<Long> = QualityLimitOverride.Inherit
)

/**
 * Durable preference identity. Content keys are provider supplied stable references, never local
 * Room row ids, so they can later be mapped safely during portable backup restore.
 */
data class PlaybackQualityPreferenceScope(
    val providerId: Long,
    val type: PlaybackQualityPreferenceScopeType,
    val key: String,
    val identityVersion: Int = 1
) {
    fun isValid(): Boolean = providerId > 0L && identityVersion > 0 && key.length <= MAX_KEY_LENGTH &&
        key == key.trim() && key.none { it.isISOControl() } && when (type) {
        PlaybackQualityPreferenceScopeType.PROVIDER_DEFAULT -> key == PROVIDER_DEFAULT_KEY
        else -> key.isNotBlank()
    }

    companion object {
        const val PROVIDER_DEFAULT_KEY = "default"
        private const val MAX_KEY_LENGTH = 512

        fun providerDefault(providerId: Long) = PlaybackQualityPreferenceScope(
            providerId = providerId,
            type = PlaybackQualityPreferenceScopeType.PROVIDER_DEFAULT,
            key = PROVIDER_DEFAULT_KEY
        )
    }
}

enum class PlaybackQualityPreferenceScopeType(val storageValue: String) {
    PROVIDER_DEFAULT("provider_default"),
    LIVE_CHANNEL_GROUP("live_channel_group"),
    LIVE_REMOTE_CHANNEL("live_remote_channel"),
    MOVIE("movie"),
    SERIES("series");

    companion object {
        fun fromStorage(value: String): PlaybackQualityPreferenceScopeType? =
            entries.firstOrNull { it.storageValue == value }
    }
}

enum class PlaybackQualityConstraintSource {
    PROFILE,
    CONNECTION,
    PROVIDER_DEFAULT,
    CONTENT_PREFERENCE,
    TEMPORARY_SELECTION,
    COMPATIBILITY_MODE,
    MULTIVIEW,
    DECODER_PRESSURE
}

data class PlaybackQualityConstraint(
    val source: PlaybackQualityConstraintSource,
    val limit: PlaybackQualityLimit
)

data class PlaybackQualityPolicyInput(
    val preferences: PlaybackQualityPreferences,
    val connection: PlaybackConnectionType = PlaybackConnectionType.UNCLASSIFIED,
    val providerDefault: ScopedPlaybackQualityPreference? = null,
    val contentPreference: ScopedPlaybackQualityPreference? = null,
    val temporarySelection: ScopedPlaybackQualityPreference? = null,
    val runtimeConstraints: List<PlaybackQualityConstraint> = emptyList()
)

data class ResolvedPlaybackQualityPolicy(
    val limit: PlaybackQualityLimit,
    /** All active constraints, retained so diagnostics can explain the result. */
    val constraints: List<PlaybackQualityConstraint>
)

/** Pure policy resolver shared by storage, settings, and player adapters. */
object PlaybackQualityPolicyResolver {
    fun resolve(input: PlaybackQualityPolicyInput): ResolvedPlaybackQualityPolicy {
        val preferences = input.preferences.normalized()
        val constraints = mutableListOf(
            PlaybackQualityConstraint(PlaybackQualityConstraintSource.PROFILE, preferences.profileLimit())
        )
        preferences.connectionLimit(input.connection)?.let {
            constraints += PlaybackQualityConstraint(PlaybackQualityConstraintSource.CONNECTION, it)
        }

        var scopedLimit = PlaybackQualityLimit.NONE
        var scopedSource: PlaybackQualityConstraintSource? = null
        input.providerDefault?.let { preference ->
            val next = applyOverride(scopedLimit, preference)
            if (next != scopedLimit) scopedSource = PlaybackQualityConstraintSource.PROVIDER_DEFAULT
            scopedLimit = next
        }
        input.contentPreference?.let { preference ->
            val next = applyOverride(scopedLimit, preference)
            if (next != scopedLimit) scopedSource = PlaybackQualityConstraintSource.CONTENT_PREFERENCE
            scopedLimit = next
        }
        input.temporarySelection?.let { preference ->
            val next = applyOverride(scopedLimit, preference)
            if (next != scopedLimit) scopedSource = PlaybackQualityConstraintSource.TEMPORARY_SELECTION
            scopedLimit = next
        }
        if (scopedSource != null) constraints += PlaybackQualityConstraint(scopedSource, scopedLimit)
        constraints += input.runtimeConstraints.map { it.copy(limit = it.limit.normalized()) }

        val normalizedConstraints = constraints.map { it.copy(limit = it.limit.normalized()) }
        return ResolvedPlaybackQualityPolicy(
            limit = PlaybackQualityLimit(
                maxVideoHeight = normalizedConstraints.mapNotNull { it.limit.maxVideoHeight }.minOrNull(),
                maxVideoBitrateBps = normalizedConstraints.mapNotNull { it.limit.maxVideoBitrateBps }.minOrNull()
            ),
            constraints = normalizedConstraints
        )
    }

    private fun applyOverride(
        inherited: PlaybackQualityLimit,
        preference: ScopedPlaybackQualityPreference
    ): PlaybackQualityLimit = PlaybackQualityLimit(
        maxVideoHeight = applyOverride(inherited.maxVideoHeight, preference.maxVideoHeight),
        maxVideoBitrateBps = applyOverride(inherited.maxVideoBitrateBps, preference.maxVideoBitrateBps)
    )

    private fun <T : Comparable<T>> applyOverride(
        inherited: T?,
        preference: QualityLimitOverride<T>
    ): T? = when (preference) {
        QualityLimitOverride.Inherit -> inherited
        QualityLimitOverride.NoAdditionalLimit -> null
        is QualityLimitOverride.Maximum -> {
            val isPositive = when (val value = preference.value) {
                is Int -> value > 0
                is Long -> value > 0L
                else -> true
            }
            if (isPositive) preference.value else inherited
        }
    }
}

data class VideoQualityCandidate(
    val identity: String,
    val height: Int?,
    val advertisedBitrateBps: Long?
)

enum class VideoQualityIssue {
    EXCEEDS_HEIGHT_LIMIT,
    EXCEEDS_BITRATE_LIMIT,
    UNKNOWN_HEIGHT,
    UNKNOWN_BITRATE
}

data class VideoQualityCandidateEvaluation(
    val candidate: VideoQualityCandidate,
    val issues: Set<VideoQualityIssue>
) {
    val isEligible: Boolean get() = issues.isEmpty()
}

fun ResolvedPlaybackQualityPolicy.evaluate(candidate: VideoQualityCandidate): VideoQualityCandidateEvaluation {
    val issues = buildSet {
        val heightLimit = limit.maxVideoHeight
        if (heightLimit != null) {
            val height = candidate.height?.takeIf { it > 0 }
            when {
                height == null -> add(VideoQualityIssue.UNKNOWN_HEIGHT)
                height > heightLimit -> add(VideoQualityIssue.EXCEEDS_HEIGHT_LIMIT)
            }
        }
        val bitrateLimit = limit.maxVideoBitrateBps
        if (bitrateLimit != null) {
            val bitrate = candidate.advertisedBitrateBps?.takeIf { it > 0L }
            when {
                bitrate == null -> add(VideoQualityIssue.UNKNOWN_BITRATE)
                bitrate > bitrateLimit -> add(VideoQualityIssue.EXCEEDS_BITRATE_LIMIT)
            }
        }
    }
    return VideoQualityCandidateEvaluation(candidate, issues)
}
