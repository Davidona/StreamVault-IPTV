package com.streamvault.app.di

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.streamvault.app.BuildConfig
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.streamvault.data.remote.NetworkTimeoutConfig
import com.streamvault.data.remote.http.DefaultUserAgentInterceptor
import com.streamvault.data.remote.http.buildAppRequestProfile
import com.streamvault.data.remote.http.buildAppUserAgent
import com.streamvault.data.remote.stalker.OkHttpStalkerApiService
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.data.parser.XmltvParser
import com.streamvault.player.AudioCompatibilityMemoryStore
import com.streamvault.player.Media3PlayerEngine
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlaybackSupportSnapshotStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit.SECONDS
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context
    ): OkHttpClient {
        val appUserAgent = buildAppUserAgent(BuildConfig.VERSION_NAME)
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val loggingLevel = if (isDebuggable) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }

        val httpLogger = HttpLoggingInterceptor { message ->
            Log.d("OkHttp", XtreamUrlFactory.sanitizeLogMessage(message))
        }.apply {
            level = loggingLevel
        }

        return OkHttpClient.Builder()
            .cache(
                Cache(
                    directory = File(context.cacheDir, "streamvault_http_cache"),
                    maxSize = 16L * 1024 * 1024
                )
            )
            .connectTimeout(NetworkTimeoutConfig.CONNECT_TIMEOUT_SECONDS, SECONDS)
            .readTimeout(NetworkTimeoutConfig.READ_TIMEOUT_SECONDS, SECONDS)
            .writeTimeout(NetworkTimeoutConfig.WRITE_TIMEOUT_SECONDS, SECONDS)
            .addInterceptor(DefaultUserAgentInterceptor(appUserAgent))
            .addInterceptor(httpLogger)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(okhttp3.ConnectionPool(10, 5, java.util.concurrent.TimeUnit.MINUTES)) // Allow more idle connections
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 10 // Increase host limit for Multi-View
            })
            .build()
    }

    @Provides
    @Singleton
    fun provideXtreamApiService(okHttpClient: OkHttpClient, xtreamJson: Json): XtreamApiService =
        OkHttpXtreamApiService(
            client = okHttpClient,
            json = xtreamJson,
            defaultRequestProfile = buildAppRequestProfile(BuildConfig.VERSION_NAME, ownerTag = "app/xtream")
        )

    @Provides
    @Singleton
    fun provideStalkerApiService(okHttpClient: OkHttpClient, xtreamJson: Json): StalkerApiService =
        OkHttpStalkerApiService(okHttpClient.newBuilder().applyUnsafeTlsBypass().build(), xtreamJson)

    @Provides
    @Singleton
    fun provideXtreamJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun OkHttpClient.Builder.applyUnsafeTlsBypass(): OkHttpClient.Builder {
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAllManager), SecureRandom())
        }
        return sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
    }

    @Provides
    @Singleton
    fun provideXmltvParser(): XmltvParser = XmltvParser()

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder().create()

    @Provides
    @Singleton
    @MainPlayerEngine
    fun provideMainPlayerEngine(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        playbackCompatibilityRepository: com.streamvault.domain.repository.PlaybackCompatibilityRepository,
        audioCompatibilityMemoryStore: AudioCompatibilityMemoryStore,
        playbackSupportSnapshotStore: PlaybackSupportSnapshotStore
    ): PlayerEngine = Media3PlayerEngine(
        context,
        okHttpClient,
        playbackCompatibilityRepository,
        audioCompatibilityMemoryStore,
        playbackSupportSnapshotStore
    )

    /**
     * Factory binding for preview and multiview playback.
     * Each Provider.get() call returns a fresh engine instance.
     */
    @Provides
    @AuxiliaryPlayerEngine
    fun provideAuxiliaryPlayerEngine(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        playbackCompatibilityRepository: com.streamvault.domain.repository.PlaybackCompatibilityRepository,
        audioCompatibilityMemoryStore: AudioCompatibilityMemoryStore,
        playbackSupportSnapshotStore: PlaybackSupportSnapshotStore
    ): PlayerEngine = Media3PlayerEngine(
        context,
        okHttpClient,
        playbackCompatibilityRepository,
        audioCompatibilityMemoryStore,
        playbackSupportSnapshotStore
    ).apply {
        enableMediaSession = false
        bypassAudioFocus = true
    }
}
