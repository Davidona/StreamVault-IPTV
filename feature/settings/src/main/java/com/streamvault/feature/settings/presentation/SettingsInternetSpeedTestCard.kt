package com.streamvault.feature.settings.presentation

import com.streamvault.feature.settings.presentation.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.Secondary
import com.streamvault.core.ui.theme.SurfaceElevated

@Composable
public fun InternetSpeedTestCard(
    valueLabel: String,
    summary: String,
    recommendationLabel: String,
    isRunning: Boolean,
    canApplyRecommendation: Boolean,
    onRunTest: () -> Unit,
    onApplyWifi: () -> Unit,
    onApplyEthernet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    TvClickableSurface(
        onClick = onRunTest,
        enabled = !isRunning,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = Primary.copy(alpha = 0.18f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .focusRequester(focusRequester)
            .mouseClickable(
                enabled = !isRunning,
                focusRequester = focusRequester,
                onClick = onRunTest
            )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.settings_speed_test_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = OnBackground
                    )
                    Text(
                        text = valueLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = Primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (isRunning) {
                    CircularProgressIndicator(
                        color = Primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_speed_test_run_action),
                        style = MaterialTheme.typography.labelLarge,
                        color = Primary
                    )
                }
            }

            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )

            Text(
                text = stringResource(R.string.settings_speed_test_recommendation, recommendationLabel),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvClickableSurface(
                    onClick = onRunTest,
                    enabled = !isRunning,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary.copy(alpha = 0.18f),
                        focusedContainerColor = Primary.copy(alpha = 0.32f)
                    )
                ) {
                    Text(
                        text = stringResource(if (isRunning) R.string.settings_speed_test_running_action else R.string.settings_speed_test_run_action),
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                TvClickableSurface(
                    onClick = onApplyWifi,
                    enabled = canApplyRecommendation && !isRunning,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Secondary.copy(alpha = 0.16f),
                        focusedContainerColor = Secondary.copy(alpha = 0.28f)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.settings_speed_test_apply_wifi),
                        style = MaterialTheme.typography.labelMedium,
                        color = Secondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                TvClickableSurface(
                    onClick = onApplyEthernet,
                    enabled = canApplyRecommendation && !isRunning,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Secondary.copy(alpha = 0.16f),
                        focusedContainerColor = Secondary.copy(alpha = 0.28f)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.settings_speed_test_apply_ethernet),
                        style = MaterialTheme.typography.labelMedium,
                        color = Secondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}
