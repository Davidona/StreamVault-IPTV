package com.streamvault.domain.model.stremio

import java.security.MessageDigest

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

/** Stable, non-secret identity for a configured addon install. */
fun StremioAddon.installFingerprint(): String = MessageDigest.getInstance("SHA-256")
    .digest("stremio-addon-install\u0000$installUrl".toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
