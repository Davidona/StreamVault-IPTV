package com.streamvault.feature.playback.player

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.player.PlayerChapter

private val CREDITS_TITLE_PATTERN = Regex("\\bcredits?\\b", RegexOption.IGNORE_CASE)
private val EXPLICIT_END_CREDITS_TITLE_PATTERN = Regex(
    "\\b(?:end|closing)\\s+credits?\\b|\\bcredit\\s+roll\\b",
    RegexOption.IGNORE_CASE
)
private val OPENING_CREDITS_TITLE_PATTERN = Regex(
    "\\bopening\\b.*\\bcredits?\\b",
    RegexOption.IGNORE_CASE
)
private val POST_CREDITS_SCENE_TITLE_PATTERN = Regex(
    "\\bpost\\s+credits?\\s+scene\\b",
    RegexOption.IGNORE_CASE
)
private val INTRO_TITLE_PATTERN = Regex("\\bintro(?:duction)?\\b", RegexOption.IGNORE_CASE)
private val OPENING_TITLE_PATTERN = Regex(
    "\\b(?:opening|opening credits|title sequence|theme song?)\\b",
    RegexOption.IGNORE_CASE
)
private val RECAP_TITLE_PATTERN = Regex("\\b(?:recap|previously on)\\b", RegexOption.IGNORE_CASE)
private val OUTRO_TITLE_PATTERN = Regex("\\boutro\\b", RegexOption.IGNORE_CASE)

enum class SkippableChapterType {
    INTRO,
    OPENING,
    RECAP,
    OUTRO
}

internal data class SkippableChapterAction(
    val type: SkippableChapterType,
    val targetPositionMs: Long
)

internal fun isCreditsChapterTitle(title: String): Boolean =
    CREDITS_TITLE_PATTERN.containsMatchIn(title) &&
        !OPENING_CREDITS_TITLE_PATTERN.containsMatchIn(title) &&
        !POST_CREDITS_SCENE_TITLE_PATTERN.containsMatchIn(title)

private fun isEndCreditsChapter(
    chapter: PlayerChapter,
    durationMs: Long
): Boolean {
    if (!isCreditsChapterTitle(chapter.title)) return false
    if (EXPLICIT_END_CREDITS_TITLE_PATTERN.containsMatchIn(chapter.title)) return true
    return durationMs > 0L && chapter.startTimeMs >= (durationMs * 0.6f).toLong()
}

internal fun skippableChapterType(title: String): SkippableChapterType? = when {
    RECAP_TITLE_PATTERN.containsMatchIn(title) -> SkippableChapterType.RECAP
    OUTRO_TITLE_PATTERN.containsMatchIn(title) -> SkippableChapterType.OUTRO
    OPENING_TITLE_PATTERN.containsMatchIn(title) -> SkippableChapterType.OPENING
    INTRO_TITLE_PATTERN.containsMatchIn(title) -> SkippableChapterType.INTRO
    else -> null
}

internal fun PlayerChapter.isCreditsChapter(): Boolean =
    isCreditsChapterTitle(title)

internal fun ContentType.isSkippableChapterContent(): Boolean =
    this == ContentType.VOD || this == ContentType.MOVIE || this == ContentType.SERIES_EPISODE

internal data class PlayerChapterObservation(
    val positionMs: Long,
    val durationMs: Long,
    val chapters: List<PlayerChapter>,
    val nextEpisode: Episode?,
    val autoPlayEnabled: Boolean
)

internal fun findCreditsChapter(
    chapters: List<PlayerChapter>,
    positionMs: Long,
    durationMs: Long = 0L
): PlayerChapter? = chapters
    .asSequence()
    .sortedBy(PlayerChapter::startTimeMs)
    .firstOrNull { chapter ->
        val endTimeMs = chapter.endTimeMs
        isEndCreditsChapter(chapter, durationMs) &&
            positionMs >= chapter.startTimeMs &&
            (endTimeMs == null || positionMs < endTimeMs)
    }

internal fun findSkippableChapterAction(
    chapters: List<PlayerChapter>,
    positionMs: Long
): SkippableChapterAction? = chapters
    .asSequence()
    .sortedBy(PlayerChapter::startTimeMs)
    .firstNotNullOfOrNull { chapter ->
        val endTimeMs = chapter.endTimeMs
        val type = skippableChapterType(chapter.title)
        if (type == null || endTimeMs == null || endTimeMs <= chapter.startTimeMs) {
            null
        } else if (positionMs >= chapter.startTimeMs && positionMs < endTimeMs) {
            SkippableChapterAction(type = type, targetPositionMs = endTimeMs)
        } else {
            null
        }
    }

internal fun shouldStartCreditsAutoPlay(
    currentChapter: PlayerChapter?,
    lastTriggeredChapterStartMs: Long?,
    hasNextEpisode: Boolean,
    autoPlayEnabled: Boolean,
    contentType: ContentType
): Boolean = currentChapter != null &&
    currentChapter.isCreditsChapter() &&
    currentChapter.startTimeMs != lastTriggeredChapterStartMs &&
    hasNextEpisode &&
    autoPlayEnabled &&
    contentType == ContentType.SERIES_EPISODE
