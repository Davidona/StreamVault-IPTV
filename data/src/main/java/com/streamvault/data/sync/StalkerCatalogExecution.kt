package com.streamvault.data.sync

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.StalkerCatalogLoadOrder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal suspend fun <T : Any> executeStalkerCatalogSections(
    sections: List<ContentType>,
    order: StalkerCatalogLoadOrder,
    headsOnly: Boolean,
    isPending: suspend (ContentType) -> Boolean,
    process: suspend (ContentType) -> T?
): List<T> {
    if (headsOnly || order == StalkerCatalogLoadOrder.PARALLEL) return coroutineScope {
        sections.map { section -> async { process(section) } }.awaitAll().filterNotNull()
    }
    val ordered = if (order == StalkerCatalogLoadOrder.SERIES_FIRST) sections.sortedBy {
        if (it == ContentType.SERIES) 0 else 1
    } else sections.sortedBy { if (it == ContentType.MOVIE) 0 else 1 }
    return buildList {
        for (section in ordered) {
            process(section)?.let(::add)
            // Retry waits also keep the other section queued. Interactive browsing is independent.
            if (isPending(section)) break
        }
    }
}
