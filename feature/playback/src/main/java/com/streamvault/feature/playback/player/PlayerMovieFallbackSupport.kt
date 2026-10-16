package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.model.VodMovieVariant
import com.streamvault.player.PLAYER_TRACK_AUTO_ID
import com.streamvault.player.PlayerTrack
import com.streamvault.player.playback.ResolvedStreamType
import com.streamvault.player.playback.StreamTypeResolver
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

private val AVC_CODEC_TOKENS = listOf("avc", "h264", "h 264", "x264")
private val HEVC_CODEC_TOKENS = listOf("hevc", "h265", "h 265", "x265", "hev1", "hvc1")
private val MOVIE_CODEC_TOKEN_REGEX = Regex("""[^a-z0-9]+""")
private val MOVIE_TITLE_METADATA_REGEX = Regex(
    """(?i)(?<![a-z0-9])(?:8k|4320p|uhd|ultra\s*hd|4k|2160[pi]?|2k|qhd|1440p|full\s*hd|fhd|1080[pi]?|720p|576p|540p|480p|360p|240p|hd|hq|sd|dolby\s*vision|hdr10|hdr|hevc|h[ ._-]*265|x265|hev1|hvc1|avc|h[ ._-]*264|x264|web\s*dl|web\s*rip|blu\s*ray|bluray|remux)(?![a-z0-9])"""
)
private val MOVIE_TITLE_NON_ALPHANUMERIC_REGEX = Regex("""[^\p{L}\p{N}]+""")
private val MOVIE_RESOLUTION_PATTERNS = listOf(
    Regex("""(?<![a-z0-9])(?:8k|4320p)(?![a-z0-9])""") to 4320,
    Regex("""(?<![a-z0-9])(?:uhd|ultra\s*hd|4k|2160[pi]?)(?![a-z0-9])""") to 2160,
    Regex("""(?<![a-z0-9])(?:2k|qhd|1440p)(?![a-z0-9])""") to 1440,
    Regex("""(?<![a-z0-9])(?:full\s*hd|fhd|1080[pi]?)(?![a-z0-9])""") to 1080,
    Regex("""(?<![a-z0-9])(?:720p|hd)(?![a-z0-9])""") to 720,
    Regex("""(?<![a-z0-9])(?:576p|540p|hq|sd)(?![a-z0-9])""") to 576,
    Regex("""(?<![a-z0-9])480p(?![a-z0-9])""") to 480,
    Regex("""(?<![a-z0-9])360p(?![a-z0-9])""") to 360,
    Regex("""(?<![a-z0-9])240p(?![a-z0-9])""") to 240
)

