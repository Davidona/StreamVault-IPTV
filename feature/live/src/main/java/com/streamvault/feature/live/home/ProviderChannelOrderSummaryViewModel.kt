package com.streamvault.feature.live.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.LiveChannelOrderMode
import com.streamvault.domain.policy.liveChannelOrderScope
import com.streamvault.domain.repository.LiveChannelOrderRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.feature.live.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ProviderChannelOrderSummaryUiState(
    val mode: LiveChannelOrderMode = LiveChannelOrderMode.USE_DEFAULT,
    val defaultMode: LiveChannelOrderMode = LiveChannelOrderMode.PROVIDER,
    val loading: Boolean = true,
    val message: Int? = null
)

/** Reads only the saved order metadata for Category controls; it does not load channel rows. */
@HiltViewModel
class ProviderChannelOrderSummaryViewModel @Inject constructor(
    private val providers: ProviderRepository,
    private val orders: LiveChannelOrderRepository,
    private val preferences: SettingsPreferences
) : ViewModel() {
    private val state = MutableStateFlow(ProviderChannelOrderSummaryUiState())
    val uiState = state.asStateFlow()
    private var job: Job? = null

    fun observe(providerId: Long, category: Category) {
        job?.cancel()
        state.value = ProviderChannelOrderSummaryUiState()
        job = viewModelScope.launch {
            try {
                val provider = checkNotNull(providers.getProvider(providerId))
                val scope = liveChannelOrderScope(provider, category)
                combine(orders.observe(scope), preferences.defaultLiveChannelOrder) { order, defaultMode ->
                    ProviderChannelOrderSummaryUiState(
                        mode = order.mode,
                        defaultMode = defaultMode,
                        loading = false
                    )
                }.collect { state.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = ProviderChannelOrderSummaryUiState(
                    loading = false,
                    message = R.string.live_category_order_load_failed
                )
            }
        }
    }

    fun reset() {
        job?.cancel()
        job = null
        state.value = ProviderChannelOrderSummaryUiState()
    }
}
