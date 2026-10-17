package com.streamvault.data.preferences

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.streamvault.data.local.dao.ChannelPreferenceDao
import com.streamvault.data.local.dao.SearchHistoryDao
import com.streamvault.domain.sports.provider.SportsEventSourcePreference
import com.streamvault.domain.sports.provider.SportsScoreSourcePreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PreferencesRepositorySportsEventSourceTest {
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
    fun `event source preference defaults to automatic and persists choices`() = runBlocking {
        val repository = repository()
        assertEquals(SportsEventSourcePreference.AUTOMATIC, repository.eventSourcePreference.first())
        assertEquals(SportsScoreSourcePreference.AUTOMATIC, repository.scoreSourcePreference.first())

        repository.setEventSourcePreference(SportsEventSourcePreference.provider("sports-account"))
        assertEquals(SportsEventSourcePreference.provider("sports-account"), repository.eventSourcePreference.first())
    }

    @Test
    fun `score source preference defaults to automatic and persists account choice`() = runBlocking {
        val repository = repository()
        assertEquals(SportsScoreSourcePreference.AUTOMATIC, repository.scoreSourcePreference.first())
        repository.setScoreSourcePreference(SportsScoreSourcePreference.provider("scores-account"))
        assertEquals(SportsScoreSourcePreference.provider("scores-account"), repository.scoreSourcePreference.first())
    }

    private fun repository() = PreferencesRepository(
        context = context,
        channelPreferenceDao = mock<ChannelPreferenceDao>(),
        searchHistoryDao = mock<SearchHistoryDao>(),
        corruptionRecovery = mock<PreferencesCorruptionRecovery>(),
    )
}
