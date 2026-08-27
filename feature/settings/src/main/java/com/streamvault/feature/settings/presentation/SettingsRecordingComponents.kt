package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.streamvault.core.ui.design.FocusSpec
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.theme.*
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingSourceType

@Composable
public fun RecordingMetaPill(label: String, value: String) {
    Column(
        modifier = Modifier
            .widthIn(min = 92.dp, max = 160.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 5.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
        Text(text = value, style = MaterialTheme.typography.labelMedium, color = OnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

public fun summarizeRecordingOutputPath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return trimmed
    val decoded = runCatching { android.net.Uri.decode(trimmed) }.getOrDefault(trimmed)
    return decoded
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { decoded }
}

@Composable
public fun RecordingActionButton(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    TvButton(
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = 168.dp, max = 220.dp)
            .heightIn(min = 52.dp)
            .then(modifier),
        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ButtonDefaults.colors(
            containerColor = accent.copy(alpha = 0.14f),
            focusedContainerColor = accent.copy(alpha = 0.28f),
            contentColor = accent,
            focusedContentColor = accent
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(10.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, Color.White),
                shape = RoundedCornerShape(10.dp)
            )
        ),
        scale = ButtonDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = label,
            color = accent,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

public fun formatRecordingSourceType(sourceType: RecordingSourceType): String = when (sourceType) {
    RecordingSourceType.TS -> "TS"
    RecordingSourceType.HLS -> "HLS"
    RecordingSourceType.DASH -> "DASH"
    RecordingSourceType.UNKNOWN -> "Auto"
}

public fun formatRecordingFailureCategory(category: RecordingFailureCategory): String = when (category) {
    RecordingFailureCategory.NONE -> "None"
    RecordingFailureCategory.NETWORK -> "Network"
    RecordingFailureCategory.STORAGE -> "Storage"
    RecordingFailureCategory.AUTH -> "Auth"
    RecordingFailureCategory.TOKEN_EXPIRED -> "Token"
    RecordingFailureCategory.DRM_UNSUPPORTED -> "DRM"
    RecordingFailureCategory.FORMAT_UNSUPPORTED -> "Format"
    RecordingFailureCategory.SCHEDULE_CONFLICT -> "Conflict"
    RecordingFailureCategory.PROVIDER_LIMIT -> "Connection limit"
    RecordingFailureCategory.UNKNOWN -> "Unknown"
}

