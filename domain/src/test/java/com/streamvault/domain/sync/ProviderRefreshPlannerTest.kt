package com.streamvault.domain.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.SyncMetadata
import org.junit.Test

class ProviderRefreshPlannerTest {
    private val now = 10 * 86_400_000L
    private val policy = AutomaticSyncSettings()
    private val fresh = SyncMetadata(7L, lastLiveSuccess = now, lastMovieSuccess = now, lastSeriesSuccess = now, lastEpgSuccess = now)

    @Test fun `defaults use daily catalog and six hour guide freshness`() {
        val metadata = fresh.copy(lastLiveSuccess = now - 7 * 3_600_000L, lastEpgSuccess = now - 7 * 3_600_000L)
        assertThat(ProviderRefreshPlanner.plan(ProviderType.XTREAM_CODES, metadata, policy, now).sections).containsExactly(RefreshSection.EPG)
    }

    @Test fun `failed attempts do not postpone a due refresh`() {
        val metadata = fresh.copy(lastLiveSuccess = now - 25 * 3_600_000L, lastLiveSync = now)
        assertThat(ProviderRefreshPlanner.plan(ProviderType.STALKER_PORTAL, metadata, policy, now).sections).containsExactly(RefreshSection.LIVE)
    }

    @Test fun `jellyfin fresh catalog is not fetched every check`() {
        val plan = ProviderRefreshPlanner.plan(ProviderType.JELLYFIN, fresh, policy, now)
        assertThat(plan.sections).isEmpty()
        assertThat(plan.nextDueAt).isEqualTo(now + 24 * 3_600_000L)
    }

    @Test fun `jellyfin exposes movies and series only`() {
        assertThat(ProviderRefreshPlanner.plan(ProviderType.JELLYFIN, null, policy, now).sections)
            .containsExactly(RefreshSection.MOVIES, RefreshSection.SERIES).inOrder()
    }

    @Test fun `M3U has no independent series fetch`() {
        assertThat(ProviderRefreshPlanner.plan(ProviderType.M3U, null, policy, now).sections)
            .containsExactly(RefreshSection.LIVE, RefreshSection.MOVIES, RefreshSection.EPG).inOrder()
    }

    @Test fun `section selection and disabled guide are respected`() {
        val plan = ProviderRefreshPlanner.plan(ProviderType.XTREAM_CODES, null, policy.copy(movies = false, series = false), now, epgAvailable = false)
        assertThat(plan.sections).containsExactly(RefreshSection.LIVE)
    }

    @Test fun `custom intervals can refresh before the former fixed TTL`() {
        val plan = ProviderRefreshPlanner.plan(ProviderType.M3U, fresh.copy(lastLiveSuccess = now - 2 * 3_600_000L), policy.copy(catalogHours = 1), now)
        assertThat(plan.sections).containsExactly(RefreshSection.LIVE)
    }

    @Test fun `manual refresh ignores automatic disable and freshness`() {
        assertThat(ProviderRefreshPlanner.plan(ProviderType.JELLYFIN, fresh, policy.copy(enabled = false), now, force = true).sections)
            .containsExactly(RefreshSection.MOVIES, RefreshSection.SERIES)
    }

    @Test fun `global disable cannot be bypassed by provider override`() {
        val config = AutomaticSyncConfiguration(policy.copy(enabled = false), mapOf(7L to policy))
        assertThat(config.forProvider(7L).enabled).isFalse()
        assertThat(ProviderRefreshPlanner.plan(ProviderType.M3U, null, config.forProvider(7L), now).nextDueAt).isNull()
    }

    @Test fun `future timestamps are recovered as due work`() {
        assertThat(ProviderRefreshPlanner.plan(ProviderType.M3U, fresh.copy(lastLiveSuccess = now + 1), policy, now).sections)
            .containsExactly(RefreshSection.LIVE)
    }

    @Test fun `unified Stalker VOD does not request split series`() {
        assertThat(ProviderRefreshPlanner.plan(ProviderType.STALKER_PORTAL, null, policy, now, unifiedVod = true).sections)
            .containsExactly(RefreshSection.LIVE, RefreshSection.MOVIES, RefreshSection.EPG)
    }

    @Test fun `local windows support midnight boundaries`() {
        val overnight = policy.copy(windowStartHour = 22, windowEndHour = 6)
        assertThat(overnight.allowsHour(23)).isTrue()
        assertThat(overnight.allowsHour(0)).isTrue()
        assertThat(overnight.allowsHour(6)).isFalse()
        assertThat(overnight.allowsHour(12)).isFalse()
        assertThat(policy.allowsHour(12)).isTrue()
    }

    @Test fun `multiple players keep work deferred until the last owner stops`() {
        val first = Any(); val second = Any()
        try {
            AutomaticSyncActivity.started(first); AutomaticSyncActivity.started(second)
            AutomaticSyncActivity.stopped(first)
            assertThat(AutomaticSyncActivity.hasPlayback).isTrue()
            AutomaticSyncActivity.stopped(second)
            assertThat(AutomaticSyncActivity.hasPlayback).isFalse()
        } finally { AutomaticSyncActivity.stopped(first); AutomaticSyncActivity.stopped(second) }
    }
}
