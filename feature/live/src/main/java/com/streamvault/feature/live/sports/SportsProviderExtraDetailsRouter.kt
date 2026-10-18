package com.streamvault.feature.live.sports

import com.streamvault.domain.sports.provider.ProviderAttribution
import com.streamvault.domain.sports.provider.ProviderArtwork
import com.streamvault.domain.sports.provider.ProviderEntityRef
import com.streamvault.domain.sports.provider.ProviderEntityType
import com.streamvault.domain.sports.provider.ProviderEventDetailRequest
import com.streamvault.domain.sports.provider.ProviderEventStatistics
import com.streamvault.domain.sports.provider.ProviderLineup
import com.streamvault.domain.sports.provider.ProviderTeamMetadata
import com.streamvault.domain.sports.provider.SportsCapability
import com.streamvault.domain.sports.provider.SportsProviderRegistry
import com.streamvault.domain.sports.provider.SportsProviderResult
import com.streamvault.domain.sports.provider.SportsProviderStore
import com.streamvault.domain.sports.provider.SportsSourceRef
import com.streamvault.domain.sports.provider.ArtworkProvider
import com.streamvault.domain.sports.provider.LineupProvider
import com.streamvault.domain.sports.provider.StatisticsProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal data class LoadedSportsEventExtraDetails(
    val statistics: ProviderEventStatistics? = null,
    val lineups: List<ProviderLineup> = emptyList(),
    val teamArtwork: List<LoadedSportsTeamArtwork> = emptyList(),
    val providerName: String,
    val attribution: ProviderAttribution?,
)

internal data class LoadedSportsTeamArtwork(
    val teamName: String,
    val artwork: ProviderArtwork,
)

/** Fetches optional per-event details only from the provider that owns the event ID. */
@Singleton
internal class SportsProviderExtraDetailsRouter @Inject constructor(
    private val registry: SportsProviderRegistry,
    private val store: SportsProviderStore,
) {
    suspend fun load(event: SportsEvent): LoadedSportsEventExtraDetails? {
        val sources = event.provenance.sources.filterIsInstance<SportsSourceRef.Provider>()
        if (sources.isEmpty()) return null
        val configs = try {
            store.configurations()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        for (source in sources) {
            val config = configs.firstOrNull {
                it.enabled && it.instanceId == source.instanceId && it.typeId == source.typeId
            } ?: continue
            val capabilities = registry.capabilities(config)
            if (SportsCapability.LINEUPS !in capabilities &&
                SportsCapability.STATISTICS !in capabilities &&
                SportsCapability.ARTWORK !in capabilities
            ) continue
            val provider = try {
                registry.create(config)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            val request = ProviderEventDetailRequest(
                eventId = source.externalEventId,
                sport = source.sport,
                leagueId = source.leagueId,
            )
            val statistics = if (SportsCapability.STATISTICS in capabilities && provider is StatisticsProvider) {
                try {
                    (provider.statistics(request) as? SportsProviderResult.Success)?.value
                        ?.takeIf { it.statistics.isNotEmpty() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            } else null
            val lineups = if (SportsCapability.LINEUPS in capabilities && provider is LineupProvider) {
                try {
                    (provider.lineups(request) as? SportsProviderResult.Success)?.value?.items.orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            } else emptyList()
            val teamArtwork = if (SportsCapability.ARTWORK in capabilities && provider is ArtworkProvider) {
                loadTeamArtwork(
                    provider,
                    event.provenance.teamMetadata?.value.orEmpty(),
                    request.sport,
                    request.leagueId,
                )
            } else emptyList()
            if (statistics == null && lineups.isEmpty() && teamArtwork.isEmpty()) continue
            val descriptor = registry.descriptor(config.typeId)
            return LoadedSportsEventExtraDetails(
                statistics = statistics,
                lineups = lineups,
                teamArtwork = teamArtwork,
                providerName = registry.displayName(config) ?: descriptor?.displayName ?: config.typeId,
                attribution = descriptor?.compliance?.attribution,
            )
        }
        return null
    }

    private suspend fun loadTeamArtwork(
        provider: ArtworkProvider,
        teams: List<ProviderTeamMetadata>,
        sport: String?,
        leagueId: String?,
    ): List<LoadedSportsTeamArtwork> {
        val results = mutableListOf<LoadedSportsTeamArtwork>()
        for (team in teams.take(MAX_TEAM_ARTWORK_REQUESTS)) {
            val teamId = team.team.id ?: continue
            val result = try {
                provider.artwork(ProviderEntityRef(ProviderEntityType.TEAM, teamId, sport, leagueId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            if (result is SportsProviderResult.Success) {
                result.value.items.forEach { artwork ->
                    results += LoadedSportsTeamArtwork(team.team.name, artwork)
                    if (results.size >= MAX_TEAM_ARTWORKS) return results
                }
            }
        }
        return results
    }

    private companion object {
        const val MAX_TEAM_ARTWORK_REQUESTS = 4
        const val MAX_TEAM_ARTWORKS = 8
    }
}
