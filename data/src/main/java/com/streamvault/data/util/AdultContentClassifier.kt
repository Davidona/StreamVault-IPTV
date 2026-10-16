package com.streamvault.data.util

import com.streamvault.domain.util.AdultContentNameClassifier
import java.text.Normalizer
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

object AdultContentClassifier {
    private const val MAX_CLASSIFICATION_CACHE_SIZE = 4096
    private val diacriticsRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val separatorsRegex = Regex("[^a-z0-9+]+")

    private val classificationCache: MutableMap<String, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > MAX_CLASSIFICATION_CACHE_SIZE
            }
        }
    )

    fun isAdultCategoryName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val normalized = normalize(name)
        synchronized(classificationCache) {
            classificationCache[normalized]?.let { return it }
        }
        val isAdult = AdultContentNameClassifier.isAdultCategoryName(name)
        synchronized(classificationCache) {
            classificationCache[normalized] = isAdult
        }
        return isAdult
    }

    fun adultCategoryIds(namesById: Map<Long, String>): Set<Long> {
        return namesById
            .filterValues(::isAdultCategoryName)
            .keys
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed
            .replace(diacriticsRegex, "")
            .lowercase(Locale.ROOT)
            .replace(separatorsRegex, " ")
            .trim()
    }

    internal fun resetCacheForTesting() {
        synchronized(classificationCache) {
            classificationCache.clear()
        }
    }

    internal fun cacheSizeForTesting(): Int =
        synchronized(classificationCache) { classificationCache.size }

    internal fun isCachedForTesting(name: String): Boolean =
        synchronized(classificationCache) { classificationCache.containsKey(normalize(name)) }
}
