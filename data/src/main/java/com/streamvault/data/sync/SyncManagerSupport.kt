package com.streamvault.data.sync

import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.util.AdultContentClassifier
import com.streamvault.domain.model.ContentType
import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest

internal data class SyncOutcome(
    val partial: Boolean = false,
    val warnings: List<String> = emptyList(),
    /** Number of catalog rows accepted by the staging/activation path during this execution. */
    val stagedMutations: Int = 0,
    /** Durable work that was handed off before this plan returned. */
    val continuationWork: List<SyncContinuation> = emptyList(),
    /** What happened to the active catalog; executors commit before returning this receipt. */
    val activation: SyncActivation = SyncActivation.NO_CATALOG_CHANGE
)

internal data class SyncContinuation(
    val operation: SyncContinuationOperation,
    val section: ContentType? = null,
    val reason: String,
    val force: Boolean = false
)

internal enum class SyncContinuationOperation {
    INDEX_CATALOG,
    REFRESH_GUIDE,
    /** Re-runs the full provider sync in background to complete a bootstrap-scoped initial sync. */
    FULL_CATALOG
}

internal enum class SyncActivation {
    /** A staged snapshot (or safe staged subset) was promoted before the executor returned. */
    ACTIVATED_CATALOG,
    /** The operation completed successfully without changing catalog activation. */
    NO_CATALOG_CHANGE,
    /** Existing active rows were deliberately retained because a replacement was not safe. */
    PRESERVED_ACTIVE_CATALOG,
    /** No replacement was activated; declared durable work must finish the requested section. */
    DEFERRED_TO_FOLLOW_UP
}

internal object SyncActivationPolicy {
    fun validate(outcome: SyncOutcome): SyncOutcome {
        require(outcome.stagedMutations >= 0) {
            "stagedMutations must be non-negative"
        }
        if (outcome.activation == SyncActivation.DEFERRED_TO_FOLLOW_UP) {
            require(outcome.continuationWork.isNotEmpty()) {
                "DEFERRED_TO_FOLLOW_UP outcomes must declare continuation work"
            }
        }
        if (outcome.stagedMutations > 0) {
            require(outcome.activation == SyncActivation.ACTIVATED_CATALOG) {
                "staged mutations must be reported as an activated catalog"
            }
        }
        return outcome
    }
}

internal val SyncOutcome.requiresPartialActivation: Boolean
    get() = partial ||
        activation == SyncActivation.PRESERVED_ACTIVE_CATALOG ||
        activation == SyncActivation.DEFERRED_TO_FOLLOW_UP

internal sealed interface CatalogStrategyResult<out T> {
    val strategyName: String

    data class Success<T>(
        override val strategyName: String,
        val items: List<T>,
        val warnings: List<String> = emptyList()
    ) : CatalogStrategyResult<T>

    data class Partial<T>(
        override val strategyName: String,
        val items: List<T>,
        val warnings: List<String>
    ) : CatalogStrategyResult<T>

    data class Failure(
        override val strategyName: String,
        val error: Throwable,
        val warnings: List<String> = emptyList()
    ) : CatalogStrategyResult<Nothing>

    data class EmptyValid(
        override val strategyName: String,
        val warnings: List<String> = emptyList()
    ) : CatalogStrategyResult<Nothing>
}

internal data class XtreamStrategyFeedback(
    val preferredSegmentedFirst: Boolean = false,
    val attemptedFullCatalog: Boolean = false,
    val fullCatalogUnsafe: Boolean = false,
    val segmentedStressDetected: Boolean = false
)

internal data class CatalogSyncPayload<T>(
    val catalogResult: CatalogStrategyResult<T>,
    val categories: List<CategoryEntity>?,
    val warnings: List<String> = emptyList(),
    val strategyFeedback: XtreamStrategyFeedback = XtreamStrategyFeedback(),
    val stagedSessionId: Long? = null,
    val stagedAcceptedCount: Int = 0
)

internal data class LiveCatalogSnapshot(
    val categories: List<CategoryEntity>?,
    val channels: List<ChannelEntity>
)

internal data class SequentialProviderAdaptation(
    val rememberSequential: Boolean,
    val healthyStreak: Int
)

