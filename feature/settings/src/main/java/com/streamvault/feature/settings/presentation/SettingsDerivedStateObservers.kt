@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.streamvault.feature.settings.presentation

import com.streamvault.feature.settings.presentation.*

import android.content.Context
import com.streamvault.feature.settings.R
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun observeProviderDiagnostics(
    providerRepository: ProviderRepository,
    syncMetadataRepository: SyncMetadataRepository,
    movieRepository: MovieRepository,
    seriesRepository: SeriesRepository,
    programDao: ProgramDao,
    application: Context
): Flow<Map<Long, ProviderDiagnosticsUiModel>> {
    return providerRepository.getProviders()
        .flatMapLatest { providers ->
            if (providers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    providers.map { provider ->
                        combine(
                            syncMetadataRepository.observeMetadata(provider.id),
                            movieRepository.getLibraryCount(provider.id),
                            seriesRepository.getLibraryCount(provider.id),
                            programDao.observeCountByProvider(provider.id)
                        ) { metadata, movieCount, seriesCount, epgCount ->
                            provider.id to ProviderDiagnosticsUiModel(
                                lastSyncStatus = metadata?.lastSyncStatus ?: "NONE",
                                lastLiveSync = metadata?.lastLiveSync ?: 0L,
                                lastLiveSuccess = metadata?.lastLiveSuccess ?: 0L,
                                lastMovieSync = metadata?.lastMovieSync ?: 0L,
                                lastMovieAttempt = metadata?.lastMovieAttempt ?: 0L,
                                lastMovieSuccess = metadata?.lastMovieSuccess ?: 0L,
                                lastMoviePartial = metadata?.lastMoviePartial ?: 0L,
                                lastSeriesSync = metadata?.lastSeriesSync ?: 0L,
                                lastSeriesSuccess = metadata?.lastSeriesSuccess ?: 0L,
                                lastEpgSync = metadata?.lastEpgSync ?: 0L,
                                lastEpgSuccess = metadata?.lastEpgSuccess ?: 0L,
                                liveCount = metadata?.liveCount ?: 0,
                                movieCount = movieCount,
                                seriesCount = seriesCount,
                                epgCount = epgCount,
                                movieSyncMode = metadata?.movieSyncMode ?: VodSyncMode.UNKNOWN,
                                movieWarningsCount = metadata?.movieWarningsCount ?: 0,
                                movieCatalogStale = metadata?.movieCatalogStale ?: false,
                                liveSequentialFailuresRemembered = metadata?.liveSequentialFailuresRemembered ?: false,
                                liveHealthySyncStreak = metadata?.liveHealthySyncStreak ?: 0,
                                movieParallelFailuresRemembered = metadata?.movieParallelFailuresRemembered ?: false,
                                movieHealthySyncStreak = metadata?.movieHealthySyncStreak ?: 0,
                                seriesSequentialFailuresRemembered = metadata?.seriesSequentialFailuresRemembered ?: false,
                                seriesHealthySyncStreak = metadata?.seriesHealthySyncStreak ?: 0,
                                capabilitySummary = buildCapabilitySummary(application, provider),
                                sourceLabel = provider.sourceLabel(),
                                expirySummary = provider.expirySummary(),
                                connectionSummary = "${provider.maxConnections} connection(s)",
                                archiveSummary = provider.archiveSummary()
                            )
                        }
                    }
                ) { pairs ->
                    pairs.toMap()
                }
            }
        }
}

internal fun observeCategoryManagement(
    activeProviderIdFlow: Flow<Long?>,
    preferencesRepository: com.streamvault.data.preferences.PreferencesRepository,
    categoryRepository: CategoryRepository
): Flow<CategoryManagementSnapshot> {
    return activeProviderIdFlow.flatMapLatest { providerId ->
        if (providerId == null) {
            flowOf(CategoryManagementSnapshot())
        } else {
            combine(
                observeCategorySortModes(providerId, preferencesRepository),
                categoryRepository.getCategories(providerId),
                observeHiddenCategoryIdsByType(providerId, preferencesRepository)
            ) { sortModes, categories, hiddenByType ->
                CategoryManagementSnapshot(
                    categorySortModes = sortModes,
                    hiddenCategories = categories
                        .filter { category -> category.id in hiddenByType[category.type].orEmpty() }
                        .sortedWith(compareBy<Category>({ it.type.ordinal }, { it.name.lowercase() }))
                )
            }
        }
    }
}

