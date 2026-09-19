package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Projects a fetched guide window into the program slices consumed by player UI. */
class PlayerGuideTimelineCoordinator @Inject constructor() {
    private companion object {
        const val MAX_HISTORY_ITEMS = 18
        const val MAX_UPCOMING_ITEMS = 24
    }

    private val _currentProgram = MutableStateFlow<Program?>(null)
    val currentProgram: StateFlow<Program?> = _currentProgram.asStateFlow()

    private val _nextProgram = MutableStateFlow<Program?>(null)
    val nextProgram: StateFlow<Program?> = _nextProgram.asStateFlow()

    private val _programHistory = MutableStateFlow<List<Program>>(emptyList())
    val programHistory: StateFlow<List<Program>> = _programHistory.asStateFlow()

    private val _upcomingPrograms = MutableStateFlow<List<Program>>(emptyList())
    val upcomingPrograms: StateFlow<List<Program>> = _upcomingPrograms.asStateFlow()

    internal fun apply(programs: List<Program>, now: Long, channel: Channel?) {
        val timeline = buildProgramTimeline(
            programs = programs,
            now = now,
            channel = channel,
            maxHistoryItems = MAX_HISTORY_ITEMS,
            maxUpcomingItems = MAX_UPCOMING_ITEMS
        )
        _currentProgram.value = timeline.currentProgram
        _nextProgram.value = timeline.nextProgram
        _programHistory.value = timeline.programHistory
        _upcomingPrograms.value = timeline.upcomingPrograms
    }

    internal fun clear() {
        _currentProgram.value = null
        _nextProgram.value = null
        _programHistory.value = emptyList()
        _upcomingPrograms.value = emptyList()
    }
}
