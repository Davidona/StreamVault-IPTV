package com.streamvault.feature.settings.navigation

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.core.ui.components.shell.UiDestination
import com.streamvault.domain.model.Result
import com.streamvault.feature.settings.api.SettingsBackupFileCandidate
import com.streamvault.feature.settings.api.SettingsBackupFileHost
import com.streamvault.feature.settings.api.SettingsBuildInfo
import com.streamvault.feature.settings.api.SettingsOfficialBuildStatus
import com.streamvault.feature.settings.api.SettingsPlatformHost
import com.streamvault.feature.settings.api.SettingsRecordingPlaybackRequest
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRouteGraphBehaviorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsRoute_decodesEncodedBackupUriThroughFeatureGraph() {
        lateinit var navController: TestNavHostController
        var receivedBackupUri: String? = null
        val backupUri = "content://backups/fixture backup.json?slot=1"

        composeRule.setContent {
            navController = rememberTestNavController()
            NavHost(navController = navController, startDestination = "host") {
                composable("host") {}
                registerSettingsGraph(
                    actions = NoOpNavigationActions,
                    platformHost = EmptySettingsPlatformHost,
                    navigationDestinations = emptyList(),
                    settingsContent = { uri, _, _ ->
                        receivedBackupUri = uri
                        androidx.tv.material3.Text("Settings route")
                    },
                    parentalControlContent = { _, _ -> androidx.tv.material3.Text("Parental route") }
                )
            }
            LaunchedEffect(Unit) {
                navController.navigate(
                    "settings?backupUri=${Uri.encode(backupUri)}"
                )
            }
        }

        composeRule.waitUntil(3_000) { receivedBackupUri == backupUri }
        composeRule.onNodeWithText("Settings route").assertIsDisplayed()
        composeRule.runOnIdle {
            assertThat(receivedBackupUri).isEqualTo(backupUri)
        }
    }

    @Test
    fun parentalRoute_preservesLongProviderIdArgument() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController = rememberTestNavController()
            NavHost(navController = navController, startDestination = "host") {
                composable("host") {}
                registerSettingsGraph(
                    actions = NoOpNavigationActions,
                    platformHost = EmptySettingsPlatformHost,
                    navigationDestinations = emptyList(),
                    settingsContent = { _, _, _ -> androidx.tv.material3.Text("Settings route") },
                    parentalControlContent = { _, _ -> androidx.tv.material3.Text("Parental route") }
                )
            }
            LaunchedEffect(Unit) {
                navController.navigate("parental_control_groups/987654321")
            }
        }

        composeRule.waitUntil(3_000) {
            navController.currentBackStackEntry?.arguments?.getLong("providerId") == 987654321L
        }
        composeRule.onNodeWithText("Parental route").assertIsDisplayed()
        composeRule.runOnIdle {
            assertThat(navController.currentBackStackEntry?.arguments?.getLong("providerId"))
                .isEqualTo(987654321L)
        }
    }

    @Test
    fun parentalRoute_passesTopNavigationDestinationsToParentalContent() {
        lateinit var navController: TestNavHostController
        var receivedDestinationIds: List<String>? = null
        val destinations = listOf(
            UiDestination(
                id = "home",
                label = "Home",
                icon = Icons.Default.Home,
            ),
        )

        composeRule.setContent {
            navController = rememberTestNavController()
            NavHost(navController = navController, startDestination = "host") {
                composable("host") {}
                registerSettingsGraph(
                    actions = NoOpNavigationActions,
                    platformHost = EmptySettingsPlatformHost,
                    navigationDestinations = destinations,
                    settingsContent = { _, _, _ -> androidx.tv.material3.Text("Settings route") },
                    parentalControlContent = { _, receivedDestinations ->
                        receivedDestinationIds = receivedDestinations.map(UiDestination::id)
                        androidx.tv.material3.Text("Parental route")
                    },
                )
            }
            LaunchedEffect(Unit) {
                navController.navigate("parental_control_groups/987654321")
            }
        }

        composeRule.waitUntil(3_000) { receivedDestinationIds != null }
        composeRule.runOnIdle {
            assertThat(receivedDestinationIds).containsExactly("home")
        }
    }

    @Composable
    private fun rememberTestNavController(): TestNavHostController {
        val context = LocalContext.current
        return remember(context) {
            TestNavHostController(context).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
        }
    }
}

private object NoOpNavigationActions : NavigationActions {
    override fun navigate(destination: AppDestination, options: NavigationOptions) = Unit
    override fun openPlayer(request: PlayerNavigationRequest) = Unit
    override fun back(): Boolean = true
    override fun returnTo(destination: AppDestination?): Boolean = true
}

private object EmptySettingsPlatformHost : SettingsPlatformHost {
    override val buildInfo = SettingsBuildInfo(
        versionName = "test",
        versionCode = 1,
        applicationId = "com.streamvault.feature.settings.test",
        buildType = "debug"
    )
    override val backupFiles: SettingsBackupFileHost = EmptyBackupFileHost
    override fun officialBuildStatus() = SettingsOfficialBuildStatus.VERIFICATION_UNAVAILABLE
    override fun playRecording(request: SettingsRecordingPlaybackRequest) = Unit
    override fun shareBackup(uri: Uri): Result<Unit> = error("not used")
    override fun shareCrashReport(): Result<Unit> = error("not used")
    override fun removableBackupDirectory(): File? = null
}

private object EmptyBackupFileHost : SettingsBackupFileHost {
    override val jsonMimeType: String = "application/json"
    override fun rememberManagedExport(uri: Uri) = Unit
    override fun listManagedBackups(): List<SettingsBackupFileCandidate> = emptyList()
    override fun listPickerFreeBackups(): List<SettingsBackupFileCandidate> = emptyList()
    override fun listBackups(directory: File): List<SettingsBackupFileCandidate> = emptyList()
    override fun createShareExportFile(): File = error("not used")
    override fun createPickerFreeExportUri(): Uri? = null
    override fun finishPickerFreeExport(uri: Uri, success: Boolean): Boolean = false
    override fun createExportFile(directory: File): File = error("not used")
    override fun delete(candidate: SettingsBackupFileCandidate): Boolean = false
    override fun providerUri(file: File): Uri = error("not used")
}
