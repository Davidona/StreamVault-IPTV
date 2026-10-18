package com.streamvault.data.preferences

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ChannelPreferenceDao
import com.streamvault.data.local.dao.SearchHistoryDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PreferencesRepositoryTraktDiscoveryVisibilityTest {
    private lateinit var context: Context
    private lateinit var isolatedDirectory: File

    @Before
    fun setUp() {
        isolatedDirectory = File(
            RuntimeEnvironment.getApplication().cacheDir,
            "trakt-discovery-preferences-${System.nanoTime()}"
        )
        isolatedDirectory.deleteRecursively()
        isolatedDirectory.mkdirs()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = isolatedDirectory
            override fun getApplicationContext(): Context = this
        }
        context.preferencesDataStoreFile("user_preferences").delete()
    }

    @After
    fun tearDown() {
        context.preferencesDataStoreFile("user_preferences").delete()
        isolatedDirectory.deleteRecursively()
    }

    @Test
    fun `Trakt discovery categories default visible and persist independently`() = runBlocking {
        val repository = repository()

        assertThat(repository.showTraktTrendingMovies.first()).isTrue()
        assertThat(repository.showTraktPopularMovies.first()).isTrue()
        assertThat(repository.showTraktTrendingSeries.first()).isTrue()
        assertThat(repository.showTraktPopularSeries.first()).isTrue()

        repository.setShowTraktTrendingMovies(false)

        assertThat(repository.showTraktTrendingMovies.first()).isFalse()
        assertThat(repository.showTraktPopularMovies.first()).isTrue()
        assertThat(repository.showTraktTrendingSeries.first()).isTrue()
        assertThat(repository.showTraktPopularSeries.first()).isTrue()
    }

    @Test
    fun `Trakt playback scrobbling preference defaults on and can be disabled`() = runBlocking {
        val repository = repository()

        assertThat(repository.scrobblingEnabled.first()).isTrue()
        repository.setScrobblingEnabled(false)
        assertThat(repository.scrobblingEnabled.first()).isFalse()
    }

    @Test
    fun `Trakt history sync preference defaults on and can be disabled`() = runBlocking {
        val repository = repository()

        assertThat(repository.historySyncEnabled.first()).isTrue()
        repository.setHistorySyncEnabled(false)
        assertThat(repository.historySyncEnabled.first()).isFalse()
    }

    private fun repository() = PreferencesRepository(
        context = context,
        channelPreferenceDao = mock<ChannelPreferenceDao>(),
        playbackQualityPreferenceDao = mock(),
        searchHistoryDao = mock<SearchHistoryDao>(),
        corruptionRecovery = mock<PreferencesCorruptionRecovery>()
    )
}
