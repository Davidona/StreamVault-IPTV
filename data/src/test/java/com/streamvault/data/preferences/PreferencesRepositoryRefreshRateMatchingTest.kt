package com.streamvault.data.preferences

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreferencesRepositoryRefreshRateMatchingTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.preferencesDataStoreFile("user_preferences").delete()
    }

    @Test fun `refresh matching defaults on and saves explicit off and on choices`() = runBlocking {
        val original = PreferencesRepository(context, mock(), mock(), mock(), mock())
        assertThat(original.playerRefreshRateMatchingEnabled.first()).isTrue()
        original.setPlayerRefreshRateMatchingEnabled(false)
        assertThat(original.playerRefreshRateMatchingEnabled.first()).isFalse()
        original.setPlayerRefreshRateMatchingEnabled(true)
        assertThat(original.playerRefreshRateMatchingEnabled.first()).isTrue()
    }
}
