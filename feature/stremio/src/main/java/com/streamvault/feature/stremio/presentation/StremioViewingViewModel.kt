package com.streamvault.feature.stremio.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.stremio.*
import com.streamvault.domain.repository.StremioAddonRepository
import com.streamvault.domain.repository.StremioPlaybackHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant

internal data class ViewingCard(val addonId: Long, val history: StremioPlaybackHistory,
    val preview: StremioMetaPreview, val dismissKey: String = history.contentKey,
    val upNext: Boolean = false, val newEpisodes: Int = 0)
internal data class ReleaseCard(val addonId: Long, val meta: StremioMetaPreview, val video: StremioVideo)
internal data class ViewingState(val watching: List<ViewingCard> = emptyList(),
    val history: List<ViewingCard> = emptyList(), val releases: List<ReleaseCard> = emptyList(),
    val loading: Boolean = false, val failed: Boolean = false)

@HiltViewModel
class StremioViewingViewModel @Inject constructor(
    private val addons: StremioAddonRepository,
    private val histories: StremioPlaybackHistoryRepository,
    private val access: StremioContentAccess,
    private val parental: StremioCatalogParentalAccess
) : ViewModel() {
    private val mutableState = MutableStateFlow(ViewingState())
    internal val state = mutableState.asStateFlow()
    private val refresh = MutableStateFlow(0L)
    private var lastRefreshAt = System.currentTimeMillis()
    private data class Cached(val at: Long, val meta: StremioMetaDetail)
    private val cache = mutableMapOf<String, Cached>()
    init {
        viewModelScope.launch {
            combine(addons.observeAddons(), histories.observeHistory(), access.state, parental.state, refresh) {
                installed, history, visibility, parent, _ -> Triple(installed, history, visibility.canAccessContent && parent.isReady)
            }.collectLatest { (installed, history, allowed) ->
                if (!allowed) { mutableState.value = ViewingState(); return@collectLatest }
                val available = installed.filter { it.enabled }.associateBy { it.installFingerprint() }
                val immediate = history.filter { it.lastWatchedAt > 0L }.mapNotNull { h -> available[h.catalogOriginKey]?.let { addon ->
                    val preview = StremioMetaPreview(h.parentSeriesId ?: h.exactVideoId,
                        StremioMediaType.fromTypeName(h.mediaType), h.title, poster = h.posterUrl)
                    val gate = parental.state.value.accessFor(StremioCatalogRef(preview.type, preview.id, preview.name))
                    ViewingCard(addon.id, h, preview).takeIf { !gate.hidden && !gate.locked }
                } }
                mutableState.update { current -> current.copy(loading = true, failed = false,
                    history = immediate.take(500), watching = immediate.distinctBy { it.history.viewingGroupKey() }
                            .filter { !it.history.watched && it.history.resumePositionMs > 0 && it.history.dismissedAtMs == 0L }.take(40)) }
                try {
                    val enabled = installed.filter { it.enabled }.associateBy { it.installFingerprint() }
                    val now = Instant.now()
                    val limiter = Semaphore(4)
                    val latest = latestStremioItems(history.filter { it.lastWatchedAt > 0L })
                    val targets = buildList {
                        latest.take(100).forEach { h -> enabled[h.catalogOriginKey]?.let { addon ->
                            add(Triple(addon, StremioMediaType.fromTypeName(h.mediaType), h.parentSeriesId ?: h.exactVideoId))
                        } }
                        enabled.values.forEach { addon -> addon.catalogSettings.filterValues { it }.keys
                            .mapNotNull(::decodeStremioLibraryKey).filter { it.first == StremioMediaType.SERIES }.take(100)
                            .forEach { (type, id) -> add(Triple(addon, type, id)) } }
                    }.distinctBy { (a,t,id) -> "${a.id}:${t.typeName}:$id" }.take(100)
                    val metas = coroutineScope { targets.map { (addon,type,id) -> async {
                        val key = "${addon.installFingerprint()}:${type.typeName}:$id"
                        val cached = cache[key]?.takeIf { System.currentTimeMillis() - it.at < 10 * 60_000 }
                        val meta = cached?.meta ?: limiter.withPermit {
                            withTimeoutOrNull(6_000L) {
                                val resolved = addons.resolveMeta(addon.id, type, id)
                                resolved.result?.resource
                            }
                        }?.also { cache[key] = Cached(System.currentTimeMillis(), it) }
                        key to (meta ?: cached?.meta)
                    } }.awaitAll().toMap() }
                    cache.keys.retainAll(metas.keys)
                    fun metaFor(addon: StremioAddon, type: StremioMediaType, id: String) =
                        metas["${addon.installFingerprint()}:${type.typeName}:$id"]
                    fun visible(preview: StremioMetaPreview): Boolean {
                        val parent = parental.state.value
                        val ref = StremioCatalogRef(preview.type, preview.id, preview.name)
                        val category = parent.accessFor(ref)
                        return !category.hidden && !category.locked && preview.genres.none {
                            val genre = parent.accessForGenre(ref, it); genre.hidden || genre.locked }
                    }
                    fun card(h: StremioPlaybackHistory): ViewingCard? {
                        val addon = enabled[h.catalogOriginKey] ?: return null
                        val type = StremioMediaType.fromTypeName(h.mediaType)
                        val meta = metaFor(addon, type, h.parentSeriesId ?: h.exactVideoId)
                        val preview = meta?.toPreview() ?: StremioMetaPreview(h.parentSeriesId ?: h.exactVideoId, type, h.title, poster = h.posterUrl)
                        return ViewingCard(addon.id, h, preview).takeIf { visible(preview) }
                    }
                    val watching = latest.mapNotNull { h ->
                        val row = card(h) ?: return@mapNotNull null
                        if (h.dismissedAtMs > 0L) return@mapNotNull null
                        if (!h.watched && h.resumePositionMs > 0L) return@mapNotNull row
                        if (!h.watched || h.parentSeriesId == null) return@mapNotNull null
                        val addon = enabled[h.catalogOriginKey] ?: return@mapNotNull null
                        val meta = metaFor(addon, StremioMediaType.fromTypeName(h.mediaType), h.parentSeriesId ?: return@mapNotNull null) ?: return@mapNotNull null
                        val groupHistory = history.filter { it.viewingGroupKey() == h.viewingGroupKey() }
                        val watchedIds = meta.videos.filter { video ->
                            groupHistory.firstOrNull { it.exactVideoId == video.id }?.watched
                                ?: (addon.catalogSettings[stremioWatchedKey(meta.type, video.id)] == true)
                        }.map { it.id }.toSet()
                        val next = nextUnwatchedStremioVideo(meta, h.exactVideoId, watchedIds, now)
                        if (next == null) return@mapNotNull null
                        val key = stremioPlaybackContentKey(h.mediaType, next.id, h.parentSeriesId, h.catalogOriginKey) ?: return@mapNotNull null
                        row.copy(history = h.copy(contentKey = key, exactVideoId = next.id,
                            title = "${meta.name} · ${next.title ?: next.id}", watched = false, resumePositionMs = 0, totalDurationMs = 0), upNext = true)
                    }
                    val releases = mutableListOf<ReleaseCard>()
                    val counts = mutableMapOf<String, Int>()
                    for ((addon,type,id) in targets) {
                        if (type != StremioMediaType.SERIES || addon.catalogSettings[stremioLibraryKey(type,id)] != true) continue
                        if (addon.catalogSettings[stremioNotificationsKey(type,id)] == false) continue
                        val meta = metaFor(addon,type,id) ?: continue
                        if (!visible(meta.toPreview())) continue
                        val last = latest.firstOrNull { it.catalogOriginKey == addon.installFingerprint() && it.parentSeriesId == id }
                        val baseline = last?.lastWatchedAt ?: stremioFollowedAt(addon.catalogSettings,type,id) ?: now.toEpochMilli()
                        val recent = meta.videos.count { v -> v.releaseInstant()?.let { it.toEpochMilli() > baseline && it <= now } == true &&
                            history.none { h -> h.catalogOriginKey == addon.installFingerprint() && h.exactVideoId == v.id && h.watched } }
                        last?.let { counts[it.viewingGroupKey()] = recent }
                        meta.videos.filter { v -> v.releaseInstant()?.let { it > now && it < now.plusSeconds(90 * 86400L) } == true }
                            .forEach { releases += ReleaseCard(addon.id, meta.toPreview(), it) }
                    }
                    mutableState.value = ViewingState(watching = watching.take(40).map { it.copy(newEpisodes = counts[it.history.viewingGroupKey()] ?: 0) },
                        history = history.filter { it.lastWatchedAt > 0L }.mapNotNull(::card).take(500), releases = releases.sortedBy { it.video.releaseInstant() }.take(200))
                } catch (cancel: CancellationException) { throw cancel
                } catch (_: Exception) { mutableState.update { it.copy(loading = false, failed = true) } }
            }
        }
    }
    fun refresh() { lastRefreshAt = System.currentTimeMillis(); cache.clear(); refresh.value++ }
    fun refreshIfStale() { if (System.currentTimeMillis() - lastRefreshAt >= 10 * 60_000) refresh() }
    internal fun dismiss(row: ViewingCard) = action { histories.dismiss(row.dismissKey) }
    internal fun startOver(row: ViewingCard, open: () -> Unit) = action {
        val result = histories.save(row.history.copy(resumePositionMs = 0, watched = false, dismissedAtMs = 0))
        if (result.isSuccess) open()
        result
    }
    private fun action(block: suspend () -> com.streamvault.domain.model.Result<Unit>) {
        viewModelScope.launch {
            try { if (block().isError) mutableState.update { it.copy(failed = true) }
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { mutableState.update { it.copy(failed = true) } }
        }
    }
}
