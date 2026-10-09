package com.streamvault.app.ui

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import com.streamvault.player.PlaybackState
import dagger.hilt.internal.GeneratedComponentManager
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Opt-in integration probe; requires the local Stage 4 provider and valid matching media.
 * Error injection validates recovery wiring, not platform decoder-failure detection.
 */
@RunWith(AndroidJUnit4::class)
class MovieFallbackRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<com.streamvault.app.MainActivity>()
    @Test
    fun injectedDecoderFailurePreservesPositionAndTracksAndDoesNotLoop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("movieFallbackFixture") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val component = (app as GeneratedComponentManager<*>).generatedComponent()
        val providerField = component.javaClass.getDeclaredField("provideMainPlayerEngineProvider").apply { isAccessible = true }
        val engine = (providerField.get(component) as Provider<*>).get() as PlayerEngine
        val device = UiDevice.getInstance(instrumentation)
        compose.waitForIdle()
        fun clickText(text: String) {
            compose.waitForIdle()
            val node = device.wait(Until.findObject(By.textContains(text)), 2_000)
                ?: (if (text == "Play") device.wait(Until.findObject(By.textStartsWith("Resume from")), 5_000) else null)
                ?: error("Missing fixture UI: $text")
            val bounds = node.visibleBounds
            device.executeShellCommand("input mouse tap ${bounds.centerX()} ${bounds.centerY()}")
            device.waitForIdle()
            compose.waitForIdle()
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            check(condition()) { "Timed out: $label" }
        }
        fun source(): String = engine.javaClass.getDeclaredField("lastStreamInfo").apply { isAccessible = true }
            .get(engine).let { (it as? StreamInfo)?.url.orEmpty() }
        fun inject() {
            @Suppress("UNCHECKED_CAST")
            val errors = engine.javaClass.getDeclaredField("_error").apply { isAccessible = true }.get(engine) as MutableSharedFlow<PlayerError?>
            instrumentation.runOnMainSync { check(errors.tryEmit(PlayerError.DecoderError("Stage 4 injected decoder failure"))) }
        }
        clickText("Movies")
        clickText("Resilience Fixture")
        clickText("Play")
        device.wait(Until.findObject(By.text("Resume")), 1_000)?.let {
            val b = it.visibleBounds
            device.executeShellCommand("input mouse tap ${b.centerX()} ${b.centerY()}")
            compose.waitForIdle()
        }
        await("valid HEVC startup") { source().endsWith("/101.mp4") && engine.availableAudioTracks.value.size >= 2 }
        instrumentation.runOnMainSync { engine.seekTo(60_000); engine.pause() }
        await("nonzero seek") { engine.currentPosition.value >= 59_000 }
        device.pressDPadCenter()
        device.waitForIdle()
        val spanish = engine.availableAudioTracks.value.first { it.language in listOf("es", "spa") }.name
        val english = engine.availableSubtitleTracks.value.first { it.language in listOf("en", "eng") }.name
        compose.onNodeWithContentDescription("Audio").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(spanish, substring = true).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription("Subs").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(english, substring = true).performSemanticsAction(SemanticsActions.OnClick) { it() }
        await("manual track selections") {
            engine.availableAudioTracks.value.any { it.isSelected && it.language in listOf("es", "spa") } &&
                engine.availableSubtitleTracks.value.any { it.isSelected && it.language in listOf("en", "eng") }
        }
        val position = engine.currentPosition.value
        inject()
        SystemClock.sleep(2_000) // Allow the existing software-decoder retry to complete first.
        if (source().endsWith("/101.mp4")) inject()
        await("AVC fallback") { source().endsWith("/102.mp4") }
        await("AVC decoder running") {
            engine.playerStats.value.videoCodec.let { it.contains("avc", ignoreCase = true) || it.contains("264") }
        }
        await("resumed tracks") {
            engine.currentPosition.value >= position - 2_000 &&
                engine.availableAudioTracks.value.any { it.isSelected && it.language in listOf("es", "spa") } &&
                engine.availableSubtitleTracks.value.any { it.isSelected && it.language in listOf("en", "eng") }
        }
        assertThat(engine.currentPosition.value).isLessThan(position + 15_000)
        await("candidate playing") { engine.isPlaying.value && engine.playbackState.value == PlaybackState.READY }
        val resumedPosition = engine.currentPosition.value
        await("candidate position advancing") { engine.currentPosition.value >= resumedPosition + 2_000 }
        println("Stage4: AVC active; resume within tolerance; Spanish audio and English subtitles retained")
        fun recoveryLog() = device.executeShellCommand("logcat -d -s PlayerVM:I Media3PlayerEngine:I")
        fun handledErrors(log: String) = log.lineSequence().count { "handle-playback-error type=DecoderError contentType=MOVIE" in it }
        val beforeSecondError = recoveryLog()
        val beforePrepareCount = beforeSecondError.lineSequence().count { "prepare resolvedStreamType" in it }
        inject()
        await("second error consumed by movie recovery") { handledErrors(recoveryLog()) > handledErrors(beforeSecondError) }
        val observationEnd = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < observationEnd) {
            assertThat(source()).endsWith("/102.mp4")
            SystemClock.sleep(100)
        }
        assertThat(recoveryLog().lineSequence().count { "prepare resolvedStreamType" in it }).isEqualTo(beforePrepareCount)
        println("Stage4: second candidate error caused no further variant switch")
    }
}
