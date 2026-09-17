package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.theme.ErrorColor
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import java.text.DateFormat
import java.util.Locale

@Composable
public fun ProviderDiagnosticsPanel(
    provider: Provider,
    diagnostics: ProviderDiagnosticsUiModel,
    appTimeFormat: AppTimeFormat,
    movieIndexInProgress: Boolean,
    databaseMaintenance: DatabaseMaintenanceUiModel?
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.settings_provider_diagnostics_title),
            style = MaterialTheme.typography.titleSmall,
            color = Primary
        )
        Text(
            text = diagnostics.capabilitySummary,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        Text(
            text = stringResource(R.string.diagnostics_summary_format, diagnostics.sourceLabel, diagnostics.connectionSummary),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface
        )
        Text(
            text = diagnostics.expirySummary,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface
        )
        Text(
            text = diagnostics.archiveSummary,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        if (provider.type == ProviderType.STALKER_PORTAL) {
            val notValidated = stringResource(R.string.settings_diagnostic_not_validated)
            Text(
                text = stringResource(
                    R.string.settings_diagnostic_stalker_protocol,
                    provider.stalkerProtocolFamily.name.replace('_', ' '),
                    provider.stalkerRequestedProfileId,
                    provider.stalkerLearnedProfileId.ifBlank { notValidated },
                    provider.stalkerEndpointPreference.name.replace('_', ' '),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )
            Text(
                text = stringResource(
                    R.string.settings_diagnostic_stalker_catalog,
                    stringResource(
                        if (provider.stalkerLearnedProfileId.isBlank()) {
                            R.string.settings_diagnostic_not_validated
                        } else {
                            R.string.settings_diagnostic_validated
                        }
                    ),
                    stringResource(
                        if (provider.stalkerSerialNumber.isNotBlank() || provider.stalkerDeviceId.isNotBlank()) {
                            R.string.settings_diagnostic_manual_identity
                        } else {
                            R.string.settings_diagnostic_mac_first
                        }
                    ),
                    provider.stalkerProfileVerification.name,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            Text(
                text = buildList {
                    add(
                        stringResource(
                            R.string.settings_diagnostic_transport,
                            provider.stalkerTransportMode.name.replace('_', ' '),
                        )
                    )
                    provider.stalkerTransportOrigin.takeIf(String::isNotBlank)?.let { origin ->
                        add(stringResource(R.string.settings_diagnostic_origin, origin))
                    }
                    if (provider.stalkerTransportConsentAt > 0L) {
                        add(stringResource(R.string.settings_diagnostic_user_approved))
                    }
                    add(
                        stringResource(
                            R.string.settings_diagnostic_generation,
                            provider.stalkerConfigurationGeneration,
                        )
                    )
                    if (provider.lastSyncedAt > 0L) {
                        add(
                            stringResource(
                                R.string.settings_diagnostic_last_verification,
                                DateFormat.getDateTimeInstance(
                                    DateFormat.MEDIUM,
                                    DateFormat.SHORT,
                                    Locale.getDefault()
                                ).format(java.util.Date(provider.lastSyncedAt))
                            )
                        )
                    }
                }.joinToString(" \u00B7 "),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            provider.stalkerCapabilitiesJson.takeIf(String::isNotBlank)?.let { states ->
                Text(
                    text = stringResource(R.string.settings_diagnostic_capabilities, states),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
            provider.stalkerDiscoverySummary.takeIf(String::isNotBlank)?.let { summary ->
                Text(
                    text = stringResource(R.string.settings_diagnostic_last_discovery, summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_diagnostic_status, diagnostics.lastSyncStatus),
            style = MaterialTheme.typography.labelSmall,
            color = OnSurface
        )
        diagnostics.healthSummary(provider.type, movieIndexInProgress)?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = ErrorColor,
                fontWeight = FontWeight.Medium
            )
        }
        databaseMaintenance?.let { report ->
            DatabaseMaintenancePanel(report = report, appTimeFormat = appTimeFormat)
        }
    }
}

@Composable
private fun DatabaseMaintenancePanel(
    report: DatabaseMaintenanceUiModel,
    appTimeFormat: AppTimeFormat
) {
    val dateTimeFormat = remember(appTimeFormat) { appTimeFormat.createSettingsDateTimeFormat() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(com.streamvault.core.ui.theme.SurfaceElevated, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_database_health),
            style = MaterialTheme.typography.titleSmall,
            color = Primary
        )
        Text(
            text = stringResource(
                R.string.settings_database_last_maintenance,
                formatDiagnosticTimestamp(report.ranAt, dateTimeFormat)
                    ?: stringResource(R.string.settings_database_maintenance_unknown),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface
        )
        Text(
            text = stringResource(
                R.string.settings_database_pruned_summary,
                report.deletedPrograms,
                report.deletedExternalProgrammes,
                report.deletedOrphanEpisodes,
                report.deletedStaleFavorites,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        Text(
            text = stringResource(
                R.string.settings_database_size_summary,
                formatMaintenanceBytes(report.mainDbBytes),
                formatMaintenanceBytes(report.walBytes),
                formatMaintenanceBytes(report.reclaimableBytes),
                stringResource(
                    if (report.vacuumRan) R.string.settings_database_vacuum_ran
                    else R.string.settings_database_vacuum_skipped
                ),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface
        )
        Text(
            text = stringResource(
                R.string.settings_database_catalog_rows,
                formatMaintenanceCount(report.channelRows),
                formatMaintenanceCount(report.movieRows),
                formatMaintenanceCount(report.seriesRows),
                formatMaintenanceCount(report.episodeRows),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        Text(
            text = stringResource(
                R.string.settings_database_activity_rows,
                formatMaintenanceCount(report.programRows),
                formatMaintenanceCount(report.epgProgrammeRows),
                formatMaintenanceCount(report.playbackHistoryRows),
                formatMaintenanceCount(report.favoriteRows),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
    }
}

@Composable
private fun ProviderDiagnosticsUiModel.healthSummary(
    providerType: ProviderType,
    movieIndexInProgress: Boolean
): String? {
    val warnings = buildList {
        if (liveSequentialFailuresRemembered) {
            add(stringResource(R.string.settings_diagnostic_live_attention))
        }
        if (movieParallelFailuresRemembered) {
            add(
                if (movieWarningsCount > 0) {
                    pluralStringResource(
                        R.plurals.settings_diagnostic_movie_warnings,
                        movieWarningsCount,
                        movieWarningsCount,
                    )
                } else {
                    stringResource(R.string.settings_diagnostic_movie_attention)
                }
            )
        }
        if (movieCatalogStale && !movieIndexInProgress) {
            add(stringResource(R.string.settings_diagnostic_movie_catalog_stale))
        }
        if (providerType == ProviderType.XTREAM_CODES && seriesSequentialFailuresRemembered) {
            add(stringResource(R.string.settings_diagnostic_series_attention))
        }
    }
    if (warnings.isEmpty()) {
        val streakParts = buildList {
            if (liveHealthySyncStreak > 0) {
                add(stringResource(R.string.settings_diagnostic_live_streak, liveHealthySyncStreak))
            }
            if (movieHealthySyncStreak > 0) {
                add(stringResource(R.string.settings_diagnostic_movie_streak, movieHealthySyncStreak))
            }
            if (providerType == ProviderType.XTREAM_CODES && seriesHealthySyncStreak > 0) {
                add(stringResource(R.string.settings_diagnostic_series_streak, seriesHealthySyncStreak))
            }
        }
        return streakParts.takeIf { it.isNotEmpty() }?.joinToString(" \u00B7 ")
    }
    return warnings.joinToString(" \u00B7 ")
}

private fun formatDiagnosticTimestamp(timestamp: Long, dateTimeFormat: DateFormat): String? =
    if (timestamp <= 0L) {
        null
    } else {
        dateTimeFormat.format(java.util.Date(timestamp))
    }

private fun formatMaintenanceBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val formatted = if (value >= 10 || unitIndex == 0) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
    return "$formatted ${units[unitIndex]}"
}

private fun formatMaintenanceCount(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}
