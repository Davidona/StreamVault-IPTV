package com.streamvault.feature.playback.player

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun PlayerViewModel.setStopPlaybackTimer(minutes: Int) {
    val normalized = sanitizePlaybackTimerMinutes(minutes, PlayerViewModel.PLAYBACK_TIMER_PRESETS_MINUTES)
    if (normalized == 0) {
        disableStopPlaybackTimer()
        return
    }
    stopPlaybackTimerEndsAtMs = SystemClock.elapsedRealtime() + normalized * 60_000L
    sleepTimerExitEmitted = false
    updateSleepTimerState(stopMinutes = normalized)
    startStopPlaybackTimerJob(normalized)
    showPlayerNotice(message = "Playback will stop in ${formatTimerMinutesNotice(normalized)}.")
}

fun PlayerViewModel.setIdleStandbyTimer(minutes: Int) {
    val normalized = sanitizePlaybackTimerMinutes(minutes, PlayerViewModel.PLAYBACK_TIMER_PRESETS_MINUTES)
    if (normalized == 0) {
        disableIdleStandbyTimer()
        return
    }
    idleStandbyTimerEndsAtMs = SystemClock.elapsedRealtime() + normalized * 60_000L
    sleepTimerExitEmitted = false
    updateSleepTimerState(idleMinutes = normalized)
    startIdleStandbyTimerJob(normalized)
    showPlayerNotice(message = "Idle standby will be allowed after ${formatTimerMinutesNotice(normalized)} without activity.")
}

fun PlayerViewModel.extendStopPlaybackTimer(minutes: Int = 30) {
    val current = _sleepTimerUiState.value
    if (!current.stopTimerActive) return
    stopPlaybackTimerEndsAtMs += minutes * 60_000L
    sleepTimerExitEmitted = false
    updateSleepTimerState(stopMinutes = current.stopTimerMinutes)
}

fun PlayerViewModel.extendIdleStandbyTimer(minutes: Int = 30) {
    val current = _sleepTimerUiState.value
    if (!current.idleTimerActive) return
    idleStandbyTimerEndsAtMs += minutes * 60_000L
    sleepTimerExitEmitted = false
    updateSleepTimerState(idleMinutes = current.idleTimerMinutes)
}

fun PlayerViewModel.disableStopPlaybackTimer() {
    stopPlaybackTimerJob?.cancel()
    stopPlaybackTimerJob = null
    stopPlaybackTimerEndsAtMs = 0L
    _sleepTimerUiState.update { it.copy(stopTimerMinutes = 0, stopRemainingMs = 0L) }
}

fun PlayerViewModel.disableIdleStandbyTimer() {
    idleStandbyTimerJob?.cancel()
    idleStandbyTimerJob = null
    idleStandbyTimerEndsAtMs = 0L
    _sleepTimerUiState.update { it.copy(idleTimerMinutes = 0, idleRemainingMs = 0L) }
}

fun PlayerViewModel.notifyUserActivity() {
    val current = _sleepTimerUiState.value
    if (!current.idleTimerActive) return
    idleStandbyTimerEndsAtMs = SystemClock.elapsedRealtime() + current.idleTimerMinutes * 60_000L
    updateSleepTimerState(idleMinutes = current.idleTimerMinutes)
}

fun PlayerViewModel.consumeSleepTimerExitEvent() {
    _sleepTimerExitEvent.value = 0
}

internal fun PlayerViewModel.applyDefaultPlaybackTimersIfNeeded() {
    if (playbackTimerDefaultsApplied) return
    playbackTimerDefaultsApplied = true
    sleepTimerExitEmitted = false
    viewModelScope.launch {
        val stopMinutes = sanitizePlaybackTimerMinutes(
            playerPreferencesCoordinator.defaultStopPlaybackTimerMinutes.first(),
            PlayerViewModel.PLAYBACK_TIMER_PRESETS_MINUTES
        )
        val idleMinutes = sanitizePlaybackTimerMinutes(
            playerPreferencesCoordinator.defaultIdleStandbyTimerMinutes.first(),
            PlayerViewModel.PLAYBACK_TIMER_PRESETS_MINUTES
        )
        if (!playbackTimerDefaultsApplied || _sleepTimerUiState.value.stopTimerActive || _sleepTimerUiState.value.idleTimerActive) {
            return@launch
        }
        if (stopMinutes > 0) {
            setStopPlaybackTimer(stopMinutes)
        }
        if (idleMinutes > 0) {
            setIdleStandbyTimer(idleMinutes)
        }
    }
}

internal fun PlayerViewModel.startStopPlaybackTimerJob(minutes: Int) {
    stopPlaybackTimerJob?.cancel()
    stopPlaybackTimerJob = viewModelScope.launch {
        while (true) {
            val remainingMs = updateSleepTimerState(stopMinutes = minutes).stopRemainingMs
            if (remainingMs <= 0L) {
                expirePlaybackTimer()
                return@launch
            }
            delay(PlayerViewModel.TIMER_TICK_MS)
        }
    }
}

internal fun PlayerViewModel.startIdleStandbyTimerJob(minutes: Int) {
    idleStandbyTimerJob?.cancel()
    idleStandbyTimerJob = viewModelScope.launch {
        while (true) {
            val remainingMs = updateSleepTimerState(idleMinutes = minutes).idleRemainingMs
            if (remainingMs <= 0L) {
                expirePlaybackTimer()
                return@launch
            }
            delay(PlayerViewModel.TIMER_TICK_MS)
        }
    }
}

internal fun PlayerViewModel.updateSleepTimerState(
    stopMinutes: Int = _sleepTimerUiState.value.stopTimerMinutes,
    idleMinutes: Int = _sleepTimerUiState.value.idleTimerMinutes
): SleepTimerUiState {
    val next = computeSleepTimerUiState(
        current = _sleepTimerUiState.value,
        nowElapsedMs = SystemClock.elapsedRealtime(),
        stopPlaybackTimerEndsAtMs = stopPlaybackTimerEndsAtMs,
        idleStandbyTimerEndsAtMs = idleStandbyTimerEndsAtMs,
        stopMinutes = stopMinutes,
        idleMinutes = idleMinutes
    )
    _sleepTimerUiState.value = next
    return next
}

internal fun PlayerViewModel.expirePlaybackTimer() {
    if (sleepTimerExitEmitted) return
    sleepTimerExitEmitted = true
    stopPlaybackTimerJob?.cancel()
    idleStandbyTimerJob?.cancel()
    stopPlaybackTimerJob = null
    idleStandbyTimerJob = null
    stopPlaybackTimerEndsAtMs = 0L
    idleStandbyTimerEndsAtMs = 0L
    _sleepTimerUiState.value = SleepTimerUiState()
    _sleepTimerExitEvent.value += 1
}
