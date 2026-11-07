package com.streamvault.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.streamvault.domain.model.*
import java.security.MessageDigest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Lives in the existing settings store so sort mode, sequences and revision commit atomically. */
internal class LiveCategoryOrderStore(private val store: DataStore<Preferences>) {
    private data class Keys(
        val mode: Preferences.Key<String>,
        val normal: Preferences.Key<String>,
        val pinned: Preferences.Key<String>,
        val personal: Preferences.Key<String>,
        val pins: Preferences.Key<String>,
        val revision: Preferences.Key<Long>
    )

    fun observe(providerId: Long) = store.data.map { read(it, providerKeys(providerId)) }.distinctUntilChanged()

    fun observeCombined(profileIdentity: String) =
        store.data.map { read(it, combinedKeys(it[redirectKey(profileIdentity)] ?: profileIdentity)) }.distinctUntilChanged()

    /** Retain the old copy so restarting between store and Room writes is safe. */
    suspend fun prepareCombinedIdentityMigration(oldIdentity: String): String {
        require(oldIdentity.isNotBlank())
        val old = combinedKeys(oldIdentity)
        var result: String? = null
        store.edit { values ->
            values[redirectKey(oldIdentity)]?.let { existing -> result = existing; return@edit }
            val newIdentity = java.util.UUID.randomUUID().toString()
            val target = combinedKeys(newIdentity)
            val stringPairs = listOf(old.mode to target.mode, old.normal to target.normal,
                old.pinned to target.pinned, old.personal to target.personal, old.pins to target.pins)
            stringPairs.forEach { (source, destination) ->
                val sourceValue = values[source]
                require(values[destination] == null || values[destination] == sourceValue) { "Destination category order already exists" }
                if (sourceValue != null) values[destination] = sourceValue
            }
            val sourceRevision = values[old.revision]
            require(values[target.revision] == null || values[target.revision] == sourceRevision) { "Destination category revision already exists" }
            if (sourceRevision != null) values[target.revision] = sourceRevision
            values[redirectKey(oldIdentity)] = newIdentity
            result = newIdentity
        }
        return checkNotNull(result)
    }

    suspend fun save(providerId: Long, revision: Long, requested: LiveCategoryOrder, includePins: Boolean = false): SaveLiveCategoryOrderResult {
        require(providerId > 0 && revision >= 0)
        return save(providerKeys(providerId), revision, requested, includePins)
    }

    suspend fun saveCombined(profileIdentity: String, revision: Long, requested: LiveCategoryOrder): SaveLiveCategoryOrderResult {
        require(profileIdentity.isNotBlank() && revision >= 0)
        return save(combinedKeys(profileIdentity), revision, requested, includePins = true, combinedIdentity = profileIdentity)
    }

    private suspend fun save(keys: Keys, revision: Long, requested: LiveCategoryOrder, includePins: Boolean, combinedIdentity: String? = null): SaveLiveCategoryOrderResult {
        val snapshot = requested.copy(normalIds = requested.normalIds.toList(), pinnedIds = requested.pinnedIds.toList(), personalIds = requested.personalIds.toList(), pinnedCategoryIds = requested.pinnedCategoryIds.toSet())
        var result: SaveLiveCategoryOrderResult? = null
        store.edit { values ->
            val keys = combinedIdentity?.let { combinedKeys(values[redirectKey(it)] ?: it) } ?: keys
            val current = read(values, keys)
            if (current.revision != revision) result = SaveLiveCategoryOrderResult.Conflict(current)
            else if (current.copy(revision = 0) == snapshot.copy(revision = 0, pinnedCategoryIds = if (includePins) snapshot.pinnedCategoryIds else current.pinnedCategoryIds)) result = SaveLiveCategoryOrderResult.Saved(current)
            else {
                val saved = snapshot.copy(revision = Math.addExact(current.revision, 1), pinnedCategoryIds = if (includePins) snapshot.pinnedCategoryIds else current.pinnedCategoryIds)
                values[keys.mode] = saved.sortMode.name
                values[keys.normal] = saved.normalIds.joinToString(",")
                values[keys.pinned] = saved.pinnedIds.joinToString(",")
                values[keys.personal] = saved.personalIds.joinToString(",")
                if (includePins) {
                    if (saved.pinnedCategoryIds.isEmpty()) values.remove(keys.pins)
                    else values[keys.pins] = saved.pinnedCategoryIds.sorted().joinToString(",")
                }
                values[keys.revision] = saved.revision
                result = SaveLiveCategoryOrderResult.Saved(saved)
            }
        }
        return checkNotNull(result)
    }

    companion object {
        private fun redirectKey(identity: String) = stringPreferencesKey("combinedLiveCategoryRedirect_$identity")
        fun modeKey(providerId: Long) = stringPreferencesKey("category_sort_${providerId}_LIVE")
        fun revisionKey(providerId: Long) = longPreferencesKey("liveCategoryOrder_${providerId}_revision")
        private fun listKey(providerId: Long, section: String) = stringPreferencesKey("liveCategoryOrder_${providerId}_$section")
        private fun providerKeys(providerId: Long) = Keys(
            mode = modeKey(providerId),
            normal = listKey(providerId, "normal"),
            pinned = listKey(providerId, "pinned"),
            personal = listKey(providerId, "personal"),
            pins = stringPreferencesKey("pinned_categories_${providerId}_LIVE"),
            revision = revisionKey(providerId)
        )
        private fun combinedKeys(profileIdentity: String): Keys {
            require(profileIdentity.isNotBlank())
            val digest = MessageDigest.getInstance("SHA-256").digest(profileIdentity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val prefix = "combinedLiveCategoryOrder_$digest"
            return Keys(
                mode = stringPreferencesKey("${prefix}_mode"),
                normal = stringPreferencesKey("${prefix}_normal"),
                pinned = stringPreferencesKey("${prefix}_pinned"),
                personal = stringPreferencesKey("${prefix}_personal"),
                pins = stringPreferencesKey("${prefix}_pins"),
                revision = longPreferencesKey("${prefix}_revision")
            )
        }
        fun bumpRevision(values: MutablePreferences, providerId: Long) {
            values[revisionKey(providerId)] = Math.addExact(values[revisionKey(providerId)] ?: 0, 1)
        }
        private fun read(values: Preferences, keys: Keys): LiveCategoryOrder {
            fun ids(key: Preferences.Key<String>) = values[key]?.takeIf { it.isNotEmpty() }
                ?.split(',')?.map(String::toLong).orEmpty()
            return LiveCategoryOrder(
                sortMode = values[keys.mode]?.let { CategorySortMode.valueOf(it) } ?: CategorySortMode.DEFAULT,
                normalIds = ids(keys.normal), pinnedIds = ids(keys.pinned), personalIds = ids(keys.personal),
                pinnedCategoryIds = values[keys.pins]?.split(',')?.mapNotNull(String::toLongOrNull)?.toSet().orEmpty(),
                revision = values[keys.revision] ?: 0
            )
        }
    }
}
