package com.streamvault.feature.settings.presentation

import android.content.ActivityNotFoundException
import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.domain.trakt.TraktAccountState
import com.streamvault.domain.trakt.TraktFeaturePreferences
import com.streamvault.domain.trakt.TraktFavoriteImporter
import com.streamvault.domain.trakt.TraktFavoriteImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TraktSettingsViewModelTest {
    private val historySync = object : com.streamvault.domain.trakt.TraktHistorySync {
        override suspend fun sync() = com.streamvault.domain.trakt.TraktHistorySyncResult.Success(0, 0, 0, 0)
    }
    private val favoriteImporter = object : TraktFavoriteImporter {
        override suspend fun importToLocal() = TraktFavoriteImportResult.Success(0, 0, 0, 0)
    }

    @Test fun `manual history sync is single flight and reports counts`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var calls = 0
            val sync = object : com.streamvault.domain.trakt.TraktHistorySync {
                override suspend fun sync(): com.streamvault.domain.trakt.TraktHistorySyncResult {
                    calls++
                    return com.streamvault.domain.trakt.TraktHistorySyncResult.Success(2, 3, 4, 5)
                }
            }
            val viewModel = TraktSettingsViewModel(Account(), sync, FeaturePreferences(), favoriteImporter)
            advanceUntilIdle()
            viewModel.syncHistory()
            viewModel.syncHistory()
            advanceUntilIdle()
            assertEquals(1, calls)
            assertEquals(com.streamvault.domain.trakt.TraktHistorySyncResult.Success(2, 3, 4, 5), viewModel.syncResult.value)
            assertFalse(viewModel.busy.value)
            viewModel.disconnect()
            advanceUntilIdle()
            assertNull(viewModel.syncResult.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `disabled history sync prevents manual sync and preference toggles persist`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var calls = 0
            val sync = object : com.streamvault.domain.trakt.TraktHistorySync {
                override suspend fun sync(): com.streamvault.domain.trakt.TraktHistorySyncResult {
                    calls++
                    return com.streamvault.domain.trakt.TraktHistorySyncResult.Success(0, 0, 0, 0)
                }
            }
            val preferences = FeaturePreferences()
            val viewModel = TraktSettingsViewModel(Account(), sync, preferences, favoriteImporter)
            advanceUntilIdle()
            viewModel.setHistorySyncEnabled(false)
            viewModel.setScrobblingEnabled(false)
            advanceUntilIdle()
            viewModel.syncHistory()
            advanceUntilIdle()
            assertFalse(viewModel.historySyncEnabled.value)
            assertFalse(viewModel.scrobblingEnabled.value)
            assertEquals(0, calls)
        } finally { Dispatchers.resetMain() }
    }
    private class Account : TraktAccount {
        override val state = MutableStateFlow(TraktAccountState(configured = true))
        var cancellations = 0
        override suspend fun load() = Unit
        override suspend fun beginSignIn(): String {
            state.value = state.value.copy(connecting = true)
            return "https://auth.trakt.tv/oauth/authorize"
        }
        override suspend fun cancelSignIn() {
            cancellations++
            state.value = state.value.copy(connecting = false)
        }
        override suspend fun completeSignIn(callback: String) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun refreshProfile() = Unit
    }

    private class FeaturePreferences : TraktFeaturePreferences {
        private val scrobbling = MutableStateFlow(true)
        private val history = MutableStateFlow(true)
        override val scrobblingEnabled: Flow<Boolean> = scrobbling
        override val historySyncEnabled: Flow<Boolean> = history
        override suspend fun setScrobblingEnabled(enabled: Boolean) { scrobbling.value = enabled }
        override suspend fun setHistorySyncEnabled(enabled: Boolean) { history.value = enabled }
    }

    @Test fun `missing browser cancels pending login and allows retry after browser is installed`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account()
            val viewModel = TraktSettingsViewModel(account, historySync, FeaturePreferences(), favoriteImporter)
            advanceUntilIdle()
            viewModel.connect { throw ActivityNotFoundException() }
            advanceUntilIdle()
            assertEquals(1, account.cancellations)
            assertFalse(account.state.value.connecting)
            assertTrue(viewModel.browserUnavailable.value)
            assertFalse(viewModel.failed.value)
            assertFalse(viewModel.busy.value)
            var browserOpened = false
            viewModel.connect { browserOpened = true }
            advanceUntilIdle()
            assertTrue(browserOpened)
            assertTrue(account.state.value.connecting)
            assertFalse(viewModel.browserUnavailable.value)
        } finally { Dispatchers.resetMain() }
    }
}
