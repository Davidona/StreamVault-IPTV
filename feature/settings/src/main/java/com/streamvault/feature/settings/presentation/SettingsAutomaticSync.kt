package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.sync.*
import com.streamvault.feature.settings.R
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AutomaticSyncSettingsCard(
    viewModel: SettingsViewModel,
    provider: Provider? = null,
    modifier: Modifier = Modifier,
) {
    val configuration by viewModel.automaticSyncConfiguration.collectAsState()
    val statuses by viewModel.automaticSyncStatuses.collectAsState()
    val override = provider?.let { configuration.providers[it.id] }
    val policy = override ?: configuration.defaults
    val effectivePolicy = provider?.let { configuration.forProvider(it.id) } ?: policy
    var expanded by remember(provider?.id) { mutableStateOf(false) }
    var showHistory by remember(provider?.id) { mutableStateOf(false) }
    var dialog by remember(provider?.id) { mutableStateOf<String?>(null) }
    val update: (AutomaticSyncSettings) -> Unit = {
        viewModel.setAutomaticSyncSettings(provider?.id, it)
    }
    val editable = provider == null || override != null

    Surface(
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    if (provider == null) R.string.settings_auto_sync_title
                    else R.string.settings_auto_sync_provider_title
                ),
                style = MaterialTheme.typography.titleMedium,
                color = OnSurface,
            )

            if (provider == null) {
                Text(
                    text = stringResource(R.string.settings_auto_sync_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                )
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_auto_sync_enabled),
                    value = stringResource(R.string.settings_auto_sync_all_providers),
                    checked = policy.enabled,
                    onCheckedChange = { update(policy.copy(enabled = it)) },
                    modifier = modifier,
                )
            } else {
                SwitchSettingsRow(
                    label = stringResource(R.string.settings_auto_sync_override),
                    value = stringResource(R.string.settings_auto_sync_override_hint),
                    checked = override != null,
                    onCheckedChange = {
                        viewModel.setAutomaticSyncSettings(
                            provider.id,
                            if (it) configuration.defaults.copy(enabled = true) else null,
                        )
                    },
                )
                if (override == null) {
                    Text(
                        text = stringResource(
                            R.string.settings_auto_sync_using_defaults,
                            configuration.defaults.catalogHours,
                            configuration.defaults.epgHours,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    SwitchSettingsRow(
                        label = stringResource(R.string.settings_auto_sync_enabled),
                        value = stringResource(R.string.settings_auto_sync_provider_enabled_hint),
                        checked = policy.enabled,
                        onCheckedChange = { update(policy.copy(enabled = it)) },
                    )
                }

                val metadataFlow = remember(provider.id) {
                    viewModel.observeAutomaticSyncMetadata(provider.id)
                }
                val metadata by metadataFlow.collectAsState(initial = null)
                val status = statuses[provider.id]
                val outcome = if (!effectivePolicy.enabled) {
                    AutomaticSyncOutcome.DISABLED
                } else {
                    status?.outcome
                }
                SettingsRow(
                    label = stringResource(R.string.settings_auto_sync_status),
                    value = stringResource(
                        when (outcome) {
                            null -> R.string.settings_auto_sync_status_pending
                            AutomaticSyncOutcome.DISABLED -> R.string.settings_auto_sync_status_disabled
                            AutomaticSyncOutcome.UP_TO_DATE -> R.string.settings_auto_sync_status_fresh
                            AutomaticSyncOutcome.WAITING_NETWORK -> R.string.settings_auto_sync_status_network
                            AutomaticSyncOutcome.WAITING_CHARGING -> R.string.settings_auto_sync_status_charging
                            AutomaticSyncOutcome.WAITING_IDLE -> R.string.settings_auto_sync_status_idle
                            AutomaticSyncOutcome.WAITING_PLAYBACK -> R.string.settings_auto_sync_status_playback
                            AutomaticSyncOutcome.WAITING_WINDOW -> R.string.settings_auto_sync_status_window
                            AutomaticSyncOutcome.RUNNING -> R.string.settings_auto_sync_status_running
                            AutomaticSyncOutcome.QUEUED -> R.string.settings_auto_sync_status_queued
                            AutomaticSyncOutcome.REFRESHED -> R.string.settings_auto_sync_status_refreshed
                            AutomaticSyncOutcome.PARTIAL -> R.string.settings_auto_sync_status_partial
                            AutomaticSyncOutcome.FAILED -> R.string.settings_auto_sync_status_failed
                        }
                    ),
                )

                val timestamp: (Long?) -> String = { value ->
                    if (value == null || value <= 0L) "" else {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(value))
                    }
                }
                ClickableSettingsRow(
                    label = stringResource(
                        if (showHistory) R.string.settings_auto_sync_hide_history
                        else R.string.settings_auto_sync_history
                    ),
                    value = timestamp(status?.checkedAt).ifBlank {
                        stringResource(R.string.settings_auto_sync_status_pending)
                    },
                    onClick = { showHistory = !showHistory },
                )
                if (showHistory) {
                    val never = stringResource(R.string.settings_auto_sync_never)
                    if (provider.type != ProviderType.JELLYFIN) {
                        SettingsRow(
                            stringResource(R.string.settings_auto_sync_last_live),
                            timestamp(metadata?.lastLiveSuccess).ifBlank { never },
                        )
                    }
                    SettingsRow(
                        stringResource(R.string.settings_auto_sync_last_movies),
                        timestamp(metadata?.lastMovieSuccess).ifBlank { never },
                    )
                    if (provider.type != ProviderType.M3U) {
                        SettingsRow(
                            stringResource(R.string.settings_auto_sync_last_series),
                            timestamp(metadata?.lastSeriesSuccess).ifBlank { never },
                        )
                    }
                    if (provider.type != ProviderType.JELLYFIN) {
                        SettingsRow(
                            stringResource(R.string.settings_auto_sync_last_epg),
                            timestamp(metadata?.lastEpgSuccess).ifBlank { never },
                        )
                    }
                    val next = ProviderRefreshPlanner.plan(
                        provider.type,
                        metadata,
                        effectivePolicy,
                        System.currentTimeMillis(),
                        epgAvailable = provider.epgSyncMode !=
                            com.streamvault.domain.model.ProviderEpgSyncMode.SKIP,
                        unifiedVod = provider.catalogLayout ==
                            com.streamvault.domain.model.CatalogLayout.UNIFIED_VOD,
                    ).nextDueAt
                    if (next != null) {
                        SettingsRow(
                            stringResource(R.string.settings_auto_sync_next_due),
                            if (next <= System.currentTimeMillis()) {
                                stringResource(R.string.settings_auto_sync_overdue)
                            } else {
                                timestamp(next)
                            },
                        )
                    }
                }
            }

            if (provider == null || override != null) {
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_auto_sync_configure),
                    value = stringResource(
                        R.string.settings_auto_sync_summary,
                        policy.catalogHours,
                        policy.epgHours,
                    ),
                    onClick = { expanded = !expanded },
                    enabled = editable,
                )
            }
            if (expanded) {
                Text(
                    text = stringResource(R.string.settings_auto_sync_frequency_heading),
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_auto_sync_catalog_interval),
                    value = pluralStringResource(
                        R.plurals.settings_auto_sync_hours,
                        policy.catalogHours,
                        policy.catalogHours,
                    ),
                    onClick = { dialog = "catalog" },
                    enabled = editable,
                )
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_auto_sync_epg_interval),
                    value = pluralStringResource(
                        R.plurals.settings_auto_sync_hours,
                        policy.epgHours,
                        policy.epgHours,
                    ),
                    onClick = { dialog = "epg" },
                    enabled = editable && provider?.type != ProviderType.JELLYFIN,
                )
                Text(
                    text = stringResource(R.string.settings_auto_sync_sections_heading),
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
                if (provider?.type != ProviderType.JELLYFIN) {
                    SwitchSettingsRow(
                        stringResource(R.string.settings_auto_sync_live), "", policy.live,
                        { update(policy.copy(live = it)) }, enabled = editable,
                    )
                }
                SwitchSettingsRow(
                    stringResource(R.string.settings_auto_sync_movies),
                    if (provider?.type == ProviderType.M3U) {
                        stringResource(R.string.settings_auto_sync_m3u_hint)
                    } else "",
                    policy.movies,
                    { update(policy.copy(movies = it)) },
                    enabled = editable,
                )
                if (provider?.type != ProviderType.M3U &&
                    provider?.catalogLayout != com.streamvault.domain.model.CatalogLayout.UNIFIED_VOD
                ) {
                    SwitchSettingsRow(
                        stringResource(R.string.settings_auto_sync_series), "", policy.series,
                        { update(policy.copy(series = it)) }, enabled = editable,
                    )
                }
                if (provider?.type != ProviderType.JELLYFIN) {
                    SwitchSettingsRow(
                        stringResource(R.string.settings_auto_sync_epg),
                        stringResource(R.string.settings_auto_sync_epg_hint),
                        policy.epg,
                        { update(policy.copy(epg = it)) },
                        enabled = editable,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_auto_sync_conditions_heading),
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
                SwitchSettingsRow(
                    stringResource(R.string.settings_auto_sync_defer),
                    stringResource(R.string.settings_auto_sync_defer_hint),
                    policy.deferWhileBusy,
                    { update(policy.copy(deferWhileBusy = it)) },
                    enabled = editable,
                )
                SwitchSettingsRow(
                    stringResource(R.string.settings_auto_sync_unmetered), "", policy.unmeteredOnly,
                    { update(policy.copy(unmeteredOnly = it)) }, enabled = editable,
                )
                SwitchSettingsRow(
                    stringResource(R.string.settings_auto_sync_charging), "", policy.chargingOnly,
                    { update(policy.copy(chargingOnly = it)) }, enabled = editable,
                )
                SwitchSettingsRow(
                    stringResource(R.string.settings_auto_sync_idle),
                    stringResource(R.string.settings_auto_sync_idle_hint),
                    policy.deviceIdleOnly,
                    { update(policy.copy(deviceIdleOnly = it)) },
                    enabled = editable,
                )
                ClickableSettingsRow(
                    label = stringResource(R.string.settings_auto_sync_window),
                    value = if (policy.windowStartHour == policy.windowEndHour) {
                        stringResource(R.string.settings_auto_sync_any_time)
                    } else {
                        stringResource(
                            R.string.settings_auto_sync_window_value,
                            policy.windowStartHour,
                            policy.windowEndHour,
                        )
                    },
                    onClick = { dialog = "window" },
                    enabled = editable,
                )
            }
        }
    }

    dialog?.let { choice ->
        PremiumDialog(
            onDismissRequest = { dialog = null },
            title = stringResource(
                when (choice) {
                    "catalog" -> R.string.settings_auto_sync_catalog_interval
                    "epg" -> R.string.settings_auto_sync_epg_interval
                    else -> R.string.settings_auto_sync_window
                }
            ),
            content = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (choice == "window") {
                        item {
                            ClickableSettingsRow(
                                stringResource(R.string.settings_auto_sync_any_time), "",
                                {
                                    update(policy.copy(windowStartHour = 0, windowEndHour = 0))
                                    dialog = null
                                },
                            )
                            ClickableSettingsRow(
                                stringResource(R.string.settings_auto_sync_overnight), "",
                                {
                                    update(policy.copy(windowStartHour = 2, windowEndHour = 6))
                                    dialog = null
                                },
                            )
                            ClickableSettingsRow(
                                stringResource(R.string.settings_auto_sync_start),
                                stringResource(R.string.settings_auto_sync_hour, policy.windowStartHour),
                                { dialog = "start" },
                            )
                            ClickableSettingsRow(
                                stringResource(R.string.settings_auto_sync_end),
                                stringResource(R.string.settings_auto_sync_hour, policy.windowEndHour),
                                { dialog = "end" },
                            )
                        }
                    } else if (choice == "start" || choice == "end") {
                        items((0..23).toList()) { hour ->
                            ClickableSettingsRow(
                                stringResource(R.string.settings_auto_sync_hour, hour),
                                "",
                                {
                                    update(
                                        if (choice == "start") {
                                            policy.copy(windowStartHour = hour)
                                        } else {
                                            policy.copy(windowEndHour = hour)
                                        }
                                    )
                                    dialog = "window"
                                },
                            )
                        }
                    } else {
                        items(listOf(1, 3, 6, 12, 24, 48, 168)) { hours ->
                            ClickableSettingsRow(
                                pluralStringResource(R.plurals.settings_auto_sync_hours, hours, hours),
                                "",
                                {
                                    update(
                                        if (choice == "catalog") policy.copy(catalogHours = hours)
                                        else policy.copy(epgHours = hours)
                                    )
                                    dialog = null
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}
