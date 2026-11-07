package com.streamvault.domain.model

/** Portable source/category identities, never display names or local database row IDs. */
data class LiveChannelOrderScope(val sourceKey: String, val categoryKey: String) {
    init {
        require(sourceKey.isNotBlank())
        require(categoryKey.isNotBlank())
    }
}

enum class LiveChannelOrderMode {
    USE_DEFAULT, PROVIDER, TITLE_ASC, TITLE_DESC, RECENTLY_WATCHED, MOST_WATCHED, MANUAL
}

data class LiveChannelOrder(
    val mode: LiveChannelOrderMode = LiveChannelOrderMode.USE_DEFAULT,
    val manualKeys: List<String> = emptyList(),
    val revision: Long = 0,
    /** Local complete-catalog token used to reject drafts opened before a refresh. */
    val catalogRevision: String? = null
) {
    init {
        require(revision >= 0)
        require(manualKeys.all { it.isNotBlank() })
        require(manualKeys.distinct().size == manualKeys.size)
    }

    /** Call only with a successful, complete membership snapshot, never a fetched page. */
    fun reconcile(completeProviderKeys: List<String>): LiveChannelOrder {
        require(completeProviderKeys.all { it.isNotBlank() })
        require(completeProviderKeys.distinct().size == completeProviderKeys.size)
        val members = completeProviderKeys.toHashSet()
        val saved = manualKeys.toHashSet()
        return copy(manualKeys = manualKeys.filter { it in members } + completeProviderKeys.filterNot { it in saved })
    }
}
