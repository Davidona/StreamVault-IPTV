package com.streamvault.data.preferences

import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LiveChannelOrderMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreferencesRepositoryLiveChannelOrderTest {
    @Test fun `default preserves provider order persists presets and rejects manual inheritance`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.preferencesDataStoreFile("user_preferences").delete()
        val preferences = PreferencesRepository(context, mock(), mock(), mock(), mock())
        assertThat(preferences.defaultLiveChannelOrder.first()).isEqualTo(LiveChannelOrderMode.PROVIDER)
        preferences.setDefaultLiveChannelOrder(LiveChannelOrderMode.TITLE_DESC)
        assertThat(preferences.defaultLiveChannelOrder.first()).isEqualTo(LiveChannelOrderMode.TITLE_DESC)
        assertThat(runCatching { preferences.setDefaultLiveChannelOrder(LiveChannelOrderMode.MANUAL) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(preferences.defaultLiveChannelOrder.first()).isEqualTo(LiveChannelOrderMode.TITLE_DESC)
    }
}
