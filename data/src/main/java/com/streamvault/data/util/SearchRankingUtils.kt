package com.streamvault.data.util

import com.streamvault.domain.util.SearchTextNormalizer

internal fun <T> List<T>.rankSearchResults(rawQuery: String, nameSelector: (T) -> String): List<T> {
    val normalizedQuery = SearchTextNormalizer.normalize(rawQuery.trim())
    if (normalizedQuery.isBlank()) return this

    val queryTokens = normalizedQuery
        .split(Regex("\\s+"))
        .map { it.replace(Regex("[^\\p{L}\\p{M}\\p{N}_]"), "") }
        .filter { it.length >= 2 }

    if (queryTokens.isEmpty()) return this

    return sortedWith(
        compareBy<T> { item ->
            val normalizedName = SearchTextNormalizer.normalize(nameSelector(item).trim())
            relevanceBucket(normalizedName, normalizedQuery, queryTokens)
        }.thenBy { item ->
            val normalizedName = SearchTextNormalizer.normalize(nameSelector(item).trim())
            firstMatchPosition(normalizedName, queryTokens)
        }.thenBy { item ->
            nameSelector(item).length
        }.thenBy { item ->
            SearchTextNormalizer.normalize(nameSelector(item))
        }
    )
}

private fun relevanceBucket(normalizedName: String, normalizedQuery: String, queryTokens: List<String>): Int {
    return when {
        normalizedName == normalizedQuery -> 0
        normalizedName.startsWith(normalizedQuery) -> 1
        queryTokens.all { token -> normalizedName.startsWith(token) || normalizedName.contains(" $token") } -> 2
        queryTokens.any { token -> normalizedName.contains(token) } -> 3
        else -> 4
    }
}

private fun firstMatchPosition(normalizedName: String, queryTokens: List<String>): Int {
    return queryTokens.minOfOrNull { token ->
        normalizedName.indexOf(token).takeIf { it >= 0 } ?: Int.MAX_VALUE
    } ?: Int.MAX_VALUE
}
