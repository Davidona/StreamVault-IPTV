package com.streamvault.feature.playback.player

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    if (externalSubtitleSearchStateFlow.value.account == null) {
        externalSubtitleSearchStateFlow.value = externalSubtitleSearchStateFlow.value.copy(
            isSearching = false,
            subtitles = emptyList(),
            errorMessage = null,
        )
        return
    }

    externalSubtitleSearchJob = viewModelScope.launch {
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
            val preferredLanguage = playerPreferencesCoordinator.preferredAudioLanguage.first()
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
                ?: "en"
            when (val result = searchExternalSubtitlesUseCase(
                contentType = query.contentType,
                title = query.title,
                year = query.year,
                tmdbId = query.tmdbId,
                parentTmdbId = query.parentTmdbId,
                seasonNumber = query.seasonNumber,
                episodeNumber = query.episodeNumber,
                language = preferredLanguage,
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

internal fun PlayerViewModel.cancelExternalSubtitleSearch() {
    externalSubtitleSearchJob?.cancel()
    externalSubtitleDownloadJob?.cancel()
    externalSubtitleSearchStateFlow.update { it.copy(isSearching = false, downloadingSubtitleId = null) }
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
