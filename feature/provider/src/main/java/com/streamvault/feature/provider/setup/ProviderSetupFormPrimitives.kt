package com.streamvault.feature.provider.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.browse.ErrorColor
import com.streamvault.domain.model.StalkerTransportChallenge
import com.streamvault.domain.model.StalkerTransportChallengeReason
import com.streamvault.feature.provider.R

@Composable
internal fun FormErrors(
    validationError: String?,
    error: String?,
    transportChallenge: StalkerTransportChallenge? = null
) {
    validationError?.let {
        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = ErrorColor)
    }
    error?.let {
        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = ErrorColor)
    }
    transportChallenge?.let { challenge ->
        val body = when (challenge.reason) {
            StalkerTransportChallengeReason.INVALID_TLS ->
                stringResource(R.string.stalker_transport_invalid_tls_body, challenge.displayHost)
            StalkerTransportChallengeReason.CLEARTEXT_HTTP ->
                stringResource(R.string.stalker_transport_http_body, challenge.displayHost)
            StalkerTransportChallengeReason.ORIGIN_CHANGED ->
                stringResource(R.string.stalker_transport_origin_changed_body, challenge.displayHost)
        }
        Text(text = body, style = MaterialTheme.typography.bodyMedium, color = ErrorColor)
    }
}

internal class RevealingPasswordVisualTransformation(
    private val revealedIndex: Int?
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val transformed = buildString(text.length) {
            text.text.forEachIndexed { index, ch ->
                append(if (revealedIndex != null && index == revealedIndex) ch else '*')
            }
        }
        return TransformedText(AnnotatedString(transformed), OffsetMapping.Identity)
    }
}
