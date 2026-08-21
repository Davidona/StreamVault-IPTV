package com.streamvault.app.tvinput

import android.content.ComponentName
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.provider.BaseColumns
import android.util.Log
import com.streamvault.app.MainActivity
import com.streamvault.app.device.isTelevisionDevice
import com.streamvault.app.navigation.PlayerNavigationRequest
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

@Singleton
class TvInputChannelSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providerRepository: ProviderRepository,
    private val channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository
) {

    suspend fun refreshTvInputCatalog() {
        refreshTvInputCatalogResult().onFailure { throwable ->
            Log.w(TAG, "TV input catalog sync failed", throwable)
        }
    }

    suspend fun refreshTvInputCatalogResult(): Result<Unit> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            runCatching {
                if (!context.isTelevisionDevice()) {
                    return@runCatching
                }
                val provider = providerRepository.getActiveProvider().first()
                if (provider == null) {
                    deleteManagedChannels()
                    return@runCatching
                }

                val channels = channelRepository.getChannels(provider.id).first()
                publishChannels(provider, channels)
                val publishedChannelIds = loadExistingChannels().toMutableMap()

                val programsByEpgId = loadPrograms(provider.id, channels)
                publishPrograms(provider, channels, publishedChannelIds, programsByEpgId)
            }
        }
    }

    private fun publishChannels(provider: Provider, channels: List<Channel>) {
        val existing = loadExistingChannelRows()
        val targets = channels.associateBy { stableTvChannelKey(provider, it) }
        val operations = ArrayList<ContentProviderOperation>()
        existing.filterKeys { it !in targets }.values.forEach { row ->
            operations += ContentProviderOperation.newDelete(
                ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, row.id)
            ).build()
        }
        targets.forEach { (key, channel) ->
            val values = buildChannelValues(provider, channel)
            val row = existing[key]
            when {
                row == null -> operations += ContentProviderOperation.newInsert(TvContract.Channels.CONTENT_URI)
                    .withValues(values)
                    .build()
                row.providerData != values.getAsString(CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA) ->
                    operations += ContentProviderOperation.newUpdate(
                        ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, row.id)
                    ).withValues(values).build()
            }
        }
        applyBatches(operations, "channels")
    }

    private fun publishPrograms(
        provider: Provider,
        channels: List<Channel>,
        channelIds: Map<String, Long>,
        programsByEpgId: Map<String, List<Program>>
    ) {
        val desired = linkedMapOf<String, ContentValues>()
        val replaceableChannelIds = linkedSetOf<Long>()
        channels.forEach { channel ->
            val programs = programsByEpgId[channel.epgChannelId].orEmpty()
            if (!shouldReplaceTvPrograms(channel, programs)) return@forEach
            val tvChannelId = channelIds[stableTvChannelKey(provider, channel)] ?: return@forEach
            replaceableChannelIds += tvChannelId
            programs.sortedBy { it.startTime }.take(MAX_PROGRAMS_PER_CHANNEL).forEach { program ->
                val values = buildProgramValues(tvChannelId, provider.id, channel, program)
                val fingerprint = stableTvProgramKey(provider, channel, program)
                values.put(PROGRAM_COLUMN_INTERNAL_PROVIDER_DATA, fingerprint)
                desired[fingerprint] = values
            }
        }
        val existing = loadExistingPrograms(replaceableChannelIds)
        val operations = ArrayList<ContentProviderOperation>()
        existing.filterKeys { it !in desired }.values.forEach { programId ->
            operations += ContentProviderOperation.newDelete(
                ContentUris.withAppendedId(TvContract.Programs.CONTENT_URI, programId)
            ).build()
        }
        desired.filterKeys { it !in existing }.values.forEach { values ->
            operations += ContentProviderOperation.newInsert(TvContract.Programs.CONTENT_URI)
                .withValues(values)
                .build()
        }
        applyBatches(operations, "programs")
    }

    private fun loadExistingChannelRows(): Map<String, ExistingTvChannel> {
        val targetInputId = inputId()
        return context.contentResolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(
                BaseColumns._ID,
                CHANNEL_COLUMN_INPUT_ID,
                CHANNEL_COLUMN_INTERNAL_PROVIDER_ID,
                CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val inputIdIndex = cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_INPUT_ID)
            val keyIndex = cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_INTERNAL_PROVIDER_ID)
            val dataIndex = cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA)
            buildMap {
                while (cursor.moveToNext()) {
                    if (cursor.getString(inputIdIndex) == targetInputId) {
                        put(
                            cursor.getString(keyIndex),
                            ExistingTvChannel(cursor.getLong(idIndex), cursor.getString(dataIndex).orEmpty())
                        )
                    }
                }
            }
        }.orEmpty()
    }

    private fun loadExistingPrograms(channelIds: Set<Long>): Map<String, Long> {
        if (channelIds.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, Long>()
        channelIds.chunked(PROGRAM_QUERY_CHANNEL_CHUNK_SIZE).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            context.contentResolver.query(
                TvContract.Programs.CONTENT_URI,
                arrayOf(BaseColumns._ID, PROGRAM_COLUMN_INTERNAL_PROVIDER_DATA),
                "$PROGRAM_COLUMN_CHANNEL_ID IN ($placeholders)",
                chunk.map(Long::toString).toTypedArray(),
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val dataIndex = cursor.getColumnIndexOrThrow(PROGRAM_COLUMN_INTERNAL_PROVIDER_DATA)
                while (cursor.moveToNext()) {
                    cursor.getString(dataIndex)?.let { result[it] = cursor.getLong(idIndex) }
                }
            }
        }
        return result
    }

    private fun applyBatches(operations: List<ContentProviderOperation>, stage: String) {
        val startedAt = System.currentTimeMillis()
        operations.chunked(CONTENT_PROVIDER_BATCH_SIZE).forEach { chunk ->
            context.contentResolver.applyBatch(TvContract.AUTHORITY, ArrayList(chunk))
        }
        Log.i(TAG, "TV input $stage operations=${operations.size} took=${System.currentTimeMillis() - startedAt}ms")
    }

    private data class ExistingTvChannel(val id: Long, val providerData: String)

    private suspend fun loadPrograms(providerId: Long, channels: List<Channel>): Map<String, List<Program>> {
        val epgIds = channels.mapNotNull { it.epgChannelId?.takeIf(String::isNotBlank) }
        if (epgIds.isEmpty()) return emptyMap()

        val now = System.currentTimeMillis()
        val start = now - PROGRAM_LOOKBACK_MS
        val end = now + PROGRAM_LOOKAHEAD_MS
        val merged = mutableMapOf<String, List<Program>>()

        epgIds.distinct().chunked(EPG_QUERY_CHUNK_SIZE).forEach { chunk ->
            merged += epgRepository.getProgramsForChannelsSnapshot(providerId, chunk, start, end)
        }
        return merged
    }

    private fun loadExistingChannels(): Map<String, Long> {
        val targetInputId = inputId()
        return context.contentResolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(BaseColumns._ID, CHANNEL_COLUMN_INPUT_ID, CHANNEL_COLUMN_INTERNAL_PROVIDER_ID),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val inputIdIndex = cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_INPUT_ID)
            val keyIndex = cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_INTERNAL_PROVIDER_ID)
            buildMap {
                while (cursor.moveToNext()) {
                    if (cursor.getString(inputIdIndex) == targetInputId) {
                        put(cursor.getString(keyIndex), cursor.getLong(idIndex))
                    }
                }
            }
        }.orEmpty()
    }

    private fun buildChannelValues(provider: Provider, channel: Channel): ContentValues = ContentValues().apply {
        put(CHANNEL_COLUMN_INPUT_ID, inputId())
        put(CHANNEL_COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
        put(CHANNEL_COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
        put(CHANNEL_COLUMN_DISPLAY_NUMBER, channel.number.toString())
        put(CHANNEL_COLUMN_DISPLAY_NAME, channel.name)
        put(CHANNEL_COLUMN_DESCRIPTION, channel.categoryName ?: "IPTV")
        put(CHANNEL_COLUMN_INTERNAL_PROVIDER_ID, stableTvChannelKey(provider, channel))
        put(CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA, encodeChannelData(provider.id, channel))
        put(CHANNEL_COLUMN_APP_LINK_INTENT_URI, buildChannelIntent(channel).toUri(Intent.URI_INTENT_SCHEME))
    }

    private fun buildProgramValues(channelId: Long, providerId: Long, channel: Channel, program: Program): ContentValues = ContentValues().apply {
        put(PROGRAM_COLUMN_CHANNEL_ID, channelId)
        put(PROGRAM_COLUMN_TITLE, program.title)
        put(PROGRAM_COLUMN_DESCRIPTION, program.description)
        put(PROGRAM_COLUMN_START_TIME_UTC_MILLIS, program.startTime)
        put(PROGRAM_COLUMN_END_TIME_UTC_MILLIS, program.endTime)
        put(PROGRAM_COLUMN_INTERNAL_PROVIDER_DATA, "${providerId}:${channel.id}:${program.startTime}")
    }

    private fun buildChannelIntent(channel: Channel): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(
                MainActivity.EXTRA_PLAYER_REQUEST,
                PlayerNavigationRequest(
                    streamUrl = channel.streamUrl,
                    title = channel.name,
                    channelId = channel.epgChannelId,
                    internalId = channel.id,
                    categoryId = channel.categoryId,
                    providerId = channel.providerId,
                    contentType = "LIVE"
                )
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun deleteManagedChannels() {
        val operations = loadExistingChannels().values.map { channelId ->
            ContentProviderOperation.newDelete(
                ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, channelId)
            ).build()
        }
        applyBatches(operations, "channel deletions")
    }

    private fun inputId(): String = ComponentName(context, StreamVaultTvInputService::class.java).flattenToShortString()

    private fun encodeChannelData(providerId: Long, channel: Channel): String =
        listOf(
            providerId,
            channel.id,
            channel.epgChannelId.orEmpty(),
            sha256Prefix(
                listOf(
                    channel.streamId,
                    channel.number,
                    channel.name,
                    channel.categoryName.orEmpty(),
                    channel.streamUrl
                ).joinToString("|")
            )
        ).joinToString(ENTRY_SEPARATOR)

    private companion object {
        const val TAG = "TvInputChannelSync"
        const val CHANNEL_COLUMN_INPUT_ID = "input_id"
        const val CHANNEL_COLUMN_TYPE = "type"
        const val CHANNEL_COLUMN_SERVICE_TYPE = "service_type"
        const val CHANNEL_COLUMN_DISPLAY_NUMBER = "display_number"
        const val CHANNEL_COLUMN_DISPLAY_NAME = "display_name"
        const val CHANNEL_COLUMN_DESCRIPTION = "description"
        const val CHANNEL_COLUMN_BROWSABLE = "browsable"
        const val CHANNEL_COLUMN_INTERNAL_PROVIDER_ID = "internal_provider_id"
        const val CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA = "internal_provider_data"
        const val CHANNEL_COLUMN_APP_LINK_INTENT_URI = "app_link_intent_uri"
        const val PROGRAM_COLUMN_CHANNEL_ID = "channel_id"
        const val PROGRAM_COLUMN_TITLE = "title"
        const val PROGRAM_COLUMN_DESCRIPTION = "description"
        const val PROGRAM_COLUMN_START_TIME_UTC_MILLIS = "start_time_utc_millis"
        const val PROGRAM_COLUMN_END_TIME_UTC_MILLIS = "end_time_utc_millis"
        const val PROGRAM_COLUMN_INTERNAL_PROVIDER_DATA = "internal_provider_data"
        const val EPG_QUERY_CHUNK_SIZE = 200
        const val PROGRAM_QUERY_CHANNEL_CHUNK_SIZE = 500
        const val CONTENT_PROVIDER_BATCH_SIZE = 200
        const val MAX_PROGRAMS_PER_CHANNEL = 24
        const val PROGRAM_LOOKBACK_MS = 3 * 60 * 60 * 1000L
        const val PROGRAM_LOOKAHEAD_MS = 18 * 60 * 60 * 1000L
        const val ENTRY_SEPARATOR = ":"
        val syncMutex = Mutex()
    }
}

internal fun stableTvChannelKey(provider: Provider, channel: Channel): String {
    val normalizedUrl = runCatching {
        val uri = URI(provider.serverUrl.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val port = when {
            uri.port < 0 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        "$scheme://$host$port${uri.rawPath.orEmpty().trimEnd('/')}"
    }.getOrElse { provider.serverUrl.trim().trimEnd('/').lowercase(Locale.ROOT) }
    val providerIdentity = listOf(
        normalizedUrl,
        provider.username.trim(),
        provider.type.name,
        provider.stalkerMacAddress.trim().uppercase(Locale.ROOT)
    ).joinToString("|")
    val providerHash = sha256Prefix(providerIdentity)
    val remoteStreamId = channel.streamId.takeIf { it != 0L }
        ?: error("TV input channel '${channel.name}' has no portable stream identity")
    return "$providerHash:$remoteStreamId"
}

private fun stableTvProgramKey(provider: Provider, channel: Channel, program: Program): String {
    val channelKey = stableTvChannelKey(provider, channel)
    val contentHash = sha256Prefix(
        listOf(program.title, program.description.orEmpty()).joinToString("|"),
        byteCount = 6
    )
    return "$channelKey:${program.startTime}:${program.endTime}:$contentHash"
}

private fun sha256Prefix(value: String, byteCount: Int = 12): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(byteCount)
        .joinToString("") { byte -> "%02x".format(byte) }

internal fun shouldReplaceTvPrograms(channel: Channel, programs: List<Program>): Boolean {
    if (programs.isNotEmpty()) return true
    return channel.epgChannelId.isNullOrBlank()
}
