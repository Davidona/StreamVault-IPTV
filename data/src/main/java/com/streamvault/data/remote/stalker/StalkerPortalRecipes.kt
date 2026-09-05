package com.streamvault.data.remote.stalker

import com.streamvault.domain.model.StalkerCompatibilityRegistry

import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerCookieMode
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import com.streamvault.domain.model.StalkerPortalFingerprint
import com.streamvault.domain.model.StalkerPortalProfile

internal data class StalkerMagPresetSpec(
    val defaultDeviceProfile: String,
    val versionString: String,
    val imageVersion: String,
    val hwVersion: String,
    val apiSignature: String,
    val localization: String,
    val requireStrictIdentity: Boolean = false
)

internal data class StalkerRecipeSpec(
    val recipe: StalkerBootstrapRecipe,
    val magPreset: StalkerMagPreset,
    val authMode: StalkerAuthMode,
    val requestAccountInfo: Boolean,
    val requestModules: Boolean,
    val requestLocalization: Boolean,
    val strictIdentityRequired: Boolean,
    val endpointPreference: StalkerEndpointPreference = StalkerEndpointPreference.AUTO,
    val preferLocalizationBeforeProfile: Boolean = false,
    val cookieMode: StalkerCookieMode = StalkerCookieMode.NONE,
    val playbackBackendHint: StalkerPlaybackBackendHint = StalkerPlaybackBackendHint.AUTO,
    val compatibilityProfileId: String = StalkerCompatibilityRegistry.idForLegacyPreset(magPreset)
)

internal fun stalkerMagPresetSpec(preset: StalkerMagPreset): StalkerMagPresetSpec = when (preset) {
    StalkerMagPreset.GENERIC_SAFE -> StalkerMagPresetSpec(
        defaultDeviceProfile = "MAG250",
        versionString = "ImageDescription: 0.2.18-r19-pub-250; ImageDate: Mon Jun 12 11:04:49 EEST 2017; PORTAL version: 5.6.10; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x23",
        imageVersion = "218",
        hwVersion = "1.7-BD-00",
        apiSignature = "262",
        localization = "en_US.utf8"
    )

    StalkerMagPreset.MAG250_LEGACY -> StalkerMagPresetSpec(
        defaultDeviceProfile = "MAG250",
        versionString = "ImageDescription: 0.2.16-r17-250; ImageDate: Thu Sep 13 12:08:56 EEST 2017; PORTAL version: 5.3.0; API Version: JS API version: 331; STB API version: 141; Player Engine version: 0x572",
        imageVersion = "216",
        hwVersion = "1.7-BD-00",
        apiSignature = "254",
        localization = "en_GB.utf8"
    )

    StalkerMagPreset.MAG254_STRICT -> StalkerMagPresetSpec(
        defaultDeviceProfile = "MAG254",
        versionString = "ImageDescription: 0.2.18-r23-254; ImageDate: Thu Nov 1 11:14:12 EET 2018; PORTAL version: 5.6.8; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c",
        imageVersion = "218",
        hwVersion = "2.6-IB-00",
        apiSignature = "263",
        localization = "en_US.utf8",
        requireStrictIdentity = true
    )

    StalkerMagPreset.MINISTRA_MODERN -> StalkerMagPresetSpec(
        defaultDeviceProfile = "MAG322",
        versionString = "ImageDescription: 0.2.21-r14-254; ImageDate: Wed Apr 24 13:42:11 EEST 2019; PORTAL version: 5.6.8; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x5a1",
        imageVersion = "221",
        hwVersion = "2.6-IB-00",
        apiSignature = "270",
        localization = "en_US.utf8",
        requireStrictIdentity = true
    )
}

