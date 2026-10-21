package com.streamvault.data.sync

import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.remote.stalker.StalkerPagedResult
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerVodCatalogItem
import com.streamvault.data.remote.stalker.StalkerTrafficCoordinator
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.VodCatalogItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

internal object StalkerCatalogPriority {
    private val categories = ConcurrentHashMap<Pair<Long, ContentType>, Long>()
    fun select(providerId: Long, type: ContentType, categoryId: Long) { categories[providerId to type] = categoryId }
    fun selected(providerId: Long, type: ContentType): Long? = categories[providerId to type]
}

/** A concurrent category-head sweep, followed by resumable ordered page windows per category. */
internal class StalkerPagedCatalogImporter(
    private val operations: StalkerIncrementalIndexOperations,
    private val concurrency: Int = 8
) {
    private data class Task(val category: CategoryEntity, val page: Int)
    private data class Reply(val task: Task, val result: Result<StalkerPagedResult<out Any>>)

    suspend fun prepare(
        provider: Provider,
        api: StalkerProvider,
        type: ContentType,
        freshSource: Boolean,
        onProgress: ((String) -> Unit)?
    ) {
        run(provider, api, type, headsOnly = true, onProgress = onProgress)
        val categories = categories(provider, api, type)
        val receipts = categories.mapNotNull { operations.getHydration(provider.id, type, it.categoryId) }
        val deferredPages = receipts.sumOf { (it.totalPages - it.lastSuccessfulPage).coerceAtLeast(0) }
        val pageSize = receipts.maxOfOrNull { it.pageSize } ?: 0
        val defer = freshSource && pageSize in 1..50 && deferredPages >= 200
        operations.log("Stalker ${type.name} catalog plan provider=${provider.id} categories=${categories.size} pageSize=$pageSize remainingPages=$deferredPages deferred=$defer")
        if (!defer) run(provider, api, type, onProgress = onProgress)
    }

    private suspend fun categories(provider: Provider, api: StalkerProvider, type: ContentType): List<CategoryEntity> {
        val all = operations.allVisibleCategories(provider.id, type)
        val visible = operations.visibleCategories(type, all, api)
        val concrete = visible.filterNot { api.isWildcardCategory(it.type, it.categoryId) }
        return concrete.ifEmpty { visible }
    }

    suspend fun run(
        provider: Provider, api: StalkerProvider, type: ContentType,
        headsOnly: Boolean = false, maxPages: Int? = null,
        onProgress: ((String) -> Unit)? = null
    ) = Import(provider, api, type, headsOnly, maxPages).execute()

    private inner class Import(
        private val provider: Provider,
        private val api: StalkerProvider,
        private val type: ContentType,
        private val headsOnly: Boolean,
        private val maxPages: Int?
    ) {
        private val started = System.currentTimeMillis()
        private lateinit var categories: List<CategoryEntity>
        private val checkpoints = mutableMapOf<Long, StalkerHydrationSnapshot?>()
        private val buffers = mutableMapOf<Long, java.util.TreeMap<Int, Reply>>()
        private val blocked = mutableSetOf<Long>()
        private var committed = 0
        private var rows = 0
        private var restoreProgress = false
        private var nextPublication = 256
        private var initialCounts: Map<Long, Int> = emptyMap()

        private suspend fun accept(reply: Reply): Int {
            val id = reply.task.category.categoryId
            if (id in blocked) return 1
            var consumed = 0
            val buffer = buffers.getOrPut(id) { java.util.TreeMap() }
            buffer[reply.task.page] = reply
            while (true) {
                val previous = checkpoints[id]
                val next = ((previous?.lastSuccessfulPage ?: 0) + 1).coerceAtLeast(1)
                val ready = buffer.remove(next) ?: break
                consumed++
                val result = ready.result
                val now = System.currentTimeMillis()
                val payload = (result as? Result.Success)?.data
                val fingerprint = payload?.let { StalkerIndexPolicy.pageFingerprint(it.items, type) }
                val anomaly = payload?.let { StalkerIndexPolicy.detectPageAnomaly(previous, next, it, fingerprint) }
                if (payload == null || anomaly != null) {
                    val error = (result as? Result.Error)
                    operations.markAttemptFailed(provider.id, type, id, previous, next, now,
                        anomaly ?: error?.message ?: "Unexpected loading state",
                        error?.exception?.let { operations.failureState(it) != "FAILED_PERMANENT" } ?: true,
                        previous?.lastPageFingerprint)
                    blocked += id
                    consumed += buffer.size
                    buffer.clear()
                    checkpoints[id] = operations.getHydration(provider.id, type, id)
                    break
                }
                val items = StalkerIndexPolicy.dedupePageItems(payload.items, type)
                store(provider.id, type, items, now)
                operations.commitCatalogPage(provider, id, payload)
                if (type == ContentType.MOVIE && items.isNotEmpty()) restoreProgress = true
                val unchanged = provider.catalogLayout != com.streamvault.domain.model.CatalogLayout.UNIFIED_VOD &&
                    next == 1 && (initialCounts[id] ?: 0) > 0 && initialCounts[id] == payload.advertisedTotalItems
                if (unchanged) operations.preserveCategoryRows(provider.id, type, id)
                val complete = payload.isComplete || unchanged
                val receiptPage = if (unchanged) payload.totalPages.coerceAtLeast(1) else next
                val count = if (unchanged) initialCounts.getValue(id) else (previous?.itemCount ?: 0) + items.size
                operations.markAttemptSucceeded(provider.id, type, id, previous, receiptPage, now, count,
                    payload.totalPages, payload.pageSize, payload.advertisedTotalItems, payload.advertisedTotalPages,
                    complete, payload.isTruncated, payload.terminationReason, fingerprint)
                checkpoints[id] = StalkerHydrationSnapshot(now, count, if (payload.isTruncated) "TRUNCATED" else "SUCCESS",
                    payload.terminationReason, receiptPage, receiptPage, receiptPage, payload.totalPages, payload.advertisedTotalItems,
                    payload.advertisedTotalPages, complete, payload.pageSize, 0, 0, StalkerIndexCheckpointStore.STALKER_CATEGORY_RETRY_BUDGET, fingerprint)
                committed++
                rows += items.size
                if (complete || payload.isTruncated) {
                    blocked += id
                    consumed += buffer.size
                    buffer.clear()
                    break
                }
            }
            if (rows >= nextPublication) {
                publish(provider.id, type, "RUNNING", categories, checkpoints, System.currentTimeMillis())
                nextPublication = rows + 256
            }
            return consumed
        }

        private suspend fun fetch(task: Task): Reply {
            var result: Result<StalkerPagedResult<out Any>> = Result.Loading
            for (attempt in 0..2) {
                result = try {
                    operations.fetchSummaryPage(provider, api, type, task.category.categoryId, task.page)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.error(error.message ?: error.javaClass.simpleName, error) }
                val failure = result as? Result.Error ?: break
                operations.recordRequestFailure(provider.id, failure.exception)
                if (attempt == 2 || failure.exception?.let { operations.failureState(it) == "FAILED_PERMANENT" } == true) break
                delay(750L * (attempt + 1))
            }
            return Reply(task, result)
        }

        private suspend fun drain(tasks: List<Task>) = coroutineScope {
            if (tasks.isEmpty()) return@coroutineScope
            val requests = Channel<Task>(concurrency.coerceIn(1, 8))
            val replies = Channel<Reply>(concurrency.coerceIn(1, 8))
            // A slow early page cannot make the reorder buffer grow with the entire catalog.
            val credits = Semaphore(concurrency.coerceIn(1, 8) * 2)
            launch {
                val queue = ArrayDeque(tasks)
                var previousPriority: Long? = null
                try {
                    while (queue.isNotEmpty()) {
                        credits.acquire()
                        val priority = StalkerCatalogPriority.selected(provider.id, type)
                        if (priority != null && priority != previousPriority) {
                            val preferred = queue.filter { it.category.categoryId == priority }
                            queue.removeAll(preferred.toSet())
                            preferred.asReversed().forEach { queue.addFirst(it) }
                            previousPriority = priority
                        }
                        requests.send(queue.removeFirst())
                    }
                } finally { requests.close() }
            }
            val workers = List(concurrency.coerceIn(1, 8)) {
                launch { for (task in requests) replies.send(fetch(task)) }
            }
            launch { workers.forEach { it.join() }; replies.close() }
            for (reply in replies) repeat(accept(reply)) { credits.release() }
        }

        private suspend fun drainBackfill() {
            val queue = ArrayDeque(categories.filter { category ->
                category.categoryId !in blocked &&
                    StalkerIndexPolicy.canAttempt(checkpoints[category.categoryId], System.currentTimeMillis())
            })
            while (queue.isNotEmpty()) {
                val priority = StalkerCatalogPriority.selected(provider.id, type)
                queue.firstOrNull { it.categoryId == priority }?.let { queue.remove(it); queue.addFirst(it) }
                val category = queue.removeFirst()
                while (category.categoryId !in blocked) {
                    if (StalkerTrafficCoordinator.shouldDeferIndexForPlayback(provider.id)) return
                    val h = checkpoints[category.categoryId] ?: break
                    if (h.isComplete || h.isTruncated || h.isTerminalFailure) break
                    val next = h.lastSuccessfulPage + 1
                    val last = h.totalPages.coerceAtLeast(next)
                    val remaining = maxPages?.minus(committed) ?: Int.MAX_VALUE
                    if (remaining <= 0) return
                    val size = minOf(concurrency.coerceIn(1, 8), remaining, last - next + 1)
                    val replies = coroutineScope {
                        (next until next + size).map { page -> async { fetch(Task(category, page)) } }.awaitAll()
                    }
                    // Commit only the contiguous prefix. A hole leaves the cursor at that page
                    // and the next pass retries it before any later page is made visible.
                    for (reply in replies) {
                        accept(reply)
                        if (category.categoryId in blocked) break
                    }
                }
            }
        }

        suspend fun execute() {
            categories = categories(provider, api, type)
            initialCounts = operations.categoriesWithLocalRows(provider.id, type)
            for (category in categories) checkpoints[category.categoryId] = operations.getHydration(provider.id, type, category.categoryId)
            publish(provider.id, type, "RUNNING", categories, checkpoints, started)
            try {
                // A paginated all-category response must never turn into a second full catalog walk.
                val all = operations.allVisibleCategories(provider.id, type)
                val wildcard = all.firstOrNull { api.isWildcardCategory(it.type, it.categoryId) }
                if (wildcard != null && categories.none { it.categoryId == wildcard.categoryId } &&
                    checkpoints.values.all { (it?.lastSuccessfulPage ?: 0) == 0 } &&
                    operations.getHydration(provider.id, type, wildcard.categoryId) == null) {
                    val probe = operations.fetchWildcardPage(provider, api, type, wildcard.categoryId, 1)
                    val page = (probe as? Result.Success)?.data
                    val whole = page != null && !page.isTruncated && page.items.isNotEmpty() &&
                        page.advertisedTotalItems?.let { it > 0 && page.items.size >= it } == true
                    api.recordWildcardCapability(type, whole)
                    if (whole) {
                        val fullPage = requireNotNull(page)
                        val visible = StalkerIndexPolicy.filterToVisibleCategories(fullPage.items, type, categories.map { it.categoryId }.toSet())
                        val now = System.currentTimeMillis()
                        store(provider.id, type, visible, now)
                        restoreProgress = type == ContentType.MOVIE
                        for (category in categories) {
                            val categoryItems = StalkerIndexPolicy.filterToVisibleCategories(visible, type, setOf(category.categoryId))
                            operations.commitCatalogPage(provider, category.categoryId,
                                StalkerPagedResult(items = categoryItems, page = 1, totalPages = 1, pageSize = fullPage.pageSize,
                                    advertisedTotalItems = categoryItems.size, advertisedTotalPages = 1))
                            operations.markAttemptSucceeded(provider.id, type, category.categoryId, null, 1, now, categoryItems.size,
                                1, fullPage.pageSize, categoryItems.size, 1, true, false, null, null)
                            checkpoints[category.categoryId] = operations.getHydration(provider.id, type, category.categoryId)
                        }
                        operations.pruneStaleRows(provider.id, type)
                        publish(provider.id, type, "SUCCESS", categories, checkpoints, now)
                        return
                    }
                    operations.markAttemptSucceeded(provider.id, type, wildcard.categoryId, null, 1, System.currentTimeMillis(),
                        0, page?.totalPages ?: 1, page?.pageSize ?: 0, page?.advertisedTotalItems,
                        page?.advertisedTotalPages, false, false, null, null)
                }
    
                val heads = categories.filter { (checkpoints[it.categoryId]?.lastSuccessfulPage ?: 0) == 0 &&
                    StalkerIndexPolicy.canAttempt(checkpoints[it.categoryId], System.currentTimeMillis()) }
                drain(heads.map { Task(it, 1) })
                publish(provider.id, type, "RUNNING", categories, checkpoints, System.currentTimeMillis())
                if (!headsOnly) {
                    drainBackfill()
                }
                val pending = checkpoints.values.any { it == null || (!it.isComplete && !it.isTruncated && !it.isTerminalFailure) }
                val partial = checkpoints.values.any { it?.hasPruneSuppressionRisk == true || it?.isTerminalFailure == true }
                val state = when { pending -> "QUEUED"; partial -> "PARTIAL"; else -> "SUCCESS" }
                if (state == "SUCCESS") operations.pruneStaleRows(provider.id, type)
                publish(provider.id, type, state, categories, checkpoints, System.currentTimeMillis())
                operations.log("Stalker ${type.name} catalog provider=${provider.id} state=$state pages=$committed rows=$rows elapsedMs=${System.currentTimeMillis() - started}")
            } finally {
                if (restoreProgress) operations.restoreMovieWatchProgress(provider.id)
            }
        }
    }

    private suspend fun store(providerId: Long, type: ContentType, items: List<Any>, now: Long) {
        if (type == ContentType.SERIES) {
            operations.upsertSeriesSummaryBatch(providerId, items.filterIsInstance<Series>(), now)
        } else {
            val classified = items.filterIsInstance<StalkerVodCatalogItem>()
            operations.upsertMovieSummaryBatch(providerId, items.filterIsInstance<Movie>() +
                classified.mapNotNull { (it.item as? VodCatalogItem.MovieItem)?.movie }, now)
            operations.upsertVodDerivedSeriesSummaryBatch(providerId,
                classified.mapNotNull { (it.item as? VodCatalogItem.SeriesItem)?.series }, now)
        }
    }

    private suspend fun publish(
        providerId: Long, type: ContentType, state: String, categories: List<CategoryEntity>,
        checkpoints: Map<Long, StalkerHydrationSnapshot?>, now: Long
    ) {
        val count = operations.currentIndexedRowCount(providerId, type)
        operations.upsertJob(CatalogIndexJobUpdate(providerId, type.name, state, now,
            totalCategories = categories.size, completedCategories = checkpoints.values.count { it?.isComplete == true },
            indexedRows = count, lastAttemptAt = now, lastSuccessAt = now.takeIf { state == "SUCCESS" }))
        operations.updateSummaryMetadata(providerId, type, count, state, now)
    }
}
