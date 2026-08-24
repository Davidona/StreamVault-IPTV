package com.streamvault.app.ui.screens.player

import com.streamvault.domain.playback.isArchivePlayable
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program

internal data class PlayerProgramTimeline(
    val currentProgram: Program?,
    val nextProgram: Program?,
    val programHistory: List<Program>,
    val upcomingPrograms: List<Program>
)

internal fun buildProgramTimeline(
    programs: List<Program>,
    now: Long,
    channel: Channel?,
    maxHistoryItems: Int,
    maxUpcomingItems: Int
): PlayerProgramTimeline {
    val sortedPrograms = programs.sortedBy { it.startTime }
    val currentProgram = sortedPrograms.firstOrNull { it.startTime <= now && it.endTime > now }
    val nextProgram = sortedPrograms.firstOrNull { it.startTime > now }
    val programHistory = sortedPrograms
        .filter { program -> channel?.isArchivePlayable(program, now) == true }
        .sortedByDescending { it.startTime }
        .take(maxHistoryItems)
    val upcomingPrograms = sortedPrograms
        .filter { it.endTime > now || it == currentProgram }
        .sortedBy { it.startTime }
        .take(maxUpcomingItems)
    return PlayerProgramTimeline(
        currentProgram = currentProgram,
        nextProgram = nextProgram,
        programHistory = programHistory,
        upcomingPrograms = upcomingPrograms
    )
}
