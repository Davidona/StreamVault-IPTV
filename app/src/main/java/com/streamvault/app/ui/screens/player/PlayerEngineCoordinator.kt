package com.streamvault.app.ui.screens.player

import com.streamvault.player.di.MainPlayerEngine
import com.streamvault.player.PlayerEngine
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Owns the active engine and the MediaSession handoff between engine instances. */
@ViewModelScoped
class PlayerEngineCoordinator @Inject constructor(
    @param:MainPlayerEngine
    val mainEngine: PlayerEngine
) {
    private val _activeEngine = MutableStateFlow(mainEngine)
    val activeEngine: StateFlow<PlayerEngine> = _activeEngine.asStateFlow()

    val currentEngine: PlayerEngine
        get() = _activeEngine.value

    fun switchTo(engine: PlayerEngine) {
        if (_activeEngine.value === engine) return
        // Media3 session IDs must be globally unique. Disable the outgoing session before the
        // incoming engine is observed by the ViewModel's engine-derived flows.
        _activeEngine.value.setMediaSessionEnabled(false)
        _activeEngine.value = engine
    }
}
