package com.streamvault.feature.settings.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsRoutePatternsTest {

    @Test
    fun routePatternsRemainCompatibleWithExistingAppRoutes() {
        assertThat(SettingsRoutePatterns.SETTINGS).isEqualTo("settings")
        assertThat(SettingsRoutePatterns.SETTINGS_DESTINATION)
            .isEqualTo("settings?backupUri={backupUri}")
        assertThat(SettingsRoutePatterns.PARENTAL_CONTROL_GROUPS)
            .isEqualTo("parental_control_groups/{providerId}")
    }
}
