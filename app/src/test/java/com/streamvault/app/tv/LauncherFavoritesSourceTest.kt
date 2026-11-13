package com.streamvault.app.tv

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.ExternalItemAction
import com.streamvault.core.navigation.ExternalItemType
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.entity.ChannelBrowseEntity
import com.streamvault.data.local.entity.MovieBrowseEntity
import com.streamvault.data.local.entity.SeriesBrowseEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherFavoritesSourceTest {
    private val providers = mock<ProviderRepository>()
    private val favorites = mock<FavoriteRepository>()
    private val movies = mock<MovieDao>()
    private val series = mock<SeriesDao>()
    private val channels = mock<ChannelDao>()
    private val categories = mock<CategoryRepository>()
    private val preferences = mock<PreferencesRepository>()
    private val active = MutableStateFlow<LegacyProvider?>(LegacyProvider(7, "Fixture", ProviderType.M3U, ""))
    private val saved = MutableStateFlow<List<Favorite>>(emptyList())
    private val movieRows = MutableStateFlow<List<MovieBrowseEntity>>(emptyList())
    private val seriesRows = MutableStateFlow<List<SeriesBrowseEntity>>(emptyList())
    private val channelRows = MutableStateFlow<List<ChannelBrowseEntity>>(emptyList())
    private val categoryRows = MutableStateFlow<List<Category>>(emptyList())
    private val hiddenChannels = MutableStateFlow<Set<Long>>(emptySet())
    private val hiddenCategories = ContentType.entries.associateWith { MutableStateFlow<Set<Long>>(emptySet()) }
    private val source = LauncherFavoritesSource(providers, favorites, movies, series, channels, categories, preferences)

    @Before fun setup() {
        whenever(providers.getActiveProvider()).thenReturn(active)
        whenever(favorites.getFavorites(any<Long>(), org.mockito.kotlin.isNull())).thenReturn(saved)
        whenever(categories.getCategories(any())).thenReturn(categoryRows)
        whenever(preferences.getHiddenChannelIds(any())).thenReturn(hiddenChannels)
        ContentType.entries.forEach { type ->
            whenever(preferences.getHiddenCategoryIds(7, type)).thenReturn(hiddenCategories.getValue(type))
            whenever(preferences.getHiddenCategoryIds(8, type)).thenReturn(hiddenCategories.getValue(type))
        }
        whenever(movies.getByIds(any())).thenAnswer { call ->
            val ids = call.getArgument<List<Long>>(0).toSet()
            movieRows.map { rows -> rows.filter { it.id in ids } }
        }
        whenever(series.getByIds(any())).thenAnswer { call ->
            val ids = call.getArgument<List<Long>>(0).toSet()
            seriesRows.map { rows -> rows.filter { it.id in ids } }
        }
        whenever(channels.getByIds(any())).thenAnswer { call ->
            val ids = call.getArgument<List<Long>>(0).toSet()
            channelRows.map { rows -> rows.filter { it.id in ids } }
        }
    }

    @Test fun `mixed global favorites preserve saved order and use credential-free links`() = runTest {
        saved.value = listOf(favorite(1, ContentType.MOVIE, 30), favorite(2, ContentType.SERIES, 10), favorite(3, ContentType.LIVE, 20))
        movieRows.value = listOf(movie(1).copy(streamUrl = "https://user:secret@example.invalid/movie", durationSeconds = 120, watchProgress = 30_000))
        seriesRows.value = listOf(SeriesBrowseEntity(2, name = "Show", providerId = 7))
        channelRows.value = listOf(ChannelBrowseEntity(3, name = "News", providerId = 7))
        val items = source.observe().first().items
        assertThat(items.map { it.link.id }).containsExactly(2L, 3L, 1L).inOrder()
        assertThat(items.map { it.link.type }).containsExactly(ExternalItemType.SERIES, ExternalItemType.LIVE, ExternalItemType.MOVIE).inOrder()
        assertThat(items.map { it.link.action }).containsExactly(ExternalItemAction.RESUME, ExternalItemAction.PLAY, ExternalItemAction.RESUME).inOrder()
        assertThat(items.last().durationMs).isEqualTo(120_000)
        assertThat(items.last().positionMs).isEqualTo(30_000)
        assertThat(items.toString()).doesNotContain("secret")
    }

    @Test fun `groups duplicates stale IDs wrong owners and unsupported types are excluded`() = runTest {
        saved.value = listOf(favorite(1), favorite(1), favorite(2).copy(groupId = 9), favorite(3),
            favorite(4).copy(providerId = 8), favorite(5), favorite(6, ContentType.SERIES_EPISODE))
        movieRows.value = listOf(movie(1), movie(2), movie(4).copy(providerId = 8), movie(5).copy(providerId = 8))
        assertThat(source.observe().first().items.map { it.link.id }).containsExactly(1L)
    }

    @Test fun `raw item and category locks are excluded even when the app has a PIN unlock`() = runTest {
        saved.value = (1L..6L).map { favorite(it) }
        movieRows.value = listOf(movie(1), movie(2).copy(isAdult = true), movie(3).copy(isUserProtected = true),
            movie(4).copy(categoryId = 40), movie(5).copy(categoryId = 50), movie(6).copy(categoryId = 60))
        categoryRows.value = listOf(Category(40, name = "Locked", type = ContentType.MOVIE, isUserProtected = true),
            Category(50, name = "Adult", type = ContentType.MOVIE, isAdult = true))
        assertThat(source.observe().first().items.map { it.link.id }).containsExactly(1L)
    }

    @Test fun `hidden live channels and both native and generic VOD category preferences apply`() = runTest {
        saved.value = listOf(favorite(1), favorite(2), favorite(3, ContentType.LIVE), favorite(4))
        movieRows.value = listOf(movie(1).copy(categoryId = 10), movie(2).copy(categoryId = 20), movie(4).copy(categoryId = 30))
        channelRows.value = listOf(ChannelBrowseEntity(3, name = "Hidden", providerId = 7))
        categoryRows.value = listOf(Category(10, name = "Hidden", type = ContentType.MOVIE),
            Category(20, name = "Generic hidden", type = ContentType.VOD), Category(30, name = "Generic visible", type = ContentType.VOD))
        hiddenCategories.getValue(ContentType.MOVIE).value = setOf(10)
        hiddenCategories.getValue(ContentType.VOD).value = setOf(20)
        hiddenChannels.value = setOf(3)
        assertThat(source.observe().first().items.map { it.link.id }).containsExactly(4L)
    }

    @Test fun `metadata removal reordering and protection changes update the observed snapshot`() = runTest {
        saved.value = listOf(favorite(1, position = 1), favorite(2, position = 2))
        movieRows.value = listOf(movie(1), movie(2))
        val emissions = mutableListOf<LauncherFavoritesSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.observe().toList(emissions) }
        runCurrent()
        assertThat(emissions.last().items.map { it.link.id }).containsExactly(1L, 2L).inOrder()
        saved.value = saved.value.map { it.copy(position = 3 - it.position) }
        runCurrent()
        assertThat(emissions.last().items.map { it.link.id }).containsExactly(2L, 1L).inOrder()
        movieRows.value = listOf(movie(1).copy(categoryId = 10), movie(2).copy(name = "Renamed"))
        categoryRows.value = listOf(Category(10, name = "Private", type = ContentType.MOVIE, isUserProtected = true))
        runCurrent()
        assertThat(emissions.last().items.map { it.title }).containsExactly("Renamed")
        movieRows.value = emptyList()
        runCurrent()
        assertThat(emissions.last().items).isEmpty()
    }

    @Test fun `provider switching and deletion never carry over another providers favorites`() = runTest {
        saved.value = listOf(favorite(1))
        movieRows.value = listOf(movie(1))
        val emissions = mutableListOf<LauncherFavoritesSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.observe().toList(emissions) }
        runCurrent()
        active.value = active.value!!.copy(id = 8)
        runCurrent()
        assertThat(emissions.last()).isEqualTo(LauncherFavoritesSnapshot(8, emptyList()))
        saved.value = listOf(favorite(2).copy(providerId = 8))
        movieRows.value = listOf(movie(2).copy(providerId = 8))
        runCurrent()
        assertThat(emissions.last().items.single().link.providerId).isEqualTo(8)
        active.value = null
        runCurrent()
        assertThat(emissions.last()).isEqualTo(LauncherFavoritesSnapshot(null, emptyList()))
    }

    @Test fun `large lists are queried in SQLite-safe batches and fill the cap after privacy filtering`() = runTest {
        saved.value = (1L..600L).map { favorite(it, position = it.toInt()) }
        movieRows.value = (1L..600L).map { movie(it).copy(isUserProtected = it <= 300) }
        val items = source.observe().first().items
        assertThat(items).hasSize(24)
        assertThat(items.first().link.id).isEqualTo(301)
        assertThat(items.last().link.id).isEqualTo(324)
    }

    private fun favorite(id: Long, type: ContentType = ContentType.MOVIE, position: Int = 0) =
        Favorite(id = id, providerId = 7, contentId = id, contentType = type, position = position)
    private fun movie(id: Long) = MovieBrowseEntity(id, name = "Movie $id", providerId = 7)
}
