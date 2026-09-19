package com.streamvault.data.preferences

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ChannelPreferenceDao
import com.streamvault.data.local.dao.SearchHistoryDao
import com.streamvault.domain.model.LiveClockFont
import com.streamvault.domain.model.LiveClockPosition
import com.streamvault.domain.model.LiveClockSize
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PreferencesRepositoryLiveClockTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.preferencesDataStoreFile("user_preferences").delete()
    }

    @After
    fun tearDown() {
        context.preferencesDataStoreFile("user_preferences").delete()
    }

    @Test
    fun `live clock preferences default safely and persist position changes`() = runBlocking {
        val repository = PreferencesRepository(
            context = context,
            channelPreferenceDao = mock<ChannelPreferenceDao>(),
            searchHistoryDao = mock<SearchHistoryDao>(),
            corruptionRecovery = mock<PreferencesCorruptionRecovery>()
        )

        assertThat(repository.playerLiveClockEnabled.first()).isFalse()
        assertThat(repository.playerLiveClockPosition.first()).isEqualTo(LiveClockPosition.TOP_END)
        assertThat(repository.playerLiveClockSize.first()).isEqualTo(LiveClockSize.MEDIUM)
        assertThat(repository.playerLiveClockFont.first()).isEqualTo(LiveClockFont.DIGITAL_MONO)

        repository.setPlayerLiveClockPosition(LiveClockPosition.BOTTOM_START)

        assertThat(repository.playerLiveClockPosition.first()).isEqualTo(LiveClockPosition.BOTTOM_START)
    }
}
