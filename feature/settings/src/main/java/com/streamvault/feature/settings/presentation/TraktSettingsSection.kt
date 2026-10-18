package com.streamvault.feature.settings.presentation

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.domain.trakt.TraktMessage
import com.streamvault.domain.trakt.TraktHistorySync
import com.streamvault.domain.trakt.TraktHistorySyncResult
import com.streamvault.domain.trakt.TraktSyncFailure
import com.streamvault.domain.trakt.TraktFeaturePreferences
import com.streamvault.domain.trakt.TraktFavoriteImporter
import com.streamvault.domain.trakt.TraktFavoriteImportResult
import com.streamvault.domain.trakt.TraktFavoriteFailure
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.design.AppColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TraktSettingsViewModel @Inject constructor(private val account: TraktAccount,
    private val historySync: TraktHistorySync,
    private val preferences: TraktFeaturePreferences,
    private val favoriteImporter: TraktFavoriteImporter) : ViewModel() {
    val state = account.state
    val scrobblingEnabled = preferences.scrobblingEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val historySyncEnabled = preferences.historySyncEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableFailed = MutableStateFlow(false)
    val failed = mutableFailed.asStateFlow()
    private val mutableBrowserUnavailable = MutableStateFlow(false)
    val browserUnavailable = mutableBrowserUnavailable.asStateFlow()
    private val mutableSyncResult = MutableStateFlow<TraktHistorySyncResult?>(null)
    val syncResult = mutableSyncResult.asStateFlow()
    private val mutableFavoriteImportResult = MutableStateFlow<TraktFavoriteImportResult?>(null)
    val favoriteImportResult = mutableFavoriteImportResult.asStateFlow()
    init { perform { account.load() } }
    fun connect(openBrowser: (String) -> Unit) = perform {
        mutableSyncResult.value = null
        mutableFavoriteImportResult.value = null
        val url = account.beginSignIn()
        try { openBrowser(url) } catch (failure: Exception) {
            account.cancelSignIn()
            if (failure is ActivityNotFoundException) mutableBrowserUnavailable.value = true
            else throw failure
        }
    }
    fun cancel() = perform { account.cancelSignIn() }
    fun disconnect() = perform {
        mutableSyncResult.value = null
        mutableFavoriteImportResult.value = null
        account.disconnect()
    }
    fun profile() = perform { account.refreshProfile() }
    fun setScrobblingEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setScrobblingEnabled(enabled) }
    }
    fun setHistorySyncEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setHistorySyncEnabled(enabled) }
    }
    fun syncHistory() = perform {
        mutableSyncResult.value = null
        if (preferences.historySyncEnabled.first()) mutableSyncResult.value = historySync.sync()
    }
    fun importFavorites() = perform {
        mutableFavoriteImportResult.value = favoriteImporter.importToLocal()
    }
    private fun perform(action: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        mutableFailed.value = false
        mutableBrowserUnavailable.value = false
        viewModelScope.launch {
            try { action() } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableFailed.value = true
            } finally { mutableBusy.value = false }
        }
    }
}

