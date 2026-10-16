package com.streamvault.feature.stremio.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class FakeStremioContentAccess(
    initialState: StremioVisibilityState = StremioVisibilityState(
        hidden = false
    )
) : StremioContentAccess {
    private val mutableState = MutableStateFlow(initialState)
    override val state: StateFlow<StremioVisibilityState> = mutableState

    fun setState(state: StremioVisibilityState) {
        mutableState.value = state
    }
}