internal sealed interface Attempt<out T> {
    data class Success<T>(val value: T) : Attempt<T>
    data class Failure(val error: Exception) : Attempt<Nothing>
}

internal sealed interface CategoryFetchOutcome<out T> {
    data class Success<T>(
        val categoryName: String,
        val items: List<T>,
        val rawCount: Int = items.size
    ) : CategoryFetchOutcome<T>
    data class Empty(val categoryName: String) : CategoryFetchOutcome<Nothing>
    data class Failure(val categoryName: String, val error: Throwable) : CategoryFetchOutcome<Nothing>
}

internal data class TimedCategoryOutcome<T>(
    val category: XtreamCategory,
    val outcome: CategoryFetchOutcome<T>,
    val elapsedMs: Long
)

internal data class CategoryExecutionPlan<T>(
    val outcomes: List<TimedCategoryOutcome<T>>,
    val warnings: List<String> = emptyList()
)

internal data class M3uImportStats(
    val header: M3uParser.M3uHeader,
    val liveCount: Int,
    val movieCount: Int,
    val warnings: List<String> = emptyList()
)

internal data class StreamedPlaylist(
    val inputStream: InputStream,
    val contentEncoding: String? = null,
    val contentLength: Long? = null,
    val sourceName: String? = null,
    val declaredCharset: Charset? = null
)

internal class FallbackCategoryCollector(
    private val providerId: Long,
    private val type: ContentType
) {
    private val categories = LinkedHashMap<Long, CategoryEntity>()

    fun record(categoryId: Long?, categoryName: String?, isAdult: Boolean) {
        val resolvedCategoryId = categoryId ?: return
        val resolvedCategoryName = categoryName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "Category $resolvedCategoryId"
        val candidate = CategoryEntity(
            categoryId = resolvedCategoryId,
            name = resolvedCategoryName,
            parentId = 0,
            type = type,
            providerId = providerId,
            isAdult = isAdult || AdultContentClassifier.isAdultCategoryName(resolvedCategoryName)
        )
        val existing = categories[resolvedCategoryId]
        categories[resolvedCategoryId] = if (existing == null) {
            candidate
        } else {
            existing.copy(
                name = preferredCategoryName(existing.name, candidate.name, resolvedCategoryId),
                isAdult = existing.isAdult || candidate.isAdult,
                isUserProtected = existing.isUserProtected || candidate.isUserProtected
            )
        }
    }

    fun entities(): List<CategoryEntity> = categories.values.toList()

    private fun preferredCategoryName(
        currentName: String,
        candidateName: String,
        categoryId: Long
    ): String {
        val placeholderName = "Category $categoryId"
        return when {
            currentName == placeholderName && candidateName != placeholderName -> candidateName
            currentName != placeholderName -> currentName
            else -> candidateName
        }
    }
}

internal data class StagedCatalogSnapshot(
    val sessionId: Long?,
    val acceptedCount: Int,
    val fallbackCategories: List<CategoryEntity>?
)

internal class StableLongHasher {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun hash(input: String): Long {
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return result and Long.MAX_VALUE
    }
}

internal class CategoryAccumulator(
    private val providerId: Long,
    private val type: ContentType,
    private val hasher: StableLongHasher
) {
    private val categoryIds = LinkedHashMap<String, Long>()
    val count: Int
        get() = categoryIds.size

    fun idFor(name: String): Long {
        return categoryIds.getOrPut(name) { stableId(providerId, type, name, hasher) }
    }

    fun register(name: String, categoryId: Long) {
        categoryIds.putIfAbsent(name, categoryId)
    }

    fun entities(): List<CategoryEntity> {
        return categoryIds.map { (name, id) ->
            CategoryEntity(
                categoryId = id,
                name = name,
                parentId = 0,
                type = type,
                providerId = providerId,
                isAdult = AdultContentClassifier.isAdultCategoryName(name)
            )
        }
    }

    private fun stableId(
        providerId: Long,
        type: ContentType,
        name: String,
        hasher: StableLongHasher
    ): Long {
        return hasher.hash("$providerId/${type.name}/$name").coerceAtLeast(1L)
    }
}
