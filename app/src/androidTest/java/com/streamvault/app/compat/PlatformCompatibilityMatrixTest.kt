package com.streamvault.app.compat

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.core.navigation.AppDestination
import com.streamvault.app.service.DownloadServiceStartMode
import com.streamvault.app.service.resolveDownloadServiceStartMode
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Small, repeatable platform contract suite. CI runs this class on API 25, 26, 28, 32, 33, 35,
 * and 36 with
 * -PcompatApi so a green host JVM test cannot hide a platform-only regression.
 */
@RunWith(AndroidJUnit4::class)
class PlatformCompatibilityMatrixTest {

    @Test
    fun deviceIsOnRequestedCompatibilityApi() {
        val expectedApi = InstrumentationRegistry.getArguments()
            .getString("expected_api")
            ?.toIntOrNull()

        expectedApi?.let { assertThat(Build.VERSION.SDK_INT).isEqualTo(it) }
        assertThat(Build.VERSION.SDK_INT).isAtLeast(25)
    }

    @Test
    fun legacyRouteParsingKeepsEncodedUtf8ValuesOnEverySupportedApi() {
        val encodedImport = URLEncoder.encode("https://fixture.invalid/playlist?name=Ειδήσεις", StandardCharsets.UTF_8.name())
        val destination = AppRouteCodec.decodeLegacyExternalRoute(
            "provider_setup?providerId=7&importUri=$encodedImport"
        )

        assertThat(destination).isEqualTo(
            AppDestination.ProviderSetup(
                providerId = 7L,
                importUri = "https://fixture.invalid/playlist?name=Ειδήσεις"
            )
        )
        assertThat(
            AppRouteCodec.decodeLegacyExternalRoute("provider_setup?importUri=%not-a-valid-escape")
        ).isEqualTo(AppDestination.ProviderSetup())
    }

    @Test
    fun encodedReturnRouteAndRepeatedParametersRemainSafe() {
        assertThat(
            AppRouteCodec.decodeLegacyExternalRoute(
                "movie_detail/42?returnRoute=live_tv%3FcategoryId%3D7"
            )
        ).isEqualTo(
            AppDestination.MovieDetail(
                movieId = 42L,
                returnDestination = AppDestination.LiveTv(7L)
            )
        )

        assertThat(
            AppRouteCodec.decodeLegacyExternalRoute(
                "movie_detail/42?returnRoute=live_tv&returnRoute=home"
            )
        ).isEqualTo(
            AppDestination.MovieDetail(movieId = 42L, returnDestination = AppDestination.Home)
        )
    }

    @Test
    fun stickyDownloadServiceRestartAlwaysEntersDurableRecovery() {
        assertThat(resolveDownloadServiceStartMode(null))
            .isEqualTo(DownloadServiceStartMode.RECOVER_INTERRUPTED)
        assertThat(resolveDownloadServiceStartMode(" "))
            .isEqualTo(DownloadServiceStartMode.RECOVER_INTERRUPTED)
        assertThat(resolveDownloadServiceStartMode("download-fixture"))
            .isEqualTo(DownloadServiceStartMode.OBSERVE_REQUESTED)
    }
}
