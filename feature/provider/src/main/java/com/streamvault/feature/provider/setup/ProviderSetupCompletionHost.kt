package com.streamvault.feature.provider.setup

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Composable
internal fun ProviderSetupCompletionLayer(
    uiState: ProviderSetupState,
    knownLocalM3uUrls: Set<String>,
    selectedM3uUrl: String,
    filesDir: java.io.File,
    onProviderAdded: () -> Unit,
    onAttachCreatedProvider: () -> Unit,
    onSkipCreatedProviderCombinedAttach: () -> Unit,
    cleanupImportedFiles: suspend (java.io.File, Set<String>, Int) -> Unit = ::cleanupOldImportedM3uFilesAsync
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(uiState.onboardingCompletion, uiState.completionWarning, uiState.pendingCombinedAttachProfileId) {
        if (
            uiState.onboardingCompletion != ProviderSetupViewModel.OnboardingCompletion.NONE &&
            uiState.pendingCombinedAttachProfileId == null
        ) {
            if (uiState.completionWarning != null) {
                Toast.makeText(
                    context,
                    "Provider saved. Sync will resume in background.",
                    Toast.LENGTH_LONG
                ).show()
            }
            val previousLocal = uiState.m3uUrl.takeIf { it.startsWith("file://") }
            val selectedLocal = selectedM3uUrl.takeIf { it.startsWith("file://") }
            val protectedUris = buildSet {
                knownLocalM3uUrls.forEach { knownUri ->
                    if (knownUri != previousLocal || previousLocal == selectedLocal) add(knownUri)
                }
                selectedLocal?.let(::add)
            }
            cleanupImportedFiles(filesDir, protectedUris, 20)
            lifecycle.awaitResumedForProviderSetup()
            onProviderAdded()
        }
    }

    if (uiState.pendingCombinedAttachProfileId != null) {
        PremiumDialog(
            title = "Add Playlist To Combined M3U?",
            subtitle = "Add ${uiState.createdProviderName ?: "this playlist"} to " +
                "${uiState.pendingCombinedAttachProfileName ?: "the active combined source"} " +
                "and keep that combined source active for Live TV?",
            onDismissRequest = onSkipCreatedProviderCombinedAttach,
            content = {},
            footer = {
                PremiumDialogFooterButton(
                    label = "Not Now",
                    onClick = onSkipCreatedProviderCombinedAttach
                )
                PremiumDialogFooterButton(
                    label = "Add To Combined",
                    onClick = onAttachCreatedProvider,
                    emphasized = true
                )
            }
        )
    }
}

private suspend fun Lifecycle.awaitResumedForProviderSetup() {
    if (currentState.isAtLeast(Lifecycle.State.RESUMED)) return

    suspendCancellableCoroutine { continuation ->
        lateinit var observer: LifecycleEventObserver
        observer = LifecycleEventObserver { source, _ ->
            if (source.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                source.lifecycle.removeObserver(observer)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        addObserver(observer)
        continuation.invokeOnCancellation { removeObserver(observer) }
    }
}
