package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.StalkerPortalStateDao
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.StalkerCookieMode
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.util.Base64

class StalkerPortalStateStoreTest {
    @Test
    fun `first run adopts learning generation created after handshake`() = runTest {
        val dao = FakePortalStateDao()
        val store = StalkerPortalStateStore(dao, TestCredentialCrypto())
        val coordinator = StalkerRequestCoordinator(store, true) { testScheduler.currentTime }
        coordinator.acquireNetworkPermit(83L, StalkerNetworkPriority.FOREGROUND).also {
            coordinator.releaseNetworkPermit(it)
        }
        dao.upsert(StalkerPortalStateEntity(providerId = 83L, configurationGeneration = 9L))
        val page = coordinator.acquireNetworkPermit(83L, StalkerNetworkPriority.INDEX)
        coordinator.recordIndexResponse(page, java.net.SocketTimeoutException())
        val persisted = requireNotNull(store.get(83L))
        assertThat(persisted.configurationGeneration).isEqualTo(9L)
        assertThat(store.indexPacing(persisted).intervalMillis).isEqualTo(10_000L)
    }

    @Test
    fun `learned index interval and stress ceiling survive coordinator restart`() = runTest {
        val dao = FakePortalStateDao()
        val store = StalkerPortalStateStore(dao, TestCredentialCrypto())
        dao.upsert(StalkerPortalStateEntity(providerId = 82L, configurationGeneration = 7L))
        val firstCoordinator = StalkerRequestCoordinator(store, true) { testScheduler.currentTime }
        val permit = firstCoordinator.acquireNetworkPermit(82L, StalkerNetworkPriority.INDEX)
        firstCoordinator.recordIndexResponse(permit, java.net.SocketTimeoutException())
        val learned = store.indexPacing(requireNotNull(store.get(82L)))
        assertThat(learned.intervalMillis).isEqualTo(10_000L)
        assertThat(learned.minimumIntervalMillis).isEqualTo(10_000L)

        val restarted = StalkerRequestCoordinator(store, true) { testScheduler.currentTime }
        val first = restarted.acquireNetworkPermit(82L, StalkerNetworkPriority.INDEX)
        val second = restarted.acquireNetworkPermit(82L, StalkerNetworkPriority.INDEX)
        assertThat(second.admittedAt - first.admittedAt).isEqualTo(10_000L)
        // A changed configuration cannot inherit pacing from the old generation.
        val changed = requireNotNull(store.get(82L)).copy(configurationGeneration = 8L)
        assertThat(store.indexPacing(changed).intervalMillis).isEqualTo(5_000L)
    }

    @Test
    fun `malformed pacing falls back safely and valid values are bounded`() = runTest {
        val store = StalkerPortalStateStore(FakePortalStateDao(), TestCredentialCrypto())
        assertThat(store.indexPacing(StalkerPortalStateEntity(1L, learningJson = "bad")).intervalMillis)
            .isEqualTo(5_000L)
        store.recordIndexPacing(1L, 3L, 1L, 1L)
        assertThat(store.indexPacing(requireNotNull(store.get(1L))).intervalMillis).isEqualTo(500L)
    }

    @Test
    fun `VOD page base is persisted and isolated by configuration generation`() = runTest {
        val store = StalkerPortalStateStore(FakePortalStateDao(), TestCredentialCrypto())

        store.recordVodPageBase(providerId = 42L, pageBase = 0, configurationGeneration = 7L)

        assertThat(store.getVodPageBase(providerId = 42L, configurationGeneration = 7L)).isEqualTo(0)
        assertThat(store.getVodPageBase(providerId = 42L, configurationGeneration = 8L)).isNull()
        val learning = Json.parseToJsonElement(requireNotNull(store.get(42L)).learningJson).jsonObject
        assertThat(learning.getValue("vodPageBase").jsonObject.getValue("source").jsonPrimitive.content)
            .isEqualTo("CATALOG")

        store.recordVodPageBase(providerId = 42L, pageBase = 1, configurationGeneration = 7L)
        assertThat(store.getVodPageBase(providerId = 42L, configurationGeneration = 7L)).isEqualTo(1)
        assertThat(store.getVodPageBase(providerId = 42L, configurationGeneration = 8L)).isNull()
    }

