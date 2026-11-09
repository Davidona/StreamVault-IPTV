package com.streamvault.feature.provider.pairing

import com.streamvault.core.ui.companion.CompanionHttpParser
import com.streamvault.core.ui.companion.MAX_COMPANION_BODY_BYTES
import java.io.InputStream

internal const val MAX_PAIRING_FORM_BYTES = MAX_COMPANION_BODY_BYTES
internal typealias ParsedProviderPairingRequest = com.streamvault.core.ui.companion.ParsedCompanionRequest
internal typealias ProviderPairingRequestException = com.streamvault.core.ui.companion.CompanionRequestException

/** Provider setup and ordering share the same bounded HTTP parsing rules. */
internal object ProviderPairingHttpParser {
    fun read(input: InputStream): ParsedProviderPairingRequest = CompanionHttpParser.read(input)
}
