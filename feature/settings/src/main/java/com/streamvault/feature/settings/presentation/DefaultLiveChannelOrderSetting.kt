package com.streamvault.feature.settings.presentation

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.streamvault.domain.model.LiveChannelOrderMode
import com.streamvault.domain.policy.liveChannelPresetModes
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.feature.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@HiltViewModel
class DefaultLiveChannelOrderViewModel @Inject constructor(private val preferences: SettingsPreferences) : ViewModel() {
    val mode = preferences.defaultLiveChannelOrder.stateIn(viewModelScope,
        SharingStarted.WhileSubscribed(5_000), LiveChannelOrderMode.PROVIDER)
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()

    fun select(mode: LiveChannelOrderMode) {
        if (_saving.value || mode !in liveChannelPresetModes) return
        _saving.value = true
        _failed.value = false
        viewModelScope.launch {
            try { preferences.setDefaultLiveChannelOrder(mode) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _failed.value = true }
            finally { _saving.value = false }
        }
    }
}

@Composable
internal fun DefaultLiveChannelOrderSetting(viewModel: DefaultLiveChannelOrderViewModel = hiltViewModel()) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(false) }
    val title = stringResource(R.string.settings_default_live_channel_order)
    val labels = mapOf(
        LiveChannelOrderMode.PROVIDER to stringResource(R.string.settings_category_sort_default),
        LiveChannelOrderMode.TITLE_ASC to stringResource(R.string.settings_category_sort_az),
        LiveChannelOrderMode.TITLE_DESC to stringResource(R.string.settings_category_sort_za),
        LiveChannelOrderMode.RECENTLY_WATCHED to stringResource(R.string.settings_live_order_recent),
        LiveChannelOrderMode.MOST_WATCHED to stringResource(R.string.settings_live_order_most),
    )
    ClickableSettingsRow(label = title, value = labels.getValue(mode), onClick = { show = true })
    if (show) SettingsSingleChoiceDialog(title = title, onDismiss = { if (!saving) show = false }) {
        Text(stringResource(R.string.settings_live_order_usage_help), color = com.streamvault.core.ui.design.AppColors.current.textSecondary)
        if (failed) Text(stringResource(R.string.settings_live_order_save_failed))
        liveChannelPresetModes.forEach { option ->
            SettingsChoiceOption(label = labels.getValue(option), selected = option == mode,
                enabled = !saving, onSelect = { viewModel.select(option) })
        }
    }
}
