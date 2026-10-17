package com.streamvault.data.preferences

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.streamvault.data.local.dao.ChannelPreferenceDao
import com.streamvault.data.local.dao.SearchHistoryDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PreferencesRepositorySportsEventChannelPickTest {
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
    fun `event channel pick persists for its event`() = runBlocking {
        val repository = repository()
        repository.saveSportsEventChannelPick("event-one", 7L, 12L, expiresAtMillis = 2_000L)

        assertEquals(
            mapOf("event-one" to SportsEventChannelPick(7L, 12L, 2_000L)),
            repository.getSportsEventChannelPicks(setOf("event-one"), 1_000L),
        )
    }

    private fun repository() = PreferencesRepository(
        context = context,
        channelPreferenceDao = mock<ChannelPreferenceDao>(),
        searchHistoryDao = mock<SearchHistoryDao>(),
        corruptionRecovery = mock<PreferencesCorruptionRecovery>(),
    )
}
