package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import com.streamvault.app.R
import com.streamvault.app.ui.components.TvEmptyState
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.mouseClickable
import com.streamvault.app.ui.theme.*
import com.streamvault.app.ui.time.LocalAppTimeFormat
import com.streamvault.app.ui.time.createDateTimeFormat
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingSourceType
import com.streamvault.domain.model.RecordingStatus
import androidx.compose.foundation.layout.ExperimentalLayoutApi

@Composable
internal fun RecordingMetaPill(label: String, value: String) {
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

internal fun summarizeRecordingOutputPath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return trimmed
    val decoded = runCatching { android.net.Uri.decode(trimmed) }.getOrDefault(trimmed)
    return decoded
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { decoded }
}

@Composable
internal fun RecordingActionButton(
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
                border = BorderStroke(FocusSpec.BorderWidth, FocusBorder),
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

internal fun formatRecordingSourceType(sourceType: RecordingSourceType): String = when (sourceType) {
    RecordingSourceType.TS -> "TS"
    RecordingSourceType.HLS -> "HLS"
    RecordingSourceType.DASH -> "DASH"
    RecordingSourceType.UNKNOWN -> "Auto"
}

internal fun formatRecordingFailureCategory(category: RecordingFailureCategory): String = when (category) {
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

