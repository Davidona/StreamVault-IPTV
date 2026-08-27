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
            Text(
                text = buildString {
                    append("Protocol: ")
                    append(provider.stalkerProtocolFamily.name.replace('_', ' '))
                    append(" • Requested: ")
                    append(provider.stalkerRequestedProfileId)
                    append(" • Winner: ")
                    append(provider.stalkerLearnedProfileId.ifBlank { "not validated" })
                    append(" • Endpoint: ")
                    append(provider.stalkerEndpointPreference.name.replace('_', ' '))
                },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )
            Text(
                text = buildString {
                    append("Catalog: ")
                    append(if (provider.stalkerLearnedProfileId.isBlank()) "not validated" else "validated")
                    append(" • Identity: ")
                    append(
                        if (provider.stalkerSerialNumber.isNotBlank() || provider.stalkerDeviceId.isNotBlank()) {
                            "manual fields"
                        } else {
                            "MAC first"
                        }
                    )
                    append(" • Verification: ")
                    append(provider.stalkerProfileVerification.name)
                },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            Text(
                text = buildString {
                    append("Transport: ")
                    append(provider.stalkerTransportMode.name.replace('_', ' '))
                    provider.stalkerTransportOrigin.takeIf(String::isNotBlank)?.let { origin ->
                        append(" • Origin: ")
                        append(origin)
                    }
                    if (provider.stalkerTransportConsentAt > 0L) {
                        append(" • User approved")
                    }
                    append(" • Generation: ")
                    append(provider.stalkerConfigurationGeneration)
                    if (provider.lastSyncedAt > 0L) {
                        append(" | Last verification: ")
                        append(
                            DateFormat.getDateTimeInstance(
                                DateFormat.MEDIUM,
                                DateFormat.SHORT,
                                Locale.getDefault()
                            ).format(java.util.Date(provider.lastSyncedAt))
                        )
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
            provider.stalkerCapabilitiesJson.takeIf(String::isNotBlank)?.let { states ->
                Text(
                    text = "Capabilities: $states",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
            provider.stalkerDiscoverySummary.takeIf(String::isNotBlank)?.let { summary ->
                Text(
                    text = "Last discovery: $summary",
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
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Database Health",
            style = MaterialTheme.typography.titleSmall,
            color = Primary
        )
        Text(
            text = "Last maintenance ${formatDiagnosticTimestamp(report.ranAt, dateTimeFormat)}",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface
        )
        Text(
            text = buildString {
                append("Pruned ")
                append(report.deletedPrograms)
                append(" internal programs, ")
                append(report.deletedExternalProgrammes)
                append(" external programs, ")
                append(report.deletedOrphanEpisodes)
                append(" orphan episodes, and ")
                append(report.deletedStaleFavorites)
                append(" stale favorites.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        Text(
            text = buildString {
                append("Main DB ")
                append(formatMaintenanceBytes(report.mainDbBytes))
                append(" • WAL ")
                append(formatMaintenanceBytes(report.walBytes))
                append(" • Reclaimable ")
                append(formatMaintenanceBytes(report.reclaimableBytes))
                append(" • VACUUM ")
                append(if (report.vacuumRan) "ran" else "not needed or skipped")
            },
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface
        )
        Text(
            text = buildString {
                append("Rows: channels ")
                append(formatMaintenanceCount(report.channelRows))
                append(", movies ")
                append(formatMaintenanceCount(report.movieRows))
                append(", series ")
                append(formatMaintenanceCount(report.seriesRows))
                append(", episodes ")
                append(formatMaintenanceCount(report.episodeRows))
            },
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        Text(
            text = buildString {
                append("Programs ")
                append(formatMaintenanceCount(report.programRows))
                append(", external EPG ")
                append(formatMaintenanceCount(report.epgProgrammeRows))
                append(", history ")
                append(formatMaintenanceCount(report.playbackHistoryRows))
                append(", favorites ")
                append(formatMaintenanceCount(report.favoriteRows))
            },
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
    }
}

private fun ProviderDiagnosticsUiModel.healthSummary(
    providerType: ProviderType,
    movieIndexInProgress: Boolean
): String? {
    val warnings = buildList {
        if (liveSequentialFailuresRemembered) {
            add("Live sync needs attention")
        }
        if (movieParallelFailuresRemembered) {
            add(
                if (movieWarningsCount > 0) {
                    "Movies have $movieWarningsCount remembered warning(s)"
                } else {
                    "Movie sync still has remembered warnings"
                }
            )
        }
        if (movieCatalogStale && !movieIndexInProgress) {
            add("Movie catalog is running stale")
        }
        if (providerType == ProviderType.XTREAM_CODES && seriesSequentialFailuresRemembered) {
            add("Series sync needs attention")
        }
    }
    if (warnings.isEmpty()) {
        val streakParts = buildList {
            if (liveHealthySyncStreak > 0) add("Live streak $liveHealthySyncStreak")
            if (movieHealthySyncStreak > 0) add("Movies streak $movieHealthySyncStreak")
            if (providerType == ProviderType.XTREAM_CODES && seriesHealthySyncStreak > 0) {
                add("Series streak $seriesHealthySyncStreak")
            }
        }
        return streakParts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
    }
    return warnings.joinToString(" • ")
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
