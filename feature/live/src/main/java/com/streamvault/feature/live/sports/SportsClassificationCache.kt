package com.streamvault.feature.live.sports

import com.streamvault.domain.model.Program

/** Content-based LRU; never retains whole programme records, channels or stream URLs. */
internal class SportsClassificationCache(
    private val maxEntries: Int = 8_192,
    private val maxEstimatedBytes: Long = 8L * 1024L * 1024L,
    private val classifier: (Program, SportsProgrammeClassifier.ChannelContext) -> SportsClassification =
        SportsProgrammeClassifier::classify,
) {
    private data class Key(
        val title: String,
        val description: String,
        val genre: String?,
        val category: String?,
        val channelSport: SportType,
        val isSportsChannel: Boolean,
        val groupTitle: String?,
    )

    private data class Entry(val classification: SportsClassification, val estimatedBytes: Long)

    internal data class Stats(val hits: Long, val misses: Long, val entries: Int, val estimatedBytes: Long)

    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private var estimatedBytes = 0L
    private var hits = 0L
    private var misses = 0L

    @Synchronized
    fun classify(program: Program, context: SportsProgrammeClassifier.ChannelContext): SportsClassification {
        val key = Key(
            program.title, program.description, program.genre, program.category,
            context.detectedSport, context.isSportsChannel, context.channel.groupTitle,
        )
        entries[key]?.let { cached ->
            hits++
            return cached.classification
        }
        misses++
        val classification = classifier(program, context)
        // Include key/result characters and a conservative allowance for objects and collections.
        val characters = program.title.length.toLong() + program.description.length +
            (program.genre?.length ?: 0) + (program.category?.length ?: 0) +
            (context.channel.groupTitle?.length ?: 0) + classification.displayTitle.length +
            classification.competitors.sumOf { it.length } + (classification.session?.length ?: 0) +
            (classification.categoryLabel?.length ?: 0)
        val weight = 768L + characters * 2L
        // One unusually large description must not evict an otherwise useful working set.
        if (maxEntries <= 0 || weight > maxEstimatedBytes / 8L) return classification
        entries[key] = Entry(classification, weight)
        estimatedBytes += weight
        val iterator = entries.entries.iterator()
        while (entries.size > maxEntries || estimatedBytes > maxEstimatedBytes) {
            val oldest = iterator.next()
            estimatedBytes -= oldest.value.estimatedBytes
            iterator.remove()
        }
        return classification
    }

    @Synchronized
    fun stats(): Stats = Stats(hits, misses, entries.size, estimatedBytes)

    @Synchronized
    fun clear() {
        entries.clear()
        estimatedBytes = 0L
    }
}
