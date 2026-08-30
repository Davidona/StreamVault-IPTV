package com.streamvault.data.provider

import com.google.gson.Gson

internal fun Gson.providerPayloadHasEmbeddedType(payload: String): Boolean =
    fromJson(payload, Map::class.java)?.containsKey("type") == true
