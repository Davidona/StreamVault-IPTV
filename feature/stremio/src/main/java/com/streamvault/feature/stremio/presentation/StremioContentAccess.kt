package com.streamvault.feature.stremio.presentation

import androidx.lifecycle.ViewModel
import com.streamvault.domain.settings.SettingsPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// Null means the persisted preference has not loaded yet; routes wait before fetching content.
data class StremioVisibilityState(val hidden: Boolean? = null) {
    val canAccessContent: Boolean get() = hidden == false
    val showContinueWatching: Boolean get() = canAccessContent
}

interface StremioContentAccess {
    val state: StateFlow<StremioVisibilityState>
    fun canAccessContentNow(): Boolean = state.value.canAccessContent
}

@dagger.hilt.android.lifecycle.HiltViewModel
class StremioVisibilityViewModel @Inject constructor(
    contentAccess: StremioContentAccess
) : ViewModel() {
    val state = contentAccess.state
}

@Singleton
class StremioVisibilityManager @Inject constructor(
    preferences: SettingsPreferences
) : StremioContentAccess {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(StremioVisibilityState())
    override val state: StateFlow<StremioVisibilityState> = _state.asStateFlow()

    init {
        scope.launch {
            preferences.hideStremioAddons.distinctUntilChanged().collect { hidden ->
                _state.value = StremioVisibilityState(hidden = hidden)
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StremioContentAccessModule {
    @Binds
    @Singleton
    abstract fun bindStremioContentAccess(
        implementation: StremioVisibilityManager
    ): StremioContentAccess
}