internal suspend fun PlayerViewModel.tryFallbackToAvcMovieVariant(
    requestVersion: Long,
    playbackUrl: String
): Boolean {
    if (currentContentType != com.streamvault.domain.model.ContentType.MOVIE) return false
    if (currentProviderId <= 0L || currentContentId <= 0L) return false
    if (playerRecoveryCoordinator.retriedWithAvcMovieVariant) return false
    val episodeToken = episodeSourceState.token
    val videoSelectionRevision = episodeSourceState.manualVideoRevision
    fun isCurrent(requireOriginalUrl: Boolean = true): Boolean = isEpisodePreparationCurrent(
        state = episodeSourceState,
        token = episodeToken,
        sessionActive = if (requireOriginalUrl) isActivePlaybackSession(requestVersion, playbackUrl)
            else isActivePlaybackSession(requestVersion),
        manualVideo = hasManualVideoTrackChoice(playerEngine.availableVideoTracks.value),
        videoRevision = videoSelectionRevision,
        requireAuto = true
    )
    if (!isCurrent()) return false

    val currentMovie = playerContentResolver.getMovie(currentContentId) ?: return false
    if (!isCurrent()) return false
    val variants = playerContentResolver.getMovieVariants(currentMovie.id)
    if (!isCurrent()) return false
    val currentStreamInfo = currentResolvedStreamInfo ?: return false
    if (StreamTypeResolver.resolve(currentStreamInfo) != ResolvedStreamType.PROGRESSIVE) return false
    val currentStreamType = currentStreamInfo.streamType.takeUnless { it == StreamType.UNKNOWN }
        ?: StreamType.PROGRESSIVE
    val hasExplicitVariantChoice = playerContentResolver.hasExplicitMovieVariantSelection(currentMovie.id)
    if (!isCurrent()) return false
    val hasManualTrackChoice = hasManualVideoTrackChoice(playerEngine.availableVideoTracks.value)
    val fallbackVariant = selectAvcMovieFallbackVariant(
        currentMovie = currentMovie,
        variants = variants,
        currentStreamType = currentStreamType,
        hasExplicitVariantChoice = hasExplicitVariantChoice,
        hasManualVideoTrackChoice = hasManualTrackChoice,
        currentCodecHint = playerEngine.playerStats.value.videoCodec
    ) ?: return false
    val fallbackMovie = playerContentResolver.getMovie(fallbackVariant.rawMovieId) ?: return false
    if (!isCurrent()) return false
    if (fallbackMovie.providerId != currentMovie.providerId) return false
    val resumePositionMs = playerEngine.currentPosition.value.coerceAtLeast(0L)
    val sourceDurationMs = playerEngine.duration.value.takeIf { it > 0L }
        ?: currentMovie.durationSeconds.takeIf { it > 0 }?.times(1_000L)
        ?: return false
    if (!areMovieVariantDurationsCompatible(sourceDurationMs, fallbackMovie.durationSeconds)) return false
    val fallbackDurationMs = fallbackMovie.durationSeconds * 1_000L
    if (resumePositionMs >= fallbackDurationMs - MOVIE_VARIANT_END_GUARD_MS) return false
    val fallbackStreamInfo = playerContentResolver.getMovieStreamInfo(fallbackMovie).getOrNull() ?: return false

    if (!isCurrent()) return false
    if (playerContentResolver.hasExplicitMovieVariantSelection(currentMovie.id)) return false
    if (!isCurrent()) return false

    val previousContentId = currentContentId
    val previousStreamUrl = currentStreamUrl
    val previousTitle = currentTitle
    val previousArtworkUrl = currentArtworkUrl
    playerRecoveryCoordinator.retriedWithAvcMovieVariant = true
    currentContentId = fallbackMovie.id
    currentStreamUrl = fallbackMovie.streamUrl

    val resolvedStreamInfo = fallbackStreamInfo.copy(title = previousTitle.ifBlank { currentMovie.name })
    if (!preparePlayer(
            resolvedStreamInfo,
            requestVersion,
            preserveVodTrackPreferences = true,
            episodeToken = episodeToken,
            requiredSourceType = ResolvedStreamType.PROGRESSIVE,
            requireAutoVideoSelection = true,
            videoSelectionRevision = videoSelectionRevision
        )) {
        if (!isActivePlaybackSession(requestVersion)) return false
        currentContentId = previousContentId
        currentStreamUrl = previousStreamUrl
        currentTitle = previousTitle
        playbackTitleFlow.value = previousTitle
        currentArtworkUrl = previousArtworkUrl
        playerRecoveryCoordinator.retriedWithAvcMovieVariant = false
        return false
    }
    if (!isCurrent(requireOriginalUrl = false)) return false
    if (resumePositionMs > 0L) playerEngine.seekTo(resumePositionMs)
    setLastFailureReason("HEVC playback failed. Retrying with a compatible AVC/H.264 movie variant.")
    appendRecoveryAction("Switched to compatible AVC/H.264 movie variant")
    showPlayerNotice(
        message = "Retrying with a compatible AVC/H.264 movie variant.",
        recoveryType = PlayerRecoveryType.DECODER,
        actions = buildRecoveryActions(PlayerRecoveryType.DECODER)
    )
    playerEngine.play()
    return true
}

private const val MOVIE_VARIANT_DURATION_TOLERANCE_MS = 90_000L
private const val MOVIE_VARIANT_DURATION_TOLERANCE_PERCENT = 3L
private const val MOVIE_VARIANT_END_GUARD_MS = 15_000L

