package com.streamvault.feature.playback.player.overlay

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

private const val RECORD_GOLDENS_ARGUMENT = "recordGoldens"

internal fun SemanticsNodeInteraction.assertPlayerOverlayGolden(goldenName: String) {
    val bitmap = captureToImage().asAndroidBitmap()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val outputDir = File(context.filesDir, "ui-goldens").apply { mkdirs() }
    val goldenFile = File(outputDir, "$goldenName.png")
    val shouldRecord = InstrumentationRegistry.getArguments().getString(RECORD_GOLDENS_ARGUMENT) == "true"

    if (shouldRecord || !goldenFile.exists()) {
        goldenFile.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        assertTrue(goldenFile.exists())
        return
    }

    val expected = android.graphics.BitmapFactory.decodeFile(goldenFile.absolutePath)
    assertEquals(expected.width, bitmap.width)
    assertEquals(expected.height, bitmap.height)

    for (y in 0 until expected.height) {
        for (x in 0 until expected.width) {
            assertEquals("$goldenName@$x,$y", expected.getPixel(x, y), bitmap.getPixel(x, y))
        }
    }
}
