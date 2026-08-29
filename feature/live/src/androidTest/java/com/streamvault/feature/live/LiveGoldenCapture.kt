package com.streamvault.feature.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

private const val RECORD_GOLDENS_ARGUMENT = "recordGoldens"

fun SemanticsNodeInteraction.assertAgainstGolden(goldenName: String) {
    val bitmap = captureToImage().asAndroidBitmap()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val shouldRecord = InstrumentationRegistry.getArguments().getString(RECORD_GOLDENS_ARGUMENT) == "true"

    if (shouldRecord) {
        // The connected-test task removes its instrumentation package after the
        // run, so the host pulls these candidates while the test process is live.
        val outputDir = File(instrumentation.targetContext.externalCacheDir, "ui-goldens").apply { mkdirs() }
        Log.i("LiveGolden", "recordDir=${outputDir.absolutePath} target=${instrumentation.targetContext.packageName}")
        val goldenFile = File(outputDir, "$goldenName.png")
        goldenFile.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        assertTrue(goldenFile.exists())
        instrumentation.uiAutomation
            .executeShellCommand("cp ${goldenFile.absolutePath} /sdcard/Download/streamvault-live-golden-$goldenName.png")
            .close()
        Thread.sleep(5_000)
        return
    }

    val expected = try {
        instrumentation.context.assets
            .open("goldens/$goldenName.png")
            .use { stream ->
                BitmapFactory.decodeStream(stream)
                    ?: throw AssertionError("Golden asset goldens/$goldenName.png is not a readable bitmap")
            }
    } catch (_: FileNotFoundException) {
        throw AssertionError(
            "Missing golden asset goldens/$goldenName.png; run the connected test with " +
                "-Pandroid.testInstrumentationRunnerArguments.recordGoldens=true and review/copy the output."
        )
    }

    assertEquals(expected.width, bitmap.width)
    assertEquals(expected.height, bitmap.height)
    for (y in 0 until expected.height) {
        for (x in 0 until expected.width) {
            assertEquals("$goldenName@$x,$y", expected.getPixel(x, y), bitmap.getPixel(x, y))
        }
    }
}
