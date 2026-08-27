package com.streamvault.feature.settings.presentation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.TimeUnit

enum class InternetSpeedTestTransport {
    WIFI,
    ETHERNET,
    CELLULAR,
    OTHER,
    UNKNOWN
}

data class InternetSpeedTestSnapshot(
    val megabitsPerSecond: Double,
    val recommendedMaxVideoHeight: Int?,
    val measuredAtMs: Long,
    val transport: InternetSpeedTestTransport,
    val isEstimated: Boolean
)

sealed interface InternetSpeedTestResult {
    data class Success(val snapshot: InternetSpeedTestSnapshot) : InternetSpeedTestResult
    data class Error(val message: String) : InternetSpeedTestResult
}

@Singleton
class InternetSpeedTestRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadSpeedProbe: InternetDownloadSpeedProbe
) {
    suspend fun run(): InternetSpeedTestResult = withContext(Dispatchers.IO) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@withContext InternetSpeedTestResult.Error("Network service unavailable")
        val network = connectivityManager.activeNetwork
            ?: return@withContext InternetSpeedTestResult.Error("No active network connection")
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return@withContext InternetSpeedTestResult.Error("Unable to inspect active network")

        val transport = capabilities.toTransport()
        val measuredAtMs = System.currentTimeMillis()

        runCatching {
            val megabitsPerSecond = downloadSpeedProbe.measureMegabitsPerSecond()
            InternetSpeedTestResult.Success(
                InternetSpeedTestSnapshot(
                    megabitsPerSecond = megabitsPerSecond,
                    recommendedMaxVideoHeight = recommendMaxVideoHeight(megabitsPerSecond),
                    measuredAtMs = measuredAtMs,
                    transport = transport,
                    isEstimated = false
                )
            )
        }.getOrElse {
            InternetSpeedTestResult.Error(it.message ?: "Speed test failed")
        }
    }

    private fun recommendMaxVideoHeight(megabitsPerSecond: Double): Int? {
        return when {
            megabitsPerSecond < 6.0 -> 480
            megabitsPerSecond < 12.0 -> 720
            megabitsPerSecond < 25.0 -> 1080
            megabitsPerSecond < 40.0 -> 2160
            else -> null
        }
    }

    private fun NetworkCapabilities.toTransport(): InternetSpeedTestTransport {
        return when {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> InternetSpeedTestTransport.WIFI
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> InternetSpeedTestTransport.ETHERNET
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> InternetSpeedTestTransport.CELLULAR
            else -> InternetSpeedTestTransport.OTHER
        }
    }
}

class InternetDownloadSpeedProbe @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private var urlFactory: (Long) -> String = { bytesToDownload ->
        "https://speed.cloudflare.com/__down?bytes=$bytesToDownload&seed=${SystemClock.elapsedRealtime()}"
    }
    private var nanoTime: () -> Long = SystemClock::elapsedRealtimeNanos

    internal constructor(
        okHttpClient: OkHttpClient,
        urlFactory: (Long) -> String,
        nanoTime: () -> Long
    ) : this(okHttpClient) {
        this.urlFactory = urlFactory
        this.nanoTime = nanoTime
    }

    fun measureMegabitsPerSecond(bytesToDownload: Long = DEFAULT_DOWNLOAD_BYTES): Double {
        val request = Request.Builder()
            .url(urlFactory(bytesToDownload))
            .cacheControl(CacheControl.Builder().noCache().noStore().build())
            .build()
        val startedAtNs = nanoTime()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Speed test failed with HTTP ${response.code}")
            }
            val body = response.body ?: error("Speed test returned no data")
            val bytesRead = body.source().use { source ->
                val sink = Buffer()
                var total = 0L
                while (true) {
                    val read = source.read(sink, READ_CHUNK_BYTES)
                    if (read == -1L) break
                    total += read
                    sink.clear()
                }
                total
            }
            if (bytesRead <= 0L) {
                error("Speed test returned no data")
            }
            val elapsedSeconds = (nanoTime() - startedAtNs)
                .coerceAtLeast(TimeUnit.MILLISECONDS.toNanos(1)) / 1_000_000_000.0
            return ((bytesRead * 8.0) / elapsedSeconds) / 1_000_000.0
        }
    }

    private companion object {
        const val DEFAULT_DOWNLOAD_BYTES = 8_000_000L
        const val READ_CHUNK_BYTES = 16_384L
    }
}
