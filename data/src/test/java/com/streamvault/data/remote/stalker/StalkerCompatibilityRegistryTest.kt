package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StalkerCompatibilityProfileIds
import com.streamvault.domain.model.StalkerCompatibilityRegistry
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerProfileVerification
import com.streamvault.domain.model.StalkerProtocolFamily
import org.junit.Test

class StalkerCompatibilityRegistryTest {
    @Test
    fun stableIds_areUniqueAndResolvable() {
        val profiles = StalkerCompatibilityRegistry.profiles
        assertThat(profiles.map { it.id }.distinct()).hasSize(profiles.size)
        profiles.forEach { profile ->
            assertThat(StalkerCompatibilityRegistry.find(profile.id)).isEqualTo(profile)
            assertThat(profile.revision).isGreaterThan(0)
            assertThat(profile.model).isNotEmpty()
            assertThat(profile.generation).isNotEmpty()
        }
    }

    @Test
    fun verifiedClassicProfiles_haveCompleteCapturedFingerprint() {
        val verified = StalkerCompatibilityRegistry.profiles.filter {
            it.protocolFamily == StalkerProtocolFamily.CLASSIC_MAG &&
                it.verification == StalkerProfileVerification.VERIFIED
        }
        assertThat(verified.map { it.id }).containsExactly(
            StalkerCompatibilityProfileIds.CLASSIC_MAG250_GENERIC,
            StalkerCompatibilityProfileIds.CLASSIC_MAG250_LEGACY,
            StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT,
            StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN
        )
        verified.forEach { profile ->
            assertThat(profile.preset).isNotNull()
            assertThat(profile.bootstrapRecipe).isNotNull()
            val fingerprint = stalkerMagPresetSpec(profile.preset!!)
            assertThat(fingerprint.versionString).contains("ImageDescription")
            assertThat(fingerprint.imageVersion).isNotEmpty()
            assertThat(fingerprint.hwVersion).isNotEmpty()
            assertThat(fingerprint.apiSignature).isNotEmpty()
        }
    }

    @Test
    fun experimentalProfiles_areManualOnlyAndResolveWholeBaseFingerprint() {
        StalkerCompatibilityRegistry.profiles
            .filter { it.verification == StalkerProfileVerification.EXPERIMENTAL }
            .forEach { profile ->
                assertThat(profile.automaticRepresentative).isFalse()
                if (profile.protocolFamily == StalkerProtocolFamily.CLASSIC_MAG) {
                    val base = StalkerCompatibilityRegistry.baseFingerprint(profile)
                    assertThat(base.verification).isEqualTo(StalkerProfileVerification.VERIFIED)
                    assertThat(base.preset).isNotNull()
                }
            }
    }

    @Test
    fun autoDiscovery_isBoundedAndKeepsPreferredFirst() {
        val preferred = StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT
        val order = StalkerCompatibilityRegistry.classicAutomaticOrder(preferred)
        assertThat(order.first().id).isEqualTo(preferred)
        assertThat(order.size).isAtMost(StalkerCompatibilityRegistry.MAX_AUTOMATIC_ATTEMPTS)
        assertThat(order.all { it.verification == StalkerProfileVerification.VERIFIED }).isTrue()
    }

    @Test
    fun oldPresets_mapWithoutChangingFingerprint() {
        assertThat(StalkerCompatibilityRegistry.idForLegacyPreset(StalkerMagPreset.GENERIC_SAFE))
            .isEqualTo(StalkerCompatibilityProfileIds.CLASSIC_MAG250_GENERIC)
        assertThat(StalkerCompatibilityRegistry.idForLegacyPreset(StalkerMagPreset.MAG250_LEGACY))
            .isEqualTo(StalkerCompatibilityProfileIds.CLASSIC_MAG250_LEGACY)
        assertThat(StalkerCompatibilityRegistry.idForLegacyPreset(StalkerMagPreset.MAG254_STRICT))
            .isEqualTo(StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT)
        assertThat(StalkerCompatibilityRegistry.idForLegacyPreset(StalkerMagPreset.MINISTRA_MODERN))
            .isEqualTo(StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN)
    }

    @Test
    fun portalModelSignal_resolvesOnlyVerifiedAutomaticFingerprints() {
        assertThat(StalkerCompatibilityRegistry.findVerifiedByModelSignal("mag254")?.id)
            .isEqualTo(StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT)
        assertThat(StalkerCompatibilityRegistry.findVerifiedByModelSignal("MAG255")?.id)
            .isEqualTo(StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT)
        assertThat(StalkerCompatibilityRegistry.findVerifiedByModelSignal("MAG270")).isNull()
        assertThat(StalkerCompatibilityRegistry.findVerifiedByModelSignal("unknown")).isNull()
    }

    @Test
    fun mag254Fingerprint_usesFirmwareImageVersionRatherThanModelNumber() {
        val fingerprint = stalkerMagPresetSpec(StalkerMagPreset.MAG254_STRICT)
        assertThat(fingerprint.versionString).contains("0.2.18")
        assertThat(fingerprint.imageVersion).isEqualTo("218")
    }

    @Test
    fun urlDiscovery_normalizesCopiedPortalPageUrls() {
        assertThat(
            StalkerUrlFactory.loadUrlCandidates(
                "https://portal.example.com/stalker_portal/c/index.html?ref=setup"
            )
        ).containsAtLeast(
            "https://portal.example.com/stalker_portal/server/load.php",
            "https://portal.example.com/stalker_portal/portal.php"
        )
    }
}
