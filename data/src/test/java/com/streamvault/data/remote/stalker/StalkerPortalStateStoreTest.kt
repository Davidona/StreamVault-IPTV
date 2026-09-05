package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.StalkerPortalStateDao
import com.streamvault.data.local.entity.StalkerPortalStateEntity
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

class StalkerPortalStateStoreTest {
    @Test
    fun `rate limit cooldown is persisted independently and can be cleared`() = runTest {
        val dao = FakePortalStateDao()
        val store = StalkerPortalStateStore(dao)

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
        val store = StalkerPortalStateStore(dao)
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
        val store = StalkerPortalStateStore(dao)
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
        val store = StalkerPortalStateStore(dao)
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
        val store = StalkerPortalStateStore(dao)
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
        val store = StalkerPortalStateStore(dao)
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
    fun `resumable session round-trips and respects generation age and clearing`() = runTest {
        val dao = FakePortalStateDao()
        val store = StalkerPortalStateStore(dao)
        val session = StalkerSession(
            loadUrl = "https://portal.test/load.php",
            portalReferer = "https://portal.test/c/",
            token = "tok123"
        ).copy(authenticatedAtMillis = 10_000L)
        store.recordAuthentication(
            providerId = 11L,
            session = session,
            profile = StalkerProviderProfile(accountName = "Room"),
            now = 10_000L,
            configurationGeneration = 7L
        )

        val resumed = store.resumableAuth(providerId = 11L, configurationGeneration = 7L, now = 70_000L)
        assertThat(resumed?.session?.token).isEqualTo("tok123")
        assertThat(resumed?.profile?.accountName).isEqualTo("Room")

        assertThat(store.resumableAuth(providerId = 11L, configurationGeneration = 8L, now = 70_000L)).isNull()
        assertThat(store.resumableAuth(providerId = 11L, configurationGeneration = 7L, now = 10_000L + 31L * 60L * 1000L)).isNull()
        assertThat(store.resumableAuth(providerId = 99L, configurationGeneration = 7L, now = 70_000L)).isNull()

        store.clearResumableAuth(11L)
        assertThat(store.resumableAuth(providerId = 11L, configurationGeneration = 7L, now = 70_000L)).isNull()
    }

    private class FakePortalStateDao : StalkerPortalStateDao {        private val rows = mutableMapOf<Long, StalkerPortalStateEntity>()

        override suspend fun get(providerId: Long): StalkerPortalStateEntity? = rows[providerId]

        override suspend fun upsert(entity: StalkerPortalStateEntity) {
            rows[entity.providerId] = entity
        }

        override suspend fun invalidate(providerId: Long): Int = if (rows.remove(providerId) != null) 1 else 0
    }
}
