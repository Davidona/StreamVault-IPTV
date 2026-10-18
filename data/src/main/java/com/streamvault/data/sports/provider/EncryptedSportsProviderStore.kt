package com.streamvault.data.sports.provider

import android.content.Context
import android.util.AtomicFile
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.sports.provider.SportsProviderConfig
import com.streamvault.domain.sports.provider.SportsProviderCredentials
import com.streamvault.domain.sports.provider.SportsProviderStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

/** One atomic encrypted envelope in noBackupFilesDir, outside Room/preferences exports. */
@Singleton
class EncryptedSportsProviderStore internal constructor(
    file: File,
    private val crypto: CredentialCrypto,
) : SportsProviderStore {
    @Inject constructor(
        @ApplicationContext context: Context,
        crypto: CredentialCrypto,
    ) : this(File(context.noBackupFilesDir, "sports_providers.enc"), crypto)

    private val file = AtomicFile(file)
    private val mutex = Mutex()
    private val json = Json
    private val revision = MutableStateFlow(0L)
    override val configurationChanges = revision.asStateFlow()

    override suspend fun configurations(): List<SportsProviderConfig> = access {
        read().map { it.config }
    }

    override suspend fun credentials(instanceId: String): SportsProviderCredentials? = access {
        read().firstOrNull { it.config.instanceId == instanceId }?.credentials
    }

    override suspend fun credentialedInstanceIds(): Set<String> = access {
        read().asSequence().filter { it.credentials != null }
            .mapTo(mutableSetOf()) { it.config.instanceId }
    }

    override suspend fun save(config: SportsProviderConfig, credentials: SportsProviderCredentials?) {
        access {
            val records = read().toMutableList()
            val index = records.indexOfFirst { it.config.instanceId == config.instanceId }
            val previous = records.getOrNull(index)
            require(previous == null || previous.config.typeId == config.typeId)
            val record = Record(config, credentials ?: previous?.credentials)
            if (index < 0) records.add(record) else records[index] = record
            write(records)
            revision.update { it + 1 }
        }
    }

    override suspend fun remove(instanceId: String) {
        access {
            write(read().filterNot { it.config.instanceId == instanceId })
            revision.update { it + 1 }
        }
    }

    private suspend fun <T> access(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { block() } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never propagate crypto/JSON exception causes containing an envelope or key.
                throw SportsProviderStorageException()
            }
        }
    }

    private fun read(): List<Record> {
        // openRead also restores an interrupted AtomicFile write. Do not treat corruption as empty.
        val bytes = try {
            file.openRead().use { input ->
                input.readBytesBounded(MAX_BYTES)
            }
        } catch (e: java.io.FileNotFoundException) {
            if (!file.baseFile.exists()) return emptyList() else throw e
        }
        val ciphertext = bytes.toString(Charsets.UTF_8)
        val plaintext = crypto.decryptIfNeeded(ciphertext)
        require(plaintext != ciphertext)
        val envelope = json.parseToJsonElement(plaintext).jsonObject
        require(envelope.getValue("version").jsonPrimitive.int == 1)
        val records = envelope.getValue("providers").jsonArray.map { item ->
            val obj = item.jsonObject
            Record(
                config = SportsProviderConfig(
                    instanceId = obj.getValue("id").jsonPrimitive.content,
                    typeId = obj.getValue("type").jsonPrimitive.content,
                    configurationVersion = obj.getValue("configVersion").jsonPrimitive.int,
                    cacheRevision = obj["cacheRevision"]?.jsonPrimitive?.long ?: 0L,
                    enabled = obj.getValue("enabled").jsonPrimitive.boolean,
                    priority = obj.getValue("priority").jsonPrimitive.int,
                    baseUrl = obj["baseUrl"]?.jsonPrimitive?.content,
                    eventLeagueIds = obj["eventLeagueIds"]?.jsonArray
                        ?.map { it.jsonPrimitive.content }
                        .orEmpty(),
                    customDefinitionJson = obj["customDefinitionJson"]?.jsonPrimitive?.content,
                ),
                credentials = obj["credentials"]?.jsonObject?.let {
                    SportsProviderCredentials(
                        it.getValue("secret").jsonPrimitive.content,
                        it["username"]?.jsonPrimitive?.content,
                    )
                },
            )
        }
        require(records.map { it.config.instanceId }.distinct().size == records.size)
        return records
    }

    private fun write(records: List<Record>) {
        val envelope = buildJsonObject {
            put("version", 1)
            putJsonArray("providers") {
                records.forEach { record ->
                    add(buildJsonObject {
                        val config = record.config
                        put("id", config.instanceId)
                        put("type", config.typeId)
                        put("configVersion", config.configurationVersion)
                        put("cacheRevision", config.cacheRevision)
                        put("enabled", config.enabled)
                        put("priority", config.priority)
                        config.baseUrl?.let { put("baseUrl", it) }
                        putJsonArray("eventLeagueIds") {
                            config.eventLeagueIds.forEach(::add)
                        }
                        config.customDefinitionJson?.let { put("customDefinitionJson", it) }
                        record.credentials?.let { credentials ->
                            putJsonObject("credentials") {
                                put("secret", credentials.secret)
                                credentials.username?.let { put("username", it) }
                            }
                        }
                    })
                }
            }
        }
        val plaintext = envelope.toString()
        require(plaintext.toByteArray(Charsets.UTF_8).size <= MAX_BYTES / 2)
        val encrypted = crypto.encryptIfNeeded(plaintext)
        // Fail closed if an implementation accidentally returns plaintext.
        require(encrypted != plaintext)
        val bytes = encrypted.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    private class Record(val config: SportsProviderConfig, val credentials: SportsProviderCredentials?)

    companion object { private const val MAX_BYTES = 256 * 1024 }
}

/** Internal failure marker. Presentation must map it to a localized resource. */
class SportsProviderStorageException : RuntimeException(null, null)

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) throw SportsResponseTooLargeException()
        output.write(buffer, 0, count)
    }
}

internal class SportsResponseTooLargeException : RuntimeException()
