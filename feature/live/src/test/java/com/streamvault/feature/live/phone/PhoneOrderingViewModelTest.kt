package com.streamvault.feature.live.phone

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneOrderingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val preferences: SettingsPreferences = mock()
    private val backend: PhoneOrderingBackend = mock()
    private lateinit var model: PhoneOrderingViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        whenever(preferences.parentalControlLevel).thenReturn(flowOf(1))
        whenever(preferences.hasParentalPin).thenReturn(flowOf(true))
        model = PhoneOrderingViewModel(mock<Context>(), preferences, backend)
    }
    @After fun cleanup() { model.stop(); Dispatchers.resetMain() }

    @Test fun `protected session requires a correct PIN before loading phone data`() = runTest {
        model.open(7, 12)
        advanceUntilIdle()
        assertThat(model.state.value.pinRequired).isTrue()
        whenever(preferences.verifyParentalPin("0000")).thenReturn(false)
        model.unlock("0000")
        advanceUntilIdle()
        assertThat(model.state.value.pinRequired).isTrue()
        assertThat(model.state.value.incorrectPin).isTrue()
        verifyNoInteractions(backend)
    }

    @Test fun `default without PIN starts with protected content filtered`() {
        assertThat(phoneAccess(1, false)).isEqualTo(PhoneAccess.FILTER_PROTECTED)
        assertThat(phoneAccess(2, false)).isEqualTo(PhoneAccess.FILTER_PROTECTED)
        assertThat(phoneAccess(3, true)).isEqualTo(PhoneAccess.FILTER_PROTECTED)
        assertThat(phoneAccess(0, false)).isEqualTo(PhoneAccess.UNRESTRICTED)
        assertThat(phoneAccess(1, true)).isEqualTo(PhoneAccess.REQUIRE_PIN)
        assertThat(phoneAccess(4, false)).isEqualTo(PhoneAccess.UNSUPPORTED)
    }
}
