package com.streamvault.feature.playback.player

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ExternalSubtitleLanguage
import com.streamvault.domain.model.Result
import com.streamvault.domain.settings.VodTrackPreference
import com.streamvault.domain.settings.VodTrackPreferences
import com.streamvault.domain.settings.SubtitleLanguageOrdering
import com.streamvault.player.SubtitleTrackSource
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerViewModel.prepareExternalSubtitleSearch() {
    externalSubtitleSearchJob?.cancel()
    externalSubtitleSearchStateFlow.update {
        it.copy(
            stage = ExternalSubtitleSearchStage.LANGUAGES,
            selectedLanguageCode = null,
            selectedLanguageName = null,
            isSearching = false,
            subtitles = emptyList(),
            errorMessage = null,
            appliedSubtitleName = null,
        )
    }
    if (externalSubtitleSearchStateFlow.value.languages.isEmpty()) {
        loadExternalSubtitleLanguages()
    }
}

internal fun PlayerViewModel.loadExternalSubtitleLanguages() {
    externalSubtitleSearchJob?.cancel()
    externalSubtitleSearchJob = viewModelScope.launch {
        externalSubtitleSearchStateFlow.update {
            it.copy(isLoadingLanguages = true, errorMessage = null)
        }
        try {
            when (val result = getExternalSubtitleLanguagesUseCase()) {
                is Result.Success -> {
                    val pinned = playerPreferencesCoordinator.pinnedSubtitleLanguages.first()
                    val suggested = resolvePreferredAudioLanguage(
                        preferredAudioLanguage = playerPreferencesCoordinator.preferredAudioLanguage.first(),
                        appLanguage = playerPreferencesCoordinator.appLanguage.first(),
                    )
                    externalSubtitleSearchStateFlow.update {
                        it.copy(
                            isLoadingLanguages = false,
                            languages = orderSubtitleLanguages(result.data, pinned, suggested),
                        )
                    }
                }
                is Result.Error -> externalSubtitleSearchStateFlow.update {
                    it.copy(isLoadingLanguages = false, errorMessage = result.message)
                }
                is Result.Loading -> externalSubtitleSearchStateFlow.update {
                    it.copy(isLoadingLanguages = false)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            externalSubtitleSearchStateFlow.update {
                it.copy(
                    isLoadingLanguages = false,
                    errorMessage = e.message ?: "Subtitle languages request failed.",
                )
            }
        }
    }
}

internal fun PlayerViewModel.selectExternalSubtitleLanguage(language: ExternalSubtitleLanguage?) {
    externalSubtitleSearchStateFlow.update {
        it.copy(
            stage = ExternalSubtitleSearchStage.RESULTS,
            selectedLanguageCode = language?.code,
            selectedLanguageName = language?.name,
        )
    }
    searchExternalSubtitles()
}

internal fun PlayerViewModel.backToExternalSubtitleLanguages() {
    externalSubtitleSearchJob?.cancel()
    externalSubtitleSearchStateFlow.update {
        it.copy(stage = ExternalSubtitleSearchStage.LANGUAGES, isSearching = false)
    }
}

internal fun PlayerViewModel.searchExternalSubtitles() {
    externalSubtitleSearchJob?.cancel()
    if (currentContentType !in setOf(ContentType.MOVIE, ContentType.VOD, ContentType.SERIES_EPISODE)) {
        externalSubtitleSearchStateFlow.value = externalSubtitleSearchStateFlow.value.copy(
            isSearching = false,
            subtitles = emptyList(),
            errorMessage = "Online subtitles are available for movies and series episodes.",
        )
        return
    }
    externalSubtitleSearchJob = viewModelScope.launch {
        val language = externalSubtitleSearchStateFlow.value.selectedLanguageCode
        externalSubtitleSearchStateFlow.update {
            it.copy(
                isSearching = true,
                subtitles = emptyList(),
                errorMessage = null,
                appliedSubtitleName = null,
            )
        }
        try {
            val query = when (currentContentType) {
                ContentType.SERIES_EPISODE -> {
                    val series = currentSeries.value ?: currentSeriesId?.takeIf { it > 0L }?.let { id ->
                        playerContentResolver.getSeriesDetails(currentProviderId, id).getOrNull()
                    }
                    ExternalSubtitleQuery(
                        contentType = ContentType.SERIES_EPISODE,
                        title = series?.name?.takeIf { it.isNotBlank() }
                            ?: currentTitle.ifBlank { playbackTitleFlow.value },
                        tmdbId = null,
                        parentTmdbId = series?.tmdbId,
                        seasonNumber = currentSeasonNumber,
                        episodeNumber = currentEpisodeNumber,
                    )
                }
                ContentType.MOVIE, ContentType.VOD -> {
                    val movie = currentContentId.takeIf { it > 0L }?.let { id ->
                        playerContentResolver.getMovie(id)
                    }
                    ExternalSubtitleQuery(
                        contentType = ContentType.MOVIE,
                        title = movie?.name?.takeIf { it.isNotBlank() }
                            ?: currentTitle.ifBlank { playbackTitleFlow.value },
                        year = movie?.year?.takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull(),
                        tmdbId = movie?.tmdbId,
                    )
                }
                else -> error("Online subtitles are not available for this content.")
            }
            if (query.title.isBlank()) {
                externalSubtitleSearchStateFlow.update {
                    it.copy(isSearching = false, errorMessage = "Could not identify this title for subtitle search.")
                }
                return@launch
            }
            when (val result = searchExternalSubtitlesUseCase(
                contentType = query.contentType,
                title = query.title,
                year = query.year,
                tmdbId = query.tmdbId,
                parentTmdbId = query.parentTmdbId,
                seasonNumber = query.seasonNumber,
                episodeNumber = query.episodeNumber,
                language = language,
            )) {
                is Result.Success -> externalSubtitleSearchStateFlow.update {
                    it.copy(isSearching = false, subtitles = result.data)
                }
                is Result.Error -> externalSubtitleSearchStateFlow.update {
                    it.copy(isSearching = false, errorMessage = result.message)
                }
                is Result.Loading -> externalSubtitleSearchStateFlow.update { it.copy(isSearching = false) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            externalSubtitleSearchStateFlow.update {
                it.copy(isSearching = false, errorMessage = e.message ?: "Subtitle search failed.")
            }
        }
    }
}

internal fun PlayerViewModel.downloadExternalSubtitle(subtitleId: String) {
    val subtitle = externalSubtitleSearchStateFlow.value.subtitles.firstOrNull { it.id == subtitleId }
        ?: return
    externalSubtitleDownloadJob?.cancel()
    externalSubtitleDownloadJob = viewModelScope.launch {
        externalSubtitleSearchStateFlow.update {
            it.copy(downloadingSubtitleId = subtitle.id, errorMessage = null)
        }
        try {
            when (val result = downloadExternalSubtitleUseCase(subtitle)) {
                is Result.Success -> {
                    if (currentContentType == ContentType.MOVIE || currentContentType == ContentType.VOD) {
                        val updatedPreferences = (activeVodTrackPreferences ?: VodTrackPreferences()).copy(
                            subtitle = VodTrackPreference(
                                language = subtitle.language,
                                label = subtitle.displayLanguage,
                                externalSubtitleDownloadId = subtitle.downloadId
                            )
                        )
                        persistVodTrackPreferences(updatedPreferences, persistGlobal = false)
                        playerEngine.setVodTrackPreferences(updatedPreferences)
                    }
                    playerEngine.addExternalSubtitle(Uri.fromFile(result.data), subtitle.language)
                    externalSubtitleSearchStateFlow.update {
                        it.copy(downloadingSubtitleId = null, appliedSubtitleName = subtitle.displayLanguage)
                    }
                }
                is Result.Error -> externalSubtitleSearchStateFlow.update {
                    it.copy(downloadingSubtitleId = null, errorMessage = result.message)
                }
                is Result.Loading -> externalSubtitleSearchStateFlow.update { it.copy(downloadingSubtitleId = null) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            externalSubtitleSearchStateFlow.update {
                it.copy(downloadingSubtitleId = null, errorMessage = e.message ?: "Subtitle download failed.")
            }
        }
    }
}

internal suspend fun PlayerViewModel.restoreRememberedOpenSubtitle() {
    if (currentContentType != ContentType.MOVIE && currentContentType != ContentType.VOD) return
    val preference = activeVodTrackPreferences?.subtitle ?: return
    val downloadId = preference.externalSubtitleDownloadId ?: return
    val cachedFile = downloadExternalSubtitleUseCase.cachedSubtitleFile(downloadId) ?: return
    playerEngine.addExternalSubtitle(
        subtitleUri = Uri.fromFile(cachedFile),
        language = preference.language?.takeIf { it.isNotBlank() } ?: "und",
        label = preference.label,
        subtitleSource = SubtitleTrackSource.ONLINE
    )
}

internal fun PlayerViewModel.cancelExternalSubtitleSearch() {
    externalSubtitleSearchJob?.cancel()
    externalSubtitleDownloadJob?.cancel()
    externalSubtitleSearchStateFlow.update {
        it.copy(
            stage = ExternalSubtitleSearchStage.LANGUAGES,
            isSearching = false,
            isLoadingLanguages = false,
            downloadingSubtitleId = null,
        )
    }
}

private fun orderSubtitleLanguages(
    languages: List<ExternalSubtitleLanguage>,
    pinnedTags: List<String>,
    suggestedTag: String?,
): List<ExternalSubtitleLanguage> {
    val priorityTags = buildList {
        addAll(pinnedTags)
        suggestedTag
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && none { existing -> existing == it } }
            ?.let(::add)
    }
    val alphabetical = languages.sortedBy { it.name.lowercase(Locale.ROOT) }
    return SubtitleLanguageOrdering.sortByPinned(priorityTags, alphabetical) { it.code }
}

private data class ExternalSubtitleQuery(
    val contentType: ContentType,
    val title: String,
    val year: Int? = null,
    val tmdbId: Long? = null,
    val parentTmdbId: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)
