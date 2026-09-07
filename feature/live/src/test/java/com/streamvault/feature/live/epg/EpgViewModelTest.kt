package com.streamvault.feature.live.epg

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import android.app.Application
import androidx.lifecycle.ViewModel
import com.streamvault.feature.live.api.LivePreviewHandoffPort
import com.streamvault.feature.live.api.LivePreviewStreamPreparer
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.manager.ProgramReminderManager
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.player.PlayerEngine
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.CombinedCategory
import com.streamvault.domain.model.CombinedCategoryBinding
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.CombinedM3uProfileMember
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.LiveStreamProgramRequest
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.usecase.ScheduleRecording
import kotlinx.coroutines.Dispatchers
import javax.inject.Provider as InjectProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.argThat

@OptIn(ExperimentalCoroutinesApi::class)
class EpgViewModelTest {

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
    private val livePreviewHandoffManager: LivePreviewHandoffPort = mock()
    private val pluginManager: LivePreviewStreamPreparer = mock()
    private val playerEngine: PlayerEngine = mock()
    private val playerEngineProvider: InjectProvider<PlayerEngine> = mock()
    private val application: Application = mock()
    private val getCustomCategories by lazy { GetCustomCategories(favoriteRepository, channelRepository) }
    private val createdViewModels = mutableListOf<EpgViewModel>()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        whenever(playerEngineProvider.get()).thenReturn(playerEngine)
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(null))
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.showAllChannelsCategory).thenReturn(flowOf(true))
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideDefaultCategoryId).thenReturn(flowOf(null))
        whenever(livePreviewHandoffManager.reverseHandoffOrigin).thenReturn(MutableStateFlow(null))
        runBlocking {
            whenever(epgRepository.getResolvedProgramsForChannels(any(), any(), any(), any())).thenReturn(emptyMap())
            whenever(epgRepository.getProgramsForChannelsSnapshot(any(), any(), any(), any())).thenReturn(emptyMap())
            whenever(epgRepository.getProgramsForChannels(any(), any(), any(), any())).thenReturn(flowOf(emptyMap()))
            whenever(epgRepository.searchPrograms(any(), any(), any(), any(), anyOrNull(), any())).thenReturn(flowOf(emptyList()))
        }
        whenever(favoriteRepository.getGroups(any<Long>(), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getGroups(any<List<Long>>(), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(any<Long>(), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(any<List<Long>>(), eq(ContentType.LIVE))).thenReturn(flowOf(emptyList()))
        whenever(channelRepository.getChannelsByCategoryPage(any(), any(), any())).thenReturn(flowOf(emptyList()))
        whenever(channelRepository.getChannelsWithoutErrorsPage(any(), any(), any())).thenReturn(flowOf(emptyList()))
        whenever(recordingManager.observeRecordingItems()).thenReturn(flowOf(emptyList()))
        whenever(programReminderManager.observeUpcomingReminders()).thenReturn(flowOf(emptyList()))
    }

    @After
    fun tearDown() {
        createdViewModels.asReversed().forEach(::clearViewModel)
        createdViewModels.clear()
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun waitForUiState(maxAttempts: Int = 200, condition: () -> Boolean) {
        repeat(maxAttempts) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (condition()) {
                return
            }
        }
        testDispatcher.scheduler.advanceUntilIdle()
        assertWithMessage(createdViewModels.lastOrNull()?.uiState?.value.toString()).that(condition()).isTrue()
    }

    private fun createViewModel(providerRepository: ProviderRepository = this.providerRepository): EpgViewModel =
        EpgViewModel(
            providerRepository = providerRepository,
            combinedM3uRepository = combinedM3uRepository,
            channelRepository = channelRepository,
            epgRepository = epgRepository,
            epgSourceRepository = epgSourceRepository,
            favoriteRepository = favoriteRepository,
            preferencesRepository = preferencesRepository,
            parentalControlManager = parentalControlManager,
            programReminderManager = programReminderManager,
            getCustomCategories = getCustomCategories,
            scheduleRecording = scheduleRecording,
            recordingManager = recordingManager,
            playerEngineProvider = playerEngineProvider,
            pluginManager = pluginManager,
            livePreviewHandoffManager = livePreviewHandoffManager,
            application = application
        ).also(createdViewModels::add)

    private fun clearViewModel(viewModel: EpgViewModel) {
        val clearMethod = ViewModel::class.java.declaredMethods.firstOrNull {
            it.parameterCount == 0 && it.name.startsWith("clear")
        } ?: error("Unable to find ViewModel clear method")
        clearMethod.isAccessible = true
        clearMethod.invoke(viewModel)
    }

    @Test
    fun `guide loads programs with one batched repository call`() = runTest {
        val provider = Provider(
            id = 1L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://provider.example.com"
        )
        val channels = listOf(
            Channel(id = 1L, name = "One", providerId = provider.id, epgChannelId = "one"),
            Channel(id = 2L, name = "Two", providerId = provider.id, epgChannelId = "two")
        )
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getCategorySortMode(provider.id, ContentType.LIVE)).thenReturn(flowOf(CategorySortMode.DEFAULT))
        whenever(parentalControlManager.unlockedCategoriesForProvider(provider.id)).thenReturn(flowOf(emptySet()))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(Category(id = 10L, name = "News"))))
        whenever(channelRepository.getChannels(provider.id)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsByCategoryPage(provider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsWithoutErrorsPage(provider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(channels))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.guideDensity).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideChannelMode).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideDefaultCategoryId).thenReturn(flowOf(ChannelRepository.ALL_CHANNELS_ID))
        whenever(preferencesRepository.guideFavoritesOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideScheduledOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideAnchorTime).thenReturn(flowOf(null))
        whenever(epgRepository.getResolvedProgramsForChannels(eq(provider.id), any(), any(), any())).thenReturn(emptyMap())
        whenever(epgRepository.getProgramsForChannelsSnapshot(eq(provider.id), eq(listOf("one", "two")), any(), any())).thenReturn(
            mapOf(
                "one" to listOf(
                    Program(
                        id = 1L,
                        channelId = "one",
                        title = "Headline",
                        startTime = System.currentTimeMillis() - 60_000L,
                        endTime = System.currentTimeMillis() + 60_000L,
                        providerId = provider.id
                    )
                ),
                "two" to emptyList()
            )
        )

        val viewModel = createViewModel()

        advanceUntilIdle()
        waitForUiState { viewModel.uiState.value.programsByChannel.keys.containsAll(listOf("one", "two")) }

        assertThat(viewModel.uiState.value.programsByChannel.keys).containsExactly("one", "two")
        verify(epgRepository, atLeastOnce()).getResolvedProgramsForChannels(eq(provider.id), eq(listOf(1L, 2L)), any(), any())
        verify(epgRepository, atLeastOnce()).getProgramsForChannelsSnapshot(eq(provider.id), eq(listOf("one", "two")), any(), any())
        verify(epgRepository, never()).getProgramsForChannel(any(), any(), any(), any())
    }

    @Test
    fun `guide fallback loads native programs keyed by stream id`() = runTest {
        val provider = Provider(
            id = 1L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://provider.example.com"
        )
        val streamId = 101L
        val channels = listOf(
            Channel(id = 1L, name = "One", providerId = provider.id, epgChannelId = "native.one", streamId = streamId)
        )
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getCategorySortMode(provider.id, ContentType.LIVE)).thenReturn(flowOf(CategorySortMode.DEFAULT))
        whenever(parentalControlManager.unlockedCategoriesForProvider(provider.id)).thenReturn(flowOf(emptySet()))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(Category(id = 10L, name = "News"))))
        whenever(channelRepository.getChannels(provider.id)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsByCategoryPage(provider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsWithoutErrorsPage(provider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(channels))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.guideDensity).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideChannelMode).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideDefaultCategoryId).thenReturn(flowOf(ChannelRepository.ALL_CHANNELS_ID))
        whenever(preferencesRepository.guideFavoritesOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideScheduledOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideAnchorTime).thenReturn(flowOf(null))
        whenever(epgRepository.getResolvedProgramsForChannels(eq(provider.id), any(), any(), any())).thenReturn(emptyMap())
        whenever(epgRepository.getProgramsForChannelsSnapshot(eq(provider.id), eq(listOf("native.one", streamId.toString())), any(), any())).thenReturn(
            mapOf(
                "native.one" to listOf(
                    Program(
                        id = 1L,
                        channelId = "native.one",
                        title = "Headline",
                        startTime = System.currentTimeMillis() - 60_000L,
                        endTime = System.currentTimeMillis() + 60_000L,
                        providerId = provider.id
                    )
                )
            )
        )

        val viewModel = createViewModel()

        advanceUntilIdle()
        waitForUiState { viewModel.uiState.value.programsByChannel[streamId.toString()].orEmpty().isNotEmpty() }

        assertThat(viewModel.uiState.value.programsByChannel.keys).containsExactly(streamId.toString())
        verify(epgRepository, atLeastOnce()).getProgramsForChannelsSnapshot(eq(provider.id), eq(listOf("native.one", streamId.toString())), any(), any())
    }

    @Test
    fun `guide updates provider metadata when active provider id stays the same`() = runTest {
        val originalProvider = Provider(
            id = 1L,
            name = "Provider One",
            type = ProviderType.M3U,
            serverUrl = "https://provider.example.com"
        )
        val renamedProvider = originalProvider.copy(name = "Provider Renamed")

        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(originalProvider, renamedProvider))
        whenever(preferencesRepository.getHiddenCategoryIds(originalProvider.id, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getCategorySortMode(originalProvider.id, ContentType.LIVE)).thenReturn(flowOf(CategorySortMode.DEFAULT))
        whenever(parentalControlManager.unlockedCategoriesForProvider(originalProvider.id)).thenReturn(flowOf(emptySet()))
        whenever(channelRepository.getCategories(originalProvider.id)).thenReturn(flowOf(listOf(Category(id = 10L, name = "News"))))
        whenever(channelRepository.getChannelsByCategoryPage(originalProvider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(emptyList()))
        whenever(channelRepository.getChannelsWithoutErrorsPage(originalProvider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getFavorites(originalProvider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.guideDensity).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideChannelMode).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideFavoritesOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideScheduledOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideAnchorTime).thenReturn(flowOf(null))
        whenever(epgRepository.getResolvedProgramsForChannels(eq(originalProvider.id), any(), any(), any())).thenReturn(emptyMap())
        whenever(epgRepository.getProgramsForChannelsSnapshot(eq(originalProvider.id), any(), any(), any())).thenReturn(emptyMap())

        val viewModel = createViewModel()

        advanceUntilIdle()
        waitForUiState { viewModel.uiState.value.currentProviderName == renamedProvider.name }

        assertThat(viewModel.uiState.value.currentProviderName).isEqualTo(renamedProvider.name)
    }

    @Test
    fun `guide search finds matching program beyond initially loaded category page`() = runTest {
        val provider = Provider(
            id = 1L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://provider.example.com"
        )
        val loadedChannel = Channel(id = 1L, name = "One", providerId = provider.id, epgChannelId = "one", categoryId = 10L)
        val laterChannel = Channel(id = 2L, name = "Two", providerId = provider.id, epgChannelId = "two", categoryId = 10L)
        val channels = listOf(
            loadedChannel,
            laterChannel
        )
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getCategorySortMode(provider.id, ContentType.LIVE)).thenReturn(flowOf(CategorySortMode.DEFAULT))
        whenever(parentalControlManager.unlockedCategoriesForProvider(provider.id)).thenReturn(flowOf(emptySet()))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(Category(id = 10L, name = "News"))))
        whenever(channelRepository.getChannels(provider.id)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsByCategory(provider.id, 10L)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsByCategoryPage(provider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(listOf(loadedChannel)))
        whenever(channelRepository.getChannelsByCategoryPage(provider.id, 10L, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(listOf(loadedChannel)))
        whenever(channelRepository.getChannelsWithoutErrorsPage(provider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(listOf(loadedChannel)))
        whenever(channelRepository.getChannelsWithoutErrorsPage(provider.id, 10L, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(listOf(loadedChannel)))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.guideDensity).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideChannelMode).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideDefaultCategoryId).thenReturn(flowOf(ChannelRepository.ALL_CHANNELS_ID))
        whenever(preferencesRepository.guideFavoritesOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideScheduledOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideAnchorTime).thenReturn(flowOf(null))
        whenever(epgRepository.getResolvedProgramsForChannels(eq(provider.id), any(), any(), any())).thenReturn(emptyMap())
        whenever(epgRepository.getProgramsForChannelsSnapshot(eq(provider.id), eq(listOf("one")), any(), any())).thenReturn(
            mapOf(
                "one" to listOf(
                    Program(
                        id = 1L,
                        channelId = "one",
                        title = "Headline",
                        startTime = System.currentTimeMillis() - 60_000L,
                        endTime = System.currentTimeMillis() + 60_000L,
                        providerId = provider.id
                    )
                )
            )
        )
        whenever(epgRepository.searchPrograms(eq(provider.id), eq("Headline"), any(), any(), eq(10L), any())).thenReturn(
            flowOf(
                listOf(
                    Program(
                        id = 2L,
                        channelId = "two",
                        title = "Headline Update",
                        startTime = System.currentTimeMillis() - 60_000L,
                        endTime = System.currentTimeMillis() + 60_000L,
                        providerId = provider.id
                    )
                )
            )
        )

        val viewModel = createViewModel()

        advanceUntilIdle()
        waitForUiState {
            viewModel.uiState.value.selectedCategoryId == ChannelRepository.ALL_CHANNELS_ID &&
                !viewModel.uiState.value.isRefreshing
        }
        viewModel.selectCategory(10L)
        waitForUiState {
            viewModel.uiState.value.selectedCategoryId == 10L &&
                !viewModel.uiState.value.isRefreshing &&
                viewModel.uiState.value.channels.map { it.id } == listOf(1L) &&
                viewModel.uiState.value.programsByChannel.keys == setOf("one")
        }
        viewModel.updateProgramSearchQuery("Headline")
        testDispatcher.scheduler.advanceTimeBy(200L)
        advanceUntilIdle()
        waitForUiState {
            viewModel.uiState.value.programSearchQuery == "Headline" &&
                viewModel.uiState.value.channels.map { it.id } == listOf(2L) &&
                viewModel.uiState.value.programsByChannel.keys == setOf("two")
        }

        assertThat(viewModel.uiState.value.channels.map { it.id }).containsExactly(2L)
        assertThat(viewModel.uiState.value.programsByChannel.keys).containsExactly("two")
        assertThat(viewModel.uiState.value.programSearchQuery).isEqualTo("Headline")
        verify(epgRepository).searchPrograms(eq(provider.id), eq("Headline"), any(), any(), eq(10L), any())
        verify(epgRepository, never()).getProgramsForChannel(any(), any(), any(), any())
    }

    @Test
    fun `guide search by channel name preserves loaded schedule`() = runTest {
        val provider = Provider(
            id = 1L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://provider.example.com"
        )
        val channels = listOf(
            Channel(id = 1L, name = "BBC One", providerId = provider.id, epgChannelId = "one", categoryId = 10L, categoryName = "News"),
            Channel(id = 2L, name = "Sports Two", providerId = provider.id, epgChannelId = "two", categoryId = 10L, categoryName = "News")
        )
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getCategorySortMode(provider.id, ContentType.LIVE)).thenReturn(flowOf(CategorySortMode.DEFAULT))
        whenever(parentalControlManager.unlockedCategoriesForProvider(provider.id)).thenReturn(flowOf(emptySet()))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(Category(id = 10L, name = "News"))))
        whenever(channelRepository.getChannels(provider.id)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsByCategory(provider.id, 10L)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsByCategoryPage(provider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsByCategoryPage(provider.id, 10L, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsWithoutErrorsPage(provider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsWithoutErrorsPage(provider.id, 10L, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(channels))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.guideDensity).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideChannelMode).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideDefaultCategoryId).thenReturn(flowOf(ChannelRepository.ALL_CHANNELS_ID))
        whenever(preferencesRepository.guideFavoritesOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideScheduledOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideAnchorTime).thenReturn(flowOf(null))
        whenever(epgRepository.getResolvedProgramsForChannels(eq(provider.id), any(), any(), any())).thenReturn(emptyMap())
        whenever(epgRepository.getProgramsForChannelsSnapshot(eq(provider.id), eq(listOf("one", "two")), any(), any())).thenReturn(
            mapOf(
                "one" to listOf(
                    Program(
                        id = 1L,
                        channelId = "one",
                        title = "Breakfast",
                        startTime = System.currentTimeMillis() - 60_000L,
                        endTime = System.currentTimeMillis() + 60_000L,
                        providerId = provider.id
                    ),
                    Program(
                        id = 2L,
                        channelId = "one",
                        title = "Midday Update",
                        startTime = System.currentTimeMillis() + 60_000L,
                        endTime = System.currentTimeMillis() + 120_000L,
                        providerId = provider.id
                    )
                ),
                "two" to listOf(
                    Program(
                        id = 3L,
                        channelId = "two",
                        title = "Scoreboard",
                        startTime = System.currentTimeMillis() - 60_000L,
                        endTime = System.currentTimeMillis() + 60_000L,
                        providerId = provider.id
                    )
                )
            )
        )

        val viewModel = createViewModel()

        advanceUntilIdle()
        waitForUiState {
            viewModel.uiState.value.selectedCategoryId == ChannelRepository.ALL_CHANNELS_ID &&
                !viewModel.uiState.value.isRefreshing
        }
        viewModel.selectCategory(10L)
        waitForUiState {
            viewModel.uiState.value.selectedCategoryId == 10L &&
                !viewModel.uiState.value.isRefreshing &&
                viewModel.uiState.value.channels.map { it.id } == listOf(1L, 2L) &&
                viewModel.uiState.value.programsByChannel.keys == setOf("one", "two")
        }
        viewModel.updateProgramSearchQuery("BBC")
        testDispatcher.scheduler.advanceTimeBy(200L)
        advanceUntilIdle()
        waitForUiState {
            viewModel.uiState.value.programSearchQuery == "BBC" &&
                viewModel.uiState.value.channels.map { it.id } == listOf(1L) &&
                viewModel.uiState.value.programsByChannel["one"]?.size == 2
        }

        assertThat(viewModel.uiState.value.channels.map { it.id }).containsExactly(1L)
        assertThat(viewModel.uiState.value.programsByChannel["one"]?.map(Program::title))
            .containsExactly("Breakfast", "Midday Update")
        assertThat(viewModel.uiState.value.programSearchQuery).isEqualTo("BBC")
    }

    @Test
    fun `guide prefers healthy channels but falls back when favorite channels have errors`() = runTest {
        val provider = Provider(
            id = 1L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://provider.example.com"
        )
        val healthyChannel = Channel(
            id = 1L,
            name = "Healthy",
            providerId = provider.id,
            epgChannelId = "healthy",
            number = 1,
            categoryId = 10L
        )
        val unhealthyFavorite = Channel(
            id = 2L,
            name = "Unhealthy Favorite",
            providerId = provider.id,
            epgChannelId = "unhealthy",
            number = 2,
            categoryId = 10L,
            errorCount = 3
        )

        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getCategorySortMode(provider.id, ContentType.LIVE)).thenReturn(flowOf(CategorySortMode.DEFAULT))
        whenever(parentalControlManager.unlockedCategoriesForProvider(provider.id)).thenReturn(flowOf(emptySet()))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(Category(id = 10L, name = "News"))))
        whenever(channelRepository.getChannelsByCategoryPage(provider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(
            flowOf(listOf(healthyChannel, unhealthyFavorite))
        )
        whenever(channelRepository.getChannelsWithoutErrorsPage(provider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(
            flowOf(listOf(healthyChannel))
        )
        whenever(channelRepository.getChannelsByCategoryPage(provider.id, 10L, EpgViewModel.MAX_CHANNELS)).thenReturn(
            flowOf(listOf(healthyChannel, unhealthyFavorite))
        )
        whenever(channelRepository.getChannelsWithoutErrorsPage(provider.id, 10L, EpgViewModel.MAX_CHANNELS)).thenReturn(
            flowOf(listOf(healthyChannel))
        )
        whenever(channelRepository.getChannelsByIds(listOf(unhealthyFavorite.id))).thenReturn(
            flowOf(listOf(unhealthyFavorite))
        )
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(
            flowOf(listOf(Favorite(providerId = provider.id, contentId = unhealthyFavorite.id, contentType = ContentType.LIVE)))
        )
        whenever(preferencesRepository.guideDensity).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideChannelMode).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideFavoritesOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideScheduledOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideAnchorTime).thenReturn(flowOf(null))
        whenever(epgRepository.getResolvedProgramsForChannels(eq(provider.id), any(), any(), any())).thenReturn(emptyMap())
        whenever(epgRepository.getProgramsForChannelsSnapshot(eq(provider.id), any(), any(), any())).thenReturn(emptyMap())

        val viewModel = createViewModel()

        advanceUntilIdle()
        viewModel.toggleFavoritesOnly()
        advanceUntilIdle()
        waitForUiState { viewModel.uiState.value.channels.map { it.id } == listOf(unhealthyFavorite.id) }

        assertThat(viewModel.uiState.value.channels.map { it.id }).containsExactly(unhealthyFavorite.id)
    }

    @Test
    fun `guide favorites only loads favorite channels outside first category page`() = runTest {
        val provider = Provider(
            id = 1L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://provider.example.com"
        )
        val firstPageChannel = Channel(
            id = 1L,
            name = "Paged Channel",
            providerId = provider.id,
            epgChannelId = "paged",
            number = 1,
            categoryId = 10L
        )
        val favoriteOutsidePage = Channel(
            id = 2L,
            name = "Favorite Outside Page",
            providerId = provider.id,
            epgChannelId = "favorite",
            number = 99,
            categoryId = 10L
        )

        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getCategorySortMode(provider.id, ContentType.LIVE)).thenReturn(flowOf(CategorySortMode.DEFAULT))
        whenever(parentalControlManager.unlockedCategoriesForProvider(provider.id)).thenReturn(flowOf(emptySet()))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(Category(id = 10L, name = "News"))))
        whenever(channelRepository.getChannelsByCategoryPage(provider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(
            flowOf(listOf(firstPageChannel))
        )
        whenever(channelRepository.getChannelsWithoutErrorsPage(provider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(
            flowOf(listOf(firstPageChannel))
        )
        whenever(channelRepository.getChannelsByCategoryPage(provider.id, 10L, EpgViewModel.MAX_CHANNELS)).thenReturn(
            flowOf(listOf(firstPageChannel))
        )
        whenever(channelRepository.getChannelsWithoutErrorsPage(provider.id, 10L, EpgViewModel.MAX_CHANNELS)).thenReturn(
            flowOf(listOf(firstPageChannel))
        )
        whenever(channelRepository.getChannelsByCategory(provider.id, 10L)).thenReturn(
            flowOf(listOf(firstPageChannel, favoriteOutsidePage))
        )
        whenever(channelRepository.getChannelsByIds(listOf(favoriteOutsidePage.id))).thenReturn(
            flowOf(listOf(favoriteOutsidePage))
        )
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(
            flowOf(listOf(Favorite(providerId = provider.id, contentId = favoriteOutsidePage.id, contentType = ContentType.LIVE)))
        )
        whenever(preferencesRepository.guideDensity).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideChannelMode).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideDefaultCategoryId).thenReturn(flowOf(ChannelRepository.ALL_CHANNELS_ID))
        whenever(preferencesRepository.guideFavoritesOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideScheduledOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideAnchorTime).thenReturn(flowOf(null))
        whenever(epgRepository.getResolvedProgramsForChannels(eq(provider.id), any(), any(), any())).thenReturn(emptyMap())
        whenever(epgRepository.getProgramsForChannelsSnapshot(eq(provider.id), any(), any(), any())).thenReturn(emptyMap())

        val viewModel = createViewModel()

        advanceUntilIdle()
        waitForUiState {
            viewModel.uiState.value.selectedCategoryId == ChannelRepository.ALL_CHANNELS_ID &&
                !viewModel.uiState.value.isRefreshing
        }
        viewModel.selectCategory(10L)
        waitForUiState {
            viewModel.uiState.value.selectedCategoryId == 10L &&
                !viewModel.uiState.value.isRefreshing &&
                viewModel.uiState.value.channels.map { it.id } == listOf(firstPageChannel.id)
        }
        viewModel.toggleFavoritesOnly()
        advanceUntilIdle()
        waitForUiState {
            viewModel.uiState.value.showFavoritesOnly &&
                !viewModel.uiState.value.isRefreshing &&
                viewModel.uiState.value.channels.map { it.id } == listOf(favoriteOutsidePage.id)
        }

        assertThat(viewModel.uiState.value.channels.map { it.id }).containsExactly(favoriteOutsidePage.id)
        verify(channelRepository, atLeastOnce()).getChannelsByIds(listOf(favoriteOutsidePage.id))
    }

    @Test
    fun `guide defaults to all channels when favorite startup category is empty`() = runTest {
        val provider = Provider(
            id = 1L,
            name = "Provider",
            type = ProviderType.M3U,
            serverUrl = "https://provider.example.com"
        )
        val channels = listOf(
            Channel(id = 1L, name = "One", providerId = provider.id, epgChannelId = "one", categoryId = 10L)
        )

        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getCategorySortMode(provider.id, ContentType.LIVE)).thenReturn(flowOf(CategorySortMode.DEFAULT))
        whenever(parentalControlManager.unlockedCategoriesForProvider(provider.id)).thenReturn(flowOf(emptySet()))
        whenever(channelRepository.getCategories(provider.id)).thenReturn(flowOf(listOf(Category(id = 10L, name = "News", count = 1))))
        whenever(channelRepository.getChannelsByCategoryPage(provider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(channels))
        whenever(channelRepository.getChannelsWithoutErrorsPage(provider.id, ChannelRepository.ALL_CHANNELS_ID, EpgViewModel.MAX_CHANNELS)).thenReturn(flowOf(channels))
        whenever(favoriteRepository.getFavorites(provider.id, ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferencesRepository.guideDensity).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideChannelMode).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideFavoritesOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideScheduledOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideAnchorTime).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideDefaultCategoryId).thenReturn(flowOf(com.streamvault.domain.model.VirtualCategoryIds.FAVORITES))
        whenever(epgRepository.getResolvedProgramsForChannels(eq(provider.id), any(), any(), any())).thenReturn(emptyMap())
        whenever(epgRepository.getProgramsForChannelsSnapshot(eq(provider.id), any(), any(), any())).thenReturn(emptyMap())

        val viewModel = createViewModel()

        advanceUntilIdle()
        waitForUiState { viewModel.uiState.value.selectedCategoryId == ChannelRepository.ALL_CHANNELS_ID }

        assertThat(viewModel.uiState.value.selectedCategoryId).isEqualTo(ChannelRepository.ALL_CHANNELS_ID)
    }

    @Test
    fun `combined guide merges favorites across member providers`() = runTest {
        val providerOneChannel = Channel(
            id = 101L,
            name = "Provider One Favorite",
            providerId = 1L,
            epgChannelId = "one",
            number = 1,
            categoryId = 10L
        )
        val providerTwoChannel = Channel(
            id = 201L,
            name = "Provider Two Favorite",
            providerId = 2L,
            epgChannelId = "two",
            number = 2,
            categoryId = 20L
        )
        val profile = CombinedM3uProfile(
            id = 77L,
            name = "Combined",
            members = listOf(
                CombinedM3uProfileMember(profileId = 77L, providerId = 1L, priority = 0, enabled = true),
                CombinedM3uProfileMember(profileId = 77L, providerId = 2L, priority = 1, enabled = true)
            )
        )

        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            flowOf(ActiveLiveSource.CombinedM3uSource(profile.id))
        )
        whenever(combinedM3uRepository.getProfile(profile.id)).thenReturn(profile)
        whenever(combinedM3uRepository.getCombinedCategories(profile.id)).thenReturn(
            flowOf(
                listOf(
                    CombinedCategory(
                        category = Category(id = 10L, name = "One"),
                        bindings = listOf(CombinedCategoryBinding(providerId = 1L, providerName = "Provider One", categoryId = 10L))
                    ),
                    CombinedCategory(
                        category = Category(id = 20L, name = "Two"),
                        bindings = listOf(CombinedCategoryBinding(providerId = 2L, providerName = "Provider Two", categoryId = 20L))
                    )
                )
            )
        )
        whenever(combinedM3uRepository.getCombinedChannels(eq(profile.id), any())).thenAnswer { invocation ->
            val combinedCategory = invocation.getArgument<CombinedCategory>(1)
            when (combinedCategory.category.id) {
                10L -> flowOf(listOf(providerOneChannel))
                20L -> flowOf(listOf(providerTwoChannel))
                else -> flowOf(emptyList())
            }
        }
        whenever(channelRepository.getChannelsByIds(listOf(providerOneChannel.id, providerTwoChannel.id))).thenReturn(
            flowOf(listOf(providerOneChannel, providerTwoChannel))
        )
        whenever(favoriteRepository.getFavorites(any<List<Long>>(), eq(ContentType.LIVE))).thenReturn(
            flowOf(
                listOf(
                    Favorite(providerId = 1L, contentId = providerOneChannel.id, contentType = ContentType.LIVE, position = 0),
                    Favorite(providerId = 2L, contentId = providerTwoChannel.id, contentType = ContentType.LIVE, position = 1)
                )
            )
        )
        whenever(preferencesRepository.guideDensity).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideChannelMode).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideFavoritesOnly).thenReturn(flowOf(true))
        whenever(preferencesRepository.guideScheduledOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.guideAnchorTime).thenReturn(flowOf(null))
        whenever(preferencesRepository.guideDefaultCategoryId).thenReturn(flowOf(com.streamvault.domain.model.VirtualCategoryIds.FAVORITES))
        whenever(epgRepository.getResolvedProgramsForChannels(any(), any(), any(), any())).thenReturn(emptyMap())
        whenever(epgRepository.getProgramsForChannelsSnapshot(any(), any(), any(), any())).thenReturn(emptyMap())

        val viewModel = createViewModel()

        advanceUntilIdle()
        waitForUiState {
            viewModel.uiState.value.channels.map { it.id } == listOf(providerOneChannel.id, providerTwoChannel.id)
        }

        assertThat(viewModel.uiState.value.channels.map { it.id })
            .containsExactly(providerOneChannel.id, providerTwoChannel.id)
        verify(favoriteRepository, atLeastOnce()).getFavorites(argThat<List<Long>> { this == listOf(1L, 2L) }, eq(ContentType.LIVE))
    }
}
