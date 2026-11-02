package com.streamvault.data.remote

object NetworkTimeoutConfig {
    const val CONNECT_TIMEOUT_SECONDS = 30L
    const val READ_TIMEOUT_SECONDS = 30L
    const val WRITE_TIMEOUT_SECONDS = 30L
    // EPG files can be large and served from slow hosts — allow more time per read.
    const val EPG_READ_TIMEOUT_SECONDS = 120L
    // Large all-source XMLTV feeds can exceed 1 GiB both on the wire and after
    // decompression. XMLTV parsing streams records into the database, while the
    // finite byte, channel, and programme limits still bound ingestion.
    const val EPG_MAX_RAW_SIZE_BYTES = 2_048L * 1_048_576 // 2 GiB before decompression
    const val EPG_MAX_SIZE_BYTES = 4_096L * 1_048_576 // 4 GiB decompressed XML
    const val XTREAM_SEGMENTED_READ_TIMEOUT_SECONDS = 45L
    const val XTREAM_SEGMENTED_WRITE_TIMEOUT_SECONDS = 45L
    const val XTREAM_SEGMENTED_CALL_TIMEOUT_SECONDS = 50L
    const val XTREAM_HEAVY_READ_TIMEOUT_SECONDS = 300L
    const val XTREAM_HEAVY_WRITE_TIMEOUT_SECONDS = 60L
    const val XTREAM_HEAVY_CALL_TIMEOUT_SECONDS = 330L
}
