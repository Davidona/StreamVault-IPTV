package com.streamvault.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.StrictMode
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.doOnPreDraw
import com.streamvault.app.cast.CastRouteChooserActivity
import com.streamvault.app.device.isTelevisionDevice
import com.streamvault.core.ui.localization.resolveAppLocale
import com.streamvault.app.navigation.AppNavigation
import com.streamvault.app.navigation.AppNavigationCoordinator
import com.streamvault.app.navigation.ExternalNavigationRequestParser
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.core.navigation.ExternalNavigationRequest
import com.streamvault.core.navigation.AppDestination
import com.streamvault.feature.playback.cast.CastManager
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.app.ui.time.LocalAppTimeFormat
import com.streamvault.app.ui.time.toUiTimeFormat
import com.streamvault.core.ui.time.LocalUiTimeFormat
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.android.AndroidEntryPoint

import javax.inject.Inject
import com.streamvault.data.preferences.PreferencesRepository
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.tv.material3.Button
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import android.content.res.Configuration
import android.text.TextUtils
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.streamvault.app.diagnostics.CrashReportStore
import com.streamvault.app.backup.BackupFileBridge
import com.streamvault.app.navigation.playerNavigationRequest
import com.streamvault.app.settings.AppSettingsBackupFileAdapter
import com.streamvault.app.settings.AppSettingsPlatformHost
import com.streamvault.domain.model.Result
import com.streamvault.feature.settings.api.SettingsPlatformHost
import com.streamvault.feature.settings.api.SettingsRecordingPlaybackRequest

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PLAYER_REQUEST = "com.streamvault.app.extra.PLAYER_REQUEST"
        const val EXTRA_EXTERNAL_DESTINATION = "com.streamvault.app.extra.EXTERNAL_DESTINATION"
        const val EXTRA_EXTERNAL_ROUTE = "com.streamvault.app.extra.EXTERNAL_ROUTE"
        private const val MAX_PIP_ASPECT_RATIO = 2.39f
        private const val MIN_PIP_ASPECT_RATIO = 1f / MAX_PIP_ASPECT_RATIO
    }

    private data class PlayerPictureInPictureState(
        val enabled: Boolean = false,
        val isPlaying: Boolean = false,
        val aspectRatio: Rational? = null
    )

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var providerRepository: ProviderRepository

    @Inject
    internal lateinit var appStartupCoordinator: AppStartupCoordinator

    @Inject
    lateinit var castManager: CastManager

    @Inject
    lateinit var databaseStartupCoordinator: DatabaseStartupCoordinator

    private val _pictureInPictureModeFlow = MutableStateFlow(false)
    val pictureInPictureModeFlow: StateFlow<Boolean> = _pictureInPictureModeFlow.asStateFlow()

    private val appNavigationCoordinator: AppNavigationCoordinator by viewModels()

    private val pendingExternalNavigationRequests = ArrayDeque<ExternalNavigationRequest>()

    @Inject
    lateinit var externalNavigationRequestParser: ExternalNavigationRequestParser

    @Inject
    lateinit var settingsBackupFileAdapter: AppSettingsBackupFileAdapter

    private val settingsPlatformHost: SettingsPlatformHost by lazy {
        AppSettingsPlatformHost(
            context = this,
            backupFiles = settingsBackupFileAdapter,
            playRecordingOperation = { request: SettingsRecordingPlaybackRequest ->
                openPlayer(
                    playerNavigationRequest(
                        streamUrl = request.streamUrl,
                        title = request.title,
                        internalId = request.internalId ?: -1L,
                        providerId = request.providerId,
                        contentType = request.contentType ?: "MOVIE",
                        returnDestination = AppDestination.Settings()
                    )
                )
            },
            shareBackupOperation = { uri ->
                runCatching {
                    startActivity(BackupFileBridge.buildShareIntent(uri))
                    Result.success(Unit)
                }.getOrElse { error ->
                    Result.error(getString(R.string.settings_backup_share_failed), error)
                }
            },
            shareCrashReportOperation = {
                val file = CrashReportStore.latestReportFile(this)
                if (!file.isFile || file.length() <= 0L) {
                    Result.error(getString(R.string.settings_crash_report_missing))
                } else {
                    runCatching {
                        val uri = CrashReportStore.providerUriForFile(this, file)
                        startActivity(CrashReportStore.buildShareIntent(uri))
                        Result.success(Unit)
                    }.getOrElse { error ->
                        Result.error(getString(R.string.settings_crash_report_share_failed), error)
                    }
                }
            }
        )
    }

    private var playerPictureInPictureState = PlayerPictureInPictureState()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build()
            )
        }
        super.onCreate(savedInstanceState)
        // Disable legacy window-fitting so Compose receives IME insets directly.
        // This fixes keyboard-covers-input-field on API 30+ where adjustResize is
        // ignored when the theme sets windowFullscreen=true.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyImmersiveSystemUi()
        _pictureInPictureModeFlow.value = isInPictureInPictureMode
        handleExternalIntent(intent)
        if (isTelevisionDevice()) {
            // Lock TVs to landscape — the manifest uses "unspecified" so phones/tablets
            // can freely rotate, but TV UI is designed for landscape only.
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        setContent {
            val appLanguage by preferencesRepository.appLanguage.collectAsState(initial = "system")
            val appTimeFormat by preferencesRepository.appTimeFormat.collectAsState(initial = com.streamvault.domain.model.AppTimeFormat.SYSTEM)
            val databaseStartupState by databaseStartupCoordinator.state.collectAsState()
            val currentContext = LocalContext.current
            
            val configuration = remember(appLanguage) {
                val locale = resolveAppLocale(
                    preferredLanguageTag = appLanguage,
                    baseConfiguration = this@MainActivity.resources.configuration
                )
                val conf = Configuration(this@MainActivity.resources.configuration)
                Locale.setDefault(locale)
                conf.setLocale(locale)
                conf.setLayoutDirection(locale)
                conf
            }
            val localizedContext = remember(configuration, currentContext) {
                val configurationContext = currentContext.createConfigurationContext(configuration)
                object : ContextWrapper(currentContext) {
                    override fun getResources(): Resources = configurationContext.resources
                    override fun getAssets(): AssetManager = configurationContext.assets
                    override fun getSystemService(name: String): Any? {
                        return if (name == Context.LAYOUT_INFLATER_SERVICE) {
                            configurationContext.getSystemService(name)
                        } else {
                            super.getSystemService(name)
                        }
                    }
                }
            }

            val layoutDirection = remember(configuration) {
                if (TextUtils.getLayoutDirectionFromLocale(configuration.locales[0]) == View.LAYOUT_DIRECTION_RTL) {
                    LayoutDirection.Rtl
                } else {
                    LayoutDirection.Ltr
                }
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalLayoutDirection provides layoutDirection,
                LocalAppTimeFormat provides appTimeFormat,
                LocalUiTimeFormat provides appTimeFormat.toUiTimeFormat()
            ) {
                StreamVaultTheme {
                    when (val state = databaseStartupState) {
                        DatabaseStartupState.Opening -> DatabaseStartupScreen(state = state)
                        is DatabaseStartupState.Failed -> DatabaseStartupScreen(
                            state = state,
                            onRetry = {
                                lifecycleScope.launch { databaseStartupCoordinator.open() }
                            },
                            onShareReport = ::shareLatestFailureReport
                        )
                        DatabaseStartupState.Ready -> {
                            AppNavigation(
                                coordinator = appNavigationCoordinator,
                                settingsPlatformHost = settingsPlatformHost,
                                onCloseApp = ::finishAffinity
                            )
                            LaunchedEffect(Unit) {
                                dispatchPendingExternalNavigationRequests()
                                window.decorView.doOnPreDraw {
                                    window.decorView.post {
                                        appStartupCoordinator.onFirstUiFrameDrawn(isTelevisionDevice())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        databaseStartupCoordinator.start()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveSystemUi()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPlayerPictureInPictureModeIfEligible()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _pictureInPictureModeFlow.value = isInPictureInPictureMode
    }

    fun updatePlayerPictureInPictureState(
        enabled: Boolean,
        isPlaying: Boolean,
        videoWidth: Int,
        videoHeight: Int,
        pixelWidthHeightRatio: Float = 1f
    ) {
        if (!supportsPictureInPicture()) return
        playerPictureInPictureState = PlayerPictureInPictureState(
            enabled = enabled,
            isPlaying = isPlaying,
            aspectRatio = videoAspectRatioOrNull(videoWidth, videoHeight, pixelWidthHeightRatio)
        )
        applyPlayerPictureInPictureParams()
    }

    fun clearPlayerPictureInPictureState() {
        if (!supportsPictureInPicture()) return
        playerPictureInPictureState = PlayerPictureInPictureState()
        applyPlayerPictureInPictureParams()
    }

    fun openPlayer(request: PlayerNavigationRequest) {
        appNavigationCoordinator.submitExternalRequest(
            ExternalNavigationRequest.Player(request)
        )
    }

    fun enterPlayerPictureInPictureModeFromPlayer(): Boolean {
        return enterPlayerPictureInPictureModeIfEligible(requirePlaying = false)
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveSystemUi() {
        val decorView = window.decorView
        decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        WindowCompat.getInsetsController(window, decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun openCastRouteChooser() {
        startActivity(Intent(this, CastRouteChooserActivity::class.java))
    }

    private fun enterPlayerPictureInPictureModeIfEligible(requirePlaying: Boolean = true): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!supportsPictureInPicture() || isInPictureInPictureMode) {
            return false
        }
        val state = playerPictureInPictureState
        if (!state.enabled || (requirePlaying && !state.isPlaying)) {
            return false
        }
        return runCatching {
            PictureInPictureCompat.enter(this, state)
        }.getOrDefault(false)
    }

    private fun applyPlayerPictureInPictureParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!supportsPictureInPicture()) return
        runCatching {
            PictureInPictureCompat.apply(this, playerPictureInPictureState)
        }
    }

    private fun videoAspectRatioOrNull(
        videoWidth: Int,
        videoHeight: Int,
        pixelWidthHeightRatio: Float = 1f
    ): Rational? {
        if (videoWidth <= 0 || videoHeight <= 0) return null
        val safePixelRatio = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val rawAspectRatio = (videoWidth * safePixelRatio) / videoHeight.toFloat()
        val clampedAspectRatio = rawAspectRatio
            .coerceIn(MIN_PIP_ASPECT_RATIO, MAX_PIP_ASPECT_RATIO)
        val numerator = (clampedAspectRatio * 10_000).toInt().coerceAtLeast(1)
        return Rational(numerator, 10_000)
    }

    private fun supportsPictureInPicture(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private object PictureInPictureCompat {
        fun enter(activity: MainActivity, state: PlayerPictureInPictureState): Boolean {
            val params = build(state)
            activity.setPictureInPictureParams(params)
            return activity.enterPictureInPictureMode(params)
        }

        fun apply(activity: MainActivity, state: PlayerPictureInPictureState) {
            activity.setPictureInPictureParams(build(state))
        }

        private fun build(
            state: PlayerPictureInPictureState
        ): android.app.PictureInPictureParams {
            val builder = android.app.PictureInPictureParams.Builder()
            state.aspectRatio?.let { builder.setAspectRatio(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(state.enabled && state.isPlaying)
            }
            return builder.build()
        }
    }

    private fun handleExternalIntent(intent: Intent?) {
        intent?.let(externalNavigationRequestParser::parse)?.let {
            pendingExternalNavigationRequests += it
            dispatchPendingExternalNavigationRequests()
        }
    }

    private fun dispatchPendingExternalNavigationRequests() {
        if (databaseStartupCoordinator.state.value != DatabaseStartupState.Ready) return
        while (pendingExternalNavigationRequests.isNotEmpty()) {
            appNavigationCoordinator.submitExternalRequest(
                pendingExternalNavigationRequests.removeFirst()
            )
        }
    }

    private fun shareLatestFailureReport() {
        val file = CrashReportStore.latestReportFile(this)
        if (!file.isFile || file.length() <= 0L) return
        runCatching {
            val uri = CrashReportStore.providerUriForFile(this, file)
            startActivity(CrashReportStore.buildShareIntent(uri))
        }
    }
}

@Composable
private fun DatabaseStartupScreen(
    state: DatabaseStartupState,
    onRetry: () -> Unit = {},
    onShareReport: () -> Unit = {}
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state) {
                DatabaseStartupState.Opening -> {
                    CircularProgressIndicator()
                    Text(
                        text = "Preparing your library…",
                        modifier = Modifier.padding(top = 24.dp),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                is DatabaseStartupState.Failed -> {
                    Text(
                        text = "StreamVault couldn't open your library",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "${state.userMessage}\nError: ${state.errorType}",
                        modifier = Modifier.padding(top = 16.dp),
                        textAlign = TextAlign.Center
                    )
                    Row(
                        modifier = Modifier.padding(top = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(onClick = onRetry) { Text("Retry") }
                        Button(onClick = onShareReport) { Text("Share report") }
                    }
                }
                DatabaseStartupState.Ready -> Unit
            }
        }
    }
}
