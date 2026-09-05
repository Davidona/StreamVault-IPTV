package com.streamvault.app.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.feature.settings.presentation.BackupImportPreviewDialog

class BackupPreviewBenchmarkActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(intent.getStringExtra(EXTRA_PRESENTATION) == PRESENTATION_COMPOSE) {
            "Only the Compose presentation is available before the Views experiment is wired."
        }

        setContent {
            StreamVaultTheme {
                var plan by remember { mutableStateOf(BackupPreviewBenchmarkFixture.plan) }
                BackupImportPreviewDialog(
                    preview = BackupPreviewBenchmarkFixture.preview,
                    plan = plan,
                    onDismiss = ::finish,
                    onStrategySelected = { strategy: BackupConflictStrategy ->
                        plan = plan.copy(conflictStrategy = strategy)
                    },
                    onImportPreferencesChanged = { enabled ->
                        plan = plan.copy(importPreferences = enabled)
                    },
                    onImportProvidersChanged = { enabled ->
                        plan = plan.copy(importProviders = enabled)
                    },
                    onImportSavedLibraryChanged = { enabled ->
                        plan = plan.copy(importSavedLibrary = enabled)
                    },
                    onImportPlaybackHistoryChanged = { enabled ->
                        plan = plan.copy(importPlaybackHistory = enabled)
                    },
                    onImportMultiViewChanged = { enabled ->
                        plan = plan.copy(importMultiViewPresets = enabled)
                    },
                    onImportRecordingSchedulesChanged = { enabled ->
                        plan = plan.copy(importRecordingSchedules = enabled)
                    },
                    onConfirm = ::finish,
                )
            }
        }
    }

    companion object {
        const val EXTRA_PRESENTATION = "presentation"
        const val PRESENTATION_COMPOSE = "compose"
        const val PRESENTATION_VIEWS = "views"
    }
}
