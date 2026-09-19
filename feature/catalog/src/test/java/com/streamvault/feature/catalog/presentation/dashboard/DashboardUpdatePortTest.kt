package com.streamvault.feature.catalog.presentation.dashboard

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.feature.catalog.api.CatalogAppUpdatePort
import com.streamvault.feature.catalog.api.CatalogUpdateAction
import com.streamvault.feature.catalog.api.CatalogUpdateNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DashboardUpdatePortTest {

    @Test
    fun `dashboard notice exposes install readiness for every action`() {
        val actions = CatalogUpdateAction.entries

        actions.forEach { action ->
            val notice = DashboardUpdateNotice(
                latestVersionName = "2.0.0",
                downloadSha256 = "sha-256",
                actionState = action,
            )

            assertThat(notice.installReady)
                .isEqualTo(action == CatalogUpdateAction.InstallLatest)
            assertThat(notice.installPermissionRequired)
                .isEqualTo(action == CatalogUpdateAction.InstallPermissionRequired)
        }
    }

    @Test
    fun `dashboard update port forwards expected checksum and result`() = runTest {
        var receivedSha256: String? = null
        val port = RecordingUpdatePort { sha256 ->
            receivedSha256 = sha256
            Result.Success(Unit)
        }

        assertThat(port.installDownloadedUpdate("sha-256")).isEqualTo(Result.Success(Unit))
        assertThat(receivedSha256).isEqualTo("sha-256")
    }

    private class RecordingUpdatePort(
        private val install: suspend (String?) -> Result<Unit>,
    ) : CatalogAppUpdatePort {
        override val notice = MutableStateFlow<CatalogUpdateNotice?>(
            CatalogUpdateNotice(
                latestVersionName = "2.0.0",
                downloadSha256 = "sha-256",
                action = CatalogUpdateAction.InstallLatest,
            )
        )

        override suspend fun installDownloadedUpdate(expectedSha256: String?): Result<Unit> =
            install(expectedSha256)
    }
}
