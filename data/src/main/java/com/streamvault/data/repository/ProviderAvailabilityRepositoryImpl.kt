package com.streamvault.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.streamvault.data.provider.StalkerClientOptions
import com.streamvault.data.provider.TypedProviderClientFactory
import com.streamvault.data.remote.http.buildGenericProviderRequestProfile
import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.data.remote.http.withRequestProfile
import com.streamvault.data.remote.jellyfin.JellyfinProvider
import com.streamvault.data.remote.stalker.StalkerApiError
import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.data.remote.xtream.XtreamNetworkException
import com.streamvault.data.remote.xtream.XtreamRequestException
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.ProviderAvailabilityResult
import com.streamvault.domain.model.ProviderAvailabilityStatus
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.provider.CapabilityResolution
import com.streamvault.domain.repository.ProviderAvailabilityRepository
import com.streamvault.domain.repository.ProviderSnapshotRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class ProviderAvailabilityRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val snapshots: ProviderSnapshotRepository,
    private val clients: TypedProviderClientFactory,
    private val jellyfinProvider: JellyfinProvider,
    okHttpClient: OkHttpClient
) : ProviderAvailabilityRepository {
    private val probeClient = okHttpClient.newBuilder()
        .connectTimeout(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .build()
    private val concurrentChecks = Semaphore(MAX_CONCURRENT_CHECKS)

    override suspend fun check(providerId: Long): ProviderAvailabilityResult =
        concurrentChecks.withPermit {
            val status = try {
                if (!hasActiveNetwork()) {
                    ProviderAvailabilityStatus.UNKNOWN
                } else {
                    val snapshot = snapshots.getSnapshot(providerId)
                    if (snapshot == null) {
                        ProviderAvailabilityStatus.UNKNOWN
                    } else {
                        checkWithConfirmation(snapshot)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProviderAvailabilityStatus.UNKNOWN
            }
            ProviderAvailabilityResult(status = status, checkedAt = System.currentTimeMillis())
        }

    private suspend fun checkWithConfirmation(
        snapshot: com.streamvault.domain.model.ProviderSnapshot
    ): ProviderAvailabilityStatus {
        val first = probeOnce(snapshot)
        if (first != ProviderAvailabilityStatus.UNAVAILABLE) return first

        // A second independent failure avoids calling a transient timeout an outage.
        delay(RETRY_DELAY_MILLIS)
        return probeOnce(snapshot)
    }

    private suspend fun probeOnce(
        snapshot: com.streamvault.domain.model.ProviderSnapshot
    ): ProviderAvailabilityStatus {
        val status = withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) {
            when (val configuration = snapshot.configuration) {
                is XtreamConfig -> checkXtream(snapshot)
                is StalkerConfig -> checkStalker(snapshot)
                is JellyfinConfig -> jellyfinProvider.checkAvailability(
                    serverUrl = configuration.serverUrl,
                    username = configuration.username,
                    accessToken = configuration.credential
                )
                is M3uConfig -> checkM3u(configuration)
            }
        }
        return status ?: ProviderAvailabilityStatus.UNAVAILABLE
    }

    private suspend fun checkXtream(
        snapshot: com.streamvault.domain.model.ProviderSnapshot
    ): ProviderAvailabilityStatus {
        val authenticator = when (val resolution = clients.xtream(snapshot)) {
            is CapabilityResolution.Available -> resolution.capability
            else -> return ProviderAvailabilityStatus.UNKNOWN
        }
        return when (val result = authenticator.authenticate()) {
            is com.streamvault.domain.model.Result.Success -> when (result.data.status) {
                ProviderStatus.EXPIRED,
                ProviderStatus.DISABLED -> ProviderAvailabilityStatus.AUTHENTICATION_REQUIRED
                else -> ProviderAvailabilityStatus.ONLINE
            }
            is com.streamvault.domain.model.Result.Error -> result.exception.toAvailabilityStatus()
            is com.streamvault.domain.model.Result.Loading -> ProviderAvailabilityStatus.UNKNOWN
        }
    }

    private suspend fun checkStalker(
        snapshot: com.streamvault.domain.model.ProviderSnapshot
    ): ProviderAvailabilityStatus {
        val provider = when (val resolution = clients.stalker(
            snapshot,
            StalkerClientOptions(requireCatalogValidation = false)
        )) {
            is CapabilityResolution.Available -> resolution.capability
            else -> return ProviderAvailabilityStatus.UNKNOWN
        }
        return when (val result = provider.getAccountProfile()) {
            is com.streamvault.domain.model.Result.Success -> {
                val profile = result.data
                val accountStatus = profile.statusLabel?.trim()?.lowercase(Locale.ROOT)
                if (
                    profile.authAccess == false ||
                    profile.expirationDate?.let { it > 0L && it < System.currentTimeMillis() } == true ||
                    accountStatus in setOf("disabled", "blocked", "banned", "expired")
                ) {
                    ProviderAvailabilityStatus.AUTHENTICATION_REQUIRED
                } else {
                    ProviderAvailabilityStatus.ONLINE
                }
            }
            is com.streamvault.domain.model.Result.Error -> result.exception.toAvailabilityStatus()
            is com.streamvault.domain.model.Result.Loading -> ProviderAvailabilityStatus.UNKNOWN
        }
    }

    private suspend fun checkM3u(configuration: M3uConfig): ProviderAvailabilityStatus {
        if (configuration.playlistUrl.isBlank()) return ProviderAvailabilityStatus.UNKNOWN
        val profile = buildGenericProviderRequestProfile(
            ownerTag = "provider-availability/m3u",
            httpUserAgent = configuration.httpUserAgent,
            httpHeaders = configuration.httpHeaders
        )
        val request = Request.Builder()
            .url(configuration.playlistUrl)
            .get()
            .build()
            .withRequestProfile(profile)
        return try {
            probeClient.newCall(request).useCancellableResponse { response ->
                when {
                    response.isSuccessful -> ProviderAvailabilityStatus.ONLINE
                    response.code == 401 || response.code == 403 ->
                        ProviderAvailabilityStatus.AUTHENTICATION_REQUIRED
                    response.code == 429 -> ProviderAvailabilityStatus.UNKNOWN
                    else -> ProviderAvailabilityStatus.UNAVAILABLE
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error.toAvailabilityStatus()
        }
    }

    private fun Throwable?.toAvailabilityStatus(): ProviderAvailabilityStatus {
        val causes = generateSequence(this) { it.cause }.toList()
        if (causes.any { it is XtreamAuthenticationException }) {
            return ProviderAvailabilityStatus.AUTHENTICATION_REQUIRED
        }
        val stalkerError = causes.filterIsInstance<StalkerApiError>().firstOrNull()
        if (stalkerError is StalkerApiError.Authorization) {
            return ProviderAvailabilityStatus.AUTHENTICATION_REQUIRED
        }
        if (stalkerError is StalkerApiError.Server) {
            return when (stalkerError.httpStatus) {
                429 -> ProviderAvailabilityStatus.UNKNOWN
                else -> if (stalkerError.httpStatus?.let { it in 500..599 } == true) {
                    ProviderAvailabilityStatus.UNAVAILABLE
                } else {
                    ProviderAvailabilityStatus.UNKNOWN
                }
            }
        }
        if (stalkerError is StalkerApiError.Transport) {
            return ProviderAvailabilityStatus.UNAVAILABLE
        }
        if (stalkerError != null) {
            return ProviderAvailabilityStatus.UNKNOWN
        }
        val xtreamNetworkError = causes.filterIsInstance<XtreamNetworkException>().firstOrNull()
        if (xtreamNetworkError != null) {
            return if (xtreamNetworkError.statusCode == 429) {
                ProviderAvailabilityStatus.UNKNOWN
            } else {
                ProviderAvailabilityStatus.UNAVAILABLE
            }
        }
        val xtreamRequestError = causes.filterIsInstance<XtreamRequestException>().firstOrNull()
        if (xtreamRequestError != null) {
            return if (xtreamRequestError.statusCode == 401 || xtreamRequestError.statusCode == 403) {
                ProviderAvailabilityStatus.AUTHENTICATION_REQUIRED
            } else {
                ProviderAvailabilityStatus.UNKNOWN
            }
        }
        return if (causes.any { it is IOException }) {
            ProviderAvailabilityStatus.UNAVAILABLE
        } else {
            ProviderAvailabilityStatus.UNKNOWN
        }
    }

    private fun hasActiveNetwork(): Boolean {
        return try {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val network = manager.activeNetwork ?: return false
            manager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: SecurityException) {
            true
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 4_000L
        const val RETRY_DELAY_MILLIS = 300L
        const val MAX_CONCURRENT_CHECKS = 2
    }
}
