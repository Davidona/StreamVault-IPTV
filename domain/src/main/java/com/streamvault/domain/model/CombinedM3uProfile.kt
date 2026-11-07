package com.streamvault.domain.model

data class CombinedM3uProfile(
    val id: Long = 0,
    /** Portable identity used by combined-source ordering; never use the local Room id. */
    val identityKey: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val members: List<CombinedM3uProfileMember> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    init {
        require(name.isNotBlank()) { "Combined profile name must not be blank" }
        require(identityKey.isNotBlank()) { "Combined profile identity must not be blank" }
    }
}

data class CombinedM3uProfileMember(
    val id: Long = 0,
    val profileId: Long,
    val providerId: Long,
    val priority: Int,
    val enabled: Boolean = true,
    val providerName: String = ""
)

sealed interface ActiveLiveSource {
    data class ProviderSource(
        val providerId: Long
    ) : ActiveLiveSource

    data class CombinedM3uSource(
        val profileId: Long
    ) : ActiveLiveSource
}

data class ActiveLiveSourceOption(
    val source: ActiveLiveSource,
    val title: String,
    val subtitle: String? = null,
    val isEnabled: Boolean = true
)

data class CombinedCategoryBinding(
    val providerId: Long,
    val providerName: String,
    val categoryId: Long
)

data class CombinedCategory(
    val category: Category,
    val bindings: List<CombinedCategoryBinding>
)
