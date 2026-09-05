package com.streamvault.app.benchmark

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupPreviewBenchmarkActivityTest {

    @Test
    fun composeModeRendersDeterministicPreview() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            BackupPreviewBenchmarkActivity::class.java,
        ).putExtra(BackupPreviewBenchmarkActivity.EXTRA_PRESENTATION, "compose")

        ActivityScenario.launch<BackupPreviewBenchmarkActivity>(intent).use {
            val device = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation(),
            )

            assertThat(device.wait(Until.hasObject(By.text("Review Backup Import")), 20_000L))
                .isTrue()
            assertThat(device.hasObject(By.text("Preferences"))).isTrue()
        }
    }
}
