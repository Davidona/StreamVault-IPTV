package com.streamvault.domain.model

import java.util.Locale

/** Stable identity shared by Combined M3U category generation and portable settings backups. */
object CombinedCategoryIdentity {
    private const val ID_BASE = -2_000_000_000L

    fun keyForName(name: String): String =
        name.trim().lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ")

    fun idForKey(key: String): Long = ID_BASE - kotlin.math.abs(key.hashCode().toLong())

    fun idForName(name: String): Long = idForKey(keyForName(name))
}
