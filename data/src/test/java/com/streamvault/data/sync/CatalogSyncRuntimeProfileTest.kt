package com.streamvault.data.sync

import android.content.Context
import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CatalogSyncRuntimeProfileTest {
    @Test
    fun `normal application profile enables bulk first auto`() {
        val context = RuntimeEnvironment.getApplication()

        assertThat(context.packageName).isNotEqualTo(BENCHMARK_APPLICATION_ID)
        assertThat(CatalogSyncRuntimeProfile.from(context).bulkFirstAutoEnabled).isTrue()
    }

    @Test
    fun `benchmark UI defaults to bulk first and comparisons can explicitly opt out`() {
        val app = RuntimeEnvironment.getApplication()
        val benchmarkContext = object : ContextWrapper(app) {
            override fun getPackageName(): String = BENCHMARK_APPLICATION_ID
        }
        val preferences = app.getSharedPreferences("xtream_sync_benchmark_profile", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()

        assertThat(CatalogSyncRuntimeProfile.from(benchmarkContext).bulkFirstAutoEnabled).isTrue()

        preferences.edit().putBoolean("experimental_auto_bulk", false).commit()
        assertThat(CatalogSyncRuntimeProfile.from(benchmarkContext).bulkFirstAutoEnabled).isFalse()

        preferences.edit().putBoolean("experimental_auto_bulk", true).commit()
        assertThat(CatalogSyncRuntimeProfile.from(benchmarkContext).bulkFirstAutoEnabled).isTrue()
    }

    private companion object {
        const val BENCHMARK_APPLICATION_ID = "com.streamvault.app.syncbenchmark"
    }
}
