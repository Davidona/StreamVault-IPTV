package com.streamvault.feature.playback.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Controls keep their last snapshot for exit animations; reopening reads the latest value. */
@Composable
internal fun <T> StateFlow<T>.collectAsControlsState(active: Boolean): State<T> {
    val observed = remember(this, active) { if (active) this else emptyFlow() }
    return observed.collectAsStateWithLifecycle(initialValue = value)
}
