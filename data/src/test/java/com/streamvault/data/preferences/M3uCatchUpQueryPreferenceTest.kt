package com.streamvault.data.preferences

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.provider.DEFAULT_M3U_CATCH_UP_QUERY_FORMAT
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
class M3uCatchUpQueryPreferenceTest {
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
    fun defaultIsUsedAndCustomFormatPersists() = runBlocking {
        val preferences = repository()

        assertThat(preferences.m3uCatchUpQueryFormat.first()).isEqualTo(DEFAULT_M3U_CATCH_UP_QUERY_FORMAT)
        preferences.setM3uCatchUpQueryFormat("  &from={utc}&until={utcend}  ")
        assertThat(preferences.m3uCatchUpQueryFormat.first()).isEqualTo("&from={utc}&until={utcend}")
    }

    @Test
    fun blankFormatResetsToDefault() = runBlocking {
        val preferences = repository()
        preferences.setM3uCatchUpQueryFormat("&from={utc}")
        preferences.setM3uCatchUpQueryFormat(" ")

        assertThat(preferences.m3uCatchUpQueryFormat.first()).isEqualTo(DEFAULT_M3U_CATCH_UP_QUERY_FORMAT)
    }

    private fun repository() = PreferencesRepository(
        context = context,
        channelPreferenceDao = mock(),
        playbackQualityPreferenceDao = mock(),
        searchHistoryDao = mock(),
        corruptionRecovery = mock()
    )
}
