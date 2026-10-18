package com.streamvault.data.sports.provider

import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SportsDiscoveryPreferencesTest {
    @Test fun `sports and leagues are independently persisted and can be included again`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.preferencesDataStoreFile("user_preferences").delete()
        val repository = PreferencesRepository(context, mock(), mock(), mock(), mock())
        assertThat(repository.getExcludedSports().first()).isEmpty()
        repository.setSportsSportIncluded("HOCKEY", false)
        repository.setSportsLeagueIncluded("wnba", false)
        assertThat(repository.getExcludedSports().first()).containsExactly("HOCKEY")
        assertThat(repository.getExcludedSportsLeagues().first()).containsExactly("wnba")
        repository.setSportsSportIncluded("HOCKEY", true)
        assertThat(repository.getExcludedSports().first()).isEmpty()
        assertThat(repository.getExcludedSportsLeagues().first()).containsExactly("wnba")
        repository.resetSportsInclusions()
        assertThat(repository.getExcludedSportsLeagues().first()).isEmpty()
    }
}
