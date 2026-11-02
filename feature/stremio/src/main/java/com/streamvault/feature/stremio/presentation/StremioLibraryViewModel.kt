package com.streamvault.feature.stremio.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.stremio.*
import com.streamvault.domain.repository.StremioAddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class StremioLibraryRow(val addonId: Long, val preview: StremioMetaPreview, val lastWatchedAt: Long = 0, val watched: Boolean = false, val notifications: Boolean = true)
@HiltViewModel
class StremioLibraryViewModel @Inject constructor(
    private val repository: StremioAddonRepository,
    historyRepository: com.streamvault.domain.repository.StremioPlaybackHistoryRepository,
    contentAccess: StremioContentAccess,
    parental: StremioCatalogParentalAccess
) : ViewModel() {
    private val rows = MutableStateFlow<List<StremioLibraryRow>>(emptyList())
    val state = rows.asStateFlow()
    val loading = MutableStateFlow(false)
    init {
        viewModelScope.launch {
            combine(repository.observeAddons(), historyRepository.observeHistory(), contentAccess.state, parental.state) {
                a,h,visibility,parent -> Triple(a,h,visibility.canAccessContent && parent.isReady)
            }.collectLatest { (addons, history, allowed) ->
                if (!allowed) { rows.value = emptyList(); loading.value = false; return@collectLatest }
                loading.value = true
                try {
                    val semaphore = Semaphore(4)
                    rows.value = coroutineScope {
                        addons.filter { it.enabled }.flatMap { addon ->
                            addon.catalogSettings.filterValues { it }.keys.mapNotNull { key ->
                                decodeStremioLibraryKey(key)?.let { (type, id) ->
                                    async {
                                        semaphore.withPermit {
                                            val metadata = try {
                                                repository.resolveMeta(addon.id, type, id).result?.resource
                                            } catch (cancel: CancellationException) { throw cancel
                                            } catch (_: Exception) { null }
                                            val records = history.filter {
                                                (it.parentSeriesId == id || it.exactVideoId == id) &&
                                                stremioPlaybackContentKey(type.typeName, it.exactVideoId, id, addon.installFingerprint()) == it.contentKey
                                            }
                                            val preview = metadata?.toPreview()
                                            val released = metadata?.videos.orEmpty().filter { it.releaseInstant()?.isAfter(java.time.Instant.now()) != true }
                                            val watched = if (type == StremioMediaType.SERIES && released.isNotEmpty()) released.all { video ->
                                                val key = stremioPlaybackContentKey(type.typeName,video.id,id,addon.installFingerprint())
                                                history.firstOrNull { it.contentKey == key }?.watched
                                                    ?: (addon.catalogSettings[stremioWatchedKey(type,video.id)] == true)
                                            } else records.any { it.watched }
                                            StremioLibraryRow(addon.id, preview ?: StremioMetaPreview(id, type, id),
                                                records.maxOfOrNull { it.lastWatchedAt } ?: 0L,
                                                watched,
                                                addon.catalogSettings[stremioNotificationsKey(type,id)] != false)
                                        }
                                    }
                                }
                            }
                        }.awaitAll().filter { row ->
                            val ref = StremioCatalogRef(row.preview.type, row.preview.id, row.preview.name)
                            val parent = parental.state.value
                            val gate = parent.accessFor(ref)
                            !gate.hidden && !gate.locked && row.preview.genres.none { genre ->
                                parent.accessForGenre(ref,genre).let { it.hidden || it.locked }
                            }
                        }
                    }
                } finally { loading.value = false }
            }
        }
    }
    fun toggleNotifications(row: StremioLibraryRow) {
        viewModelScope.launch { repository.setCatalogEnabled(row.addonId,
            stremioNotificationsKey(row.preview.type,row.preview.id), !row.notifications) }
    }
}
