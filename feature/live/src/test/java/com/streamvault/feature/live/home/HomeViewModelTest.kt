package com.streamvault.feature.live.home

import android.app.Application
import androidx.lifecycle.ViewModel
import com.streamvault.feature.live.api.LiveMultiViewStatus
import com.streamvault.feature.live.api.LiveMultiViewStatusPort
import com.streamvault.feature.live.api.LivePreviewHandoffPort
import com.streamvault.feature.live.api.LivePreviewOrigin
import com.streamvault.feature.live.api.LivePreviewSession
import com.streamvault.feature.live.api.LivePreviewStreamPreparer
import com.streamvault.feature.live.api.LiveSurfaceRefreshPort
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.SyncState
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.*
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.usecase.UnlockParentalCategory
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import com.google.common.truth.Truth.assertThat
import javax.inject.Provider as InjectProvider
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val providerRepository: ProviderRepository = mock()
    private val combinedM3uRepository: CombinedM3uRepository = mock()
    private val channelRepository: ChannelRepository = mock()
    private val categoryRepository: CategoryRepository = mock()
    private val favoriteRepository: FavoriteRepository = mock()
    private val m3uClassificationRepository: M3uClassificationRepository = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val epgRepository: EpgRepository = mock()
    private val playbackHistoryRepository: PlaybackHistoryRepository = mock()
    private val getCustomCategories: GetCustomCategories = mock()
    private val unlockParentalCategory: UnlockParentalCategory = mock()
    private val parentalControlManager: ParentalControlManager = mock()
    private val syncManager: SyncManager = mock()
    private val tvInputChannelSyncManager: LiveSurfaceRefreshPort = mock()
    private val multiViewStatusPort: LiveMultiViewStatusPort = mock()
    private val livePreviewHandoffManager: LivePreviewHandoffPort = mock()
    private val pluginManager: LivePreviewStreamPreparer = mock()
    private val playerEngine: PlayerEngine = mock()
    private val playerEngineProvider: InjectProvider<PlayerEngine> = mock()
    private val application: Application = mock()
    private lateinit var reverseHandoffOrigin: MutableStateFlow<LivePreviewOrigin?>
    private val createdViewModels = mutableListOf<HomeViewModel>()

    private lateinit var viewModel: HomeViewModel

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        whenever(application.getString(any())).thenReturn("test-message")

        // Mock default flows to prevent exceptions during init
        whenever(providerRepository.getProviders()).thenReturn(flowOf(emptyList()))
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(null))
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(flowOf(null))
        whenever(combinedM3uRepository.getActiveLiveSourceOptions()).thenReturn(flowOf(emptyList()))
        reverseHandoffOrigin = MutableStateFlow(null)
        whenever(livePreviewHandoffManager.reverseHandoffOrigin).thenReturn(reverseHandoffOrigin)
        whenever(multiViewStatusPort.status).thenReturn(flowOf(LiveMultiViewStatus()))
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(favoriteRepository.getFavorites(any<Long>(), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(any<List<Long>>(), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.defaultCategoryId).thenReturn(flowOf(null))
        whenever(preferencesRepository.getLastLiveCategoryId(any())).thenReturn(flowOf(null))
        whenever(preferencesRepository.liveTvChannelMode).thenReturn(flowOf("COMPACT"))
        whenever(preferencesRepository.liveTvCategoryFilters).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.liveTvQuickFilterVisibility).thenReturn(flowOf(null))
        whenever(preferencesRepository.showRecentChannelsCategory).thenReturn(flowOf(true))
        whenever(preferencesRepository.showAllChannelsCategory).thenReturn(flowOf(true))
        whenever(preferencesRepository.showFavoritesCategory).thenReturn(flowOf(true))
        whenever(preferencesRepository.showLiveSourceSwitcher).thenReturn(flowOf(false))
        whenever(preferencesRepository.multiViewCenterTwoSlotLayout).thenReturn(flowOf(false))
        whenever(preferencesRepository.liveChannelNumberingMode).thenReturn(flowOf(ChannelNumberingMode.PROVIDER))
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        whenever(preferencesRepository.getHiddenCategoryIds(any(), any())).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getHiddenChannelIds(any())).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getCategorySortMode(any(), any())).thenReturn(flowOf(CategorySortMode.DEFAULT))
        whenever(preferencesRepository.getPinnedCategoryIds(any(), any())).thenReturn(flowOf(emptySet()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(any(), any())).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(any<Long>(), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(any<List<Long>>(), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavoritesByGroup(any())).thenReturn(flowOf(emptyList()))
        whenever(parentalControlManager.unlockedCategoriesForProvider(any())).thenReturn(flowOf(emptySet()))
        whenever(syncManager.syncStateForProvider(any())).thenReturn(flowOf(SyncState.Idle))
        runBlocking {
            whenever(epgRepository.getResolvedProgramsForChannels(any(), any(), any(), any())).thenReturn(emptyMap())
            whenever(preferencesRepository.setLastActiveProviderId(any())).thenReturn(Unit)
        }
        whenever(playerEngineProvider.get()).thenReturn(playerEngine)
        runBlocking {
            whenever(pluginManager.prepare(any())).thenAnswer { invocation ->
                Result.Success(invocation.getArgument<StreamInfo>(0))
            }
        }

        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        createdViewModels.asReversed().forEach(::clearViewModel)
        createdViewModels.clear()
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel =
        HomeViewModel(
            application = application,
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            channelRepository = channelRepository,
            categoryRepository = categoryRepository,
            favoriteRepository = favoriteRepository,
            m3uClassificationRepository = m3uClassificationRepository,
            preferencesRepository = preferencesRepository,
            epgRepository = epgRepository,
            playbackHistoryRepository = playbackHistoryRepository,
            getCustomCategories = getCustomCategories,
            unlockParentalCategory = unlockParentalCategory,
            parentalControlManager = parentalControlManager,
            syncManager = syncManager,
            tvInputChannelSyncManager = tvInputChannelSyncManager,
            multiViewStatusPort = multiViewStatusPort,
            livePreviewHandoffManager = livePreviewHandoffManager,
            pluginManager = pluginManager,
            playerEngineProvider = playerEngineProvider
        ).also(createdViewModels::add)

    private fun clearViewModel(viewModel: HomeViewModel) {
        val clearMethod = ViewModel::class.java.declaredMethods.firstOrNull {
            it.parameterCount == 0 && it.name.startsWith("clear")
        } ?: error("Unable to find ViewModel clear method")
        clearMethod.isAccessible = true
        clearMethod.invoke(viewModel)
    }

    @Test
    fun `when switchProvider is called, it delegates to repository`() = runTest {
        viewModel.switchProvider(1L)
        runCurrent()
        verify(providerRepository).setActiveProvider(1L)
        verify(combinedM3uRepository).setActiveLiveSource(ActiveLiveSource.ProviderSource(1L))
    }

    @Test
    fun `initial state has empty categories and is loading`() = runTest {
        val state = viewModel.uiState.value
        assertThat(state.isLoading).isTrue()
        assertThat(state.categories).isEmpty()
        assertThat(state.filteredChannels).isEmpty()
    }

    @Test
    fun `preview selection updates preview state without changing browse state`() = runTest {
        whenever(preferencesRepository.liveTvChannelMode).thenReturn(flowOf("PRO"))
        whenever(preferencesRepository.playerAudioDecoderMode).thenReturn(
            flowOf(com.streamvault.domain.model.DecoderMode.AUTO)
        )
        whenever(preferencesRepository.playerVideoDecoderMode).thenReturn(
            flowOf(com.streamvault.domain.model.DecoderMode.AUTO)
        )
        whenever(preferencesRepository.playerSurfaceMode).thenReturn(
            flowOf(com.streamvault.domain.model.PlayerSurfaceMode.AUTO)
        )
        whenever(preferencesRepository.playerPlaybackBufferMode).thenReturn(
            flowOf(com.streamvault.domain.model.PlaybackBufferMode.AUTO)
        )
        whenever(preferencesRepository.playerFastRetryOnTransientFailures).thenReturn(flowOf(false))
        whenever(playerEngine.playbackState).thenReturn(
            MutableStateFlow(com.streamvault.player.PlaybackState.IDLE)
        )
        whenever(playerEngine.isPlaying).thenReturn(MutableStateFlow(false))
        whenever(playerEngine.playerStats).thenReturn(
            MutableStateFlow(com.streamvault.player.PlayerStats())
        )
        whenever(playerEngine.error).thenReturn(flowOf(null))
        whenever(channelRepository.getStreamInfo(any(), any())).thenReturn(
            Result.Success(StreamInfo(url = "https://example.com/live.m3u8"))
        )
        val previewViewModel = createViewModel()
        advanceUntilIdle()

        val browseStateBeforePreview = previewViewModel.uiState.value
        val channel = Channel(id = 42L, name = "Preview Channel", providerId = 7L)

        previewViewModel.previewChannel(channel)

        assertThat(previewViewModel.previewUiState.value.previewChannelId).isEqualTo(channel.id)
        assertThat(previewViewModel.previewUiState.value.previewPlayerEngine).isSameInstanceAs(playerEngine)
        assertThat(previewViewModel.previewUiState.value.isPreviewLoading).isTrue()
        assertThat(previewViewModel.uiState.value).isEqualTo(browseStateBeforePreview)
    }

    @Test
    fun `isPreviewing reports whether a channel owns the active preview`() = runTest {
        whenever(preferencesRepository.liveTvChannelMode).thenReturn(flowOf("PRO"))
        whenever(preferencesRepository.playerAudioDecoderMode).thenReturn(
            flowOf(com.streamvault.domain.model.DecoderMode.AUTO)
        )
        whenever(preferencesRepository.playerVideoDecoderMode).thenReturn(
            flowOf(com.streamvault.domain.model.DecoderMode.AUTO)
        )
        whenever(preferencesRepository.playerSurfaceMode).thenReturn(
            flowOf(com.streamvault.domain.model.PlayerSurfaceMode.AUTO)
        )
        whenever(preferencesRepository.playerPlaybackBufferMode).thenReturn(
            flowOf(com.streamvault.domain.model.PlaybackBufferMode.AUTO)
        )
        whenever(preferencesRepository.playerFastRetryOnTransientFailures).thenReturn(flowOf(false))
        whenever(playerEngine.playbackState).thenReturn(
            MutableStateFlow(com.streamvault.player.PlaybackState.IDLE)
        )
        whenever(playerEngine.isPlaying).thenReturn(MutableStateFlow(false))
        whenever(playerEngine.playerStats).thenReturn(
            MutableStateFlow(com.streamvault.player.PlayerStats())
        )
        whenever(playerEngine.error).thenReturn(flowOf(null))
        whenever(channelRepository.getStreamInfo(any(), any())).thenReturn(
            Result.Success(StreamInfo(url = "https://example.com/live.m3u8"))
        )
        val previewViewModel = createViewModel()
        advanceUntilIdle()
        val channel = Channel(id = 42L, name = "Preview Channel", providerId = 7L)

        previewViewModel.previewChannel(channel)

        assertThat(previewViewModel.isPreviewing(channel.id)).isTrue()
        assertThat(previewViewModel.isPreviewing(channel.id + 1L)).isFalse()
    }

    @Test
    fun `preview preparation registers HOME session through the handoff port`() = runTest {
        configurePreviewDependencies()
        val streamInfo = StreamInfo(url = "https://example.com/live.m3u8")
        whenever(channelRepository.getStreamInfo(any(), any())).thenReturn(Result.Success(streamInfo))
        whenever(pluginManager.prepare(any())).thenReturn(Result.Success(streamInfo))
        viewModel = createViewModel()
        advanceUntilIdle()

        val channel = Channel(id = 101L, name = "Port Channel", providerId = 7L)
        viewModel.previewChannel(channel)
        advanceUntilIdle()

        verify(livePreviewHandoffManager).registerPreviewSession(
            eq(channel),
            eq(streamInfo),
            same(playerEngine),
            eq(LivePreviewOrigin.HOME)
        )
        assertThat(viewModel.previewUiState.value.previewChannelId).isEqualTo(channel.id)
    }

    @Test
    fun `preview preparation error is exposed without registering a session`() = runTest {
        configurePreviewDependencies()
        whenever(channelRepository.getStreamInfo(any(), any())).thenReturn(
            Result.Success(StreamInfo(url = "https://example.com/live.m3u8"))
        )
        whenever(pluginManager.prepare(any())).thenReturn(Result.Error("renewal failed"))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.previewChannel(Channel(id = 102L, name = "Error Channel", providerId = 7L))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.liveTvChannelMode).isEqualTo(com.streamvault.domain.model.LiveTvChannelMode.PRO)
        verify(channelRepository).getStreamInfo(any(), eq(false))
        verify(pluginManager).prepare(any())
        assertThat(viewModel.previewUiState.value.previewErrorMessage).isEqualTo("renewal failed")
        verify(livePreviewHandoffManager, never()).registerPreviewSession(any<Channel>(), any(), any(), any())
    }

    @Test
    fun `fullscreen handoff delegates and clears local preview only on success`() = runTest {
        configurePreviewDependencies()
        val streamInfo = StreamInfo(url = "https://example.com/live.m3u8")
        whenever(channelRepository.getStreamInfo(any(), any())).thenReturn(Result.Success(streamInfo))
        whenever(pluginManager.prepare(any())).thenReturn(Result.Success(streamInfo))
        viewModel = createViewModel()
        advanceUntilIdle()
        val channel = Channel(id = 103L, name = "Handoff Channel", providerId = 7L)
        viewModel.previewChannel(channel)
        advanceUntilIdle()

        whenever(livePreviewHandoffManager.beginFullscreenHandoff(channel.id, playerEngine)).thenReturn(false)
        assertThat(viewModel.beginPreviewHandoff(channel)).isFalse()
        assertThat(viewModel.previewUiState.value.previewChannelId).isEqualTo(channel.id)

        whenever(livePreviewHandoffManager.beginFullscreenHandoff(channel.id, playerEngine)).thenReturn(true)
        assertThat(viewModel.beginPreviewHandoff(channel)).isTrue()
        verify(livePreviewHandoffManager, times(2)).beginFullscreenHandoff(channel.id, playerEngine)
        assertThat(viewModel.previewUiState.value.previewChannelId).isNull()
        assertThat(viewModel.previewUiState.value.previewPlayerEngine).isNull()
    }

    @Test
    fun `multi view status is mapped into HomeUiState`() = runTest {
        whenever(multiViewStatusPort.status).thenReturn(flowOf(LiveMultiViewStatus(channelCount = 3, slotCapacity = 6)))
        viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.multiviewChannelCount).isEqualTo(3)
        assertThat(viewModel.uiState.value.multiviewSlotCapacity).isEqualTo(6)
    }

    @Test
    fun `reverse HOME handoff resumes preview while GUIDE notifications are ignored`() = runTest {
        configurePreviewDependencies()
        val streamInfo = StreamInfo(url = "https://example.com/live.m3u8")
        whenever(livePreviewHandoffManager.consumeReverseHandoff(LivePreviewOrigin.HOME)).thenReturn(
            LivePreviewSession(
                engine = playerEngine,
                channelId = 104L,
                providerId = 7L,
                streamInfo = streamInfo
            )
        )
        clearViewModel(viewModel)
        viewModel = createViewModel()
        advanceUntilIdle()

        reverseHandoffOrigin.value = LivePreviewOrigin.GUIDE
        advanceUntilIdle()
        verify(livePreviewHandoffManager, never()).consumeReverseHandoff(LivePreviewOrigin.GUIDE)

        reverseHandoffOrigin.value = LivePreviewOrigin.HOME
        advanceUntilIdle()

        verify(livePreviewHandoffManager).consumeReverseHandoff(LivePreviewOrigin.HOME)
        verify(livePreviewHandoffManager).registerPreviewSession(
            eq(104L),
            eq(7L),
            eq(streamInfo),
            same(playerEngine),
            eq(LivePreviewOrigin.HOME)
        )
        assertThat(viewModel.previewUiState.value.previewChannelId).isEqualTo(104L)
        assertThat(viewModel.previewUiState.value.previewPlayerEngine).isSameInstanceAs(playerEngine)
    }

    @Test
    fun `clearing preview delegates release and clears local ownership`() = runTest {
        configurePreviewDependencies()
        val streamInfo = StreamInfo(url = "https://example.com/live.m3u8")
        whenever(channelRepository.getStreamInfo(any(), any())).thenReturn(Result.Success(streamInfo))
        whenever(pluginManager.prepare(any())).thenReturn(Result.Success(streamInfo))
        viewModel = createViewModel()
        advanceUntilIdle()
        val channel = Channel(id = 105L, name = "Clear Channel", providerId = 7L)
        viewModel.previewChannel(channel)
        advanceUntilIdle()

        clearInvocations(livePreviewHandoffManager, playerEngine)
        viewModel.clearPreview()

        verify(livePreviewHandoffManager).clear(same(playerEngine))
        verify(playerEngine).stop()
        verify(playerEngine).release()
        assertThat(viewModel.previewUiState.value.previewChannelId).isNull()
        assertThat(viewModel.previewUiState.value.previewPlayerEngine).isNull()
    }

    private fun configurePreviewDependencies() {
        whenever(preferencesRepository.liveTvChannelMode).thenReturn(flowOf("PRO"))
        whenever(preferencesRepository.playerAudioDecoderMode).thenReturn(
            flowOf(com.streamvault.domain.model.DecoderMode.AUTO)
        )
        whenever(preferencesRepository.playerVideoDecoderMode).thenReturn(
            flowOf(com.streamvault.domain.model.DecoderMode.AUTO)
        )
        whenever(preferencesRepository.playerSurfaceMode).thenReturn(
            flowOf(com.streamvault.domain.model.PlayerSurfaceMode.AUTO)
        )
        whenever(preferencesRepository.playerPlaybackBufferMode).thenReturn(
            flowOf(com.streamvault.domain.model.PlaybackBufferMode.AUTO)
        )
        whenever(preferencesRepository.playerFastRetryOnTransientFailures).thenReturn(flowOf(false))
        whenever(playerEngine.playbackState).thenReturn(MutableStateFlow(PlaybackState.IDLE))
        whenever(playerEngine.isPlaying).thenReturn(MutableStateFlow(false))
        whenever(playerEngine.playerStats).thenReturn(MutableStateFlow(com.streamvault.player.PlayerStats()))
        whenever(playerEngine.error).thenReturn(flowOf(null))
    }

    @Test
    fun `updateCategorySearchQuery updates state`() = runTest {
        viewModel.updateCategorySearchQuery("News")
        assertThat(viewModel.uiState.value.categorySearchQuery).isEqualTo("News")
    }

    @Test
    fun `updateChannelSearchQuery updates state and triggers filtering`() = runTest {
        viewModel.updateChannelSearchQuery("CNN")
        assertThat(viewModel.uiState.value.channelSearchQuery).isEqualTo("CNN")
    }

    @Test
    fun `selectCategory sets selected category and triggers loading`() = runTest {
        val category = Category(id = 1L, name = "Sports", parentId = null)
        
        // Mock the repositories needed for loading channels
        whenever(channelRepository.getChannelsByCategoryPage(any(), any(), any())).thenReturn(flowOf(emptyList()))
        val provider = Provider(id = 1L, name = "Provider", type = com.streamvault.domain.model.ProviderType.M3U, serverUrl = "http://test")
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectCategory(category)
        advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertThat(state.selectedCategory).isEqualTo(category)
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `recent live history becomes a virtual recent category`() = runTest {
        val provider = Provider(
            id = 9L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "http://test"
        )
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(
            flowOf(
                listOf(
                    Category(
                        id = VirtualCategoryIds.FAVORITES,
                        name = "Favorites",
                        isVirtual = true
                    )
                )
            )
        )
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(
            flowOf(
                listOf(
                    PlaybackHistory(
                        contentId = 21L,
                        contentType = ContentType.LIVE,
                        providerId = provider.id,
                        title = "News",
                        streamUrl = "http://stream"
                    )
                )
            )
        )
        whenever(channelRepository.getChannelsByIds(listOf(21L))).thenReturn(
            flowOf(
                listOf(
                    Channel(
                        id = 21L,
                        name = "News",
                        streamUrl = "http://stream",
                        providerId = provider.id
                    )
                )
            )
        )
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()

        advanceUntilIdle()

        assertThat(viewModel.uiState.value.categories.map { it.id }).contains(VirtualCategoryIds.RECENT)
        assertThat(viewModel.uiState.value.recentChannels.map { it.id }).containsExactly(21L)
    }

    @Test
    fun `default live category requests initial channel focus on entry`() = runTest {
        val provider = Provider(
            id = 5L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "http://test"
        )
        val sportsCategory = Category(id = 12L, name = "Sports", type = ContentType.LIVE)

        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(sportsCategory)))
        whenever(preferencesRepository.defaultCategoryId).thenReturn(flowOf(sportsCategory.id))
        whenever(channelRepository.getChannelsByCategoryPage(eq(provider.id), eq(sportsCategory.id), any())).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.selectedCategory?.id).isEqualTo(sportsCategory.id)
        assertThat(state.shouldAutoFocusFirstChannelOnEntry).isTrue()

        viewModel.consumeInitialChannelFocusRequest()

        assertThat(viewModel.uiState.value.shouldAutoFocusFirstChannelOnEntry).isFalse()
    }

    @Test
    fun `hidden favorites category is removed from live tv categories`() = runTest {
        val provider = Provider(
            id = 8L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "http://test"
        )
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(
            flowOf(
                listOf(
                    Category(
                        id = VirtualCategoryIds.FAVORITES,
                        name = "Favorites",
                        type = ContentType.LIVE,
                        isVirtual = true
                    )
                )
            )
        )
        whenever(preferencesRepository.showFavoritesCategory).thenReturn(flowOf(false))

        viewModel = createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.categories.map { it.id }).doesNotContain(VirtualCategoryIds.FAVORITES)
    }

    @Test
    fun `recent category stays visible in live tv even when history is empty`() = runTest {
        val provider = Provider(
            id = 12L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "http://test"
        )
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(
            flowOf(
                listOf(
                    Category(
                        id = VirtualCategoryIds.FAVORITES,
                        name = "Favorites",
                        isVirtual = true
                    )
                )
            )
        )
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()

        advanceUntilIdle()

        val categories = viewModel.uiState.value.categories
        assertThat(categories.map { it.id }).containsExactly(
            VirtualCategoryIds.FAVORITES,
            VirtualCategoryIds.RECENT,
            ChannelRepository.ALL_CHANNELS_ID
        )
        assertThat(categories.first { it.id == VirtualCategoryIds.RECENT }.count).isEqualTo(0)
    }

    @Test
    fun `last visited live category is exposed for quick return`() = runTest {
        val provider = Provider(
            id = 14L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "http://test"
        )
        val sportsCategory = Category(id = 5L, name = "Sports")
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(sportsCategory)))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(
            flowOf(
                listOf(
                    Category(
                        id = VirtualCategoryIds.FAVORITES,
                        name = "Favorites",
                        isVirtual = true
                    )
                )
            )
        )
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.getLastLiveCategoryId(provider.id)).thenReturn(flowOf(sportsCategory.id))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()

        advanceUntilIdle()

        assertThat(viewModel.uiState.value.lastVisitedCategory?.id).isEqualTo(sportsCategory.id)
        assertThat(viewModel.uiState.value.lastVisitedCategory?.name).isEqualTo("Sports")
    }

    @Test
    fun `selecting a live category remembers it for the current provider`() = runTest {
        val provider = Provider(
            id = 20L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "http://test"
        )
        val category = Category(id = 7L, name = "Kids")
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(category)))
        whenever(channelRepository.getChannelsByCategoryPage(eq(provider.id), eq(category.id), any())).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()

        advanceUntilIdle()
        viewModel.selectCategory(category)
        advanceUntilIdle()

        verify(preferencesRepository).setLastLiveCategoryId(provider.id, category.id)
    }

    @Test
    fun `selecting recent does not overwrite remembered live group`() = runTest {
        val provider = Provider(
            id = 21L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "http://test"
        )
        val recentCategory = Category(
            id = VirtualCategoryIds.RECENT,
            name = "Recent",
            type = ContentType.LIVE,
            isVirtual = true
        )
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()

        advanceUntilIdle()
        viewModel.selectCategory(recentCategory)
        advanceUntilIdle()

        verify(preferencesRepository, never()).setLastLiveCategoryId(provider.id, recentCategory.id)
    }

    @Test
    fun `channel search delegates to repository for provider categories`() = runTest {
        val provider = Provider(
            id = 30L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://test"
        )
        val category = Category(id = 11L, name = "News")
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(category)))
        whenever(channelRepository.getChannelsByCategoryPage(eq(provider.id), eq(category.id), any())).thenReturn(
            flowOf(listOf(Channel(id = 1L, name = "BBC News", providerId = provider.id, streamUrl = "https://stream")))
        )
        whenever(channelRepository.searchChannelsByCategoryPaged(eq(provider.id), eq(category.id), eq("bbc"), any())).thenReturn(
            flowOf(listOf(Channel(id = 1L, name = "BBC News", providerId = provider.id, streamUrl = "https://stream")))
        )
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectCategory(category)
        advanceUntilIdle()
        viewModel.updateChannelSearchQuery("bbc")
        advanceUntilIdle()

        verify(channelRepository).searchChannelsByCategoryPaged(eq(provider.id), eq(category.id), eq("bbc"), any())
    }

    @Test
    fun `hidden numbering mode keeps displayed channel numbers non-negative`() = runTest {
        val provider = Provider(
            id = 31L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://test"
        )
        val category = Category(id = 12L, name = "News")
        val channel = Channel(
            id = 1L,
            name = "BBC News",
            providerId = provider.id,
            streamUrl = "https://stream",
            number = 23
        )
        whenever(preferencesRepository.liveChannelNumberingMode).thenReturn(flowOf(ChannelNumberingMode.HIDDEN))
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(category)))
        whenever(channelRepository.getChannelsByCategoryPage(eq(provider.id), eq(category.id), any())).thenReturn(flowOf(listOf(channel)))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectCategory(category)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.filteredChannels.map(Channel::number)).containsExactly(0)
    }

    @Test
    fun `hideChannel delegates to repository with hidden=true and dismisses dialog`() = runTest {
        val provider = Provider(id = 9L, name = "Acme", type = ProviderType.XTREAM_CODES, serverUrl = "url")
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()
        advanceUntilIdle()

        val channel = Channel(id = 77L, name = "BBC", streamUrl = "http://stream", providerId = provider.id)
        viewModel.hideChannel(channel)
        advanceUntilIdle()

        verify(preferencesRepository).setChannelHidden(
            providerId = provider.id,
            channelId = 77L,
            hidden = true
        )
        assertThat(viewModel.uiState.value.showDialog).isFalse()
    }

    @Test
    fun `unhideCategory delegates to repository with hidden=false`() = runTest {
        val provider = Provider(id = 9L, name = "Acme", type = ProviderType.XTREAM_CODES, serverUrl = "url")
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()
        advanceUntilIdle()

        val category = Category(id = 42L, name = "DELAY", type = ContentType.LIVE)
        viewModel.unhideCategory(category)
        advanceUntilIdle()

        verify(preferencesRepository).setCategoryHidden(
            providerId = provider.id,
            type = ContentType.LIVE,
            categoryId = 42L,
            hidden = false
        )
    }

    @Test
    fun `unhideChannel delegates to repository with hidden=false`() = runTest {
        val provider = Provider(id = 9L, name = "Acme", type = ProviderType.XTREAM_CODES, serverUrl = "url")
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()
        advanceUntilIdle()

        val channel = Channel(id = 77L, name = "BBC", streamUrl = "http://stream", providerId = provider.id)
        viewModel.unhideChannel(channel)
        advanceUntilIdle()

        verify(preferencesRepository).setChannelHidden(
            providerId = provider.id,
            channelId = 77L,
            hidden = false
        )
    }

    @Test
    fun `unhideAllLiveCategories clears the hidden set for live`() = runTest {
        val provider = Provider(id = 9L, name = "Acme", type = ProviderType.XTREAM_CODES, serverUrl = "url")
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.unhideAllLiveCategories()
        advanceUntilIdle()

        verify(preferencesRepository).setHiddenCategoryIds(
            providerId = provider.id,
            type = ContentType.LIVE,
            categoryIds = emptySet()
        )
    }

    @Test
    fun `unhideAllChannels clears the hidden set`() = runTest {
        val provider = Provider(id = 9L, name = "Acme", type = ProviderType.XTREAM_CODES, serverUrl = "url")
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.unhideAllChannels()
        advanceUntilIdle()

        verify(preferencesRepository).setHiddenChannelIds(
            providerId = provider.id,
            channelIds = emptySet()
        )
    }

    @Test
    fun `unhideCategory is a no-op when no provider is active`() = runTest {
        val category = Category(id = 42L, name = "DELAY", type = ContentType.LIVE)
        viewModel.unhideCategory(category)
        advanceUntilIdle()
        verify(preferencesRepository, never()).setCategoryHidden(any(), any(), any(), any())
    }

    @Test
    fun `hiddenLiveCategories surfaces categories whose id is in the hidden set`() = runTest {
        val provider = Provider(id = 11L, name = "Acme", type = ProviderType.XTREAM_CODES, serverUrl = "url")
        val visible = Category(id = 1L, name = "News", type = ContentType.LIVE)
        val hidden = Category(id = 2L, name = "DELAY", type = ContentType.LIVE)
        val alsoHidden = Category(id = 3L, name = "TNT HD", type = ContentType.LIVE)

        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(visible, hidden, alsoHidden)))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE))
            .thenReturn(flowOf(setOf(2L, 3L)))

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.hiddenLiveCategories.map(Category::id)).containsExactly(2L, 3L)
    }

    @Test
    fun `hiddenChannelsLiveTv surfaces channels whose id is in the hidden set, in name order`() = runTest {
        val provider = Provider(id = 11L, name = "Acme", type = ProviderType.XTREAM_CODES, serverUrl = "url")
        val hiddenA = Channel(id = 2L, name = "Alpha", streamUrl = "http://a", providerId = provider.id)
        val hiddenZ = Channel(id = 3L, name = "Zulu", streamUrl = "http://z", providerId = provider.id)

        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(emptyList()))
        whenever(getCustomCategories.invoke(eq(provider.id), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(eq(provider.id), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.getHiddenChannelIds(provider.id))
            .thenReturn(flowOf(setOf(2L, 3L)))
        whenever(channelRepository.getChannelsByIds(any<List<Long>>()))
            .thenReturn(flowOf(listOf(hiddenZ, hiddenA)))

        viewModel = createViewModel()
        // hiddenChannelsLiveTv uses SharingStarted.WhileSubscribed — keep a
        // collector alive so the upstream flow actually emits.
        backgroundScope.launch { viewModel.hiddenChannelsLiveTv.collect {} }
        advanceUntilIdle()

        val collected = viewModel.hiddenChannelsLiveTv.value
        assertThat(collected.map(Channel::id)).containsExactly(2L, 3L).inOrder()
        assertThat(collected.map(Channel::name)).containsExactly("Alpha", "Zulu").inOrder()
    }
}
