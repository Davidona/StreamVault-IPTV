package com.streamvault.app.ui.screens.provider

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.interaction.mouseClickable
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.StalkerAuthMode

@Composable
internal fun XtreamLiveSyncModeOptionRow(
    mode: ProviderXtreamLiveSyncMode,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val titleRes = when (mode) {
        ProviderXtreamLiveSyncMode.AUTO -> R.string.setup_xtream_live_sync_mode_auto_title
        ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY -> R.string.setup_xtream_live_sync_mode_category_title
        ProviderXtreamLiveSyncMode.STREAM_ALL -> R.string.setup_xtream_live_sync_mode_stream_all_title
    }
    val descriptionRes = when (mode) {
        ProviderXtreamLiveSyncMode.AUTO -> R.string.setup_xtream_live_sync_mode_auto_description
        ProviderXtreamLiveSyncMode.CATEGORY_BY_CATEGORY -> R.string.setup_xtream_live_sync_mode_category_description
        ProviderXtreamLiveSyncMode.STREAM_ALL -> R.string.setup_xtream_live_sync_mode_stream_all_description
    }
    TvSurface(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .mouseClickable(onClick = onSelect),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
            focusedContainerColor = if (selected) Primary.copy(alpha = 0.26f) else SurfaceHighlight.copy(alpha = 0.9f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(
                    1.dp,
                    if (selected) Primary.copy(alpha = 0.45f) else Color.Transparent
                )
            ),
            focusedBorder = Border(BorderStroke(3.dp, PrimaryLight))
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(titleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

@Composable
internal fun StalkerAuthModeOptionRow(
    mode: StalkerAuthMode,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val title = when (mode) {
        StalkerAuthMode.AUTO -> "Auto-detect"
        StalkerAuthMode.MAC_ONLY -> "MAC only"
        StalkerAuthMode.MAC_PLUS_CREDENTIALS -> "MAC + credentials"
        StalkerAuthMode.CREDENTIALS_ONLY -> "Credentials only"
    }
    val description = when (mode) {
        StalkerAuthMode.AUTO -> "Try the portal's likely auth flow first and retry once if a different Stalker mode fits better."
        StalkerAuthMode.MAC_ONLY -> "Use MAG-style MAC authentication without a portal account login."
        StalkerAuthMode.MAC_PLUS_CREDENTIALS -> "Keep the MAG identity and add portal account credentials for stricter portals."
        StalkerAuthMode.CREDENTIALS_ONLY -> "Use portal account credentials even if the MAC address is optional or ignored."
    }
    TvSurface(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .mouseClickable(onClick = onSelect),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
            focusedContainerColor = if (selected) Primary.copy(alpha = 0.26f) else SurfaceHighlight.copy(alpha = 0.9f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(
                    1.dp,
                    if (selected) Primary.copy(alpha = 0.45f) else Color.Transparent
                )
            ),
            focusedBorder = Border(BorderStroke(3.dp, PrimaryLight))
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

@Composable
internal fun EpgSyncModeOptionRow(
    mode: ProviderEpgSyncMode,
    sourceType: SourceType,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val titleRes = when (mode) {
        ProviderEpgSyncMode.UPFRONT -> R.string.setup_epg_sync_mode_upfront_title
        ProviderEpgSyncMode.BACKGROUND -> R.string.setup_epg_sync_mode_background_title
        ProviderEpgSyncMode.SKIP -> R.string.setup_epg_sync_mode_skip_title
    }
    val descriptionRes = when (mode) {
        ProviderEpgSyncMode.UPFRONT -> if (sourceType == SourceType.STALKER) {
            R.string.setup_stalker_epg_sync_mode_upfront_description
        } else {
            R.string.setup_epg_sync_mode_upfront_description
        }
        ProviderEpgSyncMode.BACKGROUND -> if (sourceType == SourceType.STALKER) {
            R.string.setup_stalker_epg_sync_mode_background_description
        } else {
            R.string.setup_epg_sync_mode_background_description
        }
        ProviderEpgSyncMode.SKIP -> if (sourceType == SourceType.STALKER) {
            R.string.setup_stalker_epg_sync_mode_skip_description
        } else {
            R.string.setup_epg_sync_mode_skip_description
        }
    }
    TvSurface(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .mouseClickable(onClick = onSelect),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
            focusedContainerColor = if (selected) Primary.copy(alpha = 0.26f) else SurfaceHighlight.copy(alpha = 0.9f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(
                    1.dp,
                    if (selected) Primary.copy(alpha = 0.45f) else Color.Transparent
                )
            ),
            focusedBorder = Border(BorderStroke(3.dp, PrimaryLight))
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(titleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

internal fun supportsGuideAndLogoPolicy(sourceType: SourceType): Boolean = when (sourceType) {
    SourceType.XTREAM,
    SourceType.STALKER,
    SourceType.M3U_URL,
    SourceType.M3U_FILE -> true
    SourceType.JELLYFIN -> false
}

@Composable
internal fun GuideSourcePolicyOptionRow(
    policy: GuideSourcePolicy,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val title = when (policy) {
        GuideSourcePolicy.AUTO -> "Auto"
        GuideSourcePolicy.EXTERNAL_ONLY -> "Use external EPG only"
        GuideSourcePolicy.PROVIDER_ONLY -> "Use supplier EPG only"
        GuideSourcePolicy.DISABLED -> "Disable guide data"
    }
    val description = when (policy) {
        GuideSourcePolicy.AUTO -> "Keep the current mixed behavior: external XMLTV can override supplier guide data, and unmatched channels may still use supplier fallback."
        GuideSourcePolicy.EXTERNAL_ONLY -> "Use only assigned external XMLTV sources. Unmatched channels stay without guide data."
        GuideSourcePolicy.PROVIDER_ONLY -> "Ignore assigned external XMLTV sources for this provider and use only the supplier guide paths."
        GuideSourcePolicy.DISABLED -> "Do not import or resolve guide data for this provider."
    }
    PolicyOptionRow(title = title, description = description, selected = selected, onSelect = onSelect)
}

@Composable
internal fun ChannelLogoSourcePolicyOptionRow(
    policy: ChannelLogoSourcePolicy,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val title = when (policy) {
        ChannelLogoSourcePolicy.SUPPLIER_PREFERRED -> "Supplier logos first"
        ChannelLogoSourcePolicy.EPG_PREFERRED -> "Prefer EPG logos"
        ChannelLogoSourcePolicy.SUPPLIER_ONLY -> "Supplier logos only"
        ChannelLogoSourcePolicy.EPG_ONLY -> "EPG logos only"
    }
    val description = when (policy) {
        ChannelLogoSourcePolicy.SUPPLIER_PREFERRED -> "Use the supplier logo when it exists, then fall back to the matched EPG icon."
        ChannelLogoSourcePolicy.EPG_PREFERRED -> "Use the matched EPG icon first, then fall back to the supplier logo."
        ChannelLogoSourcePolicy.SUPPLIER_ONLY -> "Always use supplier logos for this provider."
        ChannelLogoSourcePolicy.EPG_ONLY -> "Always use matched EPG icons for this provider."
    }
    PolicyOptionRow(title = title, description = description, selected = selected, onSelect = onSelect)
}

@Composable
internal fun PolicyOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    TvSurface(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .mouseClickable(onClick = onSelect),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent,
            focusedContainerColor = if (selected) Primary.copy(alpha = 0.26f) else SurfaceHighlight.copy(alpha = 0.9f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(
                    1.dp,
                    if (selected) Primary.copy(alpha = 0.45f) else Color.Transparent
                )
            ),
            focusedBorder = Border(BorderStroke(3.dp, PrimaryLight))
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            }
        }
    }
}
