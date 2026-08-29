package com.streamvault.feature.live.epg

import android.app.Application
import androidx.lifecycle.ViewModel
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.manager.ProgramReminderManager
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.usecase.ScheduleRecording
import com.streamvault.feature.live.api.LivePreviewHandoffPort
import com.streamvault.feature.live.api.LivePreviewOrigin
import com.streamvault.feature.live.api.LivePreviewStreamPreparer
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import javax.inject.Provider as InjectProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class EpgPreviewPortTest {
    private val providerRepository: ProviderRepository = mock()
    private val combinedM3uRepository: CombinedM3uRepository = mock()
    private val channelRepository: ChannelRepository = mock()
    private val epgRepository: EpgRepository = mock()
    private val epgSourceRepository: EpgSourceRepository = mock()
    private val favoriteRepository: FavoriteRepository = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val parentalControlManager: ParentalControlManager = mock()
    private val programReminderManager: ProgramReminderManager = mock()
    private val scheduleRecording: ScheduleRecording = mock()
    private val recordingManager: RecordingManager = mock()
    private val handoffPort: LivePreviewHandoffPort = mock()
    private val streamPreparer: LivePreviewStreamPreparer = mock()
    private val playerEngine: PlayerEngine = mock()
    private val playerEngineProvider: InjectProvider<PlayerEngine> = mock()
    private val application: Application = mock()
    private val createdViewModels = mutableListOf<EpgViewModel>()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        whenever(playerEngineProvider.get()).thenReturn(playerEngine)
        whenever(playerEngine.playbackState).thenReturn(MutableStateFlow(PlaybackState.IDLE))
        whenever(playerEngine.error).thenReturn(flowOf(null))
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(null))
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(flowOf(null))
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.showAllChannelsCategory).thenReturn(flowOf(true))
        whenever(preferencesRepository.guideDensity).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideChannelMode).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideDefaultCategoryId).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideFavoritesOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideScheduledOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideAnchorTime).thenReturn(flowOf(null))
        whenever(preferencesRepository.playerAudioDecoderMode).thenReturn(flowOf(DecoderMode.AUTO))
        whenever(preferencesRepository.playerVideoDecoderMode).thenReturn(flowOf(DecoderMode.AUTO))
        whenever(preferencesRepository.playerPlaybackBufferMode).thenReturn(flowOf(PlaybackBufferMode.AUTO))
        whenever(preferencesRepository.playerSurfaceMode).thenReturn(flowOf(PlayerSurfaceMode.AUTO))
        whenever(programReminderManager.observeUpcomingReminders()).thenReturn(flowOf(emptyList()))
        whenever(handoffPort.reverseHandoffOrigin).thenReturn(flowOf(null))
        whenever(favoriteRepository.getFavorites(any<Long>(), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(any<List<Long>>(), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        runBlocking {
            whenever(epgRepository.getProgramsForChannels(any(), any(), any(), any())).thenReturn(flowOf(emptyMap()))
            whenever(epgRepository.getResolvedProgramsForChannels(any(), any(), any(), any())).thenReturn(emptyMap())
            whenever(epgRepository.getProgramsForChannelsSnapshot(any(), any(), any(), any())).thenReturn(emptyMap())
            whenever(epgRepository.searchPrograms(any(), any(), any(), any(), anyOrNull(), any())).thenReturn(flowOf(emptyList()))
        }
    }

    @After
    fun tearDown() {
        createdViewModels.asReversed().forEach(::clearViewModel)
        Dispatchers.resetMain()
    }

    @Test
    fun `successful guide preview registers GUIDE session through port`() = runTest {
        val channel = Channel(id = 11L, name = "Guide One", providerId = 7L, streamUrl = "https://example.test/live")
        val streamInfo = StreamInfo(url = channel.streamUrl)
        whenever(channelRepository.getStreamInfo(channel)).thenReturn(Result.Success(streamInfo))
        whenever(streamPreparer.prepare(streamInfo)).thenReturn(Result.Success(streamInfo))
        val viewModel = createViewModel()

        viewModel.previewChannel(channel)
        advanceUntilIdle()

        verify(playerEngine).prepare(streamInfo)
        verify(playerEngine).play()
        verify(handoffPort).registerPreviewSession(
            channel,
            streamInfo,
            playerEngine,
            LivePreviewOrigin.GUIDE
        )
        assertThat(viewModel.uiState.value.previewChannelId).isEqualTo(channel.id)
        assertThat(viewModel.uiState.value.previewPlayerEngine).isSameInstanceAs(playerEngine)
    }

    @Test
    fun `guide preview preparation error stays local and does not register`() = runTest {
        val channel = Channel(id = 12L, name = "Guide Two", providerId = 7L, streamUrl = "https://example.test/live")
        val streamInfo = StreamInfo(url = channel.streamUrl)
        whenever(channelRepository.getStreamInfo(channel)).thenReturn(Result.Success(streamInfo))
        whenever(streamPreparer.prepare(streamInfo)).thenReturn(Result.Error("renewal failed"))
        val viewModel = createViewModel()

        viewModel.previewChannel(channel)
        advanceUntilIdle()

        verify(channelRepository).getStreamInfo(channel)
        verify(streamPreparer).prepare(streamInfo)
        verify(handoffPort, never()).registerPreviewSession(any(), any(), any(), any(), any())
        assertThat(viewModel.uiState.value.previewErrorMessage).isEqualTo("renewal failed")
        assertThat(viewModel.uiState.value.previewChannelId).isEqualTo(channel.id)
    }

    @Test
    fun `guide fullscreen handoff clears local preview only after port accepts`() = runTest {
        val channel = Channel(id = 13L, name = "Guide Three", providerId = 7L, streamUrl = "https://example.test/live")
        val streamInfo = StreamInfo(url = channel.streamUrl)
        whenever(channelRepository.getStreamInfo(channel)).thenReturn(Result.Success(streamInfo))
        whenever(streamPreparer.prepare(streamInfo)).thenReturn(Result.Success(streamInfo))
        whenever(handoffPort.beginFullscreenHandoff(channel.id, playerEngine)).thenReturn(true)
        val viewModel = createViewModel()

        viewModel.previewChannel(channel)
        advanceUntilIdle()
        assertThat(viewModel.beginPreviewHandoff(channel)).isTrue()

        assertThat(viewModel.uiState.value.previewChannelId).isNull()
        assertThat(viewModel.uiState.value.previewPlayerEngine).isNull()
        verify(handoffPort).beginFullscreenHandoff(channel.id, playerEngine)
    }

    private fun createViewModel(): EpgViewModel = EpgViewModel(
        providerRepository = providerRepository,
        combinedM3uRepository = combinedM3uRepository,
        channelRepository = channelRepository,
        epgRepository = epgRepository,
        epgSourceRepository = epgSourceRepository,
        favoriteRepository = favoriteRepository,
        preferencesRepository = preferencesRepository,
        parentalControlManager = parentalControlManager,
        programReminderManager = programReminderManager,
        getCustomCategories = GetCustomCategories(favoriteRepository, channelRepository),
        scheduleRecording = scheduleRecording,
        recordingManager = recordingManager,
        playerEngineProvider = playerEngineProvider,
        pluginManager = streamPreparer,
        livePreviewHandoffManager = handoffPort,
        application = application
    ).also(createdViewModels::add)

    private fun clearViewModel(viewModel: EpgViewModel) {
        val clearMethod = ViewModel::class.java.declaredMethods.firstOrNull {
            it.parameterCount == 0 && it.name.startsWith("clear")
        } ?: error("Unable to find ViewModel clear method")
        clearMethod.isAccessible = true
        clearMethod.invoke(viewModel)
    }
}