@Composable
internal fun TraktSettingsSection(modifier: Modifier = Modifier, viewModel: TraktSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val browserUnavailable by viewModel.browserUnavailable.collectAsStateWithLifecycle()
    val syncResult by viewModel.syncResult.collectAsStateWithLifecycle()
    val favoriteImportResult by viewModel.favoriteImportResult.collectAsStateWithLifecycle()
    val scrobblingEnabled by viewModel.scrobblingEnabled.collectAsStateWithLifecycle()
    val historySyncEnabled by viewModel.historySyncEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = SettingsDesignTokens.colors(AppColors.current)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space12)) {
        TraktSettingsPanel(accented = state.connected) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space16)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4)) {
                    Text(
                        text = stringResource(R.string.settings_trakt),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primaryText,
                    )
                    Text(
                        text = stringResource(if (state.connected) R.string.trakt_connected else R.string.trakt_disconnected),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (state.connected) colors.accent else colors.secondaryText,
                    )
                    state.username?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, color = colors.primaryText)
                    }
                }
                state.profile?.let { profile ->
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4)) {
                        profile.name?.let { Text(stringResource(R.string.trakt_profile_name, it), color = colors.secondaryText) }
                        profile.location?.let { Text(stringResource(R.string.trakt_profile_location, it), color = colors.secondaryText) }
                        profile.joinedAt?.let { Text(stringResource(R.string.trakt_profile_joined, it.take(10)), color = colors.secondaryText) }
                        profile.gender?.let { Text(stringResource(R.string.trakt_profile_gender, it), color = colors.secondaryText) }
                        profile.age?.let { Text(stringResource(R.string.trakt_profile_age, it), color = colors.secondaryText) }
                        if (profile.vip) Text(stringResource(R.string.trakt_profile_vip), color = colors.accent)
                        profile.about?.let { Text(stringResource(R.string.trakt_profile_about, it), color = colors.secondaryText) }
                    }
                }
            }
            if (!state.configured) Text(stringResource(R.string.trakt_configuration_required), color = colors.secondaryText)
            val message = when (state.message) {
                TraktMessage.CONFIGURATION_REQUIRED -> R.string.trakt_configuration_required
                TraktMessage.RECONNECT_REQUIRED -> R.string.trakt_reconnect_required
                TraktMessage.RATE_LIMITED -> R.string.trakt_rate_limited
                TraktMessage.NETWORK_ERROR -> R.string.trakt_network_error
                TraktMessage.SIGN_IN_FAILED -> R.string.trakt_sign_in_failed
                null -> null
            }
            message?.let { Text(stringResource(it), color = colors.error) }
            if (failed) Text(stringResource(R.string.trakt_sign_in_failed), color = colors.error)
            if (browserUnavailable) Text(stringResource(R.string.trakt_browser_required), color = colors.secondaryText)

            if (state.connected) {
                TraktSettingsAction(
                    label = stringResource(R.string.trakt_refresh_profile),
                    onClick = viewModel::profile,
                    enabled = !busy,
                    modifier = modifier,
                )
                TraktSettingsAction(
                    label = stringResource(R.string.trakt_disconnect),
                    onClick = viewModel::disconnect,
                    enabled = !busy,
                )
            } else {
                if (state.connecting) {
                    Text(stringResource(R.string.trakt_complete_browser), color = colors.secondaryText)
                    TraktSettingsAction(
                        label = stringResource(R.string.trakt_cancel),
                        onClick = viewModel::cancel,
                        enabled = !busy,
                        modifier = modifier,
                    )
                } else {
                    TraktSettingsAction(
                        label = stringResource(R.string.trakt_connect),
                        onClick = {
                            viewModel.connect { url ->
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, url.toUri())
                                        .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                                )
                            }
                        },
                        enabled = state.configured && !busy,
                        modifier = if (state.configured) modifier else modifier.focusable(),
                        emphasized = true,
                    )
                }
            }
        }

        if (state.connected) {
            TraktSettingsPanel {
                SwitchSettingsRow(
                    label = stringResource(R.string.trakt_scrobbling_setting),
                    value = stringResource(if (scrobblingEnabled) R.string.trakt_setting_enabled else R.string.trakt_setting_disabled),
                    checked = scrobblingEnabled,
                    onCheckedChange = viewModel::setScrobblingEnabled,
                    enabled = !busy,
                )
                SwitchSettingsRow(
                    label = stringResource(R.string.trakt_history_sync_setting),
                    value = stringResource(if (historySyncEnabled) R.string.trakt_setting_enabled else R.string.trakt_setting_disabled),
                    checked = historySyncEnabled,
                    onCheckedChange = viewModel::setHistorySyncEnabled,
                    enabled = !busy,
                )
            }

            TraktSettingsPanel {
                Text(stringResource(R.string.trakt_sync_description), color = colors.secondaryText)
                TraktSettingsAction(
                    label = stringResource(R.string.trakt_sync_now),
                    onClick = viewModel::syncHistory,
                    enabled = !busy && historySyncEnabled,
                    emphasized = true,
                )
                when (val result = syncResult) {
                    is TraktHistorySyncResult.Success -> Text(
                        stringResource(R.string.trakt_sync_complete, result.imported, result.uploaded, result.unchanged,
                            result.unmatched, result.skipped),
                        color = colors.secondaryText,
                    )
                    is TraktHistorySyncResult.Failure -> Column(verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4)) {
                        Text(stringResource(when (result.reason) {
                            TraktSyncFailure.READ_LIMIT -> R.string.trakt_sync_limit
                            TraktSyncFailure.INCOGNITO -> R.string.trakt_sync_incognito
                            else -> R.string.trakt_sync_failed
                        }), color = colors.error)
                        if (result.imported > 0) Text(
                            pluralStringResource(R.plurals.trakt_sync_imported_count, result.imported, result.imported),
                            color = colors.secondaryText,
                        )
                    }
                    null -> Unit
                }
                if ((syncResult as? TraktHistorySyncResult.Success)?.moreUploads == true) {
                    Text(stringResource(R.string.trakt_sync_more_uploads), color = colors.secondaryText)
                }
            }

            TraktSettingsPanel {
                TraktSettingsAction(
                    label = stringResource(R.string.trakt_import_favorites),
                    onClick = viewModel::importFavorites,
                    enabled = !busy,
                )
                when (val result = favoriteImportResult) {
                    is TraktFavoriteImportResult.Success -> Text(
                        stringResource(R.string.trakt_import_favorites_result, result.imported, result.alreadyPresent,
                            result.unmatched, result.skipped),
                        color = colors.secondaryText,
                    )
                    is TraktFavoriteImportResult.Failure -> Text(
                        stringResource(when (result.reason) {
                            TraktFavoriteFailure.DISCONNECTED -> R.string.trakt_favorites_connect_first
                            TraktFavoriteFailure.INCOGNITO -> R.string.trakt_favorites_incognito
                            TraktFavoriteFailure.NO_ACTIVE_PROVIDER -> R.string.trakt_favorites_provider_required
                            TraktFavoriteFailure.READ_LIMIT -> R.string.trakt_favorites_too_large
                            else -> R.string.trakt_favorites_import_failed
                        }, result.imported),
                        color = colors.error,
                    )
                    null -> Unit
                }
            }

            TraktCustomListsSettings()
        }
    }
}

@Composable
private fun TraktSettingsPanel(
    accented: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().settingsDetailPanel(accented = accented)
            .padding(horizontal = SettingsDesignTokens.space16, vertical = SettingsDesignTokens.space16),
        verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space12),
        content = content,
    )
}

@Composable
private fun TraktSettingsAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    SettingsActionSurface(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsDesignTokens.space16, vertical = SettingsDesignTokens.space12),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled && emphasized) colors.accent else colors.primaryText,
        )
    }
}