internal fun defaultRecipeFor(
    authMode: StalkerAuthMode,
    fingerprintHint: StalkerPortalFingerprint,
    presetHint: StalkerMagPreset
): StalkerRecipeSpec {
    if (fingerprintHint == StalkerPortalFingerprint.MODULE_GATED) {
        return StalkerRecipeSpec(
            recipe = StalkerBootstrapRecipe.MODULE_GATED,
            magPreset = presetHint.takeUnless { it == StalkerMagPreset.GENERIC_SAFE } ?: StalkerMagPreset.MINISTRA_MODERN,
            authMode = authMode,
            requestAccountInfo = true,
            requestModules = true,
            requestLocalization = true,
            strictIdentityRequired = true,
            endpointPreference = StalkerEndpointPreference.PORTAL,
            preferLocalizationBeforeProfile = true,
            cookieMode = StalkerCookieMode.CREATE_LINK,
            playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT
        )
    }
    return when (authMode) {
        StalkerAuthMode.CREDENTIALS_ONLY -> StalkerRecipeSpec(
            recipe = StalkerBootstrapRecipe.AUTH_ONLY,
            magPreset = presetHint,
            authMode = authMode,
            requestAccountInfo = true,
            requestModules = false,
            requestLocalization = false,
            strictIdentityRequired = false,
            cookieMode = StalkerCookieMode.CREATE_LINK
        )

        StalkerAuthMode.MAC_PLUS_CREDENTIALS -> StalkerRecipeSpec(
            recipe = StalkerBootstrapRecipe.AUTH_STRICT_MAG,
            magPreset = presetHint.takeUnless { it == StalkerMagPreset.GENERIC_SAFE } ?: StalkerMagPreset.MAG254_STRICT,
            authMode = authMode,
            requestAccountInfo = true,
            requestModules = false,
            requestLocalization = true,
            strictIdentityRequired = true,
            cookieMode = StalkerCookieMode.CREATE_LINK,
            playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT
        )

        else -> StalkerRecipeSpec(
            recipe = StalkerBootstrapRecipe.GENERIC_SAFE,
            magPreset = presetHint,
            authMode = authMode,
            requestAccountInfo = false,
            requestModules = false,
            requestLocalization = false,
            strictIdentityRequired = false,
            cookieMode = StalkerCookieMode.CREATE_LINK
        )
    }
}

internal fun fallbackRecipesFor(authMode: StalkerAuthMode): List<StalkerRecipeSpec> = when (authMode) {
    StalkerAuthMode.MAC_ONLY -> listOf(
        StalkerRecipeSpec(StalkerBootstrapRecipe.LEGACY_MAG, StalkerMagPreset.MAG250_LEGACY, authMode, requestAccountInfo = true, requestModules = false, requestLocalization = false, strictIdentityRequired = false, cookieMode = StalkerCookieMode.CREATE_LINK),
        StalkerRecipeSpec(StalkerBootstrapRecipe.STRICT_MAG, StalkerMagPreset.MAG254_STRICT, authMode, requestAccountInfo = true, requestModules = false, requestLocalization = true, strictIdentityRequired = true, cookieMode = StalkerCookieMode.CREATE_LINK, playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT),
        StalkerRecipeSpec(StalkerBootstrapRecipe.PORTAL_PREFERRED, StalkerMagPreset.MAG254_STRICT, authMode, requestAccountInfo = true, requestModules = false, requestLocalization = true, strictIdentityRequired = true, endpointPreference = StalkerEndpointPreference.PORTAL, cookieMode = StalkerCookieMode.CREATE_LINK, playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT),
        StalkerRecipeSpec(StalkerBootstrapRecipe.MODULE_GATED, StalkerMagPreset.MINISTRA_MODERN, authMode, requestAccountInfo = true, requestModules = true, requestLocalization = true, strictIdentityRequired = true, endpointPreference = StalkerEndpointPreference.PORTAL, preferLocalizationBeforeProfile = true, cookieMode = StalkerCookieMode.CREATE_LINK, playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT),
        StalkerRecipeSpec(StalkerBootstrapRecipe.LOCALIZATION_STRICT, StalkerMagPreset.MINISTRA_MODERN, authMode, requestAccountInfo = true, requestModules = false, requestLocalization = true, strictIdentityRequired = true, endpointPreference = StalkerEndpointPreference.PORTAL, preferLocalizationBeforeProfile = true, cookieMode = StalkerCookieMode.CREATE_LINK, playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT)
    )

    StalkerAuthMode.CREDENTIALS_ONLY -> listOf(
        StalkerRecipeSpec(StalkerBootstrapRecipe.AUTH_ONLY, StalkerMagPreset.GENERIC_SAFE, authMode, requestAccountInfo = true, requestModules = false, requestLocalization = false, strictIdentityRequired = false, cookieMode = StalkerCookieMode.CREATE_LINK),
        StalkerRecipeSpec(StalkerBootstrapRecipe.PORTAL_PREFERRED, StalkerMagPreset.MAG254_STRICT, authMode, requestAccountInfo = true, requestModules = false, requestLocalization = true, strictIdentityRequired = true, endpointPreference = StalkerEndpointPreference.PORTAL, cookieMode = StalkerCookieMode.CREATE_LINK, playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT),
        StalkerRecipeSpec(StalkerBootstrapRecipe.AUTH_STRICT_MAG, StalkerMagPreset.MINISTRA_MODERN, authMode, requestAccountInfo = true, requestModules = true, requestLocalization = true, strictIdentityRequired = true, endpointPreference = StalkerEndpointPreference.PORTAL, preferLocalizationBeforeProfile = true, cookieMode = StalkerCookieMode.CREATE_LINK, playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT)
    )

    StalkerAuthMode.MAC_PLUS_CREDENTIALS -> listOf(
        StalkerRecipeSpec(StalkerBootstrapRecipe.AUTH_STRICT_MAG, StalkerMagPreset.MAG254_STRICT, authMode, requestAccountInfo = true, requestModules = false, requestLocalization = true, strictIdentityRequired = true, cookieMode = StalkerCookieMode.CREATE_LINK, playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT),
        StalkerRecipeSpec(StalkerBootstrapRecipe.PORTAL_PREFERRED, StalkerMagPreset.MAG254_STRICT, authMode, requestAccountInfo = true, requestModules = false, requestLocalization = true, strictIdentityRequired = true, endpointPreference = StalkerEndpointPreference.PORTAL, cookieMode = StalkerCookieMode.CREATE_LINK, playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT),
        StalkerRecipeSpec(StalkerBootstrapRecipe.MODULE_GATED, StalkerMagPreset.MINISTRA_MODERN, authMode, requestAccountInfo = true, requestModules = true, requestLocalization = true, strictIdentityRequired = true, endpointPreference = StalkerEndpointPreference.PORTAL, preferLocalizationBeforeProfile = true, cookieMode = StalkerCookieMode.CREATE_LINK, playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT),
        StalkerRecipeSpec(StalkerBootstrapRecipe.LOCALIZATION_STRICT, StalkerMagPreset.MINISTRA_MODERN, authMode, requestAccountInfo = true, requestModules = false, requestLocalization = true, strictIdentityRequired = true, endpointPreference = StalkerEndpointPreference.PORTAL, preferLocalizationBeforeProfile = true, cookieMode = StalkerCookieMode.CREATE_LINK, playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT)
    )

    StalkerAuthMode.AUTO -> emptyList()
}

