package com.streamvault.data.preferences

import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CatalogSyncUpgradePreferencesTest {
    @Test fun `catchup checkpoint persists until explicitly completed and is provider scoped`() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        context.preferencesDataStoreFile("user_preferences").delete()
        val preferences = PreferencesRepository(context, mock(), mock(), mock(), mock())
        val first = preferences.catalogSyncUpgradeStartedAt(9021L)
        assertThat(first).isGreaterThan(0L)
        assertThat(preferences.catalogSyncUpgradeStartedAt(9021L)).isEqualTo(first)
        preferences.completeCatalogSyncUpgrade(9021L)
        assertThat(preferences.catalogSyncUpgradeStartedAt(9021L)).isNull()
        assertThat(preferences.catalogSyncUpgradeStartedAt(9022L)).isGreaterThan(0L)
        context.preferencesDataStoreFile("user_preferences").delete()
    }
}
