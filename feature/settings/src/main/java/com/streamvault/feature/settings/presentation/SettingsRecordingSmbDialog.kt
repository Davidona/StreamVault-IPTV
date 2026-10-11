package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.feature.settings.R
import java.net.URI

@Composable
internal fun RecordingSmbStorageDialog(
    storageState: com.streamvault.domain.model.RecordingStorageState,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(storageState.smbUrl.orEmpty()) }
    var username by remember { mutableStateOf(storageState.smbUsername.orEmpty()) }
    // Do not put passwords in saved instance state. A blank value preserves the current password
    // only when both the SMB URL and username stay unchanged.
    var password by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    PremiumDialog(
        title = androidx.compose.ui.res.stringResource(R.string.settings_recording_smb_title),
        subtitle = androidx.compose.ui.res.stringResource(R.string.settings_recording_smb_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.58f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.settings_recording_smb_url_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurfaceDim,
                )
                EpgSourceTextField(
                    value = url,
                    onValueChange = { entered ->
                        val parsed = parseSmbCredentialsFromUrl(entered)
                        if (parsed != null) {
                            url = parsed.url
                            username = parsed.username
                            password = parsed.password
                        } else {
                            url = entered
                        }
                    },
                    placeholder = androidx.compose.ui.res.stringResource(R.string.settings_recording_smb_url_hint),
                    keyboardType = KeyboardType.Uri,
                )

                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.settings_recording_smb_username_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurfaceDim,
                )
                EpgSourceTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = androidx.compose.ui.res.stringResource(R.string.settings_recording_smb_username_hint),
                )

                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.settings_recording_smb_password_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurfaceDim,
                )
                EpgSourceTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = androidx.compose.ui.res.stringResource(R.string.settings_recording_smb_password_hint),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardType = KeyboardType.Password,
                )
            }
        },
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PremiumDialogFooterButton(
                    label = androidx.compose.ui.res.stringResource(R.string.settings_cancel),
                    onClick = onDismiss,
                    enabled = !isSaving,
                )
                PremiumDialogFooterButton(
                    label = androidx.compose.ui.res.stringResource(
                        if (isSaving) R.string.settings_recording_smb_saving else R.string.settings_recording_smb_save
                    ),
                    onClick = {
                        val keepExistingPassword =
                            url.trim() == storageState.smbUrl &&
                                username.trim() == storageState.smbUsername.orEmpty() &&
                                password.isBlank()
                        isSaving = true
                        viewModel.updateRecordingSmbStorage(
                            url = url.trim(),
                            username = username.trim(),
                            password = if (keepExistingPassword) null else password,
                            onComplete = { success ->
                                isSaving = false
                                if (success) onDismiss()
                            },
                        )
                    },
                    enabled = !isSaving,
                    emphasized = true,
                )
            }
        },
    )
}

private data class ParsedSmbCredentials(
    val url: String,
    val username: String,
    val password: String,
)

private fun parseSmbCredentialsFromUrl(rawUrl: String): ParsedSmbCredentials? = runCatching {
    val parsed = URI(rawUrl.trim().replace(" ", "%20"))
    val rawUserInfo = parsed.rawUserInfo ?: return null
    require(parsed.scheme.equals("smb", ignoreCase = true) && !parsed.host.isNullOrBlank())
    val decoded = android.net.Uri.decode(rawUserInfo)
    val separator = decoded.indexOf(':')
    val username = if (separator >= 0) decoded.substring(0, separator) else decoded
    val password = if (separator >= 0) decoded.substring(separator + 1) else ""
    val cleanUrl = URI("smb", null, parsed.host, parsed.port, parsed.path, null, null).toASCIIString()
    ParsedSmbCredentials(cleanUrl, username, password)
}.getOrNull()