internal fun detectPortalFingerprint(
    profile: StalkerProviderProfile,
    effectiveAuthMode: StalkerAuthMode,
    selectedPreset: StalkerMagPreset,
    selectedRecipe: StalkerBootstrapRecipe
): StalkerPortalFingerprint = when {
    profile.moduleNames.isNotEmpty() || profile.portalCapabilities.moduleRestricted ->
        StalkerPortalFingerprint.MODULE_GATED

    (profile.portalCapabilities.nginxSecureLink || profile.portalCapabilities.useHttpTemporaryLink) &&
        selectedPreset != StalkerMagPreset.GENERIC_SAFE ->
        StalkerPortalFingerprint.TEMP_LINK_STRICT

    effectiveAuthMode == StalkerAuthMode.CREDENTIALS_ONLY ->
        StalkerPortalFingerprint.AUTH_ONLY

    effectiveAuthMode == StalkerAuthMode.MAC_PLUS_CREDENTIALS &&
        selectedRecipe == StalkerBootstrapRecipe.AUTH_STRICT_MAG ->
        StalkerPortalFingerprint.AUTH_STRICT_MAG

    selectedPreset == StalkerMagPreset.MAG254_STRICT || selectedPreset == StalkerMagPreset.MINISTRA_MODERN ->
        StalkerPortalFingerprint.STRICT_MAG

    else -> StalkerPortalFingerprint.BASIC_MAC
}

internal fun profileForFingerprint(fingerprint: StalkerPortalFingerprint): StalkerPortalProfile = when (fingerprint) {
    StalkerPortalFingerprint.BASIC_MAC -> StalkerPortalProfile.MAG_BASIC
    StalkerPortalFingerprint.STRICT_MAG,
    StalkerPortalFingerprint.TEMP_LINK_STRICT -> StalkerPortalProfile.MAG_STRICT
    StalkerPortalFingerprint.AUTH_ONLY -> StalkerPortalProfile.AUTH_REQUIRED
    StalkerPortalFingerprint.AUTH_STRICT_MAG -> StalkerPortalProfile.AUTH_PLUS_MAG
    StalkerPortalFingerprint.MODULE_GATED -> StalkerPortalProfile.MODULE_GATED
}
