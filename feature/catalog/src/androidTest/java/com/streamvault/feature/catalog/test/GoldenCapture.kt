package com.streamvault.feature.catalog.test

import android.graphics.Bitmap
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
    val targetContext = instrumentation.targetContext
    val shouldRecord = InstrumentationRegistry.getArguments()
        .getString(RECORD_GOLDENS_ARGUMENT) == "true"

    if (shouldRecord) {
        val outputDirs = listOfNotNull(
            File(targetContext.filesDir, "ui-goldens"),
            targetContext.externalCacheDir?.let { File(it, "ui-goldens") },
        )
        outputDirs.forEach { outputDir ->
            outputDir.mkdirs()
            File(outputDir, "$goldenName.png").outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
        }
        assertTrue(outputDirs.all { File(it, "$goldenName.png").exists() })
        instrumentation.uiAutomation
            .executeShellCommand(
                "cp ${outputDirs.last().absolutePath}/$goldenName.png " +
                    "/sdcard/Download/streamvault-app-golden-$goldenName.png"
            )
            .close()
        return
    }

    val expected = try {
        instrumentation.context.assets
            .open("ui-goldens/$goldenName.png")
            .use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
                    ?: throw AssertionError("Golden asset ui-goldens/$goldenName.png is not a readable bitmap")
            }
    } catch (_: FileNotFoundException) {
        throw AssertionError(
            "Missing golden asset ui-goldens/$goldenName.png; " +
                "run the connected test with -Pandroid.testInstrumentationRunnerArguments.recordGoldens=true " +
                "and copy the generated file into feature/catalog/src/androidTest/assets/ui-goldens/"
        )
    }
    assertBitmapsEqual(expected, bitmap, goldenName)
}

private fun assertBitmapsEqual(expected: Bitmap, actual: Bitmap, goldenName: String) {
    assertEquals(expected.width, actual.width)
    assertEquals(expected.height, actual.height)

    for (y in 0 until expected.height) {
        for (x in 0 until expected.width) {
            assertEquals("$goldenName@$x,$y", expected.getPixel(x, y), actual.getPixel(x, y))
        }
    }
}
