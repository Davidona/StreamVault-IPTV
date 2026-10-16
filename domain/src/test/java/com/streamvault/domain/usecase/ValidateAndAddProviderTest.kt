package com.streamvault.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.manager.ProviderSetupInputValidator
import com.streamvault.domain.manager.ValidatedJellyfinProviderInput
import com.streamvault.domain.manager.ValidatedJellyfinQuickConnectProviderInput
import com.streamvault.domain.model.Program
import com.streamvault.domain.manager.ValidatedM3uProviderInput
import com.streamvault.domain.manager.ValidatedStalkerProviderInput
import com.streamvault.domain.manager.ValidatedXtreamProviderInput
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderSavedWithSyncErrorException
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerCatalogMode
import com.streamvault.domain.model.StalkerCompatibilityProfileIds
import com.streamvault.domain.model.StalkerProtocolPreference
import com.streamvault.domain.model.StalkerReadinessInconclusiveException
import com.streamvault.domain.model.StalkerTransportChallenge
import com.streamvault.domain.model.StalkerTransportChallengeReason
import com.streamvault.domain.model.StalkerTransportConsentRequiredException
import com.streamvault.domain.model.StalkerTransportGrant
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.StalkerTransportOrigin
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.repository.ProviderDeleteProgress
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.ProviderSetupRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ValidateAndAddProviderTest {

    @Test
    fun `validateXtreamInput returns null when input is valid without calling repository`() {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(),
            providerRepository = repository
        )

        val error = useCase.validateXtreamInput(
            XtreamProviderSetupCommand(serverUrl = "https://example.com", username = "alice", password = "secret", name = "Provider")
        )

        assertThat(error).isNull()
        assertThat(repository.lastXtreamCall).isNull()
    }

    @Test
    fun `validateXtreamInput returns ValidationError without calling repository`() {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                xtreamResult = Result.error("Please enter server URL")
            ),
            providerRepository = repository
        )

        val error = useCase.validateXtreamInput(
            XtreamProviderSetupCommand(serverUrl = "", username = "alice", password = "secret", name = "Provider")
        )

        assertThat(error).isInstanceOf(ValidateAndAddProviderResult.ValidationError::class.java)
        assertThat((error as ValidateAndAddProviderResult.ValidationError).message).isEqualTo("Please enter server URL")
        assertThat(repository.lastXtreamCall).isNull()
    }

    @Test
    fun `validateM3uInput returns null when input is valid without calling repository`() {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(),
            providerRepository = repository
        )

        val error = useCase.validateM3uInput(
            M3uProviderSetupCommand(url = "https://example.com/list.m3u", name = "Playlist")
        )

        assertThat(error).isNull()
        assertThat(repository.lastM3uCall).isNull()
    }

    @Test
    fun `validateM3uInput returns ValidationError without calling repository`() {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                m3uResult = Result.error("Please enter M3U URL")
            ),
            providerRepository = repository
        )

        val error = useCase.validateM3uInput(
            M3uProviderSetupCommand(url = "", name = "Playlist")
        )

        assertThat(error).isInstanceOf(ValidateAndAddProviderResult.ValidationError::class.java)
        assertThat(repository.lastM3uCall).isNull()
    }

    @Test
    fun `validateStalkerInput returns null when input is valid without calling repository`() {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(),
            providerRepository = repository
        )

        val error = useCase.validateStalkerInput(
            StalkerProviderSetupCommand(
                portalUrl = "https://portal.example.com",
                macAddress = "00:1A:79:12:34:56",
                name = "MAG"
            )
        )

        assertThat(error).isNull()
        assertThat(repository.lastStalkerCall).isNull()
    }

    @Test
    fun `validateStalkerInput returns ValidationError without calling repository`() {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                stalkerResult = Result.error("Please enter portal URL")
            ),
            providerRepository = repository
        )

        val error = useCase.validateStalkerInput(
            StalkerProviderSetupCommand(portalUrl = "", macAddress = "00:1A:79:12:34:56", name = "MAG")
        )

        assertThat(error).isInstanceOf(ValidateAndAddProviderResult.ValidationError::class.java)
        assertThat(repository.lastStalkerCall).isNull()
    }

    @Test
    fun returns_validation_error_without_calling_repository_for_xtream() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                xtreamResult = Result.error("Please enter server URL")
            ),
            providerRepository = repository
        )

        val result = useCase.loginXtream(
            XtreamProviderSetupCommand(
                serverUrl = "",
                username = "user",
                password = "secret",
                name = "Provider"
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.ValidationError::class.java)
        assertThat((result as ValidateAndAddProviderResult.ValidationError).message).isEqualTo("Please enter server URL")
        assertThat(repository.lastXtreamCall).isNull()
    }

    @Test
    fun delegates_normalized_xtream_input_to_repository() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                xtreamResult = Result.success(
                    ValidatedXtreamProviderInput(
                        serverUrl = "https://example.com",
                        username = "alice",
                        password = "normalized-secret",
                        name = "Premium",
                        httpUserAgent = "StreamVaultTest/1.0",
                        httpHeaders = "Referer: https://example.com"
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.loginXtream(
            XtreamProviderSetupCommand(
                serverUrl = " https://example.com ",
                username = " alice ",
                password = "secret\u0000",
                name = " Premium ",
                httpUserAgent = " StreamVaultTest/1.0 ",
                httpHeaders = " Referer: https://example.com ",
                xtreamFastSyncEnabled = true,
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                existingProviderId = 7L
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.Success::class.java)
        assertThat(repository.lastXtreamCall).isEqualTo(
            XtreamCall(
                serverUrl = "https://example.com",
                username = "alice",
                password = "normalized-secret",
                name = "Premium",
                httpUserAgent = "StreamVaultTest/1.0",
                httpHeaders = "Referer: https://example.com",
                xtreamFastSyncEnabled = true,
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
                id = 7L
            )
        )
    }

    @Test
    fun allows_blank_xtream_password_when_editing_existing_provider() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                xtreamResult = Result.success(
                    ValidatedXtreamProviderInput(
                        serverUrl = "https://example.com",
                        username = "alice",
                        password = "",
                        name = "Premium",
                        httpUserAgent = "",
                        httpHeaders = ""
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.loginXtream(
            XtreamProviderSetupCommand(
                serverUrl = "https://example.com",
                username = "alice",
                password = "",
                name = "Premium",
                existingProviderId = 7L
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.Success::class.java)
        assertThat(repository.lastXtreamCall?.password).isEmpty()
    }

    @Test
    fun maps_saved_provider_sync_failure_to_saved_with_warning() = runTest {
        val repository = FakeProviderRepository().apply {
            xtreamResult = Result.error(
                "Provider login succeeded, but initial sync failed. The provider was saved and can be retried from Settings: timeout",
                ProviderSavedWithSyncErrorException(
                    provider = provider(id = 7L, name = "Premium", type = ProviderType.XTREAM_CODES).copy(status = ProviderStatus.ERROR),
                    message = "Provider login succeeded, but initial sync failed. The provider was saved and can be retried from Settings: timeout"
                )
            )
        }
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(),
            providerRepository = repository
        )

        val result = useCase.loginXtream(
            XtreamProviderSetupCommand(
                serverUrl = "https://example.com",
                username = "alice",
                password = "secret",
                name = "Premium"
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.SavedWithWarning::class.java)
        result as ValidateAndAddProviderResult.SavedWithWarning
        assertThat(result.provider.id).isEqualTo(7L)
        assertThat(result.provider.status).isEqualTo(ProviderStatus.ERROR)
        assertThat(result.warning).contains("initial sync failed")
    }

    @Test
    fun delegates_validated_m3u_input_to_repository() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                m3uResult = Result.success(
                    ValidatedM3uProviderInput(
                        url = "file://playlist.m3u",
                        name = "Local Playlist",
                        httpUserAgent = "PlaylistAgent/2.0",
                        httpHeaders = "Referer: https://example.com"
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.addM3u(
            M3uProviderSetupCommand(
                url = "file://playlist.m3u",
                name = "Local Playlist",
                httpUserAgent = "PlaylistAgent/2.0",
                httpHeaders = "Referer: https://example.com",
                epgSyncMode = ProviderEpgSyncMode.SKIP,
                existingProviderId = 11L
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.Success::class.java)
        assertThat(repository.lastM3uCall).isEqualTo(
            M3uCall(
                url = "file://playlist.m3u",
                name = "Local Playlist",
                httpUserAgent = "PlaylistAgent/2.0",
                httpHeaders = "Referer: https://example.com",
                epgSyncMode = ProviderEpgSyncMode.SKIP,
                m3uVodClassificationEnabled = false,
                id = 11L
            )
        )
    }

    @Test
    fun auto_converts_xtream_playlist_url_to_xtream_login() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                m3uResult = Result.success(
                    ValidatedM3uProviderInput(
                        url = "http://extapk2302.shop:8080/get.php?username=Hakan1605&password=wg9daUwzfV&type=m3u_plus&output=ts",
                        name = "Imported Playlist",
                        httpUserAgent = "PlaylistAgent/2.0",
                        httpHeaders = "Referer: https://example.com"
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.addM3u(
            M3uProviderSetupCommand(
                url = "http://extapk2302.shop:8080/get.php?username=Hakan1605&password=wg9daUwzfV&type=m3u_plus&output=ts",
                name = "Imported Playlist",
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                existingProviderId = 19L
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.Success::class.java)
        assertThat(repository.lastM3uCall).isNull()
        assertThat(repository.lastXtreamCall).isEqualTo(
            XtreamCall(
                serverUrl = "http://extapk2302.shop:8080",
                username = "Hakan1605",
                password = "wg9daUwzfV",
                name = "Imported Playlist",
                httpUserAgent = "PlaylistAgent/2.0",
                httpHeaders = "Referer: https://example.com",
                xtreamFastSyncEnabled = false,
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
                id = 19L
            )
        )
        assertThat(repository.lastXtreamConfig?.preferredLiveStreamFormat)
            .isEqualTo(LiveStreamFormatMode.MPEG_TS)
    }

    @Test
    fun rejects_xtream_playlist_url_with_embedded_credentials_in_authority() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                m3uResult = Result.success(
                    ValidatedM3uProviderInput(
                        url = "http://tvappapk@extapk2302.shop:8080/get.php?username=Hakan1605&password=wg9daUwzfV&type=m3u_plus",
                        name = "Imported Playlist",
                        httpUserAgent = "",
                        httpHeaders = ""
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.addM3u(
            M3uProviderSetupCommand(
                url = "http://tvappapk@extapk2302.shop:8080/get.php?username=Hakan1605&password=wg9daUwzfV&type=m3u_plus",
                name = "Imported Playlist"
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.ValidationError::class.java)
        assertThat((result as ValidateAndAddProviderResult.ValidationError).message)
            .isEqualTo("Playlist sources must not include embedded credentials in the URL authority.")
        assertThat(repository.lastXtreamCall).isNull()
    }

    @Test
    fun rejects_xtream_playlist_with_oversized_decoded_password() = runTest {
        val repository = FakeProviderRepository()
        val oversizedPassword = "p".repeat(257)
        val encodedPassword = java.net.URLEncoder.encode(oversizedPassword, java.nio.charset.StandardCharsets.UTF_8.name())
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                m3uResult = Result.success(
                    ValidatedM3uProviderInput(
                        url = "https://example.com/get.php?username=user&password=$encodedPassword&type=m3u_plus",
                        name = "Imported Playlist",
                        httpUserAgent = "",
                        httpHeaders = ""
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.addM3u(
            M3uProviderSetupCommand(
                url = "https://example.com/get.php?username=user&password=$encodedPassword&type=m3u_plus",
                name = "Imported Playlist"
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.ValidationError::class.java)
        assertThat((result as ValidateAndAddProviderResult.ValidationError).message)
            .isEqualTo("Playlist password is too long.")
        assertThat(repository.lastXtreamCall).isNull()
        assertThat(repository.lastM3uCall).isNull()
    }

    @Test
    fun delegates_validated_stalker_input_to_repository() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(
                stalkerResult = Result.success(
                    ValidatedStalkerProviderInput(
                        portalUrl = "https://portal.example.com",
                        macAddress = "00:1A:79:12:34:56",
                        authMode = StalkerAuthMode.AUTO,
                        username = "",
                        password = "",
                        name = "MAG",
                        httpUserAgent = "Stalker Agent/1.0",
                        httpHeaders = "Referer: | X-Test: enabled",
                        deviceProfile = "MAG250",
                        timezone = "UTC",
                        locale = "en"
                    )
                )
            ),
            providerRepository = repository
        )

        val result = useCase.loginStalker(
            StalkerProviderSetupCommand(
                portalUrl = " https://portal.example.com ",
                macAddress = "00-1a-79-12-34-56",
                authMode = StalkerAuthMode.AUTO,
                username = "",
                password = "",
                name = " MAG ",
                httpUserAgent = " Stalker Agent/1.0 ",
                httpHeaders = " Referer: | X-Test: enabled ",
                deviceProfile = " MAG250 ",
                timezone = " UTC ",
                locale = " en ",
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                existingProviderId = 21L
            )
        )

        assertThat(result).isInstanceOf(ValidateAndAddProviderResult.Success::class.java)
        assertThat(repository.lastStalkerCall).isEqualTo(
            StalkerCall(
                portalUrl = "https://portal.example.com",
                macAddress = "00:1A:79:12:34:56",
                authMode = StalkerAuthMode.AUTO,
                username = "",
                password = "",
                name = "MAG",
                httpUserAgent = "Stalker Agent/1.0",
                httpHeaders = "Referer: | X-Test: enabled",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en",
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                id = 21L
            )
        )
    }

    @Test
    fun `maps Stalker transport challenge without retaining credentials in a token`() = runTest {
        val challenge = StalkerTransportChallenge(
            reason = StalkerTransportChallengeReason.CLEARTEXT_HTTP,
            origin = StalkerTransportOrigin("http", "portal.example.com", 80),
            displayHost = "portal.example.com",
            detailCode = "CLEARTEXT_REQUIRES_CONSENT"
        )
        val repository = FakeProviderRepository().apply {
            stalkerResult = Result.error(
                "Transport consent required",
                StalkerTransportConsentRequiredException(challenge)
            )
        }
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(),
            providerRepository = repository
        )

        val result = useCase.loginStalker(
            StalkerProviderSetupCommand(
                portalUrl = "http://portal.example.com",
                macAddress = "00:1A:79:12:34:56",
                name = "MAG",
                password = "must-not-be-in-challenge"
            )
        )

        assertThat(result).isEqualTo(
            ValidateAndAddProviderResult.TransportConsentRequired(challenge)
        )
        val consentResult = result as ValidateAndAddProviderResult.TransportConsentRequired
        assertThat(consentResult.challenge.toString()).doesNotContain("must-not-be-in-challenge")
    }

    @Test
    fun `maps authenticated but inconclusive Live readiness to explicit save choice`() = runTest {
        val repository = FakeProviderRepository().apply {
            stalkerResult = Result.error(
                "Live readiness inconclusive",
                StalkerReadinessInconclusiveException(
                    evidenceCode = "LIVE_BUDGET_EXHAUSTED",
                    message = "Authentication succeeded, but Live TV could not be verified."
                )
            )
        }
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(),
            providerRepository = repository
        )

        val result = useCase.loginStalker(
            StalkerProviderSetupCommand(
                portalUrl = "https://portal.example.com",
                macAddress = "00:1A:79:12:34:56",
                name = "MAG",
                saveWithoutVerification = true
            )
        )

        assertThat(result).isEqualTo(
            ValidateAndAddProviderResult.VerificationInconclusive(
                "Authentication succeeded, but Live TV could not be verified."
            )
        )
        assertThat(repository.lastStalkerCall?.saveWithoutVerification).isTrue()
    }

    @Test
    fun `passes explicit repair connection permission to repository`() = runTest {
        val repository = FakeProviderRepository()
        val useCase = ValidateAndAddProvider(
            providerSetupInputValidator = FakeProviderSetupInputValidator(),
            providerRepository = repository
        )

        useCase.loginStalker(
            StalkerProviderSetupCommand(
                portalUrl = "https://portal.example.com",
                macAddress = "00:1A:79:12:34:56",
                name = "MAG",
                existingProviderId = 42L,
                repairConnection = true
            )
        )

        assertThat(repository.lastStalkerCall?.repairConnection).isTrue()
    }
}

private class FakeProviderSetupInputValidator(
    private val xtreamResult: Result<ValidatedXtreamProviderInput> = Result.success(
        ValidatedXtreamProviderInput(
            serverUrl = "https://example.com",
            username = "user",
            password = "secret",
            name = "Provider",
            httpUserAgent = "",
            httpHeaders = ""
        )
    ),
    private val m3uResult: Result<ValidatedM3uProviderInput> = Result.success(
        ValidatedM3uProviderInput(
            url = "https://example.com/playlist.m3u",
            name = "Playlist",
            httpUserAgent = "",
            httpHeaders = ""
        )
    ),
    private val stalkerResult: Result<ValidatedStalkerProviderInput> = Result.success(
        ValidatedStalkerProviderInput(
            portalUrl = "https://portal.example.com",
            macAddress = "00:1A:79:12:34:56",
            authMode = StalkerAuthMode.AUTO,
            username = "",
            password = "",
            name = "Provider",
            httpUserAgent = "",
            httpHeaders = "",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
    ),
    private val jellyfinResult: Result<ValidatedJellyfinProviderInput> = Result.success(
        ValidatedJellyfinProviderInput(
            serverUrl = "https://jellyfin.example.com",
            username = "user",
            password = "secret",
            name = "Jellyfin"
        )
    ),
    private val jellyfinQuickConnectResult: Result<ValidatedJellyfinQuickConnectProviderInput> = Result.success(
        ValidatedJellyfinQuickConnectProviderInput(
            serverUrl = "https://jellyfin.example.com",
            name = "Jellyfin"
        )
    )
) : ProviderSetupInputValidator {
    override fun validateXtream(
        serverUrl: String,
        username: String,
        password: String,
        allowBlankPassword: Boolean,
        name: String,
        httpUserAgent: String,
        httpHeaders: String
    ): Result<ValidatedXtreamProviderInput> = xtreamResult

    override fun validateM3u(
        url: String,
        name: String,
        httpUserAgent: String,
        httpHeaders: String
    ): Result<ValidatedM3uProviderInput> = m3uResult

    override fun validateStalker(
        portalUrl: String,
        macAddress: String,
        name: String,
        authMode: StalkerAuthMode,
        username: String,
        password: String,
        allowBlankPassword: Boolean,
        httpUserAgent: String,
        httpHeaders: String,
        deviceProfile: String,
        timezone: String,
        locale: String,
        serialNumber: String,
        deviceId: String,
        deviceId2: String,
        signature: String,
        stalkerAdvancedOptionsJson: String
    ): Result<ValidatedStalkerProviderInput> = stalkerResult

    override fun validateJellyfin(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        allowBlankPassword: Boolean
    ): Result<ValidatedJellyfinProviderInput> = jellyfinResult

    override fun validateJellyfinQuickConnect(
        serverUrl: String,
        name: String
    ): Result<ValidatedJellyfinQuickConnectProviderInput> = jellyfinQuickConnectResult
}

private data class XtreamCall(
    val serverUrl: String,
    val username: String,
    val password: String,
    val name: String,
    val httpUserAgent: String,
    val httpHeaders: String,
    val xtreamFastSyncEnabled: Boolean,
    val epgSyncMode: ProviderEpgSyncMode,
    val xtreamLiveSyncMode: ProviderXtreamLiveSyncMode,
    val id: Long?
)

private data class M3uCall(
    val url: String,
    val name: String,
    val httpUserAgent: String,
    val httpHeaders: String,
    val epgSyncMode: ProviderEpgSyncMode,
    val m3uVodClassificationEnabled: Boolean,
    val id: Long?
)

private data class StalkerCall(
    val portalUrl: String,
    val macAddress: String,
    val authMode: StalkerAuthMode,
    val username: String,
    val password: String,
    val name: String,
    val httpUserAgent: String,
    val httpHeaders: String,
    val deviceProfile: String,
    val timezone: String,
    val locale: String,
    val serialNumber: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val stalkerAdvancedOptionsJson: String = "",
    val protocolPreference: StalkerProtocolPreference = StalkerProtocolPreference.AUTO,
    val transportGrant: StalkerTransportGrant? = null,
    val saveWithoutVerification: Boolean = false,
    val repairConnection: Boolean = false,
    val requestedProfileId: String = StalkerCompatibilityProfileIds.AUTO,
    val epgSyncMode: ProviderEpgSyncMode,
    val id: Long?
)

private class FakeProviderRepository : ProviderRepository {
    var lastXtreamCall: XtreamCall? = null
    var lastXtreamConfig: XtreamConfig? = null
    var lastM3uCall: M3uCall? = null
    var lastStalkerCall: StalkerCall? = null
    var xtreamResult: Result<Provider>? = null
    var m3uResult: Result<Provider>? = null
    var stalkerResult: Result<Provider>? = null

    override fun getProviders(): Flow<List<Provider>> = flowOf(emptyList())

    override fun getActiveProvider(): Flow<Provider?> = flowOf(null)

    override suspend fun getProvider(id: Long): Provider? = null

    override suspend fun addProvider(provider: Provider): Result<Long> = error("Not used in test")

    override suspend fun updateProvider(provider: Provider): Result<Unit> = error("Not used in test")

    override suspend fun deleteProvider(
        id: Long,
        onProgress: ((ProviderDeleteProgress) -> Unit)?
    ): Result<com.streamvault.domain.repository.ProviderDeleteOutcome> = error("Not used in test")

    override suspend fun getAllProviderCredentials(): List<ProviderCredentials> = emptyList()

    override suspend fun updateProviderPassword(
        serverUrl: String,
        username: String,
        cleartextPassword: String
    ): Boolean = false

    override suspend fun setActiveProvider(id: Long): Result<Unit> = error("Not used in test")

    override suspend fun setupProvider(
        request: ProviderSetupRequest,
        onProgress: ((String) -> Unit)?,
        onCode: ((String) -> Unit)?
    ): Result<Provider> = when (request) {
        is ProviderSetupRequest.Configured -> when (val config = request.configuration) {
            is XtreamConfig -> {
                lastXtreamConfig = config
                loginXtream(
                    config.serverUrl, config.username, config.password, request.name,
                    config.httpUserAgent, config.httpHeaders, config.fastSyncEnabled,
                    config.epgSyncMode, config.liveSyncMode, config.guideSourcePolicy,
                    config.channelLogoSourcePolicy, onProgress, request.existingProviderId
                )
            }
            is M3uConfig -> validateM3u(
                config.playlistUrl, request.name, config.httpUserAgent, config.httpHeaders,
                config.epgSyncMode, config.vodClassificationEnabled, config.guideSourcePolicy,
                config.channelLogoSourcePolicy, onProgress, request.existingProviderId
            )
            is StalkerConfig -> loginStalker(
                config.portalUrl, config.device.macAddress, request.name, config.authMode,
                config.username, config.password, config.httpUserAgent, config.httpHeaders,
                config.device.deviceProfile, config.device.timezone, config.device.locale,
                config.device.serialNumber, config.device.deviceId, config.device.deviceId2,
                config.device.signature, config.advancedOptionsJson, config.protocolPreference,
                config.transportGrant, request.saveWithoutVerification, request.repairConnection,
                config.requestedProfileId, config.epgSyncMode, config.catalogMode,
                config.guideSourcePolicy, config.channelLogoSourcePolicy, onProgress,
                request.existingProviderId
            )
            is JellyfinConfig -> loginJellyfin(
                config.serverUrl, config.username, config.credential, request.name,
                onProgress, request.existingProviderId
            )
        }
        is ProviderSetupRequest.JellyfinQuickConnect -> loginJellyfinQuickConnect(
            request.serverUrl, request.name, onCode, onProgress, request.existingProviderId
        )
    }

    suspend fun loginXtream(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        httpUserAgent: String,
        httpHeaders: String,
        xtreamFastSyncEnabled: Boolean,
        epgSyncMode: ProviderEpgSyncMode,
        xtreamLiveSyncMode: ProviderXtreamLiveSyncMode,
        guideSourcePolicy: GuideSourcePolicy,
        channelLogoSourcePolicy: ChannelLogoSourcePolicy,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        lastXtreamCall = XtreamCall(serverUrl, username, password, name, httpUserAgent, httpHeaders, xtreamFastSyncEnabled, epgSyncMode, xtreamLiveSyncMode, id)
        return xtreamResult ?: Result.success(provider(id = id ?: 1L, name = name, type = ProviderType.XTREAM_CODES))
    }

    suspend fun validateM3u(
        url: String,
        name: String,
        httpUserAgent: String,
        httpHeaders: String,
        epgSyncMode: ProviderEpgSyncMode,
        m3uVodClassificationEnabled: Boolean,
        guideSourcePolicy: GuideSourcePolicy,
        channelLogoSourcePolicy: ChannelLogoSourcePolicy,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        lastM3uCall = M3uCall(url, name, httpUserAgent, httpHeaders, epgSyncMode, m3uVodClassificationEnabled, id)
        return m3uResult ?: Result.success(provider(id = id ?: 2L, name = name, type = ProviderType.M3U, m3uUrl = url))
    }

    suspend fun loginStalker(
        portalUrl: String,
        macAddress: String,
        name: String,
        authMode: StalkerAuthMode,
        username: String,
        password: String,
        httpUserAgent: String,
        httpHeaders: String,
        deviceProfile: String,
        timezone: String,
        locale: String,
        serialNumber: String,
        deviceId: String,
        deviceId2: String,
        signature: String,
        stalkerAdvancedOptionsJson: String,
        protocolPreference: StalkerProtocolPreference,
        transportGrant: StalkerTransportGrant?,
        saveWithoutVerification: Boolean,
        repairConnection: Boolean,
        requestedProfileId: String,
        epgSyncMode: ProviderEpgSyncMode,
        catalogMode: StalkerCatalogMode,
        guideSourcePolicy: GuideSourcePolicy,
        channelLogoSourcePolicy: ChannelLogoSourcePolicy,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        lastStalkerCall = StalkerCall(
            portalUrl = portalUrl,
            macAddress = macAddress,
            authMode = authMode,
            username = username,
            password = password,
            name = name,
            httpUserAgent = httpUserAgent,
            httpHeaders = httpHeaders,
            deviceProfile = deviceProfile,
            timezone = timezone,
            locale = locale,
            serialNumber = serialNumber,
            deviceId = deviceId,
            deviceId2 = deviceId2,
            signature = signature,
            stalkerAdvancedOptionsJson = stalkerAdvancedOptionsJson,
            protocolPreference = protocolPreference,
            transportGrant = transportGrant,
            saveWithoutVerification = saveWithoutVerification,
            repairConnection = repairConnection,
            requestedProfileId = requestedProfileId,
            epgSyncMode = epgSyncMode,
            id = id
        )
        return stalkerResult ?: Result.success(
            provider(id = id ?: 3L, name = name, type = ProviderType.STALKER_PORTAL).copy(
                serverUrl = portalUrl,
                username = username,
                password = password,
                httpUserAgent = httpUserAgent,
                httpHeaders = httpHeaders,
                stalkerMacAddress = macAddress,
                stalkerAuthMode = authMode,
                stalkerDeviceProfile = deviceProfile,
                stalkerDeviceTimezone = timezone,
                stalkerDeviceLocale = locale,
                stalkerSerialNumber = serialNumber,
                stalkerDeviceId = deviceId,
                stalkerDeviceId2 = deviceId2,
                stalkerSignature = signature
            )
        )
    }

    suspend fun loginJellyfin(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        return Result.success(
            provider(id = id ?: 4L, name = name, type = ProviderType.JELLYFIN).copy(
                serverUrl = serverUrl,
                username = username,
                password = password
            )
        )
    }

    suspend fun loginJellyfinQuickConnect(
        serverUrl: String,
        name: String,
        onCode: ((String) -> Unit)?,
        onProgress: ((String) -> Unit)?,
        id: Long?
    ): Result<Provider> {
        onCode?.invoke("ABCD")
        return Result.success(
            provider(id = id ?: 5L, name = name, type = ProviderType.JELLYFIN).copy(
                serverUrl = serverUrl,
                username = name,
                password = "quick-connect-token"
            )
        )
    }

    override suspend fun refreshProviderData(
        providerId: Long,
        force: Boolean,
        movieFastSyncOverride: Boolean?,
        epgSyncModeOverride: ProviderEpgSyncMode?,
        onProgress: ((String) -> Unit)?
    ): Result<Unit> = error("Not used in test")

    override suspend fun getProgramsForLiveStream(
        providerId: Long,
        streamId: Long,
        epgChannelId: String?,
        limit: Int
    ): Result<List<Program>> = error("Not used in test")

    override suspend fun buildCatchUpUrl(providerId: Long, streamId: Long, start: Long, end: Long): String? = null

    fun provider(
        id: Long,
        name: String,
        type: ProviderType,
        m3uUrl: String = ""
    ) = Provider(
        id = id,
        name = name,
        type = type,
        serverUrl = if (type == ProviderType.M3U) m3uUrl else "https://example.com",
        username = if (type == ProviderType.XTREAM_CODES) "user" else "",
        m3uUrl = m3uUrl,
        status = ProviderStatus.ACTIVE
    )
}