private fun observeCategorySortModes(
    providerId: Long,
    preferencesRepository: com.streamvault.data.preferences.PreferencesRepository
): Flow<Map<ContentType, CategorySortMode>> {
    return combine(
        preferencesRepository.getCategorySortMode(providerId, ContentType.LIVE),
        preferencesRepository.getCategorySortMode(providerId, ContentType.MOVIE),
        preferencesRepository.getCategorySortMode(providerId, ContentType.SERIES)
    ) { liveSort, movieSort, seriesSort ->
        mapOf(
            ContentType.LIVE to liveSort,
            ContentType.MOVIE to movieSort,
            ContentType.SERIES to seriesSort
        )
    }
}

private fun observeHiddenCategoryIdsByType(
    providerId: Long,
    preferencesRepository: com.streamvault.data.preferences.PreferencesRepository
): Flow<Map<ContentType, Set<Long>>> {
    return combine(
        preferencesRepository.getHiddenCategoryIds(providerId, ContentType.LIVE),
        preferencesRepository.getHiddenCategoryIds(providerId, ContentType.MOVIE),
        preferencesRepository.getHiddenCategoryIds(providerId, ContentType.SERIES)
    ) { hiddenLive, hiddenMovies, hiddenSeries ->
        mapOf(
            ContentType.LIVE to hiddenLive,
            ContentType.MOVIE to hiddenMovies,
            ContentType.SERIES to hiddenSeries
        )
    }
}

private fun buildCapabilitySummary(application: Context, provider: Provider): String {
    return when (provider.type) {
        ProviderType.XTREAM_CODES -> {
            if (provider.epgUrl.isNotBlank()) {
                application.getString(R.string.settings_capability_xtream_with_epg)
            } else {
                application.getString(R.string.settings_capability_xtream_without_epg)
            }
        }
        ProviderType.M3U -> {
            if (provider.epgUrl.isNotBlank()) {
                application.getString(R.string.settings_capability_m3u_with_epg)
            } else {
                application.getString(R.string.settings_capability_m3u_without_epg)
            }
        }
        ProviderType.STALKER_PORTAL -> {
            if (provider.epgUrl.isNotBlank()) {
                "Portal catalog with MAC auth, XMLTV import, and on-demand playback link resolution."
            } else {
                "Portal catalog with MAC auth and on-demand guide/playback resolution."
            }
        }
        ProviderType.JELLYFIN -> {
            if (provider.epgUrl.isNotBlank()) {
                "Jellyfin catalog with direct streaming, optional XMLTV import."
            } else {
                "Jellyfin catalog with direct streaming and guide data from the server."
            }
        }
    }
}

private fun Provider.sourceLabel(): String = when (type) {
    ProviderType.XTREAM_CODES -> "Xtream Codes"
    ProviderType.M3U -> "M3U Playlist"
    ProviderType.STALKER_PORTAL -> "Stalker/MAG Portal"
    ProviderType.JELLYFIN -> "Jellyfin"
}

private fun Provider.expirySummary(): String {
    val expirationDate = expirationDate
    return when {
        expirationDate == null -> "Expiry unknown"
        expirationDate == Long.MAX_VALUE -> "No expiry reported"
        expirationDate < System.currentTimeMillis() -> "Expired"
        else -> {
            val formatter = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
            "Active until ${formatter.format(Date(expirationDate))}"
        }
    }
}

private fun Provider.archiveSummary(): String = when (type) {
    ProviderType.XTREAM_CODES -> "Catch-up depends on provider archive flags and replay stream ids."
    ProviderType.M3U -> {
        if (epgUrl.isBlank()) {
            "M3U replay is limited without guide coverage."
        } else {
            "M3U replay depends on channel templates and guide alignment."
        }
    }
    ProviderType.STALKER_PORTAL -> {
        if (epgUrl.isBlank()) {
            "Stalker replay depends on portal support; guide falls back to portal data."
        } else {
            "Stalker replay depends on portal support with optional XMLTV coverage."
        }
    }
    ProviderType.JELLYFIN -> {
        if (epgUrl.isBlank()) {
            "Jellyfin replay depends on server guide data."
        } else {
            "Jellyfin replay combines server guide data with optional XMLTV coverage."
        }
    }
}
