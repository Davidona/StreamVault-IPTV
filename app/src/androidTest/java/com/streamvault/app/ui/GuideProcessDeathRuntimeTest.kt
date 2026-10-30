package com.streamvault.app.ui

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.internal.GeneratedComponentManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Host-coordinated SIGKILL test; never runs without the explicit fixture opt-in. */
@RunWith(AndroidJUnit4::class)
class GuideProcessDeathRuntimeTest {
    @Test fun checkpointOrResume() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("guideProcessDeathFixture") == "true")
        val run = requireNotNull(args.getString("runId"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        val app = ApplicationProvider.getApplicationContext<Application>()
        val component = (app as GeneratedComponentManager<*>).generatedComponent()
        val phase = requireNotNull(args.getString("processDeathPhase"))
        require(phase in setOf("prepare", "resume", "cleanup"))
        val file = app.filesDir.resolve("process-death-$run-$phase.jsonl")
        file.writeText("")
        val emit: (JSONObject) -> Unit = { row ->
            row.put("elapsedMs", android.os.SystemClock.elapsedRealtime())
                .put("pid", android.os.Process.myPid())
            file.appendText(row.toString() + "\n")
            Log.i("GuideProcessDeath", row.toString())
        }
        if (phase == "prepare") {
            val probe = checkNotNull(PlaybackInflightEpgProbe.start(app, component, args, run, emit))
            probe.checkpointForProcessDeath()
            // Host kills the process while the actual worker/socket are still running.
            delay(180_000L)
            error("Host did not kill the checkpointed process")
        } else {
            val (probe, saved) = PlaybackInflightEpgProbe.resumeAfterProcessDeath(app, component, run, emit)
            probe.observeAfterProcessDeath(saved, cleanupOnly = phase == "cleanup")
        }
    }
}
