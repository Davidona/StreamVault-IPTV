package com.streamvault.domain.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutomaticSyncActivityTest {
    @Test fun reprepareTransfersProviderWithoutLeakingAnOwner() {
        val owner = Any()
        try {
            AutomaticSyncActivity.started(owner, 7)
            AutomaticSyncActivity.started(owner, 7)
            AutomaticSyncActivity.started(owner, 8)
            assertThat(AutomaticSyncActivity.isProviderPlaybackActive(7)).isFalse()
            assertThat(AutomaticSyncActivity.isProviderPlaybackActive(8)).isTrue()
            assertThat(AutomaticSyncActivity.playbackActive.value).isTrue()
            AutomaticSyncActivity.stopped(owner)
            assertThat(AutomaticSyncActivity.isProviderPlaybackActive(8)).isFalse()
            assertThat(AutomaticSyncActivity.hasPlayback).isFalse()
        } finally { AutomaticSyncActivity.stopped(owner) }
    }
    @Test fun multiplePlayersAndProvidersRetainOnlyTheirOwnOwnership() {
        val first=Any(); val second=Any(); val other=Any()
        try {
            AutomaticSyncActivity.started(first,7); AutomaticSyncActivity.started(second,7)
            AutomaticSyncActivity.started(other,8)
            AutomaticSyncActivity.stopped(first)
            assertThat(AutomaticSyncActivity.isProviderPlaybackActive(7)).isTrue()
            AutomaticSyncActivity.stopped(other)
            assertThat(AutomaticSyncActivity.isProviderPlaybackActive(8)).isFalse()
            assertThat(AutomaticSyncActivity.isProviderPlaybackActive(7)).isTrue()
            AutomaticSyncActivity.stopped(second)
            assertThat(AutomaticSyncActivity.playbackActive.value).isFalse()
        } finally { listOf(first,second,other).forEach(AutomaticSyncActivity::stopped) }
    }
    @Test fun unknownPlaybackStillBlocksGlobalAdmissionWithoutClaimingAProvider() {
        val owner=Any()
        try {
            AutomaticSyncActivity.started(owner)
            assertThat(AutomaticSyncActivity.hasPlayback).isTrue()
            assertThat(AutomaticSyncActivity.isProviderPlaybackActive(1)).isFalse()
            AutomaticSyncActivity.started(owner,-1)
            assertThat(AutomaticSyncActivity.isProviderPlaybackActive(-1)).isFalse()
            assertThat(AutomaticSyncActivity.isProviderPlaybackActive(0)).isFalse()
        } finally { AutomaticSyncActivity.stopped(owner) }
    }
    @Test fun stoppedOwnerCanResumeWithProviderIdentity() {
        val owner=Any()
        try {
            AutomaticSyncActivity.started(owner,7); AutomaticSyncActivity.stopped(owner)
            assertThat(AutomaticSyncActivity.isProviderPlaybackActive(7)).isFalse()
            AutomaticSyncActivity.started(owner,7)
            assertThat(AutomaticSyncActivity.isProviderPlaybackActive(7)).isTrue()
        } finally { AutomaticSyncActivity.stopped(owner) }
    }
}
