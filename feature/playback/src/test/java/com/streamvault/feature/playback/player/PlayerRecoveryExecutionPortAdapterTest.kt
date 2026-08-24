package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.Result
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import org.junit.Test
import org.mockito.kotlin.mock

class PlayerRecoveryExecutionPortAdapterTest {

    @Test
    fun `player engine resolves from the current engine coordinator`() {
        val mainEngine = mock<PlayerEngine>()
        val adoptedEngine = mock<PlayerEngine>()
        val engineCoordinator = PlayerEngineCoordinator(mainEngine)
        val adapter = adapter(engineCoordinator)

        assertThat(adapter.playerEngine).isSameInstanceAs(mainEngine)

        engineCoordinator.switchTo(adoptedEngine)

        assertThat(adapter.playerEngine).isSameInstanceAs(adoptedEngine)
    }

    private fun adapter(
        engineCoordinator: PlayerEngineCoordinator
    ): PlayerRecoveryExecutionPortAdapter = PlayerRecoveryExecutionPortAdapter(
        appPackageName = "com.streamvault.test",
        engineCoordinator = engineCoordinator,
        recoveryState = PlayerRecoveryCoordinator(),
        isActivePlaybackSession = { _, _ -> true },
        tryAlternateStream = { _, _, _ -> false },
        tryNextCatchUpVariant = { false },
        tryFallbackToAvcMovieVariant = { _, _ -> false },
        tryRefreshXtreamPlaybackAfterAuthError = { _, _, _ -> false },
        recordMovieVariantFailureObservation = { _: PlayerError -> },
        updateDecoderModes = { _: DecoderMode, _: DecoderMode -> },
        setLastFailureReason = { _: String? -> },
        appendRecoveryAction = { _: String -> },
        buildRecoveryActions = { _: PlayerRecoveryType -> emptyList() },
        showPlayerNotice = { _: String, _: PlayerRecoveryType, _: List<PlayerNoticeAction>, _: Boolean -> },
        markStreamFailure = { _: String -> },
        incrementChannelErrorCount = { _: Long -> Result.Success(Unit) },
        logRepositoryFailure = { _: String, _: Result<Unit> -> },
        fallbackToPreviousChannel = { _: String -> false },
        hasLastChannel = { false }
    )
}
