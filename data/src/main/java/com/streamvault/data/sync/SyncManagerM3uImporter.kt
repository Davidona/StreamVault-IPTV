package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.dao.M3uClassificationDao
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.parser.M3uPlaybackMetadataCodec
import com.streamvault.data.parser.M3uMediaKind
import com.streamvault.data.parser.M3uSourceIdentity
import com.streamvault.data.parser.M3uVodClassifier
import com.streamvault.data.remote.http.HttpRequestProfile
import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.data.remote.http.safeRequestIdentitySummary
import com.streamvault.data.remote.http.toGenericRequestProfile
import com.streamvault.data.remote.http.withRequestProfile
import com.streamvault.data.util.AdultContentClassifier
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.repository.M3uClassificationRepository
import com.streamvault.domain.repository.M3uClassificationTarget
import com.streamvault.domain.repository.M3uSeriesAssignment
import com.streamvault.domain.sync.Section
import com.streamvault.domain.sync.SyncProgress
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.zip.GZIPInputStream

private const val M3U_PROGRESS_INTERVAL = 5_000
private const val M3U_IMPORTER_TAG = "SyncManagerM3u"

internal class SyncManagerM3uImporter(
    private val m3uParser: M3uParser,
    private val okHttpClient: OkHttpClient,
    private val syncCatalogStore: SyncCatalogStore,
    private val retryTransient: suspend (suspend () -> Unit) -> Unit,
    private val progress: (Long, ((String) -> Unit)?, String) -> Unit,
    private val emitProgress: (Long, SyncProgress) -> Unit,
    private val sizeLimits: CatalogSizeLimits = CatalogSizeLimits(),
    private val vodClassifier: M3uVodClassifier = M3uVodClassifier(),
    private val classificationDao: M3uClassificationDao? = null,
    private val classificationRepository: M3uClassificationRepository? = null
) {
    suspend fun importPlaylist(
        provider: Provider,
        onProgress: ((String) -> Unit)?,
        includeLive: Boolean = true,
        includeMovies: Boolean = true,
        batchSize: Int = 1000,
        afterCatalogApply: suspend () -> Unit = {}
    ): M3uImportStats {
        UrlSecurityPolicy.validatePlaylistSourceUrl(provider.m3uUrl.ifBlank { provider.serverUrl })?.let { message ->
            throw IllegalStateException(message)
        }
        progress(provider.id, onProgress, "Downloading Playlist...")
        // D14 — emission M3U etape Downloading : Section.LIVE par convention (le M3U
        // peut contenir un melange Live/VOD, mais l'UI ne distingue pas). Mode
        // indetermine (total = 0) puisqu'on ne connait pas encore la taille du flux.
        emitProgress(provider.id,
            SyncProgress(
                section = Section.LIVE,
                current = 0,
                total = 0,
                currentLabel = "",
                itemsIndexed = 0
            )
        )
        syncCatalogStore.clearProviderStaging(provider.id)
        val sessionId = syncCatalogStore.newSessionId()
        val stableLongHasher = StableLongHasher()
        val liveCategories = CategoryAccumulator(provider.id, ContentType.LIVE, stableLongHasher)
        val movieCategories = CategoryAccumulator(provider.id, ContentType.MOVIE, stableLongHasher)
        val channelBatch = ArrayList<ChannelEntity>(batchSize)
        val movieBatch = ArrayList<MovieEntity>(batchSize)
        val seenLiveStreamIds = if (includeLive) mutableSetOf<Long>() else null
        val seenMovieStreamIds = if (includeMovies) mutableSetOf<Long>() else null
        val seriesToReconcile = mutableListOf<Pair<Long, M3uSeriesAssignment?>>()
        val persistedOverrides = classificationDao?.getOverrides(provider.id).orEmpty()
        val itemOverrides = persistedOverrides.associateBy { it.sourceKey }
        // A movie that was automatically classified before the override tables existed may
        // have no source key, but its destination-independent stream id is still recoverable.
        val itemOverridesByStreamId = persistedOverrides.associateBy { it.streamId }
        val categoryRules = classificationDao?.getCategoryRules(provider.id).orEmpty().associateBy { it.groupKey }
        var header = M3uParser.M3uHeader()
        var liveCount = 0
        var movieCount = 0
        var parsedCount = 0
        var invalidEntryCount = 0
        var nextMilestone = M3U_PROGRESS_INTERVAL
        val warnings = mutableListOf<String>()
        var insecureStreamCount = 0

        fun enforceInvalidEntryRatio() {
            val candidateCount = parsedCount + invalidEntryCount
            if (candidateCount >= 100 &&
                invalidEntryCount * 100 > candidateCount * sizeLimits.maxM3uInvalidEntryRatioPercent
            ) {
                throw CatalogAdmissionExceeded("M3U invalid-entry ratio limit exceeded")
            }
        }

        try {
            openPlaylistStream(provider) { streamed ->
                streamed.contentLength
                    ?.takeIf { it > sizeLimits.maxM3uDecompressedBytes }
                    ?.let { throw CatalogAdmissionExceeded("M3U response length limit exceeded") }
                progress(provider.id, onProgress, "Parsing Playlist...")
                // D14 — emission M3U etape Parsing : meme section / mode indetermine.
                emitProgress(provider.id,
                    SyncProgress(
                        section = Section.LIVE,
                        current = 0,
                        total = 0,
                        currentLabel = "",
                        itemsIndexed = 0
                    )
                )
                BoundedInputStream(
                    input = maybeDecompressPlaylist(streamed),
                    maximumBytes = sizeLimits.maxM3uDecompressedBytes,
                    maximumLineBytes = sizeLimits.maxM3uLineBytes
                ).use { input ->
                    m3uParser.parseStreaming(
                        inputStream = input,
                        onHeader = { parsedHeader ->
                            requireM3uFieldBounds(
                                parsedHeader.tvgUrls + listOfNotNull(parsedHeader.userAgent)
                            )
                            val validEpgUrls = parsedHeader.tvgUrls.filter { UrlSecurityPolicy.validateOptionalEpgUrl(it) == null }
                            if (validEpgUrls.size != parsedHeader.tvgUrls.size) {
                                warnings += "Ignored unsupported EPG URL from playlist header."
                            }
                            header = parsedHeader.copy(tvgUrls = validEpgUrls)
                        },
                        onEntry = { entry ->
                        parsedCount++
                        if (parsedCount > sizeLimits.maxM3uEntries) {
                            throw CatalogAdmissionExceeded("M3U entry limit exceeded")
                        }
                        enforceInvalidEntryRatio()
                        requireM3uFieldBounds(
                            listOf(
                                entry.name,
                                entry.groupTitle,
                                entry.tvgId,
                                entry.tvgName,
                                entry.tvgLogo,
                                entry.tvgLanguage,
                                entry.tvgCountry,
                                entry.catchUp,
                                entry.catchUpSource,
                                entry.timeshift,
                                entry.url,
                                entry.userAgent,
                                entry.rating,
                                entry.year,
                                entry.genre,
                                entry.playbackMetadata?.let(M3uPlaybackMetadataCodec::encode)
                            )
                        )
                        if (parsedCount >= nextMilestone) {
                            progress(provider.id, onProgress, "Imported $parsedCount playlist entries...")
                            // D14 — emission M3U etape Imported : current = nombre d'entrees
                            // parsees jusqu'ici (palier de M3U_PROGRESS_INTERVAL), `itemsIndexed`
                            // refletera la meme valeur (compteur cumulatif local).
                            emitProgress(provider.id,
                                SyncProgress(
                                    section = Section.LIVE,
                                    current = parsedCount,
                                    total = 0,
                                    currentLabel = "",
                                    itemsIndexed = parsedCount
                                )
                            )
                            nextMilestone += M3U_PROGRESS_INTERVAL
                        }
                        if (!UrlSecurityPolicy.isAllowedStreamEntryUrl(entry.url)) {
                            insecureStreamCount++
                            return@parseStreaming
                        }

                        val safeLogoUrl = UrlSecurityPolicy.sanitizeImportedAssetUrl(entry.tvgLogo)
                        val safeCatchUpSource = UrlSecurityPolicy.sanitizeImportedAssetUrl(entry.catchUpSource)
                        val playbackMetadataJson = entry.playbackMetadata?.let(M3uPlaybackMetadataCodec::encode)

                        val sourceKey = M3uSourceIdentity.fromEntry(provider.id, entry)
                        val sourceStableId = M3uSourceIdentity.stableLongId(provider.id, entry)
                        val override = itemOverrides[sourceKey] ?: itemOverridesByStreamId[sourceStableId]
                        val groupRule = categoryRules[M3uSourceIdentity.groupKey(entry.groupTitle)]
                        val manualTarget = (override?.targetType ?: groupRule?.targetType)
                            ?.let { target -> runCatching { M3uClassificationTarget.valueOf(target) }.getOrNull() }
                        if (manualTarget == M3uClassificationTarget.SERIES) {
                            if (!includeLive) return@parseStreaming
                            val groupTitle = entry.groupTitle.ifBlank { "Uncategorized" }
                            val stableStreamId = override?.streamId
                                ?: sourceStableId
                            if (seenLiveStreamIds?.add(stableStreamId) != true) return@parseStreaming
                            val categoryId = liveCategories.idFor(groupTitle)
                            channelBatch.add(
                                ChannelEntity(
                                    streamId = stableStreamId,
                                    name = entry.name,
                                    logoUrl = safeLogoUrl,
                                    groupTitle = groupTitle,
                                    categoryId = categoryId,
                                    categoryName = groupTitle,
                                    epgChannelId = entry.tvgId ?: entry.tvgName,
                                    number = entry.tvgChno ?: 0,
                                    streamUrl = entry.url,
                                    catchUpSupported = !entry.catchUp.isNullOrBlank() ||
                                        !entry.catchUpSource.isNullOrBlank() ||
                                        !entry.timeshift.isNullOrBlank(),
                                    catchUpDays = entry.catchUpDays ?: 0,
                                    catchUpSource = safeCatchUpSource,
                                    providerId = provider.id,
                                    isAdult = AdultContentClassifier.isAdultCategoryName(groupTitle),
                                    playbackMetadataJson = playbackMetadataJson
                                )
                            )
                            seriesToReconcile += stableStreamId to override?.toSeriesAssignment()
                            liveCount++
                            if (channelBatch.size >= batchSize) flushChannelBatch(provider.id, sessionId, channelBatch)
                            return@parseStreaming
                        }

                        val mediaClassification = vodClassifier.classify(
                            entry = entry,
                            override = when {
                                manualTarget == M3uClassificationTarget.MOVIE -> M3uMediaKind.VOD
                                manualTarget == M3uClassificationTarget.LIVE -> M3uMediaKind.LIVE
                                provider.m3uVodClassificationEnabled -> null
                                else -> M3uMediaKind.LIVE
                            }
                        )
                        if (mediaClassification.isVod) {
                            if (!includeMovies) return@parseStreaming
                            val groupTitle = if (manualTarget == M3uClassificationTarget.MOVIE) {
                                "Movies"
                            } else {
                                entry.groupTitle.ifBlank { "Uncategorized" }
                            }
                            val stableStreamId = override?.streamId
                                ?: sourceStableId
                            if (seenMovieStreamIds?.add(stableStreamId) != true) return@parseStreaming
                            if (movieCount >= sizeLimits.maxMoviesPerProvider) throw CatalogAdmissionExceeded("M3U movie limit exceeded")
                            val categoryId = if (manualTarget == M3uClassificationTarget.MOVIE) {
                                manualCategoryId(provider.id, ContentType.MOVIE, stableLongHasher)
                            } else {
                                movieCategories.idFor(groupTitle)
                            }
                            if (manualTarget == M3uClassificationTarget.MOVIE) {
                                movieCategories.register("Movies", categoryId)
                            }
                            if (movieCategories.count > sizeLimits.maxM3uCategoriesPerType) throw CatalogAdmissionExceeded("M3U movie category limit exceeded")
                            val isAdult = AdultContentClassifier.isAdultCategoryName(groupTitle)
                            movieBatch.add(
                                MovieEntity(
                                    streamId = stableStreamId,
                                    name = entry.name,
                                    posterUrl = safeLogoUrl,
                                    categoryId = categoryId,
                                    categoryName = groupTitle,
                                    streamUrl = entry.url,
                                    providerId = provider.id,
                                    rating = entry.rating?.toFloatOrNull() ?: 0f,
                                    year = entry.year,
                                    genre = entry.genre,
                                    isAdult = isAdult,
                                    playbackMetadataJson = playbackMetadataJson
                                )
                            )
                            movieCount++
                            if (movieBatch.size >= batchSize) {
                                flushMovieBatch(provider.id, sessionId, movieBatch)
                            }
                        } else {
                            if (!includeLive) return@parseStreaming
                            val groupTitle = entry.groupTitle.ifBlank { "Uncategorized" }
                            val stableStreamId = override?.streamId
                                ?: sourceStableId
                            if (seenLiveStreamIds?.add(stableStreamId) != true) return@parseStreaming
                            if (liveCount >= sizeLimits.maxChannelsPerProvider) throw CatalogAdmissionExceeded("M3U live-channel limit exceeded")
                            val categoryId = liveCategories.idFor(groupTitle)
                            if (liveCategories.count > sizeLimits.maxM3uCategoriesPerType) throw CatalogAdmissionExceeded("M3U live category limit exceeded")
                            val isAdult = AdultContentClassifier.isAdultCategoryName(groupTitle)
                            channelBatch.add(
                                ChannelEntity(
                                    streamId = stableStreamId,
                                    name = entry.name,
                                    logoUrl = safeLogoUrl,
                                    groupTitle = groupTitle,
                                    categoryId = categoryId,
                                    categoryName = groupTitle,
                                    epgChannelId = entry.tvgId ?: entry.tvgName,
                                    number = entry.tvgChno ?: 0,
                                    streamUrl = entry.url,
                                    catchUpSupported = !entry.catchUp.isNullOrBlank() ||
                                        !entry.catchUpSource.isNullOrBlank() ||
                                        !entry.timeshift.isNullOrBlank(),
                                    catchUpDays = entry.catchUpDays ?: 0,
                                    catchUpSource = safeCatchUpSource,
                                    providerId = provider.id,
                                    isAdult = isAdult,
                                    playbackMetadataJson = playbackMetadataJson
                                )
                            )
                            liveCount++
                            if (channelBatch.size >= batchSize) {
                                flushChannelBatch(provider.id, sessionId, channelBatch)
                            }
                        }
                        },
                        onInvalidEntry = {
                        invalidEntryCount++
                        enforceInvalidEntryRatio()
                        },
                        declaredCharset = streamed.declaredCharset
                    )
                }
            }

            flushChannelBatch(provider.id, sessionId, channelBatch)
            flushMovieBatch(provider.id, sessionId, movieBatch)
            // Only commit a section if it produced at least one entry. Committing an
            // empty stage with includeLive=true runs stale deletion and wipes the entire
            // live-TV catalog — even though the absence of entries may reflect a server
            // error or a filtered playlist rather than a legitimate empty provider.
            // A successful full-playlist refresh must also commit an empty opposite section.
            // Otherwise changing the user's VOD-classification override leaves stale rows in
            // Movies or Live TV. Section-only retries retain the empty-catalog safeguard.
            val isSuccessfulFullRefresh = includeLive && includeMovies && parsedCount > 0
            val effectiveLive = includeLive && (liveCount > 0 || isSuccessfulFullRefresh)
            val effectiveMovies = includeMovies && (movieCount > 0 || isSuccessfulFullRefresh)
            syncCatalogStore.finalizeStagedImport(
                providerId = provider.id,
                sessionId = sessionId,
                liveCategories = if (effectiveLive) liveCategories.entities() else null,
                movieCategories = if (effectiveMovies) movieCategories.entities() else null,
                includeLive = effectiveLive,
                includeMovies = effectiveMovies,
                afterCatalogApply = afterCatalogApply
            )
            if (classificationRepository != null) {
                seriesToReconcile.forEach { (streamId, assignment) ->
                    when (val result = classificationRepository.classifyChannelByStream(
                        provider.id,
                        streamId,
                        M3uClassificationTarget.SERIES,
                        assignment
                    )) {
                        is com.streamvault.domain.model.Result.Error ->
                            warnings += result.message
                        else -> Unit
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                syncCatalogStore.discardStagedImport(provider.id, sessionId)
            }
        }

        if (insecureStreamCount > 0) {
            warnings += "Ignored $insecureStreamCount insecure playlist stream URL(s)."
        }

        return M3uImportStats(
            header = header,
            liveCount = liveCount,
            movieCount = movieCount,
            warnings = warnings
        )
    }

    private suspend fun openPlaylistStream(
        provider: Provider,
        block: suspend (StreamedPlaylist) -> Unit
    ) {
        val urlStr = provider.m3uUrl.ifBlank { provider.serverUrl }
        if (urlStr.startsWith("file:")) {
            java.io.File(java.net.URI(urlStr)).inputStream().use { input ->
                block(StreamedPlaylist(inputStream = input, contentLength = java.io.File(java.net.URI(urlStr)).length(), sourceName = urlStr))
            }
            return
        }

        val requestProfile = provider.toGenericRequestProfile(ownerTag = "provider:${provider.id}/m3u")
        retryTransient {
            val request = Request.Builder()
                .url(urlStr)
                .build()
                .withRequestProfile(requestProfile)
            okHttpClient.newCall(request).useCancellableResponse { response ->
                ensureSuccessfulPlaylistResponse(response, requestProfile)
                val body = response.body ?: throw IllegalStateException("Empty M3U response")
                body.byteStream().use { input ->
                    block(
                        StreamedPlaylist(
                            inputStream = input,
                            contentEncoding = response.header("Content-Encoding"),
                            contentLength = body.contentLength().takeIf { it >= 0L },
                            sourceName = urlStr,
                            declaredCharset = body.contentType()?.charset(null)
                        )
                    )
                }
            }
        }
    }

    private fun ensureSuccessfulPlaylistResponse(response: Response, requestProfile: HttpRequestProfile) {
        if (response.isSuccessful) return
        Log.w(
            M3U_IMPORTER_TAG,
            "Playlist request failed (${response.request.safeRequestIdentitySummary(requestProfile)}): HTTP ${response.code}"
        )
        if (response.code in 500..599 || response.code == 429) {
            // Transient — the retry wrapper will attempt again automatically.
            throw IOException("Transient HTTP ${response.code}")
        }
        // Non-transient failures: produce an actionable message so the user understands
        // exactly why this source was skipped (especially relevant for CombinedM3U profiles).
        val reason = when (response.code) {
            401 -> "subscription credentials were rejected (HTTP 401 Unauthorized) — check your username and password"
            403 -> "access was denied by the provider (HTTP 403 Forbidden) — your subscription may have expired or your IP is banned"
            404 -> "the playlist URL was not found on the server (HTTP 404 Not Found) — the provider URL may have changed"
            407 -> "a proxy authentication error occurred (HTTP 407) — check your network settings"
            else -> "the server returned an unexpected error (HTTP ${response.code})"
        }
        throw IllegalStateException("Failed to download M3U playlist: $reason")
    }

    private fun maybeDecompressPlaylist(streamed: StreamedPlaylist): InputStream {
        val buffered = if (streamed.inputStream is BufferedInputStream) {
            streamed.inputStream
        } else {
            BufferedInputStream(streamed.inputStream, 64 * 1024)
        }
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        val gzipMagic = first == 0x1f && second == 0x8b
        val encodedGzip = streamed.contentEncoding?.contains("gzip", ignoreCase = true) == true
        val namedGzip = streamed.sourceName?.lowercase()?.endsWith(".gz") == true
        return if (gzipMagic || encodedGzip || namedGzip) {
            GZIPInputStream(buffered, 64 * 1024)
        } else {
            buffered
        }
    }

    private suspend fun flushChannelBatch(providerId: Long, sessionId: Long, batch: MutableList<ChannelEntity>) {
        if (batch.isEmpty()) return
        syncCatalogStore.stageChannelBatch(providerId, sessionId, batch)
        batch.clear()
    }

    private suspend fun flushMovieBatch(providerId: Long, sessionId: Long, batch: MutableList<MovieEntity>) {
        if (batch.isEmpty()) return
        syncCatalogStore.stageMovieBatch(providerId, sessionId, batch)
        batch.clear()
    }

    private fun stableId(
        providerId: Long,
        contentType: ContentType,
        tvgId: String?,
        url: String,
        title: String,
        groupTitle: String?,
        hasher: StableLongHasher
    ): Long {
        val normalizedUrl = normalizeUrlForIdentity(url)
        val normalizedTvgId = tvgId?.trim()?.lowercase().orEmpty()
        val normalizedTitle = normalizeTextForIdentity(title)
        val normalizedGroup = normalizeTextForIdentity(groupTitle)
        val identity = if (normalizedTvgId.isNotBlank()) {
            "$providerId|${contentType.name}|tvg=$normalizedTvgId|url=$normalizedUrl"
        } else {
            "$providerId|${contentType.name}|url=$normalizedUrl|title=$normalizedTitle|group=$normalizedGroup"
        }
        return hasher.hash(identity)
    }

    private fun normalizeUrlForIdentity(url: String): String {
        val parsed = runCatching { URI(url) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase().orEmpty()
        val host = parsed?.host?.lowercase().orEmpty()
        val path = parsed?.path.orEmpty().trimEnd('/')
        val query = parsed?.query
            ?.split('&')
            ?.mapNotNull { pair ->
                val key = pair.substringBefore('=').lowercase()
                val value = pair.substringAfter('=', "")
                when (key) {
                    "token", "auth", "password", "username" -> null
                    else -> "$key=$value"
                }
            }
            ?.sorted()
            ?.joinToString("&")
            .orEmpty()
        return listOf(scheme, host, path, query)
            .joinToString("|")
            .ifBlank { url.trim().lowercase() }
    }

    private fun normalizeTextForIdentity(value: String?): String {
        return value.orEmpty().lowercase().replace(Regex("\\s+"), " ").trim()
    }

    private fun requireM3uFieldBounds(fields: Iterable<String?>) {
        if (fields.any { it != null && it.length > sizeLimits.maxM3uFieldLength }) {
            throw CatalogAdmissionExceeded("M3U field length limit exceeded")
        }
    }

    private class BoundedInputStream(
        input: InputStream,
        private val maximumBytes: Long,
        private val maximumLineBytes: Int
    ) : java.io.FilterInputStream(input) {
        private var readBytes = 0L
        private var lineBytes = 0

        override fun read(): Int = super.read().also { if (it >= 0) count(byteArrayOf(it.toByte())) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also {
            if (it > 0) count(buffer, offset, it)
        }

        private fun count(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
            readBytes += length
            if (readBytes > maximumBytes) throw CatalogAdmissionExceeded("M3U decompressed byte limit exceeded")
            for (index in offset until offset + length) {
                if (bytes[index].toInt().toChar() == '\n') {
                    lineBytes = 0
                } else {
                    lineBytes++
                    if (lineBytes > maximumLineBytes) throw CatalogAdmissionExceeded("M3U line length limit exceeded")
                }
            }
        }
    }

    private fun manualCategoryId(providerId: Long, type: ContentType, hasher: StableLongHasher): Long =
        hasher.hash("m3u-manual-category:$providerId:${type.name}").coerceAtLeast(1L)

    private fun com.streamvault.data.local.entity.M3uClassificationOverrideEntity.toSeriesAssignment(): M3uSeriesAssignment? {
        val name = seriesName?.takeIf(String::isNotBlank) ?: return null
        return M3uSeriesAssignment(
            seriesName = name,
            seasonNumber = seasonNumber ?: 1,
            episodeNumber = episodeNumber ?: 0,
            episodeTitle = episodeTitle
        )
    }
}

internal class CatalogAdmissionExceeded(message: String) : IllegalStateException(message)
