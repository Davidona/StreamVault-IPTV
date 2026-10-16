package com.streamvault.feature.stremio.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.settings.SettingsPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class StremioContentAccessTest {
    @Test
    fun `waits for visibility preference before fetching content`() {
        assertThat(StremioVisibilityState().canAccessContent).isFalse()
    }

    @Test
    fun `global parental levels do not block visible Stremio addons`() = runBlocking {
        for (level in 0..3) {
            val preferences = mock<SettingsPreferences>()
            whenever(preferences.parentalControlLevel).thenReturn(MutableStateFlow(level))
            whenever(preferences.hideStremioAddons).thenReturn(MutableStateFlow(false))
            val manager = StremioVisibilityManager(preferences)
            val visible = withTimeout(2_000) { manager.state.first { it.hidden == false } }
            assertThat(visible.canAccessContent).isTrue()
            assertThat(visible.showContinueWatching).isTrue()
        }
    }

    @Test
    fun `explicit hide choice blocks content and history and can be reversed`() = runBlocking {
        val hidden = MutableStateFlow(false)
        val preferences = mock<SettingsPreferences>()
        whenever(preferences.hideStremioAddons).thenReturn(hidden)
        val manager = StremioVisibilityManager(preferences)
        withTimeout(2_000) { manager.state.first { it.canAccessContent } }
        hidden.value = true
        val blocked = withTimeout(2_000) { manager.state.first { it.hidden == true } }
        assertThat(blocked.canAccessContent).isFalse()
        assertThat(blocked.showContinueWatching).isFalse()
        hidden.value = false
        assertThat(withTimeout(2_000) { manager.state.first { it.canAccessContent } }.showContinueWatching).isTrue()
    }
}
