package com.streamvault.data.preferences

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ChannelPreferenceDao
import com.streamvault.data.local.dao.SearchHistoryDao
import com.streamvault.domain.settings.VodTrackPreference
import com.streamvault.domain.settings.VodTrackPreferenceScope
import com.streamvault.domain.settings.VodTrackPreferences
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
class PreferencesRepositoryVodTrackPreferenceTest {
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
    fun `VOD track preferences persist globally and by series`() = runBlocking {
        val repository = PreferencesRepository(
            context = context,
            channelPreferenceDao = mock<ChannelPreferenceDao>(),
            searchHistoryDao = mock<SearchHistoryDao>(),
            corruptionRecovery = mock<PreferencesCorruptionRecovery>()
        )
        val preferences = VodTrackPreferences(
            audio = VodTrackPreference(language = "es", label = "Spanish"),
            subtitle = VodTrackPreference(language = "fr", label = "French")
        )
        val scope = VodTrackPreferenceScope.Series(providerId = 7L, contentId = 9L)

        repository.setGlobalVodTrackPreferences(preferences)
        repository.setVodTrackPreferences(scope, preferences)

        assertThat(repository.globalVodTrackPreferences.first()).isEqualTo(preferences)
        assertThat(repository.getVodTrackPreferences(scope).first()).isEqualTo(preferences)
    }
}
