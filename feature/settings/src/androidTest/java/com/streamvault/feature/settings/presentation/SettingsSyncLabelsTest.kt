package com.streamvault.feature.settings.presentation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.settings.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSyncLabelsTest {

    @Test
    fun sync_option_labels_use_title_case() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertThat(context.getString(R.string.settings_sync_option_sync_now))
            .isEqualTo("Sync Now")
        assertThat(context.getString(R.string.settings_sync_option_rebuild_index))
            .isEqualTo("Rebuild Index")
    }
}
