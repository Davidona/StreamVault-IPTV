package com.streamvault.feature.provider.setup

internal data class StalkerRequestRuleUiState(
    val action: String = "",
    val blockRequest: Boolean = false,
    val paramsText: String = ""
)

internal fun StalkerRequestRuleUiState.toRule(): ProviderStalkerRequestRule =
    ProviderStalkerRequestRule(
        action = action.trim(),
        blockRequest = blockRequest,
        paramOverrides = paramsText
            .split('|', '\n')
            .mapNotNull { entry ->
                val trimmed = entry.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                val separator = trimmed.indexOf('=').takeIf { it >= 0 } ?: trimmed.indexOf(':')
                val name = if (separator >= 0) trimmed.substring(0, separator).trim() else trimmed
                val value = if (separator >= 0) trimmed.substring(separator + 1).trim() else ""
                name.takeIf { it.isNotBlank() }?.let { ProviderStalkerParamOverride(it, value) }
            }
    )

internal fun ProviderStalkerRequestRule.toUiState(): StalkerRequestRuleUiState =
    StalkerRequestRuleUiState(
        action = action,
        blockRequest = blockRequest,
        paramsText = paramOverrides.joinToString(" | ") { "${it.name}=${it.value}" }
    )
