package com.streamvault.domain.model

/** Identifies missing Android exact-alarm access without exposing platform error text to UI. */
class ExactRecordingAlarmAccessException(message: String) : IllegalStateException(message)
