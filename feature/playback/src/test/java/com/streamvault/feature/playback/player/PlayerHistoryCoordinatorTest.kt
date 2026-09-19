package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.usecase.MarkAsWatched
import com.streamvault.feature.playback.api.PlaybackSurfaceRefreshPort
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PlayerHistoryCoordinatorTest {

    @Test
    fun `markAsWatched delegates to the use case`() = runTest {
        val repository = mock<PlaybackHistoryRepository>()
        val history = PlaybackHistory(
            contentId = 1L,
            contentType = ContentType.MOVIE,
            providerId = 1L,
            title = "Movie",
            streamUrl = "https://example.com/movie.mkv",
            resumePositionMs = 40_000L,
            totalDurationMs = 100_000L
        )
        val expected = Result.success(Unit)
        whenever(repository.markAsWatched(history)).thenReturn(expected)
        val coordinator = PlayerHistoryCoordinator(
            repository = repository,
            playbackSurfaceRefreshPort = mock<PlaybackSurfaceRefreshPort>(),
            markAsWatched = MarkAsWatched(repository)
        )

        val result = coordinator.markAsWatched(history)

        assertThat(result).isEqualTo(expected)
        verify(repository).markAsWatched(eq(history))
    }
}
