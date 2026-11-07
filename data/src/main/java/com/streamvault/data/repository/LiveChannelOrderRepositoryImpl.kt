package com.streamvault.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamvault.domain.model.LiveChannelOrder
import com.streamvault.domain.model.LiveChannelOrderMode
import com.streamvault.domain.model.LiveChannelOrderScope
import com.streamvault.domain.repository.LiveChannelOrderRepository
import com.streamvault.domain.repository.LiveChannelOrderSaveResult
import java.util.Base64
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Dedicated store: never mix a partly written sequence with its mode or revision. */
class LiveChannelOrderRepositoryImpl(
    private val store: DataStore<Preferences>
) : LiveChannelOrderRepository {
    /** Copy a source atomically while leaving the original available for interrupted migrations. */
    override suspend fun migrateSourceIdentity(oldSource: String, newSource: String) {
        require(oldSource.isNotBlank() && newSource.isNotBlank() && oldSource != newSource)
        store.edit { preferences ->
            val redirect = sourceRedirectKey(oldSource)
            preferences[redirect]?.let { existing ->
                require(existing == newSource) { "Source already redirected elsewhere" }
                return@edit
            }
            val oldIndex = sourceScopesKey(oldSource)
            val newIndex = sourceScopesKey(newSource)
            val categories = readList(preferences[oldIndex])
            val existingCategories = readList(preferences[newIndex])
            require(existingCategories.isEmpty() || existingCategories == categories) { "Destination ordering source already exists" }
            categories.forEach { category ->
                val oldScope = LiveChannelOrderScope(oldSource, category)
                val newScope = LiveChannelOrderScope(newSource, category)
                val oldOrder = preferences[key(oldScope)]
                val oldCatalog = preferences[catalogRevisionKey(oldScope)]
                require(preferences[key(newScope)] == null || preferences[key(newScope)] == oldOrder) { "Destination order already exists" }
                require(preferences[catalogRevisionKey(newScope)] == null || preferences[catalogRevisionKey(newScope)] == oldCatalog) { "Destination catalog already exists" }
                if (oldOrder != null) preferences[key(newScope)] = oldOrder
                if (oldCatalog != null) preferences[catalogRevisionKey(newScope)] = oldCatalog
            }
            if (categories.isNotEmpty()) preferences[newIndex] = encodeList(categories)
            preferences[redirect] = newSource
        }
    }
    override fun observe(scope: LiveChannelOrderScope): Flow<LiveChannelOrder> = store.data
        .map { values -> val actual = resolvedScope(values, scope); decode(values[key(actual)]).copy(catalogRevision = values[catalogRevisionKey(actual)]) }
        .distinctUntilChanged()

    override suspend fun hasSavedOrder(scope: LiveChannelOrderScope): Boolean =
        store.data.first().let { key(resolvedScope(it, scope)) in it }

    override suspend fun initializeIfAbsent(
        scope: LiveChannelOrderScope,
        order: LiveChannelOrder
    ): LiveChannelOrder {
        val requested = order.copy(manualKeys = order.manualKeys.toList())
        var result: LiveChannelOrder? = null
        store.edit { preferences ->
            val scope = resolvedScope(preferences, scope)
            val key = key(scope)
            val existing = preferences[key]
            if (existing != null) {
                result = decode(existing).copy(catalogRevision = preferences[catalogRevisionKey(scope)])
            } else {
                val initialized = requested.copy(revision = 1L, catalogRevision = null)
                preferences[key] = encode(initialized)
                registerScope(preferences, scope)
                result = initialized.copy(catalogRevision = preferences[catalogRevisionKey(scope)])
            }
        }
        return checkNotNull(result)
    }

    override suspend fun save(
        scope: LiveChannelOrderScope,
        expectedRevision: Long,
        order: LiveChannelOrder
    ): LiveChannelOrderSaveResult {
        require(expectedRevision >= 0)
        val requested = order.copy(manualKeys = order.manualKeys.toList())
        var result: LiveChannelOrderSaveResult? = null
        store.edit { preferences ->
            val scope = resolvedScope(preferences, scope)
            val key = key(scope)
            val current = decode(preferences[key])
            val catalogRevision = preferences[catalogRevisionKey(scope)]
            val currentSnapshot = current.copy(catalogRevision = catalogRevision)
            if (current.revision != expectedRevision || requested.catalogRevision != catalogRevision) {
                result = LiveChannelOrderSaveResult.Conflict(currentSnapshot)
            } else if (current.mode == requested.mode && current.manualKeys == requested.manualKeys) {
                registerScope(preferences, scope)
                result = LiveChannelOrderSaveResult.Saved(currentSnapshot)
            } else {
                check(current.revision < Long.MAX_VALUE)
                val saved = requested.copy(revision = current.revision + 1, catalogRevision = null)
                preferences[key] = encode(saved)
                registerScope(preferences, scope)
                result = LiveChannelOrderSaveResult.Saved(saved.copy(catalogRevision = catalogRevision))
            }
        }
        return checkNotNull(result)
    }

    override suspend fun reconcileCompleteCatalog(
        sourceKey: String,
        completeMembershipByCategory: Map<String, List<String>>
    ) {
        require(sourceKey.isNotBlank())
        completeMembershipByCategory.forEach { (categoryKey, members) ->
            require(categoryKey.isNotBlank())
            require(members.all(String::isNotBlank))
            require(members.distinct().size == members.size)
        }
        store.edit { preferences ->
            val sourceKey = preferences[sourceRedirectKey(sourceKey)] ?: sourceKey
            val scopesKey = sourceScopesKey(sourceKey)
            val trackedCategoryKeys = readList(preferences[scopesKey]).toMutableList()
            completeMembershipByCategory.keys.forEach { categoryKey ->
                if (categoryKey !in trackedCategoryKeys) trackedCategoryKeys += categoryKey
            }
            preferences[scopesKey] = encodeList(trackedCategoryKeys)

            trackedCategoryKeys.forEach { categoryKey ->
                // Favorites and groups have independent membership and are not part of a
                // provider catalog snapshot. An absent provider category, by contrast,
                // represents a removed category and must still be reconciled to empty.
                if (categoryKey == "live:favorites" || categoryKey.startsWith("live:group:")) return@forEach
                val scope = LiveChannelOrderScope(sourceKey, categoryKey)
                val members = completeMembershipByCategory[categoryKey].orEmpty()
                val revision = catalogRevision(members)
                val orderKey = key(scope)
                val current = decode(preferences[orderKey])
                val nextKeys = if (current.manualKeys.isNotEmpty() || current.mode == LiveChannelOrderMode.MANUAL) {
                    current.reconcile(members).manualKeys
                } else {
                    emptyList()
                }
                if (current.manualKeys != nextKeys) {
                    check(current.revision < Long.MAX_VALUE)
                    preferences[orderKey] = encode(current.copy(manualKeys = nextKeys, revision = current.revision + 1))
                }
                preferences[catalogRevisionKey(scope)] = revision
            }
        }
    }

    private fun registerScope(preferences: MutablePreferences, scope: LiveChannelOrderScope) {
        val indexKey = sourceScopesKey(scope.sourceKey)
        val categoryKeys = readList(preferences[indexKey])
        if (scope.categoryKey !in categoryKeys) {
            preferences[indexKey] = encodeList(categoryKeys + scope.categoryKey)
        }
    }

    private fun key(scope: LiveChannelOrderScope): Preferences.Key<String> {
        return stringPreferencesKey(encodedScope(scope))
    }

    private fun catalogRevisionKey(scope: LiveChannelOrderScope) =
        stringPreferencesKey("liveChannelOrderCatalog_${encodedScope(scope)}")

    private fun sourceScopesKey(sourceKey: String) =
        stringPreferencesKey("liveChannelOrderScopes_${encodeToken(sourceKey)}")

    private fun sourceRedirectKey(sourceKey: String) =
        stringPreferencesKey("liveChannelOrderRedirect_${encodeToken(sourceKey)}")

    private fun resolvedScope(values: Preferences, scope: LiveChannelOrderScope): LiveChannelOrderScope =
        values[sourceRedirectKey(scope.sourceKey)]?.let { scope.copy(sourceKey = it) } ?: scope

    private fun encodedScope(scope: LiveChannelOrderScope): String {
        val parts = JsonArray().apply { add(scope.sourceKey); add(scope.categoryKey) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(parts.toString().toByteArray(Charsets.UTF_8))
    }

    private fun encodeToken(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun encodeList(values: List<String>): String = JsonArray().apply { values.forEach(::add) }.toString()

    private fun readList(value: String?): List<String> = value?.let {
        JsonParser.parseString(it).asJsonArray.map { element -> element.asString }
    }.orEmpty()

    private fun catalogRevision(members: List<String>): String {
        val canonical = JsonArray().apply { members.forEach(::add) }.toString()
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun encode(order: LiveChannelOrder): String = JsonObject().apply {
        addProperty("version", 1)
        addProperty("mode", order.mode.name)
        addProperty("revision", order.revision)
        add("manualKeys", JsonArray().apply { order.manualKeys.forEach { add(it) } })
    }.toString()

    // Fail closed on invalid/future data; silently resetting it could overwrite a user's arrangement.
    private fun decode(value: String?): LiveChannelOrder {
        if (value == null) return LiveChannelOrder()
        val json = JsonParser.parseString(value).asJsonObject
        check(json.get("version").asInt == 1)
        return LiveChannelOrder(
            mode = LiveChannelOrderMode.valueOf(json.get("mode").asString),
            manualKeys = json.getAsJsonArray("manualKeys").map { it.asString },
            revision = json.get("revision").asLong
        )
    }
}
