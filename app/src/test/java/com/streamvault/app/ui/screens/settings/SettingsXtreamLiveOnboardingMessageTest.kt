package com.streamvault.app.ui.screens.settings

import com.streamvault.feature.settings.presentation.*

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.R
import org.junit.Test

class SettingsXtreamLiveOnboardingMessageTest {

    @Test
    fun `xtreamLiveOnboardingMessageRes maps durable phases to specific messages`() {
        assertThat(xtreamLiveOnboardingMessageRes("STARTING"))
            .isEqualTo(R.string.settings_provider_live_onboarding_starting)
        assertThat(xtreamLiveOnboardingMessageRes("FETCHING"))
            .isEqualTo(R.string.settings_provider_live_onboarding_fetching)
        assertThat(xtreamLiveOnboardingMessageRes("RECOVERING"))
            .isEqualTo(R.string.settings_provider_live_onboarding_recovering)
        assertThat(xtreamLiveOnboardingMessageRes("STAGED"))
            .isEqualTo(R.string.settings_provider_live_onboarding_staged)
        assertThat(xtreamLiveOnboardingMessageRes("COMMITTING"))
            .isEqualTo(R.string.settings_provider_live_onboarding_committing)
        assertThat(xtreamLiveOnboardingMessageRes("FAILED"))
            .isEqualTo(R.string.settings_provider_live_onboarding_failed)
    }

    @Test
    fun `xtreamLiveOnboardingMessageRes falls back for missing or unknown phases`() {
        assertThat(xtreamLiveOnboardingMessageRes(null))
            .isEqualTo(R.string.settings_provider_live_onboarding_incomplete)
        assertThat(xtreamLiveOnboardingMessageRes("SOMETHING_ELSE"))
            .isEqualTo(R.string.settings_provider_live_onboarding_incomplete)
    }
}
