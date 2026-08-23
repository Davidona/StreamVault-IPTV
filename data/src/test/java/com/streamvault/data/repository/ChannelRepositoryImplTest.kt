package com.streamvault.data.repository

import android.database.sqlite.SQLiteException
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.entity.CategoryCount
import com.streamvault.data.local.entity.ChannelBrowseEntity
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChannelRepositoryImplTest {

    private val channelDao: ChannelDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val favoriteDao: FavoriteDao = mock()
    private val categoryFlowCache: ChannelCategoryFlowCache = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val parentalControlManager: ParentalControlManager = mock()
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver = mock()

    @Before
    fun setUpDefaults() {
        whenever(categoryFlowCache.getOrCreate(any(), any())).thenAnswer { invocation ->
            invocation.getArgument<() -> Flow<List<com.streamvault.domain.model.Category>>>(1).invoke()
        }
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.liveChannelNumberingMode).thenReturn(flowOf(ChannelNumberingMode.PROVIDER))
        whenever(preferencesRepository.liveChannelGroupingMode).thenReturn(flowOf(LiveChannelGroupingMode.GROUPED))
        whenever(preferencesRepository.groupedChannelLabelMode).thenReturn(flowOf(GroupedChannelLabelMode.HYBRID))
        whenever(preferencesRepository.liveVariantPreferenceMode).thenReturn(flowOf(LiveVariantPreferenceMode.BALANCED))
        whenever(preferencesRepository.liveVariantSelections).thenReturn(flowOf(emptyMap()))
        whenever(preferencesRepository.liveVariantObservations).thenReturn(flowOf(emptyMap()))
        whenever(preferencesRepository.hideDecorativeLiveRows).thenReturn(flowOf(true))
        whenever(preferencesRepository.getHiddenChannelIds(any())).thenReturn(flowOf(emptySet()))
    }

    @Test
    fun `getCategories uses grouped counts without loading all channels`() = runTest {
        whenever(categoryDao.getByProviderAndType(7L, ContentType.LIVE.name)).thenReturn(
            flowOf(
                listOf(
                    categoryEntity(id = 10L, name = "News"),
                    categoryEntity(id = 20L, name = "Sports")
                )
            )
        )
        whenever(channelDao.getGroupedCategoryCounts(7L)).thenReturn(
            flowOf(
                listOf(
                    CategoryCount(categoryId = 10L, item_count = 4),
                    CategoryCount(categoryId = 20L, item_count = 6)
                )
            )
        )
        whenever(parentalControlManager.unlockedCategoriesForProvider(7L)).thenReturn(flowOf(emptySet()))

        val repository = createRepository()

        val result = repository.getCategories(7L).first()

        assertThat(result.map { it.name to it.count }).containsExactly(
            "All Channels" to 10,
            "News" to 4,
            "Sports" to 6
        ).inOrder()
        verify(channelDao).getGroupedCategoryCounts(7L)
        verify(channelDao, never()).getByProvider(any())
    }

    @Test
    fun `getCategories uses raw grouped counts when decorative rows are visible`() = runTest {
        whenever(categoryDao.getByProviderAndType(7L, ContentType.LIVE.name)).thenReturn(
            flowOf(listOf(categoryEntity(id = 10L, name = "News")))
        )
        whenever(preferencesRepository.hideDecorativeLiveRows).thenReturn(flowOf(false))
        whenever(channelDao.getRawGroupedCategoryCounts(7L)).thenReturn(
            flowOf(listOf(CategoryCount(categoryId = 10L, item_count = 5)))
        )
        whenever(parentalControlManager.unlockedCategoriesForProvider(7L)).thenReturn(flowOf(emptySet()))

        val repository = createRepository()

        val result = repository.getCategories(7L).first()

        assertThat(result.map { it.name to it.count }).containsExactly(
            "All Channels" to 5,
            "News" to 5
        ).inOrder()
        verify(channelDao).getRawGroupedCategoryCounts(7L)
        verify(channelDao, never()).getGroupedCategoryCounts(7L)
    }

    @Test
    fun `getCategories keeps unlocked protected category visible at private level`() = runTest {
        whenever(categoryDao.getByProviderAndType(7L, ContentType.LIVE.name)).thenReturn(
            flowOf(
                listOf(
                    categoryEntity(id = 10L, name = "Kids"),
                    categoryEntity(id = 20L, name = "Adults", isUserProtected = true)
                )
            )
        )
        whenever(channelDao.getGroupedCategoryCounts(7L)).thenReturn(
            flowOf(
                listOf(
                    CategoryCount(categoryId = 10L, item_count = 3),
                    CategoryCount(categoryId = 20L, item_count = 5)
                )
            )
        )
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(2))
        whenever(parentalControlManager.unlockedCategoriesForProvider(eq(7L))).thenReturn(flowOf(setOf(20L)))

        val repository = createRepository()

        val result = repository.getCategories(7L).first()

        assertThat(result.map { it.name to it.count }).containsExactly(
            "All Channels" to 8,
            "Kids" to 3,
            "Adults" to 5
        ).inOrder()
        assertThat(result.first { it.id == 20L }.isUserProtected).isFalse()
    }

    @Test
    fun `getCategories hides unlocked protected category at hidden level`() = runTest {
        whenever(categoryDao.getByProviderAndType(7L, ContentType.LIVE.name)).thenReturn(
            flowOf(
                listOf(
                    categoryEntity(id = 10L, name = "Kids"),
                    categoryEntity(id = 20L, name = "Adults", isUserProtected = true)
                )
            )
        )
        whenever(channelDao.getGroupedCategoryCounts(7L)).thenReturn(
            flowOf(
                listOf(
                    CategoryCount(categoryId = 10L, item_count = 3),
                    CategoryCount(categoryId = 20L, item_count = 5)
                )
            )
        )
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(3))
        whenever(parentalControlManager.unlockedCategoriesForProvider(eq(7L))).thenReturn(flowOf(setOf(20L)))

        val repository = createRepository()

        val result = repository.getCategories(7L).first()

        assertThat(result.map { it.name to it.count }).containsExactly(
            "All Channels" to 3,
            "Kids" to 3
        ).inOrder()
    }

    @Test
    fun `getCategoriesSnapshot bypasses the category flow cache`() = runTest {
        val categoryEntities = kotlinx.coroutines.flow.MutableStateFlow(
            listOf(categoryEntity(id = 10L, name = "Old"))
        )
        val categoryCounts = kotlinx.coroutines.flow.MutableStateFlow(
            listOf(CategoryCount(categoryId = 10L, item_count = 1))
        )
        whenever(categoryDao.getByProviderAndType(7L, ContentType.LIVE.name)).thenReturn(categoryEntities)
        whenever(channelDao.getGroupedCategoryCounts(7L)).thenReturn(categoryCounts)
        whenever(parentalControlManager.unlockedCategoriesForProvider(7L)).thenReturn(flowOf(emptySet()))

        val repository = createRepository()
        assertThat(repository.getCategories(7L).first().map { it.name }).containsExactly("All Channels", "Old")
        verify(categoryFlowCache, times(1)).getOrCreate(eq(7L), any())

        categoryEntities.value = listOf(categoryEntity(id = 20L, name = "New"))
        categoryCounts.value = listOf(CategoryCount(categoryId = 20L, item_count = 2))

        assertThat(repository.getCategoriesSnapshot(7L).map { it.name })
            .containsExactly("All Channels", "New")
        verify(categoryFlowCache, times(1)).getOrCreate(eq(7L), any())
    }

    @Test
    fun `category visibility and counts update when parental preference changes`() = runTest {
        val parentalLevel = MutableStateFlow(0)
        whenever(preferencesRepository.parentalControlLevel).thenReturn(parentalLevel)
        whenever(categoryDao.getByProviderAndType(7L, ContentType.LIVE.name)).thenReturn(
            flowOf(
                listOf(
                    categoryEntity(id = 10L, name = "Kids"),
                    categoryEntity(id = 20L, name = "Adults", isUserProtected = true)
                )
            )
        )
        whenever(channelDao.getGroupedCategoryCounts(7L)).thenReturn(
            flowOf(
                listOf(
                    CategoryCount(categoryId = 10L, item_count = 3),
                    CategoryCount(categoryId = 20L, item_count = 5)
                )
            )
        )
        whenever(parentalControlManager.unlockedCategoriesForProvider(7L)).thenReturn(flowOf(emptySet()))

        val repository = createRepository()
        assertThat(repository.getCategoriesSnapshot(7L).map { it.name to it.count }).containsExactly(
            "All Channels" to 8,
            "Kids" to 3,
            "Adults" to 5
        ).inOrder()
        parentalLevel.value = 3
        assertThat(repository.getCategoriesSnapshot(7L).map { it.name to it.count }).containsExactly(
            "All Channels" to 3,
            "Kids" to 3
        ).inOrder()
    }

    @Test
    fun `getChannelsByCategory hides numbering with zero instead of negative sentinel`() = runTest {
        whenever(channelDao.getByCategory(7L, 10L)).thenReturn(
            flowOf(
                listOf(
                    ChannelBrowseEntity(
                        id = 1L,
                        streamId = 101L,
                        name = "News",
                        categoryId = 10L,
                        categoryName = "News",
                        streamUrl = "https://stream",
                        number = 42,
                        providerId = 7L
                    )
                )
            )
        )
        whenever(parentalControlManager.unlockedCategoriesForProvider(7L)).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.liveChannelNumberingMode).thenReturn(flowOf(ChannelNumberingMode.HIDDEN))

        val repository = createRepository()

        val result = repository.getChannelsByCategory(7L, 10L).first()

        assertThat(result).hasSize(1)
        assertThat(result.first().number).isEqualTo(0)
    }

    @Test
    fun `getChannelsByCategory filters hash wrapped provider headers`() = runTest {
        whenever(channelDao.getByCategory(7L, 10L)).thenReturn(
            flowOf(
                listOf(
                    ChannelBrowseEntity(
                        id = 1L,
                        streamId = 101L,
                        name = "#### GENERAL HD/4K ####",
                        categoryId = 10L,
                        categoryName = "News",
                        streamUrl = "https://stream/header",
                        number = 1,
                        providerId = 7L
                    ),
                    ChannelBrowseEntity(
                        id = 2L,
                        streamId = 102L,
                        name = "News One HD",
                        categoryId = 10L,
                        categoryName = "News",
                        streamUrl = "https://stream/news-one",
                        number = 2,
                        providerId = 7L
                    )
                )
            )
        )
        whenever(parentalControlManager.unlockedCategoriesForProvider(7L)).thenReturn(flowOf(emptySet()))

        val repository = createRepository()

        val result = repository.getChannelsByCategory(7L, 10L).first()

        assertThat(result.map { it.name }).containsExactly("News One")
    }

    @Test
    fun `getChannelsByCategory keeps hash wrapped provider headers when setting disabled`() = runTest {
        whenever(preferencesRepository.hideDecorativeLiveRows).thenReturn(flowOf(false))
        whenever(channelDao.getByCategory(7L, 10L)).thenReturn(
            flowOf(
                listOf(
                    ChannelBrowseEntity(
                        id = 1L,
                        streamId = 101L,
                        name = "#### GENERAL HD/4K ####",
                        categoryId = 10L,
                        categoryName = "News",
                        streamUrl = "https://stream/header",
                        number = 1,
                        providerId = 7L
                    ),
                    ChannelBrowseEntity(
                        id = 2L,
                        streamId = 102L,
                        name = "News One HD",
                        categoryId = 10L,
                        categoryName = "News",
                        streamUrl = "https://stream/news-one",
                        number = 2,
                        providerId = 7L
                    )
                )
            )
        )
        whenever(parentalControlManager.unlockedCategoriesForProvider(7L)).thenReturn(flowOf(emptySet()))

        val repository = createRepository()

        val result = repository.getChannelsByCategory(7L, 10L).first()

        assertThat(result.map { it.name }).containsExactly("#### GENERAL ####", "News One")
    }

    @Test
    fun `getChannelCount uses raw count when decorative rows are visible`() = runTest {
        whenever(preferencesRepository.hideDecorativeLiveRows).thenReturn(flowOf(false))
        whenever(channelDao.getRawCount(7L)).thenReturn(flowOf(12))

        val repository = createRepository()

        val result = repository.getChannelCount(7L).first()

        assertThat(result).isEqualTo(12)
        verify(channelDao).getRawCount(7L)
        verify(channelDao, never()).getCount(7L)
    }

    @Test
    fun `offset pages keep group numbering relative to full list`() = runTest {
        whenever(channelDao.getByProviderWithoutErrorsBrowsePageOffset(7L, 60, 60)).thenReturn(
            listOf(
                ChannelBrowseEntity(
                    id = 61L,
                    streamId = 161L,
                    name = "Sixty One",
                    streamUrl = "https://stream/61",
                    number = 1,
                    providerId = 7L
                ),
                ChannelBrowseEntity(
                    id = 62L,
                    streamId = 162L,
                    name = "Sixty Two",
                    streamUrl = "https://stream/62",
                    number = 2,
                    providerId = 7L
                )
            )
        )
        whenever(preferencesRepository.liveChannelNumberingMode).thenReturn(flowOf(ChannelNumberingMode.GROUP))
        whenever(parentalControlManager.unlockedCategoriesForProvider(7L)).thenReturn(flowOf(emptySet()))

        val repository = createRepository()

        val result = repository.getChannelsWithoutErrorsPageOffset(
            providerId = 7L,
            categoryId = com.streamvault.domain.repository.ChannelRepository.ALL_CHANNELS_ID,
            limit = 60,
            offset = 60
        )

        assertThat(result.map { it.number }).containsExactly(61, 62).inOrder()
    }

    @Test
    fun `getChannel applies epg only logo policy for raw channel lookup`() = runTest {
        whenever(channelDao.getBrowseById(99L)).thenReturn(
            ChannelBrowseEntity(
                id = 99L,
                streamId = 199L,
                name = "News One",
                logoUrl = "https://supplier.example/logo.png",
                streamUrl = "https://stream/news",
                number = 9,
                providerId = 7L,
                channelLogoSourcePolicy = ChannelLogoSourcePolicy.EPG_ONLY,
                epgIconUrl = "https://epg.example/icon.png"
            )
        )

        val repository = createRepository()

        val result = repository.getChannel(99L)

        assertThat(result?.logoUrl).isEqualTo("https://epg.example/icon.png")
    }

    @Test
    fun `searchChannels returns empty list when sqlite throws for malformed fts query`() = runTest {
        whenever(channelDao.search(eq(7L), any(), any())).thenReturn(
            flow { throw SQLiteException("malformed MATCH expression") }
        )
        whenever(channelDao.searchFallback(eq(7L), any(), any())).thenReturn(
            flowOf(
                listOf(
                    ChannelBrowseEntity(
                        id = 99L,
                        streamId = 199L,
                        name = "News One",
                        streamUrl = "https://stream/news",
                        number = 9,
                        providerId = 7L
                    )
                )
            )
        )
        whenever(preferencesRepository.liveChannelNumberingMode).thenReturn(flowOf(ChannelNumberingMode.PROVIDER))
        whenever(parentalControlManager.unlockedCategoriesForProvider(7L)).thenReturn(flowOf(emptySet()))
        whenever(favoriteDao.getAllByType(7L, ContentType.LIVE.name)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.searchChannels(7L, "news").first()

        assertThat(result.map { it.name }).containsExactly("News One")
    }

    @Test
    fun `searchChannels does not run like fallback when fts returns rows`() = runTest {
        whenever(channelDao.search(eq(7L), any(), any())).thenReturn(
            flowOf(
                listOf(
                    ChannelBrowseEntity(
                        id = 100L,
                        streamId = 200L,
                        name = "News Fast",
                        streamUrl = "https://stream/news-fast",
                        number = 10,
                        providerId = 7L
                    )
                )
            )
        )
        whenever(parentalControlManager.unlockedCategoriesForProvider(7L)).thenReturn(flowOf(emptySet()))
        whenever(favoriteDao.getAllByType(7L, ContentType.LIVE.name)).thenReturn(flowOf(emptyList()))

        val repository = createRepository()

        val result = repository.searchChannels(7L, "news").first()

        assertThat(result.map { it.name }).containsExactly("News Fast")
        verify(channelDao, never()).searchFallback(eq(7L), any(), any())
    }

    private fun createRepository() = ChannelRepositoryImpl(
        channelDao = channelDao,
        categoryDao = categoryDao,
        favoriteDao = favoriteDao,
        categoryFlowCache = categoryFlowCache,
        preferencesRepository = preferencesRepository,
        parentalControlManager = parentalControlManager,
        xtreamStreamUrlResolver = xtreamStreamUrlResolver
    )

    private fun categoryEntity(
        id: Long,
        name: String,
        isUserProtected: Boolean = false
    ) = CategoryEntity(
        categoryId = id,
        name = name,
        type = ContentType.LIVE,
        providerId = 7L,
        isUserProtected = isUserProtected
    )
}
