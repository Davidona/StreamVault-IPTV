package com.streamvault.app.navigation

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.streamvault.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalDownloadsRouteRuntimeTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun externalDownloadsRouteOpensOnColdStart() {
        val intent = externalDownloadsIntent()

        ActivityScenario.launch<MainActivity>(intent).use {
            assertTrue(
                "Cold launch did not reach Downloads",
                device.wait(Until.hasObject(downloadsDestination), 20_000)
            )
        }
    }

    @Test
    fun externalDownloadsRouteOpensInAlreadyRunningActivity() {
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
            app.startActivity(
                externalDownloadsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            assertTrue(
                "Running activity did not reach Downloads",
                device.wait(Until.hasObject(downloadsDestination), 20_000)
            )
        }
    }

    private fun externalDownloadsIntent() = Intent(app, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_EXTERNAL_ROUTE, "downloads")

    private companion object {
        val downloadsDestination = By.descStartsWith("streamvault.destination:downloads")
    }
}
