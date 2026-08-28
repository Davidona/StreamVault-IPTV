package com.streamvault.feature.settings.presentation

import com.streamvault.feature.settings.presentation.*

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.settings.R
import com.streamvault.domain.model.TimeshiftBackendPreference
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SettingsTimeshiftBackendPreferenceTest {

    @Test
    fun `settings ui state defaults timeshift backend to automatic`() {
        assertThat(SettingsUiState().playerTimeshiftBackend)
            .isEqualTo(TimeshiftBackendPreference.AUTOMATIC)
    }

    @Test
    fun `formatTimeshiftBackendPreferenceLabel maps modes to settings labels`() {
        val context: Context = mock()
        whenever(context.getString(R.string.settings_live_timeshift_backend_auto)).thenReturn("Automatic")
        whenever(context.getString(R.string.settings_live_timeshift_backend_storage)).thenReturn("Storage")
        whenever(context.getString(R.string.settings_live_timeshift_backend_memory)).thenReturn("Memory")

        assertThat(
            formatTimeshiftBackendPreferenceLabel(TimeshiftBackendPreference.AUTOMATIC, context)
        ).isEqualTo("Automatic")
        assertThat(
            formatTimeshiftBackendPreferenceLabel(TimeshiftBackendPreference.STORAGE, context)
        ).isEqualTo("Storage")
        assertThat(
            formatTimeshiftBackendPreferenceLabel(TimeshiftBackendPreference.MEMORY, context)
        ).isEqualTo("Memory")
    }
}
