package com.streamvault.app.plugins

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.streamvault.app.BuildConfig
import com.streamvault.feature.playback.api.CastMediaRequest
import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.data.local.dao.PluginProviderOwnershipDao
import com.streamvault.data.local.entity.PluginProviderOwnershipEntity
import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.DrmInfo
import com.streamvault.domain.model.DrmScheme
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.provider.PluginProviderSource
import com.streamvault.domain.provider.PluginSourceIdentity
import com.streamvault.domain.provider.ProviderSource
import com.streamvault.domain.provider.ProviderSourceRegistry
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.ProviderSetupRequest
import com.streamvault.feature.system.api.InstalledStreamVaultPlugin
import com.streamvault.feature.system.api.PluginActionResult
import com.streamvault.feature.system.api.PluginConfigurationSchema
import com.streamvault.feature.system.api.PluginConfigurationSnapshot
import com.streamvault.feature.system.api.PluginDiscoveryState
import com.streamvault.feature.system.api.PluginDiscoveryStatus
import com.streamvault.feature.system.api.StreamVaultPluginComponent
import com.streamvault.feature.system.api.StreamVaultPluginContract
import com.streamvault.feature.system.api.StreamVaultPluginManifest
import com.streamvault.feature.system.api.StreamVaultPluginOwner
import com.streamvault.feature.system.api.owner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@Singleton
class StreamVaultPluginManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val messengerClient: PluginMessengerClient,
    private val providerRepository: ProviderRepository,
    private val pluginProviderOwnershipDao: PluginProviderOwnershipDao,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val tvInputChannelSyncManager: TvInputChannelSyncManager,
    private val pluginWorkCoordinator: PluginWorkCoordinator,
    private val okHttpClient: OkHttpClient,
    private val json: Json
) : ProviderSourceRegistry {
    private val prefs = context.getSharedPreferences("streamvault_plugins", Context.MODE_PRIVATE)
    private val discoveryLock = Any()
    private val pluginMutationMutex = Mutex()
    private val _discoveryStates =
        MutableStateFlow<Map<StreamVaultPluginComponent, PluginDiscoveryStatus>>(emptyMap())
    val discoveryStates: StateFlow<Map<StreamVaultPluginComponent, PluginDiscoveryStatus>> =
        _discoveryStates.asStateFlow()

    @Volatile
    private var cachedDiscovery: List<InstalledStreamVaultPlugin>? = null

    @Volatile
    private var discoveryExpiresAtMillis = 0L

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            invalidateDiscovery()
            // Package broadcasts are the authoritative lifecycle signal. Reconcile immediately
            // instead of waiting for the next process start, especially after external uninstall.
            val pendingResult = goAsync()
            pluginWorkCoordinator.launchReconciliation {
                try {
                    reconcilePluginProviders()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Startup reconciliation is the durable retry path.
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context.applicationContext,
            packageChangeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    suspend fun discoverPlugins(): List<InstalledStreamVaultPlugin> = withContext(Dispatchers.IO) {
        cachedDiscovery?.takeIf { System.currentTimeMillis() < discoveryExpiresAtMillis }?.let { return@withContext it }
        val resolveInfos = queryPluginServices()
        _discoveryStates.value = resolveInfos.mapNotNull(::componentOf)
            .associateWith { PluginDiscoveryStatus(PluginDiscoveryState.LOADING) }
        val plugins = supervisorScope {
            // Each service has its own bound, so one dead service cannot erase results from
            // services that already completed. Since all requests run concurrently, this is also
            // a bounded global discovery window independent of the number of installed plugins.
            val jobs = resolveInfos.map { resolveInfo ->
                async {
                    val component = componentOf(resolveInfo)
                    val attempt = withTimeoutOrNull(DISCOVERY_PLUGIN_TIMEOUT_MILLIS) {
                        runPluginCatching { resolvePlugin(resolveInfo) }
                    }
                    when {
                        attempt == null -> {
                            component?.let { key ->
                                _discoveryStates.update {
                                    it + (key to PluginDiscoveryStatus(
                                        PluginDiscoveryState.TIMED_OUT,
                                        "Plugin discovery timed out"
                                    ))
                                }
                            }
                            metadataOnlyPlugin(
                                resolveInfo,
                                "Plugin discovery timed out",
                                PluginDiscoveryState.TIMED_OUT
                            )
                        }
                        attempt.isFailure -> {
                            val error = attempt.exceptionOrNull()
                            component?.let { key ->
                                _discoveryStates.update {
                                    it + (key to PluginDiscoveryStatus(
                                        PluginDiscoveryState.ERROR,
                                        error?.message.orEmpty()
                                    ))
                                }
                            }
                            metadataOnlyPlugin(
                                resolveInfo,
                                error?.message.orEmpty().ifBlank { "Plugin discovery failed" },
                                PluginDiscoveryState.ERROR
                            )
                        }
                        else -> attempt.getOrNull().also { plugin ->
                            if (component != null && plugin != null) {
                                _discoveryStates.update {
                                    it + (component to PluginDiscoveryStatus(
                                        plugin.discoveryState,
                                        plugin.lastMessage
                                    ))
                                }
                            }
                        }
                    }
                }
            }
            // The per-service timeout is also the global bound because all services are queried
            // concurrently. Most importantly, awaitAll preserves completed services when one
            // peer times out instead of replacing the whole result set.
            jobs.awaitAll().filterNotNull()
        }
            .sortedBy { it.displayName.lowercase() }
        migrateLegacyEnabledStates(plugins)
        plugins.map { plugin -> plugin.copy(enabled = isEnabled(plugin)) }.also { discovered ->
            synchronized(discoveryLock) {
                cachedDiscovery = discovered
                discoveryExpiresAtMillis = System.currentTimeMillis() + DISCOVERY_CACHE_TTL_MILLIS
            }
        }
    }

    /**
     * Removes only providers whose Android service component is no longer installed.
     *
     * Manifest retrieval is intentionally absent from this decision: an unresponsive service,
     * malformed update, or manifest-ID rename is not proof that its provider is orphaned.
     */
    suspend fun reconcilePluginProviders() = withContext(Dispatchers.IO) {
        pluginMutationMutex.withLock {
            val installedComponents = queryPluginServices().mapNotNull { resolveInfo ->
                componentOf(resolveInfo)
            }.toSet()
            orphanedPluginOwnerships(pluginProviderOwnershipDao.getAll(), installedComponents)
                .forEach { ownership ->
                    if (removeOwnedProvider(ownership) == null) {
                        clearPendingMutation(
                            StreamVaultPluginOwner(
                                ownership.packageName,
                                ownership.serviceClassName,
                                ownership.manifestId
                            )
                        )
                    }
                }
            clearPendingMutationsForMissingComponents(installedComponents)

            // A mutation checkpoint is written before contacting a plugin. Replaying it here
            // closes process-death windows between remote enable/disable, provider import, and
            // ownership persistence. An enabled source with missing ownership is also repaired.
            discoverPlugins().forEach { plugin ->
                val pending = pendingMutation(plugin.owner)
                val desiredEnabled = pending ?: isEnabled(plugin)
                if (pending != null) {
                    val response = runPluginCatching {
                        sendEnabledCommand(plugin, desiredEnabled)
                    }.getOrNull() ?: return@forEach
                    if (!response.getBoolean(StreamVaultPluginContract.KEY_SUCCESS, true)) {
                        clearPendingMutation(plugin.owner)
                        return@forEach
                    }
                    prefs.edit().putBoolean(enabledKey(plugin.owner), desiredEnabled).apply()
                }
                if (!plugin.manifest.hasCapability(StreamVaultPluginContract.CAPABILITY_PROVIDER_M3U)) {
                    if (pending != null) clearPendingMutation(plugin.owner)
                    return@forEach
                }
                if (desiredEnabled) {
                    if (pending != null || trackedOwnership(plugin) == null) {
                        if (syncPluginProvider(plugin, onProgress = {}) == null) {
                            prefs.edit().putBoolean(enabledKey(plugin.owner), true).apply()
                            clearPendingMutation(plugin.owner)
                        }
                    }
                } else if (pending != null) {
                    if (removePluginProvider(plugin) == null) {
                        prefs.edit().putBoolean(enabledKey(plugin.owner), false).apply()
                        clearPendingMutation(plugin.owner)
                    }
                }
            }
        }
    }

    suspend fun setPluginEnabled(
        plugin: InstalledStreamVaultPlugin,
        enabled: Boolean,
        onProgress: (String) -> Unit = {}
    ): PluginActionResult = withContext(Dispatchers.IO) {
        pluginMutationMutex.withLock mutation@{
        if (!recordPendingMutation(plugin.owner, enabled)) {
            return@mutation PluginActionResult(false, "Could not persist plugin operation checkpoint")
        }
        val response = runPluginCatching {
            sendEnabledCommand(plugin, enabled)
        }.getOrElse { error ->
            return@mutation PluginActionResult(false, error.message ?: "Plugin did not respond")
        }

        if (!response.getBoolean(StreamVaultPluginContract.KEY_SUCCESS, true)) {
            clearPendingMutation(plugin.owner)
            return@mutation PluginActionResult(
                success = false,
                message = response.getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
                    .ifBlank { "Plugin rejected the request" }
            )
        }

        prefs.edit().putBoolean(enabledKey(plugin.owner), enabled).apply()
        if (enabled && plugin.manifest.hasCapability(StreamVaultPluginContract.CAPABILITY_PROVIDER_M3U)) {
            syncPluginProvider(plugin, onProgress)?.let { return@mutation it }
        } else if (!enabled) {
            removePluginProvider(plugin)?.let { return@mutation it }
        }

        clearPendingMutation(plugin.owner)
        invalidateDiscovery()
        PluginActionResult(
            success = true,
            message = response.getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
                .ifBlank { if (enabled) "Plugin activated" else "Plugin deactivated" }
        )
        }
    }

    suspend fun installApkFromUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val target = pluginApkFile("local-${System.currentTimeMillis()}.apk")
        runPluginCatching {
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open selected APK" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { error ->
            return@withContext Result.error("Could not copy selected plugin APK", error)
        }
        launchPackageInstaller(target)
    }

    suspend fun installApkFromUrl(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        val normalizedUrl = url.trim()
        if (!isHttpOrHttpsUrl(normalizedUrl)) {
            return@withContext Result.error("Plugin URL must be http or https")
        }

        val target = pluginApkFile("plugin-${System.currentTimeMillis()}.apk")
        runPluginCatching {
            target.parentFile?.mkdirs()
            val request = Request.Builder().url(normalizedUrl).build()
            okHttpClient.newCall(request).useCancellableResponse { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Empty response")
                target.outputStream().use { output -> body.byteStream().copyTo(output) }
            }
        }.onFailure { error ->
            return@withContext Result.error("Could not download plugin APK", error)
        }
        launchPackageInstaller(target)
    }

    fun openPluginConfiguration(plugin: InstalledStreamVaultPlugin): PluginActionResult {
        val action = plugin.manifest.configurationActivityAction?.takeIf { it.isNotBlank() }
            ?: return PluginActionResult(false, "This plugin has no configuration screen")
        val intent = Intent(action).apply {
            setPackage(plugin.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            PluginActionResult(true, "Opening ${plugin.displayName}")
        }.getOrElse { error ->
            PluginActionResult(false, error.message ?: "Could not open plugin settings")
        }
    }

    suspend fun loadPluginConfiguration(plugin: InstalledStreamVaultPlugin): Result<PluginConfigurationSnapshot> =
        withContext(Dispatchers.IO) {
            if (!plugin.manifest.supportsHostRenderedConfiguration) {
                return@withContext Result.error("This plugin does not expose a StreamVault configuration schema")
            }

            val schemaResponse = runPluginCatching {
                messengerClient.send(
                    packageName = plugin.packageName,
                    serviceClassName = plugin.serviceClassName,
                    what = StreamVaultPluginContract.MSG_GET_CONFIGURATION_SCHEMA,
                    timeoutMillis = 10_000L
                )
            }.getOrElse { error ->
                return@withContext Result.error(error.message ?: "Plugin configuration schema is unavailable")
            }
            if (!schemaResponse.getBoolean(StreamVaultPluginContract.KEY_SUCCESS, false)) {
                return@withContext Result.error(
                    schemaResponse.getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
                        .ifBlank { "Plugin configuration schema is unavailable" }
                )
            }

            val schemaJson = schemaResponse
                .getString(StreamVaultPluginContract.KEY_CONFIGURATION_SCHEMA_JSON)
                .orEmpty()
            val schema = runCatching { json.decodeFromString<PluginConfigurationSchema>(schemaJson) }
                .getOrElse { error ->
                    return@withContext Result.error(error.message ?: "Plugin configuration schema is invalid")
                }
            if (schema.sections.isEmpty() && schema.actions.isEmpty()) {
                return@withContext Result.error("Plugin configuration schema is empty")
            }

            val values = loadPluginConfigurationValues(plugin).getOrNull() ?: JsonObject(emptyMap())
            Result.success(PluginConfigurationSnapshot(plugin, schema, values))
        }

    suspend fun loadPluginConfigurationValues(plugin: InstalledStreamVaultPlugin): Result<JsonObject> =
        withContext(Dispatchers.IO) {
            val valuesResponse = runPluginCatching {
                messengerClient.send(
                    packageName = plugin.packageName,
                    serviceClassName = plugin.serviceClassName,
                    what = StreamVaultPluginContract.MSG_GET_CONFIGURATION_VALUES,
                    timeoutMillis = 10_000L
                )
            }.getOrElse { error ->
                return@withContext Result.error(error.message ?: "Plugin configuration values are unavailable")
            }
            if (!valuesResponse.getBoolean(StreamVaultPluginContract.KEY_SUCCESS, false)) {
                return@withContext Result.error(
                    valuesResponse.getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
                        .ifBlank { "Plugin configuration values are unavailable" }
                )
            }

            val valuesJson = valuesResponse
                .getString(StreamVaultPluginContract.KEY_CONFIGURATION_VALUES_JSON)
                .orEmpty()
            val values = if (valuesJson.isBlank()) {
                JsonObject(emptyMap())
            } else {
                runCatching { json.decodeFromString<JsonObject>(valuesJson) }
                    .getOrElse { error ->
                        return@withContext Result.error(error.message ?: "Plugin configuration values are invalid")
                    }
            }
            Result.success(values)
        }

    suspend fun savePluginConfiguration(
        plugin: InstalledStreamVaultPlugin,
        valuesJson: String
    ): PluginActionResult = withContext(Dispatchers.IO) {
        val response = runPluginCatching {
            messengerClient.send(
                packageName = plugin.packageName,
                serviceClassName = plugin.serviceClassName,
                what = StreamVaultPluginContract.MSG_SET_CONFIGURATION_VALUES,
                data = Bundle().apply {
                    putString(StreamVaultPluginContract.KEY_CONFIGURATION_VALUES_JSON, valuesJson)
                },
                timeoutMillis = 60_000L
            )
        }.getOrElse { error ->
            return@withContext PluginActionResult(false, error.message ?: "Plugin settings could not be saved")
        }
        response.toPluginActionResult("Plugin settings saved")
    }

    suspend fun runPluginConfigurationAction(
        plugin: InstalledStreamVaultPlugin,
        actionId: String
    ): PluginActionResult = withContext(Dispatchers.IO) {
        val response = runPluginCatching {
            messengerClient.send(
                packageName = plugin.packageName,
                serviceClassName = plugin.serviceClassName,
                what = StreamVaultPluginContract.MSG_RUN_CONFIGURATION_ACTION,
                data = Bundle().apply {
                    putString(StreamVaultPluginContract.KEY_CONFIGURATION_ACTION_ID, actionId)
                },
                timeoutMillis = 120_000L
            )
        }.getOrElse { error ->
            return@withContext PluginActionResult(false, error.message ?: "Plugin action failed")
        }
        response.toPluginActionResult("Plugin action completed")
    }

    suspend fun preparePlaybackUrl(url: String): Result<Unit> =
        preparePlaybackStreamInfo(StreamInfo(url = url)).map { Unit }

    suspend fun preparePlaybackStreamInfo(streamInfo: StreamInfo): Result<StreamInfo> = withContext(Dispatchers.IO) {
        val url = streamInfo.url
        if (url.isBlank()) return@withContext Result.success(streamInfo)
        val prepared = withPluginPlaybackDeadline(PLAYBACK_TOTAL_TIMEOUT_MILLIS) {
            val plugins = playbackCandidates(
                discoverPlugins(), url, StreamVaultPluginContract.CAPABILITY_PLAYBACK_PREPARE
            )
            coroutineScope {
                val requests = plugins.associateWith { plugin -> async {
                    runPluginCallOrNull {
                        messengerClient.send(
                            packageName = plugin.packageName,
                            serviceClassName = plugin.serviceClassName,
                            what = StreamVaultPluginContract.MSG_PREPARE_PLAYBACK,
                            data = Bundle().apply { putString(StreamVaultPluginContract.KEY_INPUT_URL, url) },
                            timeoutMillis = PLAYBACK_HANDLER_TIMEOUT_MILLIS
                        )
                    }
                } }
                for (plugin in plugins) {
                    val response = requests.getValue(plugin).await() ?: continue
                    if (!response.getBoolean(StreamVaultPluginContract.KEY_HANDLED, false)) continue
                    requests.values.forEach { it.cancel() }
                    return@coroutineScope if (response.getBoolean(StreamVaultPluginContract.KEY_SUCCESS, false)) {
                        Result.success(applyPlaybackPreparationResponse(streamInfo, response))
                    } else {
                        Result.error(response.getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
                            .ifBlank { "${plugin.displayName} could not prepare playback" })
                    }
                }
                Result.success(streamInfo)
            }
        }
        prepared ?: Result.success(streamInfo)
    }

    suspend fun rewriteCastUrl(request: CastMediaRequest): String? = withContext(Dispatchers.IO) {
        val url = request.url
        if (url.isBlank()) return@withContext url
        val rewritten = withPluginPlaybackDeadline(PLAYBACK_TOTAL_TIMEOUT_MILLIS) {
            val plugins = playbackCandidates(
                discoverPlugins(), url, StreamVaultPluginContract.CAPABILITY_CAST_REWRITE_URL
            )
            coroutineScope {
                val requests = plugins.associateWith { plugin -> async {
                    runPluginCallOrNull {
                        messengerClient.send(
                    packageName = plugin.packageName,
                    serviceClassName = plugin.serviceClassName,
                    what = StreamVaultPluginContract.MSG_REWRITE_CAST_URL,
                    data = request.toCastRewriteBundle(),
                            timeoutMillis = PLAYBACK_HANDLER_TIMEOUT_MILLIS
                        )
                    }
                } }
                for (plugin in plugins) {
                    val response = requests.getValue(plugin).await() ?: continue
                    if (!response.getBoolean(StreamVaultPluginContract.KEY_HANDLED, false)) continue
                    requests.values.forEach { it.cancel() }
                    return@coroutineScope if (response.getBoolean(StreamVaultPluginContract.KEY_SUCCESS, false)) {
                        response.getString(StreamVaultPluginContract.KEY_OUTPUT_URL).orEmpty().ifBlank { url }
                    } else null
                }
                url
            }
        }
        rewritten ?: url
    }

    suspend fun rewriteCastUrl(url: String): String? =
        rewriteCastUrl(CastMediaRequest(url = url, title = "StreamVault"))

    private fun applyPlaybackPreparationResponse(
        streamInfo: StreamInfo,
        response: Bundle
    ): StreamInfo {
        val outputUrl = response.getString(StreamVaultPluginContract.KEY_OUTPUT_URL).orEmpty()
            .ifBlank { streamInfo.url }
        val responseHeaders = parseHeadersJson(response.getString(StreamVaultPluginContract.KEY_HEADERS_JSON).orEmpty())
        val responseUserAgent = response.getString(StreamVaultPluginContract.KEY_USER_AGENT)
            ?.takeIf { it.isNotBlank() }
        val streamType = parsePluginStreamType(response.getString(StreamVaultPluginContract.KEY_STREAM_TYPE))
            ?: streamInfo.streamType
        val drmInfo = parsePluginDrmInfo(response.getString(StreamVaultPluginContract.KEY_DRM_JSON))
            ?: streamInfo.drmInfo
        return streamInfo.copy(
            url = outputUrl,
            headers = streamInfo.headers + responseHeaders,
            userAgent = responseUserAgent ?: streamInfo.userAgent,
            streamType = streamType,
            containerExtension = streamInfo.containerExtension ?: streamType.defaultContainerExtension(),
            drmInfo = drmInfo
        )
    }

    private fun CastMediaRequest.toCastRewriteBundle(): Bundle = Bundle().apply {
        putString(StreamVaultPluginContract.KEY_INPUT_URL, url)
        putString(StreamVaultPluginContract.KEY_STREAM_TYPE, mimeType.orEmpty())
        putString(StreamVaultPluginContract.KEY_HEADERS_JSON, headers.toHeadersJson())
        putString(StreamVaultPluginContract.KEY_USER_AGENT, userAgent.orEmpty())
        putBoolean(StreamVaultPluginContract.KEY_ALLOW_INVALID_SSL, allowInvalidSsl)
        playbackTransportPolicy?.let { policy ->
            putString(StreamVaultPluginContract.KEY_TRANSPORT_MODE, policy.mode.name)
            putString(StreamVaultPluginContract.KEY_TRANSPORT_ORIGIN, policy.origin.authority)
            putString(
                StreamVaultPluginContract.KEY_TRANSPORT_SPKI_SHA256,
                policy.spkiSha256.orEmpty()
            )
        }
        putString(StreamVaultPluginContract.KEY_PROXY_HOST, proxyHost)
        proxyPort?.let { putInt(StreamVaultPluginContract.KEY_PROXY_PORT, it) }
        putString(StreamVaultPluginContract.KEY_CAST_REWRITE_REASON, rewriteRequiredReason?.name.orEmpty())
    }

    private fun Map<String, String>.toHeadersJson(): String {
        if (isEmpty()) return ""
        val jsonObject = JSONObject()
        forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank() && name.isSafeHttpHeaderName()) {
                jsonObject.put(name, value)
            }
        }
        return jsonObject.toString()
    }

    private fun parseHeadersJson(raw: String): Map<String, String> =
        runCatching {
            if (raw.isBlank()) return@runCatching emptyMap()
            val jsonObject = JSONObject(raw)
            val headers = linkedMapOf<String, String>()
            jsonObject.keys().forEach { key ->
                val value = jsonObject.optString(key).takeIf { it.isNotBlank() } ?: return@forEach
                if (key.isSafeHttpHeaderName()) headers[key] = value
            }
            headers
        }.getOrDefault(emptyMap())

    private fun parsePluginDrmInfo(raw: String?): DrmInfo? =
        runCatching {
            if (raw.isNullOrBlank()) return@runCatching null
            val jsonObject = JSONObject(raw)
            val scheme = parsePluginDrmScheme(jsonObject.optString("scheme")) ?: return@runCatching null
            val licenseUrl = jsonObject.optString("licenseUrl").ifBlank {
                jsonObject.optString("license_url")
            }
            if (licenseUrl.isBlank()) return@runCatching null
            DrmInfo(
                scheme = scheme,
                licenseUrl = licenseUrl,
                headers = parseHeadersJson(jsonObject.optJSONObject("headers")?.toString().orEmpty()),
                multiSession = jsonObject.optBoolean("multiSession", false),
                forceDefaultLicenseUrl = jsonObject.optBoolean("forceDefaultLicenseUrl", true),
                playClearContentWithoutKey = jsonObject.optBoolean("playClearContentWithoutKey", true)
            )
        }.getOrNull()

    private fun parsePluginDrmScheme(raw: String): DrmScheme? {
        val normalized = raw.trim().lowercase()
        return when {
            normalized == "widevine" || normalized == "com.widevine.alpha" -> DrmScheme.WIDEVINE
            normalized == "playready" || normalized == "com.microsoft.playready" -> DrmScheme.PLAYREADY
            normalized == "clearkey" || normalized == "org.w3.clearkey" -> DrmScheme.CLEARKEY
            else -> null
        }
    }

    private fun parsePluginStreamType(raw: String?): StreamType? {
        val normalized = raw.orEmpty().trim().lowercase()
        return when (normalized) {
            "dash", "mpd", "application/dash+xml" -> StreamType.DASH
            "smooth_streaming", "smoothstreaming", "smooth-streaming", "ss", "ism", "isml",
            "application/vnd.ms-sstr+xml" -> StreamType.SMOOTH_STREAMING
            "hls", "m3u8", "application/vnd.apple.mpegurl", "application/x-mpegurl" -> StreamType.HLS
            "mpeg_ts", "mpeg-ts", "ts" -> StreamType.MPEG_TS
            "progressive", "file" -> StreamType.PROGRESSIVE
            "rtsp", "rtsps" -> StreamType.RTSP
            else -> null
        }
    }

    private suspend fun syncPluginProvider(
        plugin: InstalledStreamVaultPlugin,
        onProgress: (String) -> Unit
    ): PluginActionResult? {
        val providerResponse = runPluginCatching {
            messengerClient.send(
                packageName = plugin.packageName,
                serviceClassName = plugin.serviceClassName,
                what = StreamVaultPluginContract.MSG_GET_PROVIDER_URL,
                timeoutMillis = 120_000L
            )
        }.getOrElse { error ->
            return PluginActionResult(false, error.message ?: "Plugin provider URL is unavailable")
        }
        if (!providerResponse.getBoolean(StreamVaultPluginContract.KEY_SUCCESS, false)) {
            return PluginActionResult(
                false,
                providerResponse.getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
                    .ifBlank { "Plugin provider URL is unavailable" }
            )
        }

        val providerUrl = providerResponse.getString(StreamVaultPluginContract.KEY_URL).orEmpty()
        if (providerUrl.isBlank()) {
            return PluginActionResult(false, "Plugin did not return a provider URL")
        }
        val providerName = providerResponse.getString(StreamVaultPluginContract.KEY_PROVIDER_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: plugin.manifest.providerName?.takeIf { it.isNotBlank() }
            ?: "${plugin.displayName} Plugin"
        val existingProvider = trackedProvider(plugin)
        val activeSource = combinedM3uRepository.getActiveLiveSource().first()

        val provider = if (existingProvider != null) {
            val updatedProvider = existingProvider.copy(
                name = providerName,
                serverUrl = providerUrl,
                m3uUrl = providerUrl,
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                m3uVodClassificationEnabled = false,
                isActive = true,
                lastSyncedAt = 0L
            )
            when (val updateResult = providerRepository.updateProvider(updatedProvider)) {
                is Result.Error -> return PluginActionResult(false, updateResult.message)
                Result.Loading -> return PluginActionResult(false, "Provider update is still running")
                is Result.Success -> Unit
            }
            when (val refreshResult = providerRepository.refreshProviderData(
                providerId = updatedProvider.id,
                force = true,
                epgSyncModeOverride = ProviderEpgSyncMode.BACKGROUND,
                onProgress = onProgress
            )) {
                is Result.Error -> return PluginActionResult(false, refreshResult.message)
                Result.Loading -> return PluginActionResult(false, "Provider refresh is still running")
                is Result.Success -> Unit
            }
            updatedProvider
        } else {
            when (val createResult = providerRepository.setupProvider(
                request = ProviderSetupRequest.Configured(
                    name = providerName,
                    configuration = M3uConfig(
                        playlistUrl = providerUrl,
                        epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                        vodClassificationEnabled = false
                    )
                ),
                onProgress = onProgress
            )) {
                is Result.Error -> return PluginActionResult(false, createResult.message)
                Result.Loading -> return PluginActionResult(false, "Provider sync is still running")
                is Result.Success -> createResult.data
            }
        }

        pluginProviderOwnershipDao.upsert(
            PluginProviderOwnershipEntity(
                packageName = plugin.packageName,
                serviceClassName = plugin.serviceClassName,
                manifestId = plugin.manifest.id,
                providerId = provider.id
            )
        )
        providerRepository.setActiveProvider(provider.id)
        attachProviderToLiveSource(provider.id, activeSource)
        refreshTvInputCatalogInBackground()
        return null
    }

    private suspend fun sendEnabledCommand(
        plugin: InstalledStreamVaultPlugin,
        enabled: Boolean
    ): Bundle = messengerClient.send(
        packageName = plugin.packageName,
        serviceClassName = plugin.serviceClassName,
        what = StreamVaultPluginContract.MSG_SET_ENABLED,
        data = Bundle().apply {
            putBoolean(StreamVaultPluginContract.KEY_ENABLED, enabled)
        },
        timeoutMillis = 120_000L
    )

    private suspend fun removePluginProvider(plugin: InstalledStreamVaultPlugin): PluginActionResult? {
        val ownership = trackedOwnership(plugin) ?: return null
        return removeOwnedProvider(ownership)
    }

    private suspend fun attachProviderToLiveSource(providerId: Long, activeSource: ActiveLiveSource?) {
        when (activeSource) {
            is ActiveLiveSource.CombinedM3uSource -> {
                combinedM3uRepository.addProvider(activeSource.profileId, providerId)
                combinedM3uRepository.setActiveLiveSource(activeSource)
            }
            is ActiveLiveSource.ProviderSource,
            null -> combinedM3uRepository.setActiveLiveSource(ActiveLiveSource.ProviderSource(providerId))
        }
    }

    private suspend fun trackedProvider(plugin: InstalledStreamVaultPlugin): Provider? {
        val ownership = trackedOwnership(plugin) ?: return null
        return providerRepository.getProvider(ownership.providerId)
    }

    /**
     * Resolves an exact owner first. A sole mapping for the same installed component is an
     * unambiguous manifest-ID rename and is re-keyed atomically. Multiple legacy mappings are
     * never guessed between.
     */
    private suspend fun trackedOwnership(
        plugin: InstalledStreamVaultPlugin
    ): PluginProviderOwnershipEntity? {
        val owner = plugin.owner
        pluginProviderOwnershipDao.get(
            owner.packageName,
            owner.serviceClassName,
            owner.manifestId
        )?.let { return it }
        val componentOwnerships = pluginProviderOwnershipDao.getByComponent(
            owner.packageName,
            owner.serviceClassName
        )
        val ownership = selectPluginOwnership(owner, componentOwnerships) ?: return null
        return pluginProviderOwnershipDao.rekeyManifestId(ownership, owner.manifestId)
    }

    private fun refreshTvInputCatalogInBackground() {
        pluginWorkCoordinator.replaceCatalogRefresh(tvInputChannelSyncManager::refreshTvInputCatalog)
    }

    override suspend fun sources(): List<ProviderSource> = withContext(Dispatchers.IO) {
        val pluginsByComponent = discoverPlugins().associateBy { it.owner.component }
        pluginProviderOwnershipDao.getAll().mapNotNull { ownership ->
            val component = StreamVaultPluginComponent(ownership.packageName, ownership.serviceClassName)
            val plugin = pluginsByComponent[component] ?: return@mapNotNull null
            PluginProviderSource(
                identity = PluginSourceIdentity(
                    packageName = plugin.packageName,
                    serviceClassName = plugin.serviceClassName,
                    manifestId = plugin.manifest.id
                ),
                providerId = ownership.providerId,
                enabled = plugin.enabled,
                capabilities = plugin.manifest.capabilities.toSet(),
                backingProviderType = ProviderType.M3U
            )
        }
    }

    suspend fun pluginProviderSources(): List<PluginProviderSource> =
        sources().filterIsInstance<PluginProviderSource>()

    private suspend fun resolvePlugin(resolveInfo: ResolveInfo): InstalledStreamVaultPlugin? = coroutineScope {
        val serviceInfo = resolveInfo.serviceInfo ?: return@coroutineScope null
        val packageName = serviceInfo.packageName ?: return@coroutineScope null
        val serviceName = serviceInfo.name ?: return@coroutineScope null
        val appLabel = serviceInfo.loadLabel(context.packageManager)?.toString().orEmpty()
        val metadataManifest = readManifestFromMetadata(serviceInfo.metaData)
        val manifestResult = async { readManifestFromService(packageName, serviceName) }
        val statusResult = async {
            runPluginCallOrNull {
                messengerClient.send(
                    packageName = packageName,
                    serviceClassName = serviceName,
                    what = StreamVaultPluginContract.MSG_GET_STATUS,
                    timeoutMillis = DISCOVERY_REQUEST_TIMEOUT_MILLIS
                )
            }
        }
        val manifest = manifestResult.await()
            ?: metadataManifest
            ?: StreamVaultPluginManifest(
                id = packageName,
                name = appLabel.ifBlank { packageName },
                description = "StreamVault plugin"
            )
        val status = statusResult.await()
        InstalledStreamVaultPlugin(
            packageName = packageName,
            serviceClassName = serviceName,
            appLabel = appLabel,
            manifest = manifest,
            enabled = false,
            statusLabel = status?.getString(StreamVaultPluginContract.KEY_STATUS_LABEL).orEmpty(),
            lastMessage = status?.getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty(),
            discoveryState = if (status == null || manifestResult.await() == null) {
                PluginDiscoveryState.PARTIAL
            } else {
                PluginDiscoveryState.READY
            }
        )
    }

    private fun metadataOnlyPlugin(
        resolveInfo: ResolveInfo,
        errorMessage: String,
        discoveryState: PluginDiscoveryState = PluginDiscoveryState.TIMED_OUT
    ): InstalledStreamVaultPlugin? {
        val serviceInfo = resolveInfo.serviceInfo ?: return null
        val packageName = serviceInfo.packageName ?: return null
        val serviceName = serviceInfo.name ?: return null
        val appLabel = serviceInfo.loadLabel(context.packageManager)?.toString().orEmpty()
        val manifest = readManifestFromMetadata(serviceInfo.metaData)
            ?: StreamVaultPluginManifest(
                id = packageName,
                name = appLabel.ifBlank { packageName },
                description = "StreamVault plugin"
            )
        return InstalledStreamVaultPlugin(
            packageName = packageName,
            serviceClassName = serviceName,
            appLabel = appLabel,
            manifest = manifest,
            enabled = false,
            lastMessage = errorMessage,
            discoveryState = discoveryState
        )
    }

    private suspend fun readManifestFromService(packageName: String, serviceName: String): StreamVaultPluginManifest? =
        runPluginCallOrNull {
            val response =
                messengerClient.send(
                    packageName = packageName,
                    serviceClassName = serviceName,
                    what = StreamVaultPluginContract.MSG_GET_MANIFEST,
                    timeoutMillis = DISCOVERY_REQUEST_TIMEOUT_MILLIS
                )
            val manifestJson = response.getString(StreamVaultPluginContract.KEY_MANIFEST_JSON).orEmpty()
            json.decodeFromString<StreamVaultPluginManifest>(manifestJson)
        }

    private fun readManifestFromMetadata(metaData: Bundle?): StreamVaultPluginManifest? {
        if (metaData == null) return null

        val manifestJson = metaData.metaString(StreamVaultPluginContract.META_MANIFEST_JSON)
        if (manifestJson.isNotBlank()) {
            runCatching { json.decodeFromString<StreamVaultPluginManifest>(manifestJson) }
                .getOrNull()
                ?.let { return it }
        }

        val id = metaData.metaString(StreamVaultPluginContract.META_ID).takeIf { it.isNotBlank() }
            ?: return null
        val name = metaData.metaString(StreamVaultPluginContract.META_NAME).ifBlank { id }
        return StreamVaultPluginManifest(
            id = id,
            name = name,
            versionName = metaData.metaString(StreamVaultPluginContract.META_VERSION_NAME),
            versionCode = metaData.metaLong(StreamVaultPluginContract.META_VERSION_CODE),
            description = metaData.metaString(StreamVaultPluginContract.META_DESCRIPTION),
            capabilities = metaData.metaCsv(StreamVaultPluginContract.META_CAPABILITIES),
            configurationMode = metaData.metaString(StreamVaultPluginContract.META_CONFIGURATION_MODE)
                .takeIf { it.isNotBlank() },
            configurationActivityAction = metaData
                .metaString(StreamVaultPluginContract.META_CONFIGURATION_ACTIVITY_ACTION)
                .takeIf { it.isNotBlank() },
            providerName = metaData.metaString(StreamVaultPluginContract.META_PROVIDER_NAME)
                .takeIf { it.isNotBlank() }
        )
    }

    private fun queryPluginServices(): List<ResolveInfo> {
        val intent = Intent(StreamVaultPluginContract.ACTION_PLUGIN_SERVICE)
        val packageManager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }
    }

    private fun componentOf(resolveInfo: ResolveInfo): StreamVaultPluginComponent? {
        val serviceInfo = resolveInfo.serviceInfo ?: return null
        val packageName = serviceInfo.packageName ?: return null
        val serviceName = serviceInfo.name ?: return null
        return StreamVaultPluginComponent(packageName, serviceName)
    }

    private fun launchPackageInstaller(apkFile: File): Result<Unit> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return Result.error("Allow installs from StreamVault, then choose the plugin APK again")
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return try {
            context.startActivity(installIntent)
            Result.success(Unit)
        } catch (error: ActivityNotFoundException) {
            Result.error("No package installer is available on this device", error)
        } catch (error: SecurityException) {
            Result.error("The package installer could not be launched", error)
        }
    }

    private fun pluginApkFile(fileName: String): File {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.cacheDir, "downloads")
        return File(downloadsDir, "plugin-apks/$fileName")
    }

    private fun isHttpOrHttpsUrl(value: String): Boolean =
        runCatching {
            val parsed = URI(value)
            parsed.scheme.equals("http", ignoreCase = true) ||
                parsed.scheme.equals("https", ignoreCase = true)
        }.getOrDefault(false)

    private fun invalidateDiscovery() {
        synchronized(discoveryLock) {
            cachedDiscovery = null
            discoveryExpiresAtMillis = 0L
        }
    }

    private suspend fun <T> runPluginCallOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend inline fun <T> runPluginCatching(
        block: suspend () -> T
    ): kotlin.Result<T> = try {
        kotlin.Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        kotlin.Result.failure(error)
    }

    private suspend fun removeOwnedProvider(ownership: PluginProviderOwnershipEntity): PluginActionResult? {
        when (val result = providerRepository.deleteProvider(ownership.providerId)) {
            is Result.Error -> return PluginActionResult(false, result.message)
            Result.Loading -> return PluginActionResult(false, "Provider removal is still running")
            is Result.Success -> Unit
        }
        val activeSource = combinedM3uRepository.getActiveLiveSource().first()
        if (activeSource is ActiveLiveSource.ProviderSource && activeSource.providerId == ownership.providerId) {
            combinedM3uRepository.setActiveLiveSource(null)
        }
        pluginProviderOwnershipDao.delete(ownership.packageName, ownership.serviceClassName, ownership.manifestId)
        refreshTvInputCatalogInBackground()
        return null
    }

    private fun isEnabled(plugin: InstalledStreamVaultPlugin): Boolean =
        prefs.getBoolean(enabledKey(plugin.owner), false)

    /** Legacy state is safe to migrate only when no installed service shares its manifest ID. */
    private fun migrateLegacyEnabledStates(plugins: List<InstalledStreamVaultPlugin>) {
        val uniqueManifestIds = plugins.groupingBy { it.manifest.id }.eachCount()
            .filterValues { it == 1 }
            .keys
        val editor = prefs.edit()
        plugins.filter { it.manifest.id in uniqueManifestIds }.forEach { plugin ->
            val newKey = enabledKey(plugin.owner)
            val legacyKey = legacyEnabledKey(plugin.manifest.id)
            if (!prefs.contains(newKey) && prefs.contains(legacyKey)) {
                editor.putBoolean(newKey, prefs.getBoolean(legacyKey, false))
            }
        }
        editor.apply()
    }

    private fun enabledKey(owner: StreamVaultPluginOwner): String =
        "enabled.${owner.packageName}.${owner.serviceClassName}.${owner.manifestId}"

    private fun pendingMutationKey(owner: StreamVaultPluginOwner): String =
        "pending.${owner.packageName}|${owner.serviceClassName}|${owner.manifestId}"

    private fun recordPendingMutation(owner: StreamVaultPluginOwner, enabled: Boolean): Boolean =
        prefs.edit().putBoolean(pendingMutationKey(owner), enabled).commit()

    private fun pendingMutation(owner: StreamVaultPluginOwner): Boolean? =
        pendingMutationKey(owner).let { key ->
            if (prefs.contains(key)) prefs.getBoolean(key, false) else null
        }

    private fun clearPendingMutation(owner: StreamVaultPluginOwner) {
        prefs.edit().remove(pendingMutationKey(owner)).apply()
    }

    private fun clearPendingMutationsForMissingComponents(
        installedComponents: Set<StreamVaultPluginComponent>
    ) {
        val editor = prefs.edit()
        prefs.all.keys.asSequence()
            .filter { it.startsWith(PENDING_MUTATION_PREFIX) }
            .forEach { key ->
                val identity = key.removePrefix(PENDING_MUTATION_PREFIX).split('|', limit = 3)
                if (identity.size < 2 ||
                    StreamVaultPluginComponent(identity[0], identity[1]) !in installedComponents
                ) {
                    editor.remove(key)
                }
            }
        editor.apply()
    }

    private fun legacyEnabledKey(pluginId: String): String = "enabled.$pluginId"

    private companion object {
        const val DISCOVERY_CACHE_TTL_MILLIS = 30_000L
        const val DISCOVERY_REQUEST_TIMEOUT_MILLIS = 1_500L
        const val DISCOVERY_PLUGIN_TIMEOUT_MILLIS = 2_500L
        const val PLAYBACK_HANDLER_TIMEOUT_MILLIS = 5_000L
        const val PLAYBACK_TOTAL_TIMEOUT_MILLIS = 5_000L
        const val PENDING_MUTATION_PREFIX = "pending."
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCatching(block: suspend () -> Unit) {
    launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best-effort catalog refresh; the next provider/plugin change retries it.
        }
    }
}

