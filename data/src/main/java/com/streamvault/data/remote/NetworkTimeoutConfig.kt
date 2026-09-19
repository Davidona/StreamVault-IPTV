package com.streamvault.data.remote

object NetworkTimeoutConfig {
    const val CONNECT_TIMEOUT_SECONDS = 30L
    const val READ_TIMEOUT_SECONDS = 30L
    const val WRITE_TIMEOUT_SECONDS = 30L
    // EPG files can be large and served from slow hosts — allow more time per read.
    const val EPG_READ_TIMEOUT_SECONDS = 120L
    // Full-country XMLTV guides decompress well past 200 MB (e.g. iptv-epg.org's
    // epg-us.xml.gz expands to ~500 MB). The parser streams to the database, so a
    // higher byte ceiling is safe; the programme/channel limits remain the hard guard.
    const val EPG_MAX_RAW_SIZE_BYTES = 1024L * 1_048_576 // 1 GiB before decompression
    const val EPG_MAX_SIZE_BYTES = 1024L * 1_048_576 // 1 GiB decompressed XML
    const val XTREAM_SEGMENTED_READ_TIMEOUT_SECONDS = 45L
    const val XTREAM_SEGMENTED_WRITE_TIMEOUT_SECONDS = 45L
    const val XTREAM_SEGMENTED_CALL_TIMEOUT_SECONDS = 50L
    const val XTREAM_HEAVY_READ_TIMEOUT_SECONDS = 300L
    const val XTREAM_HEAVY_WRITE_TIMEOUT_SECONDS = 60L
    const val XTREAM_HEAVY_CALL_TIMEOUT_SECONDS = 330L
}
