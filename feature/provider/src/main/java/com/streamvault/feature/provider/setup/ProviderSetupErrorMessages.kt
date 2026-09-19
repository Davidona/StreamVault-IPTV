package com.streamvault.feature.provider.setup

import com.streamvault.domain.provider.ProviderSetupFailure
import com.streamvault.domain.provider.ProviderSetupFailureKind
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

internal object ProviderSetupErrorMessages {
    fun xtream(result: ValidateAndAddProviderResult.Error): String {
        val failure = result.exception
        val providerFailure = failure.findProviderSetupFailure()
        return when {
            result.message.startsWith(PROVIDER_LOGIN_SYNC_FAILED_PREFIX, ignoreCase = true) ->
                "Login succeeded, but the initial sync failed while loading the playlist"

            providerFailure?.kind == ProviderSetupFailureKind.CREDENTIALS_UNREADABLE ->
                providerFailure.throwable.message ?: CREDENTIALS_UNREADABLE_MESSAGE

            failure.hasCause<SSLPeerUnverifiedException>() ||
                failure.hasCause<CertificateException>() ||
                failure.hasCause<SSLException>() ->
                "Secure connection failed - the server's TLS certificate is not trusted on this device"

            providerFailure?.kind == ProviderSetupFailureKind.AUTHENTICATION ->
                "Login failed - please check your credentials and server URL"

            providerFailure?.kind == ProviderSetupFailureKind.REQUEST &&
                providerFailure.statusCode in setOf(403, 408, 429) ->
                "Server is temporarily busy - try syncing again in a moment"

            providerFailure?.kind == ProviderSetupFailureKind.REQUEST && providerFailure.statusCode == 401 ->
                "Login failed - please check your credentials and server URL"

            providerFailure?.kind == ProviderSetupFailureKind.REQUEST &&
                providerFailure.statusCode != null && providerFailure.statusCode in 500..599 ->
                "Server is temporarily busy - try syncing again in a moment"

            failure.hasCause<SocketTimeoutException>() ||
                failure.hasCause<InterruptedIOException>() ||
                failure.hasCause<UnknownHostException>() ||
                failure.hasCause<ConnectException>() ||
                failure.hasCause<NoRouteToHostException>() ||
                providerFailure?.kind == ProviderSetupFailureKind.NETWORK ->
                "Cannot reach server - check your internet connection and server URL"

            providerFailure?.kind == ProviderSetupFailureKind.RESPONSE_TOO_LARGE ->
                "Server returned an unusually large response - try again later or contact the provider"

            providerFailure?.kind == ProviderSetupFailureKind.PARSING ->
                "Server returned unreadable data - verify the provider details and try again"

            else -> result.message
        }
    }

    fun m3u(result: ValidateAndAddProviderResult.Error): String =
        if (result.message.startsWith(M3U_PLAYLIST_SYNC_FAILED_PREFIX, ignoreCase = true)) {
            "Playlist saved, but the initial sync failed while loading the content"
        } else {
            xtream(result)
        }

    fun stalker(result: ValidateAndAddProviderResult.Error): String {
        if (result.message.startsWith(PROVIDER_LOGIN_SYNC_FAILED_PREFIX, ignoreCase = true)) {
            return "Login succeeded, but the initial sync failed while loading the channel list"
        }
        val failure = result.exception
        val providerFailure = failure.findProviderSetupFailure()
        return when {
            result.message.contains("requires account credentials", ignoreCase = true) ->
                "Portal requires account credentials - switch the Stalker auth mode or add the username and password"

            result.message.contains("partially accepted MAC identity", ignoreCase = true) ->
                "Portal accepted the MAC address, but playback entitlement is incomplete for this session"

            result.message.contains("stricter MAG emulation", ignoreCase = true) ->
                "Portal requires stricter MAG emulation - keep the MAC and advanced device identity fields aligned with the working device"

            result.message.contains("legacy MAG recipe", ignoreCase = true) ->
                "Portal matched a legacy MAG recipe and was retried automatically, but playback still failed"

            result.message.contains("rediscovery attempted", ignoreCase = true) ->
                "The saved Stalker portal recipe failed, and the app already retried discovery automatically"

            result.message.contains("unsupported portal profile", ignoreCase = true) ->
                "Portal authenticated, but this Stalker profile is not supported yet"

            result.message.contains("no working recipe succeeded", ignoreCase = true) ->
                "Portal family was detected, but none of the known Stalker recipes worked for this connection"

            providerFailure?.kind == ProviderSetupFailureKind.CREDENTIALS_UNREADABLE ->
                providerFailure.throwable.message ?: CREDENTIALS_UNREADABLE_MESSAGE

            failure.hasCause<SSLPeerUnverifiedException>() ||
                failure.hasCause<CertificateException>() ||
                failure.hasCause<SSLException>() ->
                "Secure connection failed - the server's TLS certificate is not trusted on this device"

            failure.hasCause<SocketTimeoutException>() ||
                failure.hasCause<InterruptedIOException>() ||
                failure.hasCause<UnknownHostException>() ||
                failure.hasCause<ConnectException>() ||
                failure.hasCause<NoRouteToHostException>() ||
                providerFailure?.kind == ProviderSetupFailureKind.NETWORK ->
                "Cannot reach portal - check your internet connection and portal URL"

            else -> result.message
        }
    }

    fun jellyfin(result: ValidateAndAddProviderResult.Error): String {
        val failure = result.exception
        val providerFailure = failure.findProviderSetupFailure()
        return when {
            result.message.startsWith(PROVIDER_LOGIN_SYNC_FAILED_PREFIX, ignoreCase = true) ->
                "Login succeeded, but the initial sync failed while loading the Jellyfin library"

            providerFailure?.kind == ProviderSetupFailureKind.CREDENTIALS_UNREADABLE ->
                providerFailure.throwable.message ?: CREDENTIALS_UNREADABLE_MESSAGE

            failure.hasCause<SSLPeerUnverifiedException>() ||
                failure.hasCause<CertificateException>() ||
                failure.hasCause<SSLException>() ->
                "Secure connection failed - the server's TLS certificate is not trusted on this device"

            failure.hasCause<SocketTimeoutException>() ||
                failure.hasCause<InterruptedIOException>() ||
                failure.hasCause<UnknownHostException>() ||
                failure.hasCause<ConnectException>() ||
                failure.hasCause<NoRouteToHostException>() ||
                providerFailure?.kind == ProviderSetupFailureKind.NETWORK ->
                "Cannot reach server - check your internet connection and server URL"

            else -> result.message
        }
    }

    private data class FailureMatch(
        val throwable: Throwable,
        val failure: ProviderSetupFailure,
    ) {
        val kind: ProviderSetupFailureKind
            get() = failure.kind
        val statusCode: Int?
            get() = failure.statusCode
    }

    private fun Throwable?.findProviderSetupFailure(): FailureMatch? =
        generateSequence(this) { it.cause }
            .firstNotNullOfOrNull { throwable ->
                (throwable as? ProviderSetupFailure)?.let { FailureMatch(throwable, it) }
            }

    private inline fun <reified T : Throwable> Throwable?.hasCause(): Boolean =
        generateSequence(this) { it.cause }.filterIsInstance<T>().any()

    private const val PROVIDER_LOGIN_SYNC_FAILED_PREFIX =
        "Provider login succeeded, but initial sync failed"
    private const val M3U_PLAYLIST_SYNC_FAILED_PREFIX =
        "Playlist saved, but initial sync failed"
    private const val CREDENTIALS_UNREADABLE_MESSAGE =
        "Stored credentials are no longer readable. Please re-enter your provider credentials."
}
