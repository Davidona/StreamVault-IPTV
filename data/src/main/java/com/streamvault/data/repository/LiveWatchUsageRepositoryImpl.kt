package com.streamvault.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamvault.domain.model.LiveWatchDelta
import com.streamvault.domain.model.LiveWatchUsage
import com.streamvault.domain.repository.LiveWatchUsageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/** Local derived history; no URLs, account credentials, or fabricated historical durations. */
class LiveWatchUsageRepositoryImpl(
    private val store: DataStore<Preferences>,
    private val incognito: Flow<Boolean>,
    private val clock: () -> Long = System::currentTimeMillis
) : LiveWatchUsageRepository {
    override val resetVersion: Flow<Long> = store.data.map { it[RESET_VERSION] ?: 0L }.distinctUntilChanged()
    private data class Usage(
        var lastAt: Long = 0,
        val contentIds: MutableSet<Long> = linkedSetOf(),
        val logicalKeys: MutableSet<String> = linkedSetOf(),
        val days: MutableMap<Long, Long> = linkedMapOf(),
        val sessions: MutableMap<String, Long> = linkedMapOf()
    )

    override fun observe(providerId: Long): Flow<Map<String, LiveWatchUsage>> {
        require(providerId > 0)
        return combine(store.data, incognito) { values, isPrivate ->
            if (isPrivate) emptyMap() else runCatching {
                val today = today()
                decode(values[key(providerId)]).mapKeys { "stream:${it.key}" }.mapValues { (_, usage) ->
                    LiveWatchUsage(usage.lastAt, usage.days.filterKeys { it in (today - 29)..today }.values.sum())
                }
            }.getOrDefault(emptyMap())
        }.flowOn(Dispatchers.Default)
    }

    override suspend fun record(delta: LiveWatchDelta) {
        if (incognito.first()) return
        val today = today()
        if (delta.watchedDayUtc !in (today - 29)..today) return
        require(delta.watchedMs <= DAY_MS)
        store.edit { values ->
            if (incognito.first()) return@edit
            val key = key(delta.target.providerId)
            // A damaged/future store is never overwritten by a background playback callback.
            val channels = decode(values[key])
            val usage = channels.getOrPut(delta.target.streamId) { Usage() }
            if (delta.sequence <= (usage.sessions[delta.sessionId] ?: 0)) return@edit
            channels.values.forEach { row -> row.days.keys.retainAll((today - 29)..today) }
            usage.sessions[delta.sessionId] = delta.sequence
            // Only local ordered playback producers write here. Retain a bounded persisted replay window.
            while (usage.sessions.size > 64) usage.sessions.remove(usage.sessions.keys.first())
            usage.contentIds += delta.target.contentId
            usage.logicalKeys += delta.target.logicalKey
            usage.lastAt = maxOf(usage.lastAt, delta.lastWatchedAtMs)
            usage.days[delta.watchedDayUtc] = minOf(DAY_MS, (usage.days[delta.watchedDayUtc] ?: 0) + delta.watchedMs)
            values[key] = encode(channels)
        }
    }

    override suspend fun clearAll() { store.edit {
        val version = Math.addExact(it[RESET_VERSION] ?: 0L, 1)
        it.clear()
        it[RESET_VERSION] = version
    } }
    override suspend fun clearProvider(providerId: Long) { store.edit { it.remove(key(providerId)); bumpReset(it) } }
    override suspend fun clearContent(providerId: Long, contentId: Long) {
        store.edit { values ->
            val key = key(providerId)
            val channels = decode(values[key])
            val logicalKeys = channels.values.filter { contentId in it.contentIds }.flatMap { it.logicalKeys }.toSet()
            channels.entries.removeAll { contentId in it.value.contentIds || it.value.logicalKeys.any { key -> key in logicalKeys } }
            if (channels.isEmpty()) values.remove(key) else values[key] = encode(channels)
            bumpReset(values)
        }
    }

    private fun key(providerId: Long) = stringPreferencesKey("provider:$providerId")
    private fun today() = Math.floorDiv(clock(), DAY_MS)

    private fun encode(channels: Map<Long, Usage>) = JsonObject().apply {
        addProperty("version", 1)
        add("channels", JsonObject().apply {
            channels.forEach { (remoteId, row) ->
                add(remoteId.toString(), JsonObject().apply {
                    addProperty("lastAt", row.lastAt)
                    add("contentIds", JsonArray().apply { row.contentIds.forEach { add(it) } })
                    add("logicalKeys", JsonArray().apply { row.logicalKeys.forEach { add(it) } })
                    add("days", JsonObject().apply { row.days.forEach { (day, ms) -> addProperty(day.toString(), ms) } })
                    add("sessions", JsonObject().apply { row.sessions.forEach { (id, seq) -> addProperty(id, seq) } })
                })
            }
        })
    }.toString()

    private fun decode(value: String?): MutableMap<Long, Usage> {
        if (value == null) return linkedMapOf()
        val json = JsonParser.parseString(value).asJsonObject
        check(json["version"].asInt == 1)
        return json.getAsJsonObject("channels").entrySet().associateTo(linkedMapOf()) { (id, data) ->
            val row = data.asJsonObject
            val days = row.getAsJsonObject("days").entrySet().associateTo(linkedMapOf()) { (day, ms) ->
                day.toLong() to ms.asLong.also { require(it in 0..DAY_MS) }
            }
            val sessions = row.getAsJsonObject("sessions").entrySet().associateTo(linkedMapOf()) { (session, seq) ->
                session to seq.asLong.also { require(it > 0) }
            }
            id.toLong() to Usage(row["lastAt"].asLong,
                row.getAsJsonArray("contentIds").mapTo(linkedSetOf()) { it.asLong },
                row.getAsJsonArray("logicalKeys")?.mapTo(linkedSetOf()) { it.asString } ?: linkedSetOf(), days, sessions)
        }
    }

    private fun bumpReset(values: MutablePreferences) { values[RESET_VERSION] = Math.addExact(values[RESET_VERSION] ?: 0L, 1) }
    private companion object {
        const val DAY_MS = 86_400_000L
        val RESET_VERSION = longPreferencesKey("reset_version")
    }
}
