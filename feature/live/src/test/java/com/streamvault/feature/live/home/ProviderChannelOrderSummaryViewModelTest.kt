package com.streamvault.feature.live.home

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.LiveChannelOrder
import com.streamvault.domain.model.LiveChannelOrderMode
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.policy.liveChannelOrderScope
import com.streamvault.domain.repository.LiveChannelOrderRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProviderChannelOrderSummaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val providers: ProviderRepository = mock()
    private val orders: LiveChannelOrderRepository = mock()
    private val preferences: SettingsPreferences = mock()
    private val provider = LegacyProvider(
        id = 7,
        name = "Inactive provider",
        type = ProviderType.M3U,
        serverUrl = "https://example.test",
        createdAt = 123
    )
    private val category = Category(id = 12, name = "Sports")
    private lateinit var model: ProviderChannelOrderSummaryViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        model = ProviderChannelOrderSummaryViewModel(providers, orders, preferences)
    }

    @After fun cleanup() {
        model.reset()
        Dispatchers.resetMain()
    }

    @Test fun `shows category mode and effective default without reading channel membership`() = runTest {
        val scope = liveChannelOrderScope(provider, category)
        whenever(providers.getProvider(7)).thenReturn(provider)
        whenever(orders.observe(scope)).thenReturn(flowOf(LiveChannelOrder(LiveChannelOrderMode.USE_DEFAULT)))
        whenever(preferences.defaultLiveChannelOrder).thenReturn(flowOf(LiveChannelOrderMode.RECENTLY_WATCHED))

        model.observe(7, category)
        advanceUntilIdle()

        assertThat(model.uiState.value.loading).isFalse()
        assertThat(model.uiState.value.mode).isEqualTo(LiveChannelOrderMode.USE_DEFAULT)
        assertThat(model.uiState.value.defaultMode).isEqualTo(LiveChannelOrderMode.RECENTLY_WATCHED)
        verify(orders).observe(scope)
    }
}
