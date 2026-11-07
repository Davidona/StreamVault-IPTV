package com.streamvault.domain.model

data class Favorite(
    val id: Long = 0,
    val providerId: Long,
    val contentId: Long,
    val contentType: ContentType,
    /** Provider/catalog identity used by backup restore; not persisted in the favorites table. */
    val remoteContentId: String? = null,
    val position: Int = 0,
    val groupId: Long? = null,
    val addedAt: Long = System.currentTimeMillis()
) {
    init {
        require(position >= 0) { "position must be non-negative" }
    }
}

data class VirtualGroup(
    val id: Long = 0,
    val providerId: Long,
    val name: String,
    val iconEmoji: String? = null,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val contentType: ContentType = ContentType.LIVE,
    /** Stable group identity used by shared ordering and portable backups. */
    val identityKey: String = ""
) {
    init {
        require(name.isNotBlank()) { "VirtualGroup name must not be blank" }
        require(position >= 0) { "position must be non-negative" }
    }
}
