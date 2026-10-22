package com.streamvault.feature.playback.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerRefreshRateHostTest {
    @get:Rule val rule = createComposeRule()
    private val votes = mutableListOf<Float?>()
    private val engine = Proxy.newProxyInstance(PlayerEngine::class.java.classLoader, arrayOf(PlayerEngine::class.java)) { proxy, method, args ->
        when (method.name) {
            "setDisplayFrameRate" -> { votes.add(args?.first() as Float?); null }
            "equals" -> proxy === args?.first()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "RefreshRateTestEngine"
            else -> error("Unexpected call: ${method.name}")
        }
    } as PlayerEngine
    private val rate = mutableStateOf(25f)
    private val enabled = mutableStateOf(true)
    private val pip = mutableStateOf(false)
    private val casting = mutableStateOf(false)
    private val visible = mutableStateOf(true)
    private val playback = mutableStateOf(PlaybackState.READY)
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun start(): Owner {
        lateinit var owner: Owner
        rule.runOnUiThread { owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED } }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) PlayerRefreshRateHost(engine, enabled.value, rate.value, 1f, playback.value, pip.value, casting.value)
            }
        }
        rule.mainClock.advanceTimeByFrame()
        return owner
    }

    @Test fun rapidZappingOnlyRequestsLatestRate_andOffReleasesImmediately() {
        start()
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle { rate.value = 50f }
        rule.mainClock.advanceTimeBy(1_200)
        rule.runOnIdle {
            assertFalse(votes.contains(25f))
            assertEquals(50f, votes.last())
            enabled.value = false
        }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { assertNull(votes.last()) }
    }

    @Test fun pauseAndBufferingKeepVote_butPipCastingErrorAndEndReleaseIt() {
        start()
        rule.mainClock.advanceTimeBy(1_200)
        rule.runOnIdle { assertEquals(25f, votes.last()); playback.value = PlaybackState.BUFFERING }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { assertEquals(25f, votes.last()); pip.value = true }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { assertNull(votes.last()); pip.value = false; casting.value = true }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { assertNull(votes.last()); casting.value = false }
        rule.mainClock.advanceTimeBy(1_200)
        rule.runOnIdle { assertEquals(25f, votes.last()); playback.value = PlaybackState.ERROR }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { assertNull(votes.last()); playback.value = PlaybackState.READY }
        rule.mainClock.advanceTimeBy(1_200)
        rule.runOnIdle { assertEquals(25f, votes.last()); playback.value = PlaybackState.ENDED }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { assertNull(votes.last()) }
    }

    @Test fun backgroundAndDisposalReleaseVote_andUnknownRateHasABoundedGracePeriod() {
        val owner = start()
        rule.mainClock.advanceTimeBy(1_200)
        rule.runOnIdle {
            assertEquals(25f, votes.last())
            owner.registry.currentState = Lifecycle.State.CREATED
            assertNull(votes.last()) // Does not require a Compose frame after ON_STOP.
        }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.mainClock.advanceTimeBy(1_200)
        rule.runOnIdle { assertEquals(25f, votes.last()); rate.value = 0f }
        rule.mainClock.advanceTimeBy(3_000)
        rule.runOnIdle { assertEquals(25f, votes.last()); playback.value = PlaybackState.BUFFERING }
        rule.mainClock.advanceTimeBy(2_500)
        rule.runOnIdle { assertNull(votes.last()); rate.value = 29.97f }
        rule.mainClock.advanceTimeBy(1_200)
        rule.runOnIdle { assertEquals(29.97f, votes.last()); visible.value = false }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { assertNull(votes.last()) }
    }
}
