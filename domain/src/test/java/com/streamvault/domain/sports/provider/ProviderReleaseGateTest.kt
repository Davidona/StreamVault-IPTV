package com.streamvault.domain.sports.provider

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderReleaseGateTest {
    private val review = ProviderComplianceInfo(
        termsUrl = "https://provider.test/terms",
        documentationUrl = "https://provider.test/docs",
        reviewedAt = LocalDate.of(2026, 10, 17),
        appStoreAllowed = true,
        commercialUseStatus = CommercialUseStatus.PERMITTED,
        releaseGate = ProviderReleaseGate.APPROVED,
    )
    private val descriptor = SportsProviderDescriptor("vendor", setOf(SportsCapability.EVENTS),
        SportsProviderAuth.None, compliance = review)
    private val config = SportsProviderConfig("account", "vendor")

    @Test fun `blocked gates cannot be listed selected prepared or instantiated from saved accounts`() {
        for (gate in listOf(ProviderReleaseGate.DO_NOT_SHIP, ProviderReleaseGate.REQUIRES_VENDOR_CONFIRMATION,
            ProviderReleaseGate.REQUIRES_CUSTOMER_CONTRACT)) {
            val registry = registry(descriptor.copy(compliance = review.copy(releaseGate = gate)))
            assertThat(registry.descriptors).isEmpty()
            assertThat(registry.descriptor("vendor")).isNull()
            assertThat(registry.capabilities(config)).isEmpty()
            assertThat(registry.displayName(config)).isNull()
            assertThat(registry.eligible(listOf(config), SportsCapability.EVENTS)).isEmpty()
            assertThrows(IllegalArgumentException::class.java) { registry.prepare(config) }
            assertThrows(IllegalArgumentException::class.java) { registry.create(config) }
        }
    }

    @Test fun `missing review unknown app rights and unreviewed commercial rights fail closed`() {
        for (info in listOf(null, review.copy(appStoreAllowed = null), review.copy(appStoreAllowed = false),
            review.copy(commercialUseStatus = CommercialUseStatus.CONDITIONAL),
            review.copy(commercialUseStatus = CommercialUseStatus.UNREVIEWED))) {
            assertThat(registry(descriptor.copy(compliance = info)).descriptors).isEmpty()
        }
        assertThat(registry(descriptor).create(config).instanceId).isEqualTo(config.instanceId)
    }

    @Test fun `credit requirements cannot be bypassed by marking gate approved`() {
        for (gate in listOf(ProviderReleaseGate.REQUIRES_ATTRIBUTION, ProviderReleaseGate.APPROVED)) {
            val credited = descriptor.copy(compliance = review.copy(releaseGate = gate,
                attribution = ProviderAttribution("Vendor", "https://provider.test")))
            assertThat(registry(credited).descriptors).isEmpty()
            assertThat(registry(credited, SportsProviderReleasePolicy(attributionSupportedTypes = setOf("other")))
                .descriptors).isEmpty()
            assertThat(registry(credited, SportsProviderReleasePolicy(attributionSupportedTypes = setOf("vendor")))
                .descriptors).containsExactly(credited)
        }
    }

    @Test fun `development allows prototypes but never prohibited providers or uncleared artwork`() {
        val development = SportsProviderReleasePolicy(development = true)
        assertThat(registry(descriptor.copy(compliance = null), development).descriptors).hasSize(1)
        assertThat(registry(descriptor.copy(compliance = review.copy(releaseGate = ProviderReleaseGate.DO_NOT_SHIP)),
            development).descriptors).isEmpty()
        assertThat(registry(descriptor.copy(compliance = review.copy(commercialUseStatus = CommercialUseStatus.PROHIBITED)),
            development).descriptors).isEmpty()
        val artwork = descriptor.copy(capabilities = setOf(SportsCapability.ARTWORK))
        assertThat(registry(artwork, development).descriptors).isEmpty()
        assertThat(SportsProviderReleasePolicy().permits(artwork.copy(compliance = review.copy(
            artworkStatus = ArtworkUseStatus.CLEARED)))).isTrue()
    }

    @Test fun `custom transport remains available without vendor approval`() {
        val custom = descriptor.copy(typeId = CUSTOM_SPORTS_PROVIDER_TYPE_ID, compliance = null)
        assertThat(registry(custom).descriptors).containsExactly(custom)
        assertThat(registry(custom.copy(compliance = review.copy(releaseGate = ProviderReleaseGate.DO_NOT_SHIP)))
            .descriptors).isEmpty()
    }

    @Test fun `review sources reject inline credentials and invalid credit descriptors`() {
        assertThrows(IllegalArgumentException::class.java) { review.copy(termsUrl = "https://key@provider.test") }
        assertThrows(IllegalArgumentException::class.java) { review.copy(documentationUrl = "http://provider.test") }
        assertThrows(IllegalArgumentException::class.java) { review.copy(releaseGate = ProviderReleaseGate.REQUIRES_ATTRIBUTION) }
    }

    private fun registry(value: SportsProviderDescriptor, policy: SportsProviderReleasePolicy = SportsProviderReleasePolicy()) =
        SportsProviderRegistry(listOf(object : SportsProviderFactory {
            override val descriptor = value
            override fun create(config: SportsProviderConfig) = object : EventProvider {
                override val descriptor = value
                override val instanceId = config.instanceId
                override suspend fun events(request: ProviderEventRequest) =
                    SportsProviderResult.Success(ProviderBatch<ProviderEvent>(emptyList()))
            }
        }), policy)
}