    @Test
    fun `largest VOD page size is learned per content type and configuration generation`() = runTest {
        val store = StalkerPortalStateStore(FakePortalStateDao(), TestCredentialCrypto())

        store.recordVodPageSize(43L, ContentType.MOVIE, pageSize = 14, configurationGeneration = 7L, now = 100L)
        store.recordVodPageSize(43L, ContentType.MOVIE, pageSize = 8, configurationGeneration = 7L, now = 200L)
        store.recordVodPageSize(43L, ContentType.SERIES, pageSize = 12, configurationGeneration = 7L, now = 300L)

        assertThat(store.getVodPageSize(43L, ContentType.MOVIE, 7L)).isEqualTo(14)
        assertThat(store.getVodPageSize(43L, ContentType.SERIES, 7L)).isEqualTo(12)
        assertThat(store.getVodPageSize(43L, ContentType.MOVIE, 8L)).isNull()

        store.recordVodPageSize(43L, ContentType.MOVIE, pageSize = 10, configurationGeneration = 8L, now = 400L)
        assertThat(store.getVodPageSize(43L, ContentType.MOVIE, 8L)).isEqualTo(10)
        assertThat(store.getVodPageSize(43L, ContentType.SERIES, 8L)).isNull()
        store.recordVodPageSize(43L, ContentType.MOVIE, pageSize = 129, configurationGeneration = 8L)
        assertThat(store.getVodPageSize(43L, ContentType.MOVIE, 8L)).isEqualTo(10)
    }

    @Test
    fun `rate limit cooldown is persisted independently and can be cleared`() = runTest {
        val dao = FakePortalStateDao()
        val store = StalkerPortalStateStore(dao, TestCredentialCrypto())

        store.recordRateLimitCooldown(9L, cooldownUntil = 61_000L, now = 1_000L)
        val limited = requireNotNull(store.get(9L))
        assertThat(store.rateLimitCooldownUntil(limited)).isEqualTo(61_000L)
        assertThat(limited.stressCooldownUntil).isEqualTo(0L)

        store.clearRateLimitCooldown(9L)
        assertThat(store.rateLimitCooldownUntil(requireNotNull(store.get(9L)))).isEqualTo(0L)
    }

    @Test
    fun `capabilities are validated for seven days and selectively invalidated`() = runTest {
        val dao = FakePortalStateDao()
        val store = StalkerPortalStateStore(dao, TestCredentialCrypto())
        val now = 1_000L

        store.recordBulkLive(1L, supported = false, now = now)
        store.recordWildcard(1L, ContentType.MOVIE, supported = true, now = now)
        store.recordEpg(1L, supported = true, now = now)
        store.recordStressCooldown(1L, cooldownUntil = now + 60_000L, now = now)

        assertThat(store.getValidated(1L, now + 6L * 24L * 60L * 60L * 1000L)).isNotNull()
        assertThat(store.getValidated(1L, now + 8L * 24L * 60L * 60L * 1000L)).isNull()

        store.invalidateCapabilities(1L)
        val invalidated = requireNotNull(store.get(1L))
        assertThat(invalidated.bulkLiveSupported).isNull()
        assertThat(invalidated.movieWildcardSupported).isNull()
        assertThat(invalidated.epgSupported).isNull()
        assertThat(invalidated.safeMetadataConcurrency).isEqualTo(1)
        assertThat(invalidated.stressCooldownUntil).isEqualTo(now + 60_000L)
    }

    @Test
    fun `endpoint health is hashed bounded and expires independently`() = runTest {
        val dao = FakePortalStateDao()
        val store = StalkerPortalStateStore(dao, TestCredentialCrypto())
        val now = 10_000L
        val endpoint = "https://portal.example/stalker_portal/server/load.php"

        store.markEndpointUnhealthy(2L, endpoint, now)
        val state = requireNotNull(store.get(2L))

        assertThat(state.endpointHealthJson).doesNotContain("portal.example")
        assertThat(store.isEndpointHealthy(state, endpoint, now + 1L)).isFalse()
        assertThat(store.isEndpointHealthy(state, endpoint, now + 11L * 60L * 1000L)).isTrue()

        repeat(12) { index ->
            store.markEndpointUnhealthy(2L, "https://example.invalid/$index/portal.php", now + index)
        }
        val bounded = requireNotNull(store.get(2L)).endpointHealthJson
        assertThat(Regex("[a-f0-9]{24}").findAll(bounded).count()).isAtMost(8)
    }

    @Test
    fun `healthy probe restores concurrency only after persisted cooldown`() = runTest {
        val dao = FakePortalStateDao()
        val store = StalkerPortalStateStore(dao, TestCredentialCrypto())
        store.recordStressCooldown(3L, cooldownUntil = 2_000L, now = 1_000L)

        store.recordHealthyMetadataProbe(3L, now = 1_999L)
        assertThat(store.get(3L)?.safeMetadataConcurrency).isEqualTo(1)

        store.recordHealthyMetadataProbe(3L, now = 2_001L)
        assertThat(store.get(3L)?.safeMetadataConcurrency).isEqualTo(2)
        assertThat(store.get(3L)?.stressCooldownUntil).isEqualTo(0L)
    }

