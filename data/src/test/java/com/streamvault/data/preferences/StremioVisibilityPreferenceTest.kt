package com.streamvault.data.preferences

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
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
class StremioVisibilityPreferenceTest {
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

    private fun repository() = PreferencesRepository(
        context = context,
        channelPreferenceDao = mock(),
        playbackQualityPreferenceDao = mock(),
        searchHistoryDao = mock(),
        corruptionRecovery = mock()
    )

    @Test
    fun visibilityDefaultsAndPersists() = runBlocking {
        val preferences = repository()
        assertThat(preferences.hideStremioAddons.first()).isFalse()
        val globalLevel = preferences.parentalControlLevel.first()
        assertThat(globalLevel).isEqualTo(1)
        preferences.setHideStremioAddons(true)
        assertThat(preferences.hideStremioAddons.first()).isTrue()
        assertThat(preferences.parentalControlLevel.first()).isEqualTo(globalLevel)
        assertThat(context.preferencesDataStoreFile("user_preferences").isFile).isTrue()
    }
}
