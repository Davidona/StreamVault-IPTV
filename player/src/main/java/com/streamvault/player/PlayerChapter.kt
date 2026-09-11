package com.streamvault.player

data class PlayerChapter(
    val index: Int,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long?
)
