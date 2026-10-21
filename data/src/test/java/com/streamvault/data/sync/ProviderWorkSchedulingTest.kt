package com.streamvault.data.sync

import androidx.work.ExistingWorkPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderWorkSchedulingTest {
    @Test
    fun `Stalker catalog guide and setup have independent work identities`() {
        val identities = listOf(
            providerWorkUniqueName(7L),
            stalkerIndexWorkUniqueName(7L),
            providerEpgWorkUniqueName(7L),
            providerAutomaticIndexWorkUniqueName(7L)
        )

        assertThat(identities.distinct()).hasSize(4)
    }

    @Test
    fun `ordinary provider phases append without inheriting failed prerequisites`() {
        assertThat(providerWorkExistingPolicy(supersede = false))
            .isEqualTo(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    @Test
    fun `configuration revision explicitly supersedes older provider work`() {
        assertThat(providerWorkExistingPolicy(supersede = true))
            .isEqualTo(ExistingWorkPolicy.REPLACE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `provider work identity rejects invalid provider id`() {
        providerWorkUniqueName(0L)
    }
}
