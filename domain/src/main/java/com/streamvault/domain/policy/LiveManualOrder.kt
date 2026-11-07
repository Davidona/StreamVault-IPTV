package com.streamvault.domain.policy

/**
 * Applies saved positions before paging or search. Unlisted/new members follow in provider order.
 * The caller supplies stable logical-row identities; duplicate labels are deliberately irrelevant.
 * Does not reconcile persistence: a partial catalog observation must never delete saved positions.
 */
fun <T : Any> applyLiveManualOrder(
    completeProviderItems: List<T>,
    savedKeys: List<String>,
    keyOf: (T) -> String
): List<T> {
    val byKey = LinkedHashMap<String, T>()
    completeProviderItems.forEach { item ->
        val key = keyOf(item)
        require(key.isNotBlank())
        require(!byKey.containsKey(key))
        byKey[key] = item
    }
    val result = ArrayList<T>(byKey.size)
    savedKeys.forEach { key -> byKey.remove(key)?.let(result::add) }
    result.addAll(byKey.values)
    return result
}
