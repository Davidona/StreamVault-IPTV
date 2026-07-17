package com.streamvault.data.repository

import android.util.Log
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.local.entity.ProgramBrowseEntity
import com.streamvault.data.local.entity.ProgramEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.parser.XmltvParser
import com.streamvault.data.remote.http.HttpRequestProfile
import com.streamvault.data.remote.http.safeRequestIdentitySummary
import com.streamvault.data.remote.http.toGenericRequestProfile
import com.streamvault.data.remote.http.withRequestProfile
import com.streamvault.data.util.rankSearchResults
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.streamvault.data.remote.NetworkTimeoutConfig
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class EpgRepositoryImpl @Inject constructor(
    private val programDao: ProgramDao,
    private val providerDao: ProviderDao,
    private val xmltvParser: XmltvParser,
    private val okHttpClient: OkHttpClient,
    private val transactionRunner: DatabaseTransactionRunner,
    private val epgSourceRepository: EpgSourceRepository,
    private val preferencesRepository: PreferencesRepository,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : EpgRepository {

    private suspend fun shiftMsFor(providerId: Long): Long =
        preferencesRepository.getEpgTimeShiftMinutes(providerId) * 60_000L

    private fun Program.shifted(offsetMs: Long): Program =
        if (offsetMs == 0L) this
        else copy(startTime = startTime + offsetMs, endTime = endTime + offsetMs)

    private fun List<Program>.shiftAll(offsetMs: Long): List<Program> =
        if (offsetMs == 0L) this else map { it.shifted(offsetMs) }

    private val providerRefreshMutexes = ConcurrentHashMap<Long, Mutex>()

    private val epgHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(NetworkTimeoutConfig.EPG_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val MAX_EPG_SIZE_BYTES = NetworkTimeoutConfig.EPG_MAX_SIZE_BYTES
        private const val EPG_PROGRAM_BATCH_SIZE = 5000
        private const val NOW_AND_NEXT_LOOKBACK_MS = 60L * 60L * 1000L
        private const val NOW_AND_NEXT_LOOKAHEAD_MS = 2L * 60L * 60L * 1000L
        private const val NOW_AND_NEXT_REFRESH_INTERVAL_MS = 60L * 1000L

        private fun String.escapeSqlLike(escape: Char = '\\'): String =
            this.replace("$escape", "$escape$escape")
                .replace("%", "$escape%")
                .replace("_", "${escape}_")
    }

    override fun getProgramsForChannel(
        providerId: Long,
        channelId: String,
        startTime: Long,
        endTime: Long
    ): Flow<List<Program>> =
        preferencesRepository.epgTimeShiftMinutes(providerId).flatMapLatest { minutes ->
            val offsetMs = minutes * 60_000L
            programDao.getForChannel(providerId, channelId, startTime - offsetMs, endTime - offsetMs)
                .map { entities -> entities.map { it.toDomain().shifted(offsetMs) } }
        }

    override fun getProgramsForChannels(
        providerId: Long,
        channelIds: List<String>,
        startTime: Long,
        endTime: Long
    ): Flow<Map<String, List<Program>>> {
        if (channelIds.isEmpty()) return flowOf(emptyMap())
        val chunks = channelIds.chunked(500)
        if (chunks.size == 1) {
            return preferencesRepository.epgTimeShiftMinutes(providerId).flatMapLatest { minutes ->
                val offsetMs = minutes * 60_000L
                programDao.getForChannels(providerId, channelIds, startTime - offsetMs, endTime - offsetMs)
                    .map { entities -> entities.map { it.toDomain().shifted(offsetMs) }.groupBy { it.channelId } }
            }
        }
        return flow {
            emit(getProgramsForChannelsSnapshot(providerId, channelIds, startTime, endTime))
        }
    }

    override suspend fun getProgramsForChannelsSnapshot(
        providerId: Long,
        channelIds: List<String>,
        startTime: Long,
        endTime: Long
    ): Map<String, List<Program>> {
        if (channelIds.isEmpty()) return emptyMap()

        val offsetMs = shiftMsFor(providerId)
        val adjustedStart = startTime - offsetMs
        val adjustedEnd = endTime - offsetMs

        val entities = if (channelIds.size <= 500) {
            programDao.getForChannelsSync(providerId, channelIds, adjustedStart, adjustedEnd)
        } else {
            channelIds.chunked(500).flatMap { chunk ->
                programDao.getForChannelsSync(providerId, chunk, adjustedStart, adjustedEnd)
            }
        }

        return entities
            .map { it.toDomain().shifted(offsetMs) }
            .groupBy { it.channelId }
    }

    override fun getProgramsByCategory(
        providerId: Long,
        categoryId: Long,
        startTime: Long,
        endTime: Long
    ): Flow<List<Program>> =
        preferencesRepository.epgTimeShiftMinutes(providerId).flatMapLatest { minutes ->
            val offsetMs = minutes * 60_000L
            programDao.getForCategory(providerId, categoryId, startTime - offsetMs, endTime - offsetMs)
                .map { entities -> entities.map { it.toDomain().shifted(offsetMs) } }
        }

    override fun searchPrograms(
        providerId: Long,
        query: String,
        startTime: Long,
        endTime: Long,
        categoryId: Long?,
        limit: Int
    ): Flow<List<Program>> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 2) return flowOf(emptyList())
        val escaped = normalizedQuery.escapeSqlLike()
        return preferencesRepository.epgTimeShiftMinutes(providerId).flatMapLatest { minutes ->
            val offsetMs = minutes * 60_000L
            programDao.searchPrograms(
                providerId = providerId,
                queryPattern = "%$escaped%",
                startTime = startTime - offsetMs,
                endTime = endTime - offsetMs,
                categoryId = categoryId,
                limit = limit
            ).map { entities ->
                entities.map { it.toDomain().shifted(offsetMs) }
                    .rankSearchResults(normalizedQuery) { it.title }
            }
        }
    }

    override fun getNowPlaying(providerId: Long, channelId: String): Flow<Program?> =
        preferencesRepository.epgTimeShiftMinutes(providerId).flatMapLatest { minutes ->
            val offsetMs = minutes * 60_000L
            nowTicker.flatMapLatest { realNow ->
                programDao.getNowPlaying(providerId, channelId, realNow - offsetMs)
                    .map { it?.toDomain()?.shifted(offsetMs) }
            }
        }

    override fun getNowPlayingForChannels(providerId: Long, channelIds: List<String>): Flow<Map<String, Program?>> {
        if (channelIds.isEmpty()) return flowOf(emptyMap())

        val chunks = channelIds.chunked(500)
        return preferencesRepository.epgTimeShiftMinutes(providerId).flatMapLatest { minutes ->
            val offsetMs = minutes * 60_000L
            nowTicker.flatMapLatest { realNow ->
                val now = realNow - offsetMs
                if (chunks.size == 1) {
                    programDao.getNowPlayingForChannels(providerId, channelIds, now)
                        .map { entities -> mapNowPlayingByChannel(channelIds, entities, offsetMs) }
                } else {
                    combine(chunks.map { chunk ->
                        programDao.getNowPlayingForChannels(providerId, chunk, now)
                    }) { arrays ->
                        mapNowPlayingByChannel(channelIds, arrays.flatMap { it.toList() }, offsetMs)
                    }
                }
            }
        }
    }

    override suspend fun getNowPlayingForChannelsSnapshot(
        providerId: Long,
        channelIds: List<String>
    ): Map<String, Program?> {
        if (channelIds.isEmpty()) return emptyMap()

        val offsetMs = shiftMsFor(providerId)
        val now = System.currentTimeMillis() - offsetMs
        val entities = if (channelIds.size <= 500) {
            programDao.getNowPlayingForChannelsSync(providerId, channelIds, now)
        } else {
            channelIds.chunked(500).flatMap { chunk ->
                programDao.getNowPlayingForChannelsSync(providerId, chunk, now)
            }
        }

        val grouped = entities.map { it.toDomain().shifted(offsetMs) }.groupBy { it.channelId }
        return channelIds.associateWith { id -> grouped[id]?.firstOrNull() }
    }

    override fun getNowAndNext(providerId: Long, channelId: String): Flow<Pair<Program?, Program?>> =
        preferencesRepository.epgTimeShiftMinutes(providerId).flatMapLatest { minutes ->
            val offsetMs = minutes * 60_000L
            nowTicker.flatMapLatest { realNow ->
                val now = realNow - offsetMs
                programDao.getForChannel(
                    providerId = providerId,
                    channelId = channelId,
                    startTime = now - NOW_AND_NEXT_LOOKBACK_MS,
                    endTime = now + NOW_AND_NEXT_LOOKAHEAD_MS
                ).map { entities ->
                    val programs = entities.map { it.toDomain() }
                    val current = programs.find { it.startTime <= now && it.endTime > now }
                    val nextStart = current?.endTime ?: now
                    val next = programs.firstOrNull { it.startTime >= nextStart && it != current }
                    current?.shifted(offsetMs) to next?.shifted(offsetMs)
                }
            }
        }

    override suspend fun refreshEpg(providerId: Long, epgUrl: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            providerRefreshMutex(providerId).withLock {
                val stagingProviderId = -providerId
                val providerTimezoneId = providerDao.getById(providerId)
                    ?.stalkerDeviceTimezone
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                val batch = ArrayList<ProgramEntity>(EPG_PROGRAM_BATCH_SIZE)
                suspend fun flushBatch() {
                    if (batch.isEmpty()) return
                    val rows = batch.toList()
                    batch.clear()
                    transactionRunner.inTransaction {
                        programDao.insertAll(rows)
                    }
                    yield()
                }
                try {
                    val providerRequestProfile = providerDao.getById(providerId)
                        ?.toGenericRequestProfile(ownerTag = "provider:$providerId/epg")
                        ?: HttpRequestProfile(ownerTag = "provider:$providerId/epg")
                    val request = Request.Builder()
                        .url(epgUrl)
                        .build()
                        .withRequestProfile(providerRequestProfile)
                    epgHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(
                                "EpgRepository",
                                "EPG request failed for provider $providerId (${request.safeRequestIdentitySummary(providerRequestProfile)}): HTTP ${response.code}"
                            )
                            return@withLock Result.error("Failed to download EPG: HTTP ${response.code}")
                        }

                        val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                        if (contentLength > MAX_EPG_SIZE_BYTES) {
                            return@withLock Result.error("EPG file too large (${contentLength / 1_048_576}MB)")
                        }

                        val body = response.body ?: return@withLock Result.error("Empty EPG response")

                        transactionRunner.inTransaction {
                            programDao.deleteByProvider(stagingProviderId)
                        }

                        body.byteStream().use { rawStream ->
                            // Let OkHttp negotiate/decompress standard gzip responses. We still
                            // inspect the bytes so download-style URLs that return raw `.gz`
                            // payloads without transparent decompression continue to work.
                            val limitedStream = object : FilterInputStream(rawStream) {
                                private var bytesRead = 0L
                                override fun read(): Int {
                                    if (bytesRead >= MAX_EPG_SIZE_BYTES) throw IOException("EPG response too large (>200 MB)")
                                    return super.read().also { if (it >= 0) bytesRead++ }
                                }
                                override fun read(b: ByteArray, off: Int, len: Int): Int {
                                    if (bytesRead >= MAX_EPG_SIZE_BYTES) throw IOException("EPG response too large (>200 MB)")
                                    return super.read(b, off, len).also { if (it > 0) bytesRead += it }
                                }
                            }
                            xmltvParser.maybeDecompressGzip(epgUrl, limitedStream).use { xmlInput ->
                                xmltvParser.parseStreaming(xmlInput, timezoneId = providerTimezoneId) { program ->
                                    batch.add(program.copy(providerId = stagingProviderId).toEntity())
                                    if (batch.size >= EPG_PROGRAM_BATCH_SIZE) {
                                        flushBatch()
                                    }
                                }
                            }
                        }
                    }

                    flushBatch()

                    transactionRunner.inTransaction {
                        programDao.deleteByProvider(providerId)
                        programDao.moveToProvider(stagingProviderId, providerId)
                    }

                    Result.success(Unit)
                } catch (e: Exception) {
                    programDao.deleteByProvider(stagingProviderId)
                    if (e is IOException && e.message?.contains("too large", ignoreCase = true) == true) {
                        Result.error("EPG response exceeded 200 MB limit", e)
                    } else {
                        Result.error("Failed to refresh EPG: ${e.message}", e)
                    }
                }
            }
        }

    override suspend fun clearOldPrograms(beforeTime: Long) {
        programDao.deleteOld(beforeTime)
    }

    override fun onProviderDeleted(providerId: Long) {
        providerRefreshMutexes.remove(providerId)
    }

    override suspend fun getResolvedProgramsForChannels(
        providerId: Long,
        channelIds: List<Long>,
        startTime: Long,
        endTime: Long
    ): Map<String, List<Program>> {
        val offsetMs = shiftMsFor(providerId)
        return epgSourceRepository.getResolvedProgramsForChannels(
            providerId, channelIds, startTime - offsetMs, endTime - offsetMs
        ).mapValues { (_, programs) -> programs.shiftAll(offsetMs) }
    }

    override suspend fun getResolvedProgramsForPlaybackChannel(
        providerId: Long,
        internalChannelId: Long,
        epgChannelId: String?,
        streamId: Long,
        startTime: Long,
        endTime: Long
    ): List<Program> {
        val normalizedChannelId = epgChannelId?.trim()?.takeIf { it.isNotEmpty() }
        val lookupKey = normalizedChannelId ?: streamId.takeIf { it > 0L }?.toString()
        val offsetMs = shiftMsFor(providerId)

        if (internalChannelId > 0L && lookupKey != null) {
            val resolvedPrograms = epgSourceRepository.getResolvedProgramsForChannels(
                providerId = providerId,
                channelIds = listOf(internalChannelId),
                startTime = startTime - offsetMs,
                endTime = endTime - offsetMs
            )[lookupKey].orEmpty()
            if (resolvedPrograms.isNotEmpty()) {
                return resolvedPrograms.shiftAll(offsetMs).sortedBy { it.startTime }
            }
        }

        if (normalizedChannelId != null) {
            // getProgramsForChannel already applies the offset internally.
            return getProgramsForChannel(providerId, normalizedChannelId, startTime, endTime)
                .first()
                .sortedBy { it.startTime }
        }

        return emptyList()
    }

    private val nowTicker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(NOW_AND_NEXT_REFRESH_INTERVAL_MS)
        }
    }.shareIn(externalScope, SharingStarted.WhileSubscribed(), replay = 1)

    private fun mapNowPlayingByChannel(
        channelIds: List<String>,
        entities: List<ProgramBrowseEntity>,
        offsetMs: Long = 0L
    ): Map<String, Program?> {
        val grouped = entities.map { it.toDomain().shifted(offsetMs) }.groupBy { it.channelId }
        return channelIds.associateWith { id -> grouped[id]?.firstOrNull() }
    }

    private fun providerRefreshMutex(providerId: Long): Mutex =
        providerRefreshMutexes.computeIfAbsent(providerId) { Mutex() }
}