    @Test
    fun `failed authentication recipe cools down without invalidating endpoint`() = runTest {
        val dao = FakePortalStateDao()
        val store = StalkerPortalStateStore(dao, TestCredentialCrypto())
        val now = 5_000L
        dao.upsert(
            StalkerPortalStateEntity(
                providerId = 4L,
                workingEndpoint = "https://portal.invalid/portal.php",
                bootstrapRecipe = "STRICT_MAG",
                validatedAt = now
            )
        )

        store.markRecipeUnhealthy(4L, "STRICT_MAG", now)
        val state = requireNotNull(store.get(4L))

        assertThat(store.isRecipeHealthy(state, "STRICT_MAG", now + 1L)).isFalse()
        assertThat(store.isRecipeHealthy(state, "STRICT_MAG", now + 11L * 60L * 1000L)).isTrue()
        assertThat(state.workingEndpoint).isEqualTo("https://portal.invalid/portal.php")
    }

    @Test
    fun `authentication and playback observations share one generation-bound learning envelope`() = runTest {
        val dao = FakePortalStateDao()
        val store = StalkerPortalStateStore(dao, TestCredentialCrypto())
        store.recordAuthentication(
            providerId = 8L,
            session = StalkerSession(
                loadUrl = "https://portal.test/load.php",
                portalReferer = "https://portal.test/c/",
                token = "token"
            ),
            profile = StalkerProviderProfile(compatibilityProfileId = "classic-mag"),
            now = 100L,
            configurationGeneration = 5L
        )
        store.recordPlayback(
            providerId = 8L,
            playbackMode = "DIRECT_URL",
            endpointPreference = StalkerEndpointPreference.SERVER_LOAD,
            cookieMode = StalkerCookieMode.BOTH,
            backendHint = StalkerPlaybackBackendHint.DIRECT,
            now = 200L,
            configurationGeneration = 5L
        )

        val learning = Json.parseToJsonElement(requireNotNull(store.get(8L)).learningJson).jsonObject
        assertThat(learning.getValue("configurationGeneration").jsonPrimitive.long).isEqualTo(5L)
        assertThat(learning.getValue("profileId").jsonObject.getValue("source").jsonPrimitive.content)
            .isEqualTo("AUTHENTICATION")
        assertThat(learning.getValue("lastPlaybackMode").jsonObject.getValue("source").jsonPrimitive.content)
            .isEqualTo("PLAYBACK")
        assertThat(learning.getValue("workingEndpoint").jsonObject.getValue("value").jsonPrimitive.content)
            .isEqualTo("https://portal.test/load.php")
    }

    @Test
    fun `resumable session is encrypted and respects generation age and clearing`() = runTest {
        val dao = FakePortalStateDao()
        val store = StalkerPortalStateStore(dao, TestCredentialCrypto())
        val session = StalkerSession(
            loadUrl = "https://portal.test/load.php",
            portalReferer = "https://portal.test/c/",
            token = "tok123",
            serverCookieHeader = "sid=secret-cookie"
        ).copy(authenticatedAtMillis = 10_000L)

        store.recordAuthentication(
            providerId = 11L,
            session = session,
            profile = StalkerProviderProfile(accountName = "Room"),
            now = 10_000L,
            configurationGeneration = 7L
        )

        val learningJson = requireNotNull(store.get(11L)).learningJson
        assertThat(learningJson).doesNotContain("tok123")
        assertThat(learningJson).doesNotContain("secret-cookie")

        val resumed = store.resumableAuth(providerId = 11L, configurationGeneration = 7L, now = 70_000L)
        assertThat(resumed?.session?.token).isEqualTo("tok123")
        assertThat(resumed?.session?.serverCookieHeader).isEqualTo("sid=secret-cookie")
        assertThat(resumed?.profile?.accountName).isEqualTo("Room")

        assertThat(store.resumableAuth(providerId = 11L, configurationGeneration = 8L, now = 70_000L)).isNull()
        assertThat(store.resumableAuth(providerId = 11L, configurationGeneration = 7L, now = 10_000L + 31L * 60L * 1000L)).isNull()

        store.clearResumableAuth(11L)
        assertThat(store.resumableAuth(providerId = 11L, configurationGeneration = 7L, now = 70_000L)).isNull()
    }

    private class FakePortalStateDao : StalkerPortalStateDao {
        private val rows = mutableMapOf<Long, StalkerPortalStateEntity>()

        override suspend fun get(providerId: Long): StalkerPortalStateEntity? = rows[providerId]

        override suspend fun upsert(entity: StalkerPortalStateEntity) {
            rows[entity.providerId] = entity
        }

        override suspend fun invalidate(providerId: Long): Int = if (rows.remove(providerId) != null) 1 else 0
    }

    private class TestCredentialCrypto : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String =
            "enc:test:" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

        override fun decryptIfNeeded(value: String): String =
            String(Base64.getDecoder().decode(value.removePrefix("enc:test:")), Charsets.UTF_8)
    }
}
