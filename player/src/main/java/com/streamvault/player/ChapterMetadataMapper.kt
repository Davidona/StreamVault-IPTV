@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player

import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.Chapter

internal fun mapChapterMetadata(
    metadata: Metadata?,
    windowOffsetMs: Long,
    durationMs: Long
): List<PlayerChapter> {
    val candidates = metadata
        ?.getEntriesOfType(Chapter::class.java)
        .orEmpty()
        .mapNotNull { chapter ->
            if (chapter.isHidden || chapter.startTimeMs == C.TIME_UNSET) return@mapNotNull null

            val startTimeMs = chapter.startTimeMs - windowOffsetMs
            if (startTimeMs < 0L) return@mapNotNull null

            val title = chapter.title?.value
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: ""
            val explicitEndTimeMs = chapter.endTimeMs
                .takeUnless { it == C.TIME_UNSET }
                ?.minus(windowOffsetMs)

            ChapterCandidate(
                title = title,
                startTimeMs = startTimeMs,
                explicitEndTimeMs = explicitEndTimeMs
            )
        }
        .sortedWith(compareBy<ChapterCandidate> { it.startTimeMs }.thenBy { it.title })
        .distinctBy { it.startTimeMs to it.title }

    return candidates.mapIndexed { index, candidate ->
        val nextStartTimeMs = candidates.getOrNull(index + 1)?.startTimeMs
        val derivedEndTimeMs = candidate.explicitEndTimeMs
            ?.takeIf { it > candidate.startTimeMs }
            ?: nextStartTimeMs
            ?: durationMs.takeIf { it > candidate.startTimeMs }
        val endTimeMs = derivedEndTimeMs
            ?.takeIf { it > candidate.startTimeMs }
            ?.let { end ->
                if (durationMs > candidate.startTimeMs) end.coerceAtMost(durationMs) else end
            }

        PlayerChapter(
            index = index + 1,
            title = candidate.title.ifBlank { "Chapter ${index + 1}" },
            startTimeMs = candidate.startTimeMs,
            endTimeMs = endTimeMs
        )
    }
}

private data class ChapterCandidate(
    val title: String,
    val startTimeMs: Long,
    val explicitEndTimeMs: Long?
)