private fun StreamType.defaultContainerExtension(): String? = when (this) {
    StreamType.DASH -> "mpd"
    StreamType.SMOOTH_STREAMING -> "ism"
    StreamType.HLS -> "m3u8"
    StreamType.MPEG_TS -> "ts"
    StreamType.PROGRESSIVE,
    StreamType.RTSP,
    StreamType.UNKNOWN -> null
}

private fun String.isSafeHttpHeaderName(): Boolean =
    isNotBlank() && all { char ->
        char.isLetterOrDigit() || char in setOf('!', '#', '$', '%', '&', '\'', '*', '+', '.', '^', '_', '`', '|', '~', '-')
    }

internal fun orphanedPluginOwnerships(
    ownerships: List<PluginProviderOwnershipEntity>,
    installedComponents: Set<StreamVaultPluginComponent>
): List<PluginProviderOwnershipEntity> = ownerships.filter { ownership ->
    StreamVaultPluginComponent(ownership.packageName, ownership.serviceClassName) !in installedComponents
}

internal fun selectPluginOwnership(
    owner: StreamVaultPluginOwner,
    componentOwnerships: List<PluginProviderOwnershipEntity>
): PluginProviderOwnershipEntity? {
    componentOwnerships.firstOrNull { ownership ->
        ownership.packageName == owner.packageName &&
            ownership.serviceClassName == owner.serviceClassName &&
            ownership.manifestId == owner.manifestId
    }?.let { return it }
    return componentOwnerships
        .filter { ownership ->
            ownership.packageName == owner.packageName &&
                ownership.serviceClassName == owner.serviceClassName
        }
        .singleOrNull()
}

@Suppress("DEPRECATION")
private fun Bundle.metaString(key: String): String = when (val value = get(key)) {
    is String -> value
    is CharSequence -> value.toString()
    is Number -> value.toString()
    is Boolean -> value.toString()
    else -> ""
}

@Suppress("DEPRECATION")
private fun Bundle.metaLong(key: String): Long = when (val value = get(key)) {
    is Long -> value
    is Int -> value.toLong()
    is Short -> value.toLong()
    is Byte -> value.toLong()
    is String -> value.toLongOrNull() ?: 0L
    else -> 0L
}

private fun Bundle.metaCsv(key: String): List<String> =
    metaString(key)
        .split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() }

private fun Bundle.toPluginActionResult(successMessage: String): PluginActionResult {
    val success = getBoolean(StreamVaultPluginContract.KEY_SUCCESS, false)
    return PluginActionResult(
        success = success,
        message = getString(StreamVaultPluginContract.KEY_MESSAGE).orEmpty()
            .ifBlank { if (success) successMessage else "Plugin operation failed" }
    )
}
