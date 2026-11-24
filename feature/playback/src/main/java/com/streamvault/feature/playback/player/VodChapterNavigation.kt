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
