package com.streamvault.data.parser

import com.streamvault.data.remote.NetworkTimeoutConfig
import java.io.IOException

data class XmltvIngestionLimits(
    val maxRawBytes: Long = NetworkTimeoutConfig.EPG_MAX_RAW_SIZE_BYTES,
    val maxDecompressedBytes: Long = NetworkTimeoutConfig.EPG_MAX_SIZE_BYTES,
    val maxChannels: Int = 100_000,
    val maxProgrammes: Int = 2_000_000,
    val maxFieldChars: Int = 65_536,
    val maxCategoriesPerProgramme: Int = 64,
    val maxXmlDepth: Int = 32
)

enum class XmltvLimitKind(val label: String) {
    RAW_BYTES("raw download bytes"),
    DECOMPRESSED_BYTES("decompressed XML bytes"),
    CHANNELS("channels"),
    PROGRAMMES("programmes"),
    FIELD_LENGTH("field length"),
    CATEGORIES_PER_PROGRAMME("categories per programme"),
    XML_DEPTH("XML depth")
}

class XmltvLimitExceeded(
    val kind: XmltvLimitKind,
    val maximum: Long
) : IOException("XMLTV ${kind.label} limit exceeded (maximum $maximum)")
