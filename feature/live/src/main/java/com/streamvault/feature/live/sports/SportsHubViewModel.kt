package com.streamvault.feature.live.sports

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
internal class SportsHubViewModel @Inject constructor(
    private val repository: SportsHubRepository,
) : ViewModel() {
    init {
        repository.refreshPresentationNow()
    }

    val uiState = repository.uiState

    fun retry() = repository.retry()
    fun selectTimeRange(range: SportsTimeRange) = repository.selectTimeRange(range)
    fun selectSportFilter(filter: SportsSportFilter) = repository.selectSportFilter(filter)
    fun selectLeagueFilter(league: String?) = repository.selectLeagueFilter(league)
    suspend fun searchChannels(event: SportsEvent, query: String) = repository.searchChannels(event, query)
    suspend fun rememberChannelForEvent(event: SportsEvent, broadcast: SportsBroadcast) =
        repository.rememberChannelForEvent(event, broadcast)
    suspend fun forgetChannelForEvent(event: SportsEvent) = repository.forgetChannelForEvent(event)
    suspend fun loadStandings(event: SportsEvent) = repository.loadStandings(event)
}
