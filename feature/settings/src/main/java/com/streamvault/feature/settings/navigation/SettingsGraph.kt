package com.streamvault.feature.settings.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.streamvault.core.navigation.NavigationActions

/** Stable route patterns owned by the settings feature. */
object SettingsRoutePatterns {
    const val SETTINGS = "settings"
    const val SETTINGS_DESTINATION = "settings?backupUri={backupUri}"
    const val PARENTAL_CONTROL_GROUPS = "parental_control_groups/{providerId}"
}

/**
 * Registers settings-owned destinations without depending on the app navigation
 * controller or route codec. The app supplies the platform/composition content.
 */
fun NavGraphBuilder.registerSettingsGraph(
    actions: NavigationActions,
    settingsContent: @Composable (backupUri: String?) -> Unit,
    parentalControlContent: @Composable (onBack: () -> Unit) -> Unit,
) {
    composable(
        route = SettingsRoutePatterns.SETTINGS_DESTINATION,
        arguments = listOf(
            navArgument("backupUri") { type = NavType.StringType; defaultValue = "" }
        )
    ) { backStackEntry ->
        val backupUri = backStackEntry.arguments?.getString("backupUri")?.takeIf { it.isNotBlank() }
        settingsContent(backupUri)
    }

    composable(
        route = SettingsRoutePatterns.PARENTAL_CONTROL_GROUPS,
        arguments = listOf(
            navArgument("providerId") { type = NavType.LongType }
        )
    ) {
        parentalControlContent(actions::back)
    }
}