internal fun areMovieVariantDurationsCompatible(
    sourceDurationMs: Long,
    variantDurationSeconds: Int
): Boolean {
    if (sourceDurationMs <= 0L || variantDurationSeconds <= 0) return false
    val variantDurationMs = variantDurationSeconds * 1_000L
    val toleranceMs = maxOf(
        MOVIE_VARIANT_DURATION_TOLERANCE_MS,
        (sourceDurationMs / 100L) * MOVIE_VARIANT_DURATION_TOLERANCE_PERCENT
    )
    return abs(sourceDurationMs - variantDurationMs) <= toleranceMs
}

internal fun selectAvcMovieFallbackVariant(
    currentMovie: Movie,
    variants: List<VodMovieVariant>,
    currentStreamType: StreamType = StreamType.PROGRESSIVE,
    hasExplicitVariantChoice: Boolean = false,
    hasManualVideoTrackChoice: Boolean = false,
    currentCodecHint: String? = null
): VodMovieVariant? {
    if (currentStreamType != StreamType.PROGRESSIVE) return null
    if (hasExplicitVariantChoice || hasManualVideoTrackChoice) return null

    val currentTitleKey = movieVariantTitleKey(currentMovie.name)
    if (currentTitleKey.isBlank()) return null
    val currentHeight = movieVariantResolutionHeight(currentMovie.name) ?: return null
    val measuredCodec = currentCodecHint.orEmpty().trim()
    val hasMeasuredCodec = measuredCodec.isNotBlank() && !measuredCodec.equals("unknown", ignoreCase = true)
    val currentIsHevc = when {
        hasMeasuredCodec && hasHevcCodec(measuredCodec) -> true
        hasMeasuredCodec && hasAvcCodec(measuredCodec) -> false
        else -> hasHevcCodec(currentMovie.name)
    }
    if (!currentIsHevc) return null

    val candidateVariants = variants.asSequence()
        .filter { it.rawMovieId != currentMovie.id }
        .filter { it.streamUrl.isNotBlank() }
        .filter { movieVariantTitleKey(it.name) == currentTitleKey }
        .filter {
            val codecLabel = "${it.name} ${it.label}"
            hasAvcCodec(codecLabel) && !hasHevcCodec(codecLabel)
        }
        .mapNotNull { variant ->
            movieVariantResolutionHeight("${variant.name} ${variant.label}")
                ?.takeIf { height -> height <= currentHeight }
                ?.let { height -> variant to height }
        }
        .sortedWith(
            compareByDescending<Pair<VodMovieVariant, Int>> { it.second }
                .thenByDescending { it.first.addedAt }
                .thenByDescending { it.first.rating }
                .thenBy { it.first.name.lowercase(Locale.ROOT) }
                .thenBy { it.first.rawMovieId }
        )

    return candidateVariants.firstOrNull()?.first
}

internal fun hasManualVideoTrackChoice(tracks: List<PlayerTrack>): Boolean =
    tracks.any { it.id == PLAYER_TRACK_AUTO_ID } && tracks.none { it.id == PLAYER_TRACK_AUTO_ID && it.isSelected }

private fun movieVariantResolutionHeight(value: String): Int? {
    val normalized = value.lowercase(Locale.ROOT)
    return MOVIE_RESOLUTION_PATTERNS.firstNotNullOfOrNull { (pattern, height) ->
        height.takeIf { pattern.containsMatchIn(normalized) }
    }
}

private fun movieVariantTitleKey(value: String): String {
    val withoutVariantMetadata = MOVIE_TITLE_METADATA_REGEX.replace(value, " ")
    val decomposed = Normalizer.normalize(withoutVariantMetadata, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return MOVIE_TITLE_NON_ALPHANUMERIC_REGEX
        .replace(decomposed.lowercase(Locale.ROOT), "")
}

private fun hasHevcCodec(value: String): Boolean = hasCodecToken(value, HEVC_CODEC_TOKENS)

private fun hasAvcCodec(value: String): Boolean = hasCodecToken(value, AVC_CODEC_TOKENS)

private fun hasCodecToken(value: String, tokens: List<String>): Boolean {
    val normalized = normalizeMovieVariantCodecText(value)
    return tokens.any { token -> normalized.contains(" $token ") }
}

private fun normalizeMovieVariantCodecText(value: String): String {
    val normalized = value.lowercase(Locale.ROOT)
        .replace(MOVIE_CODEC_TOKEN_REGEX, " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return " $normalized "
}
