package com.streamvault.domain.sports.provider

import java.net.URI
import java.time.LocalDate

enum class ProviderReleaseGate {
    APPROVED, REQUIRES_ATTRIBUTION, REQUIRES_VENDOR_CONFIRMATION,
    REQUIRES_CUSTOMER_CONTRACT, DO_NOT_SHIP,
}

enum class CommercialUseStatus { PERMITTED, CONDITIONAL, CONTRACT_REQUIRED, UNREVIEWED, PROHIBITED }
enum class ArtworkUseStatus { DISABLED, REQUIRES_REVIEW, CLEARED }

/** Provider names are proper names; UI wording surrounding them belongs in Android resources. */
enum class ProviderAttributionFormat { SOURCE_NAME, FOOTBALL_DATA, ODBL }

data class ProviderAttribution(
    val sourceName: String,
    val sourceUrl: String,
    val format: ProviderAttributionFormat = ProviderAttributionFormat.SOURCE_NAME,
    val licenseUrl: String? = null,
) {
    init {
        require(sourceName.isNotBlank())
        requirePublicHttpsUrl(sourceUrl)
        licenseUrl?.let(::requirePublicHttpsUrl)
        require(format != ProviderAttributionFormat.ODBL || licenseUrl != null)
    }
}

/** Records a review, not a licence. Paid-account and image-specific rights remain separate. */
data class ProviderComplianceInfo(
    val termsUrl: String,
    val documentationUrl: String,
    val reviewedAt: LocalDate,
    val attribution: ProviderAttribution? = null,
    val appStoreAllowed: Boolean? = null,
    val commercialUseStatus: CommercialUseStatus = CommercialUseStatus.UNREVIEWED,
    val artworkStatus: ArtworkUseStatus = ArtworkUseStatus.DISABLED,
    val releaseGate: ProviderReleaseGate = ProviderReleaseGate.DO_NOT_SHIP,
    val requiresPaidAccount: Boolean = false,
) {
    val attributionRequired: Boolean get() = attribution != null

    init {
        requirePublicHttpsUrl(termsUrl)
        requirePublicHttpsUrl(documentationUrl)
        require(releaseGate != ProviderReleaseGate.REQUIRES_ATTRIBUTION || attribution != null)
    }
}

/** Production is the default. Only debug builds may opt into unreviewed adapter development. */
data class SportsProviderReleasePolicy(
    val development: Boolean = false,
    /** Types whose mandatory credit is implemented on every data presentation surface. */
    val attributionSupportedTypes: Set<String> = emptySet(),
) {
    fun permits(descriptor: SportsProviderDescriptor): Boolean {
        val review = descriptor.compliance
        // Custom REST is a user-configured transport, not a reviewed vendor integration.
        if (descriptor.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID && review == null) return true
        if (review?.releaseGate == ProviderReleaseGate.DO_NOT_SHIP ||
            review?.commercialUseStatus == CommercialUseStatus.PROHIBITED) return false
        if (SportsCapability.ARTWORK in descriptor.capabilities &&
            review?.artworkStatus != ArtworkUseStatus.CLEARED) return false
        if (development) return true
        if (review == null || review.appStoreAllowed != true ||
            review.commercialUseStatus != CommercialUseStatus.PERMITTED) return false
        if (review.attribution != null && descriptor.typeId !in attributionSupportedTypes) return false
        return review.releaseGate == ProviderReleaseGate.APPROVED ||
            review.releaseGate == ProviderReleaseGate.REQUIRES_ATTRIBUTION
    }
}

private fun requirePublicHttpsUrl(value: String) {
    val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException() }
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null)
}