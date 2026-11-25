package com.streamvault.feature.playback.player

import com.streamvault.player.PlayerChapter

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

/** A press of "previous chapter" within this long of a chapter start goes to the chapter before it. */
internal const val CHAPTER_RESTART_THRESHOLD_MS = 3_000L

/** Start of the chapter to seek to for "previous chapter", or null when there are no chapters. */
internal fun previousChapterTarget(chapters: List<PlayerChapter>, positionMs: Long): Long? {
    val starts = chapters.map(PlayerChapter::startTimeMs).sorted()
    if (starts.isEmpty()) return null
    val currentStart = starts.lastOrNull { it <= positionMs } ?: return starts.first()
    if (positionMs - currentStart > CHAPTER_RESTART_THRESHOLD_MS) return currentStart
    return starts.lastOrNull { it < currentStart } ?: 0L
}

/** Start of the chapter after [positionMs], or null when the last chapter is playing. */
internal fun nextChapterTarget(chapters: List<PlayerChapter>, positionMs: Long): Long? =
    chapters.map(PlayerChapter::startTimeMs).sorted().firstOrNull { it > positionMs }
