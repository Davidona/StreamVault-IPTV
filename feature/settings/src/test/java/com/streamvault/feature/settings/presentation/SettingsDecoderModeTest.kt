package com.streamvault.feature.settings.presentation

import com.streamvault.feature.settings.presentation.*

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.settings.R
import com.streamvault.domain.model.DecoderMode
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SettingsDecoderModeTest {

    @Test
    fun `settings ui state defaults audio and video decoder modes to auto`() {
        val state = SettingsUiState()

        assertThat(state.playerAudioDecoderMode).isEqualTo(DecoderMode.AUTO)
        assertThat(state.playerVideoDecoderMode).isEqualTo(DecoderMode.AUTO)
    }

    @Test
    fun `formatDecoderModeLabel maps modes to settings labels`() {
        val context: Context = mock()
        whenever(context.getString(R.string.settings_decoder_auto)).thenReturn("Auto")
        whenever(context.getString(R.string.settings_decoder_hardware)).thenReturn("Hardware")
        whenever(context.getString(R.string.settings_decoder_software)).thenReturn("Software")
        whenever(context.getString(R.string.settings_decoder_compatibility)).thenReturn("Compatibility")

        assertThat(formatDecoderModeLabel(DecoderMode.AUTO, context)).isEqualTo("Auto")
        assertThat(formatDecoderModeLabel(DecoderMode.HARDWARE, context)).isEqualTo("Hardware")
        assertThat(formatDecoderModeLabel(DecoderMode.SOFTWARE, context)).isEqualTo("Software")
        assertThat(formatDecoderModeLabel(DecoderMode.COMPATIBILITY, context)).isEqualTo("Compatibility")
    }
}
