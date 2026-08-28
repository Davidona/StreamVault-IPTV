package com.streamvault.app.ui.screens.settings

import com.streamvault.feature.settings.presentation.*

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.settings.R
import com.streamvault.domain.model.PlaybackBufferMode
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SettingsPlaybackBufferModeTest {

    @Test
    fun `settings ui state defaults live buffer size to auto`() {
        assertThat(SettingsUiState().playerPlaybackBufferMode).isEqualTo(PlaybackBufferMode.AUTO)
    }

    @Test
    fun `formatPlaybackBufferModeLabel maps modes to settings labels`() {
        val context: Context = mock()
        whenever(context.getString(R.string.settings_live_buffer_auto)).thenReturn("Auto")
        whenever(context.getString(R.string.settings_live_buffer_small)).thenReturn("Small")
        whenever(context.getString(R.string.settings_live_buffer_medium)).thenReturn("Medium")
        whenever(context.getString(R.string.settings_live_buffer_large)).thenReturn("Large")

        assertThat(formatPlaybackBufferModeLabel(PlaybackBufferMode.AUTO, context)).isEqualTo("Auto")
        assertThat(formatPlaybackBufferModeLabel(PlaybackBufferMode.SMALL, context)).isEqualTo("Small")
        assertThat(formatPlaybackBufferModeLabel(PlaybackBufferMode.MEDIUM, context)).isEqualTo("Medium")
        assertThat(formatPlaybackBufferModeLabel(PlaybackBufferMode.LARGE, context)).isEqualTo("Large")
    }
}
