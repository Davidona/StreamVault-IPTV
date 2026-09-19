package com.streamvault.feature.provider.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.theme.*
import kotlinx.coroutines.delay

@Composable
internal fun StalkerLinkOptionButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    TvSurface(
        onClick = onClick,
        modifier = Modifier.mouseClickable(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.18f) else Surface,
            focusedContainerColor = Primary.copy(alpha = 0.28f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) Primary else SurfaceHighlight)),
            focusedBorder = Border(BorderStroke(3.dp, PrimaryLight))
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary
        )
    }
}

@Composable
internal fun StalkerRequestRulesEditor(
    rules: List<StalkerRequestRuleUiState>,
    onAddRule: () -> Unit,
    onUpdateRule: (Int, StalkerRequestRuleUiState) -> Unit,
    onRemoveRule: (Int) -> Unit
) {
    val newestRuleBringIntoViewRequester = remember { BringIntoViewRequester() }
    var previousRuleCount by remember { mutableIntStateOf(rules.size) }

    LaunchedEffect(rules.size) {
        if (rules.size > previousRuleCount) {
            delay(120)
            runCatching { newestRuleBringIntoViewRequester.bringIntoView() }
        }
        previousRuleCount = rules.size
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(12.dp))
            .border(1.dp, SurfaceHighlight, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Request rules", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text(
                    "Match by action, block calls, or override params. Blank values remove params.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
            RequestRuleActionButton(text = "Add Rule", onClick = onAddRule)
        }
        rules.forEachIndexed { index, rule ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (index == rules.lastIndex) {
                            Modifier.bringIntoViewRequester(newestRuleBringIntoViewRequester)
                        } else {
                            Modifier
                        }
                    )
                    .border(1.dp, SurfaceHighlight.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Rule ${index + 1}", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    RequestRuleActionButton(
                        text = "Delete",
                        onClick = { onRemoveRule(index) },
                        compact = true
                    )
                }
                ProviderTextField(
                    value = rule.action,
                    onValueChange = { onUpdateRule(index, rule.copy(action = it.trim())) },
                    placeholder = "action, e.g. get_profile"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Block this request", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Switch(
                        checked = rule.blockRequest,
                        onCheckedChange = { onUpdateRule(index, rule.copy(blockRequest = it)) }
                    )
                }
                if (rule.blockRequest && rule.action.trim() in setOf("handshake", "get_profile")) {
                    Text(
                        text = "Warning: blocking ${rule.action.trim()} can prevent login.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentAmber
                    )
                }
                ProviderTextField(
                    value = rule.paramsText,
                    onValueChange = { onUpdateRule(index, rule.copy(paramsText = it)) },
                    placeholder = "Param overrides: name=value | remove_me="
                )
            }
        }
    }
}

@Composable
internal fun RequestRuleActionButton(
    text: String,
    onClick: () -> Unit,
    compact: Boolean = false
) {
    TvClickableSurface(
        onClick = onClick,
        modifier = Modifier
            .width(if (compact) 88.dp else 124.dp)
            .height(40.dp)
            .mouseClickable(onClick = onClick)
            .semantics { contentDescription = text },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (compact) SurfaceHighlight.copy(alpha = 0.9f) else Primary,
            focusedContainerColor = if (compact) SurfaceHighlight else PrimaryLight
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(
                    1.dp,
                    if (compact) SurfaceHighlight.copy(alpha = 0.8f) else PrimaryLight
                )
            ),
            focusedBorder = Border(BorderStroke(3.dp, FocusBorder))
        )
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
        }
    }
}
