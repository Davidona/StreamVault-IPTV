package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.theme.*
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType

@Composable
public fun ProviderSettingsCard(
    provider: Provider,
    isActive: Boolean,
    isSyncing: Boolean,
    appTimeFormat: AppTimeFormat,
    xtreamLiveOnboardingPhase: String?,
    xtreamLiveOnboarding: XtreamLiveOnboardingUiModel?,
    xtreamIndexSectionStatuses: Map<String, ProviderCatalogCountStatus>,
    diagnostics: ProviderDiagnosticsUiModel?,
    databaseMaintenance: DatabaseMaintenanceUiModel?,
    syncWarnings: List<String>,
    onRetryWarningAction: (ProviderWarningAction) -> Unit,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onParentalControl: () -> Unit,
    onToggleM3uVodClassification: (Boolean) -> Unit,
    onRefreshM3uClassification: () -> Unit
) {
    val liveOnboardingIncomplete = provider.type == ProviderType.XTREAM_CODES &&
        provider.status == ProviderStatus.PARTIAL &&
        !isActive
    val liveOnboardingMessageRes = if (liveOnboardingIncomplete) {
        xtreamLiveOnboardingMessageRes(xtreamLiveOnboardingPhase)
    } else {
        null
    }
    // Use Column layout - provider info + buttons below as separate focusable items
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isActive) SurfaceHighlight else SurfaceElevated,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isActive) 2.dp else 0.dp,
                color = if (isActive) Primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Provider info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnBackground
                )
                Text(
                    text = provider.type.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface
                )
            }
            ProviderStatusBadge(
                status = provider.status,
                requiresAttention = provider.type == ProviderType.STALKER_PORTAL &&
                    provider.status == ProviderStatus.PARTIAL &&
                    provider.stalkerTransportMode ==
                    com.streamvault.domain.model.StalkerTransportMode.AUTO_STRICT
            )
            if (isActive) {
                Text(
                    text = stringResource(R.string.settings_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier
                        .background(Primary.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Expiration Date
        val expDate = provider.expirationDate
        val expirationText = when (expDate) {
            null -> stringResource(R.string.settings_expiration_unknown)
            Long.MAX_VALUE -> stringResource(R.string.settings_expiration_never)
            else -> stringResource(
                R.string.settings_expires,
                java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                    .format(java.util.Date(expDate))
            )
        }
        Text(
            text = expirationText,
            style = MaterialTheme.typography.bodySmall,
            color = if (expDate != null && expDate < System.currentTimeMillis() && expDate != Long.MAX_VALUE) ErrorColor else OnSurfaceDim
        )

        if (liveOnboardingMessageRes != null) {
            Text(
                text = stringResource(liveOnboardingMessageRes),
                style = MaterialTheme.typography.bodySmall,
                color = Secondary
            )
            xtreamLiveOnboarding?.let { onboarding ->
                Text(
                    text = onboarding.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim
                )
            }
        }

        diagnostics?.let { model ->
            Text(
                text = listOf(model.sourceLabel, model.connectionSummary, model.expirySummary)
                    .filter { it.isNotBlank() }
                    .joinToString(" ג€¢ "),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderCompactStat(
                    title = stringResource(R.string.settings_diagnostic_live),
                    value = model.liveCatalogCount(liveOnboardingIncomplete, xtreamLiveOnboardingPhase)
                )
                ProviderCompactStat(
                    title = stringResource(R.string.settings_diagnostic_movies),
                    value = model.movieCatalogCount(xtreamIndexSectionStatuses["MOVIE"]),
                    syncingLabel = stringResource(R.string.settings_catalog_count_indexing)
                )
                if (provider.type != ProviderType.M3U) {
                    ProviderCompactStat(
                        title = stringResource(R.string.settings_diagnostic_series),
                        value = model.seriesCatalogCount(xtreamIndexSectionStatuses["SERIES"]),
                        syncingLabel = stringResource(R.string.settings_catalog_count_indexing)
                    )
                }
                ProviderCompactStat(
                    title = stringResource(R.string.settings_diagnostic_epg),
                    value = model.epgCatalogCount(xtreamIndexSectionStatuses["EPG"]),
                    syncingLabel = stringResource(R.string.settings_catalog_count_indexing)
                )
            }

            ProviderDiagnosticsPanel(
                provider = provider,
                diagnostics = model,
                appTimeFormat = appTimeFormat,
                movieIndexInProgress = xtreamIndexSectionStatuses["MOVIE"] in setOf(
                    ProviderCatalogCountStatus.QUEUED,
                    ProviderCatalogCountStatus.SYNCING
                ),
                databaseMaintenance = databaseMaintenance
            )
        }

        if (provider.type == ProviderType.M3U) {
            ProviderM3uOptionsPanel(
                m3uVodClassificationEnabled = provider.m3uVodClassificationEnabled,
                isSyncing = isSyncing,
                onToggleM3uVodClassification = onToggleM3uVodClassification,
                onRefreshM3uClassification = onRefreshM3uClassification
            )
        }

        if (syncWarnings.isNotEmpty()) {
            ProviderSyncWarningsPanel(
                providerType = provider.type,
                syncWarnings = syncWarnings,
                isSyncing = isSyncing,
                onRetryWarningAction = onRetryWarningAction
            )
        }

        ProviderActionButtons(
            isActive = isActive,
            isSyncing = isSyncing,
            liveOnboardingIncomplete = liveOnboardingIncomplete,
            onConnect = onConnect,
            onRefresh = onRefresh,
            onEdit = onEdit,
            onDelete = onDelete,
            onParentalControl = onParentalControl
        )

    }
}



public fun xtreamLiveOnboardingMessageRes(phase: String?): Int = when (phase?.uppercase()) {
    "STARTING" -> R.string.settings_provider_live_onboarding_starting
    "FETCHING" -> R.string.settings_provider_live_onboarding_fetching
    "RECOVERING" -> R.string.settings_provider_live_onboarding_recovering
    "STAGED" -> R.string.settings_provider_live_onboarding_staged
    "COMMITTING" -> R.string.settings_provider_live_onboarding_committing
    "FAILED" -> R.string.settings_provider_live_onboarding_failed
    else -> R.string.settings_provider_live_onboarding_incomplete
}

public fun ProviderDiagnosticsUiModel.liveCatalogCount(
    liveOnboardingIncomplete: Boolean,
    xtreamLiveOnboardingPhase: String?
): ProviderCatalogCountUiModel {
    if (liveOnboardingIncomplete) {
        return ProviderCatalogCountUiModel(
            count = liveCount,
            status = when (xtreamLiveOnboardingPhase?.uppercase()) {
                "FAILED" -> ProviderCatalogCountStatus.FAILED
                "STARTING", "FETCHING", "RECOVERING", "STAGED", "COMMITTING" -> ProviderCatalogCountStatus.SYNCING
                else -> ProviderCatalogCountStatus.PENDING
            }
        )
    }
    return if (lastLiveSuccess > 0L || liveCount > 0) {
        ProviderCatalogCountUiModel(liveCount, ProviderCatalogCountStatus.READY)
    } else {
        ProviderCatalogCountUiModel(liveCount, ProviderCatalogCountStatus.PENDING)
    }
}

public fun ProviderDiagnosticsUiModel.movieCatalogCount(
    jobStatus: ProviderCatalogCountStatus?
): ProviderCatalogCountUiModel = sectionCatalogCount(
    count = movieCount,
    lastSuccess = lastMovieSuccess,
    jobStatus = jobStatus
)

public fun ProviderDiagnosticsUiModel.seriesCatalogCount(
    jobStatus: ProviderCatalogCountStatus?
): ProviderCatalogCountUiModel = sectionCatalogCount(
    count = seriesCount,
    lastSuccess = lastSeriesSuccess,
    jobStatus = jobStatus
)

public fun ProviderDiagnosticsUiModel.epgCatalogCount(
    jobStatus: ProviderCatalogCountStatus?
): ProviderCatalogCountUiModel = sectionCatalogCount(
    count = epgCount,
    lastSuccess = lastEpgSuccess,
    jobStatus = jobStatus
)

public fun sectionCatalogCount(
    count: Int,
    lastSuccess: Long,
    jobStatus: ProviderCatalogCountStatus?
): ProviderCatalogCountUiModel {
    if (jobStatus != null && jobStatus != ProviderCatalogCountStatus.READY) {
        return ProviderCatalogCountUiModel(count, jobStatus)
    }
    return if (lastSuccess > 0L || count > 0) {
        ProviderCatalogCountUiModel(count, ProviderCatalogCountStatus.READY)
    } else {
        ProviderCatalogCountUiModel(count, ProviderCatalogCountStatus.PENDING)
    }
}




