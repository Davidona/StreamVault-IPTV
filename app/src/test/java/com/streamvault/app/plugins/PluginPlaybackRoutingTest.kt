package com.streamvault.app.plugins

import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.system.api.InstalledStreamVaultPlugin
import com.streamvault.feature.system.api.StreamVaultPluginContract
import com.streamvault.feature.system.api.StreamVaultPluginManifest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PluginPlaybackRoutingTest {

    @Test
    fun `only explicitly matching handlers are routed and priority is deterministic`() {
        val generic = plugin("generic", schemes = listOf("*"), hosts = listOf("*"), priority = 1)
        val matching = plugin("match", schemes = listOf("https"), hosts = listOf("video.example"), priority = 10)
        val wrongHost = plugin("wrong", schemes = listOf("https"), hosts = listOf("other.example"), priority = 20)
        val legacyUnscoped = plugin("legacy")

        val candidates = playbackCandidates(
            listOf(generic, wrongHost, legacyUnscoped, matching),
            "https://video.example/live.m3u8",
            StreamVaultPluginContract.CAPABILITY_PLAYBACK_PREPARE
        )

        assertThat(candidates.map { it.manifest.id }).containsExactly("match", "generic").inOrder()
    }

    @Test
    fun `invalid or unowned URLs have no playback handler`() {
        val plugin = plugin("scoped", schemes = listOf("https"), hosts = listOf("video.example"))

        assertThat(playbackCandidates(listOf(plugin), "not a url", StreamVaultPluginContract.CAPABILITY_PLAYBACK_PREPARE)).isEmpty()
        assertThat(playbackCandidates(listOf(plugin), "https://other.example/live", StreamVaultPluginContract.CAPABILITY_PLAYBACK_PREPARE)).isEmpty()
    }

    @Test
    fun `playback deadline bounds discovery and handler work together`() = runTest {
        val result = withPluginPlaybackDeadline(5_000L) {
            delay(3_000L) // discovery
            delay(3_000L) // handler
            "handled"
        }

        assertThat(result).isNull()
        assertThat(currentTime).isEqualTo(5_000L)
        assertThat(currentCoroutineContext().isActive).isTrue()
    }

    private fun plugin(
        id: String,
        schemes: List<String> = emptyList(),
        hosts: List<String> = emptyList(),
        priority: Int = 0
    ) = InstalledStreamVaultPlugin(
        packageName = "com.example.$id",
        serviceClassName = "$id.Service",
        appLabel = id,
        manifest = StreamVaultPluginManifest(
            id = id,
            name = id,
            capabilities = listOf(StreamVaultPluginContract.CAPABILITY_PLAYBACK_PREPARE),
            playbackUrlSchemes = schemes,
            playbackUrlHosts = hosts,
            playbackPriority = priority
        ),
        enabled = true
    )
}
