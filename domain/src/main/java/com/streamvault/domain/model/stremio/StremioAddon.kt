package com.streamvault.domain.model.stremio

data class StremioAddon(
    val id: Long = 0L,
    val installUrl: String,
    val manifestId: String,
    val name: String,
    val version: String,
    val manifestJson: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val catalogSettings: Map<String, Boolean> = emptyMap(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    init {
        require(installUrl.isNotBlank()) { "installUrl must not be blank" }
        require(manifestId.isNotBlank()) { "manifestId must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
    }

    fun isCatalogEnabled(catalogKey: String): Boolean = catalogSettings[catalogKey] ?: true
}
