package com.streamvault.feature.playback.player

import com.streamvault.player.PlayerChapter

const val CHAPTER_RESTART_THRESHOLD_MS = 3_000L

/** Returns the chapter containing [positionMs], or null when the position is in a gap. */
internal fun currentChapter(
    chapters: List<PlayerChapter>,
    positionMs: Long
): PlayerChapter? = chapters
    .sortedBy(PlayerChapter::startTimeMs)
    .firstOrNull { chapter ->
        val endTimeMs = chapter.endTimeMs
        positionMs >= chapter.startTimeMs &&
            (endTimeMs == null || positionMs < endTimeMs)
    }

/**
 * Returns the target for a previous-chapter action.
 *
 * A press later than the restart threshold restarts the current chapter. Near its
 * start (or while in a gap), it targets the preceding chapter instead.
 */
internal fun previousChapterTarget(
    chapters: List<PlayerChapter>,
    positionMs: Long,
    restartThresholdMs: Long = CHAPTER_RESTART_THRESHOLD_MS
): Long? {
    val sortedChapters = chapters.sortedBy(PlayerChapter::startTimeMs)
    val current = currentChapter(sortedChapters, positionMs)
    if (current != null && positionMs - current.startTimeMs > restartThresholdMs) {
        return current.startTimeMs
    }

    return sortedChapters
        .asSequence()
        .filter { chapter -> chapter.startTimeMs < positionMs }
        .lastOrNull { chapter -> chapter != current }
        ?.startTimeMs
}

/** Returns the first chapter start strictly after [positionMs]. */
internal fun nextChapterTarget(
    chapters: List<PlayerChapter>,
    positionMs: Long
): Long? = chapters
    .asSequence()
    .sortedBy(PlayerChapter::startTimeMs)
    .firstOrNull { chapter -> chapter.startTimeMs > positionMs }
    ?.startTimeMs
